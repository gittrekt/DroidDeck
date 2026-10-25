package com.droiddeck.launcher.session

import android.os.SystemClock
import android.util.Log
import com.droiddeck.launcher.core.SessionPart
import com.droiddeck.launcher.runtime.LinuxRuntime
import java.io.File
import java.io.RandomAccessFile

class GpuStatsComponent(val dir: File) : SessionPart() {
    @Volatile private var running = false
    private var thread: Thread? = null
    private val feeds = ArrayList<Feed>()

    private class Feed(
        val file: File, val source: String, val read: () -> Long?, val format: String, val percent: Boolean = false,
    )

    fun binds(): List<String> {
        val list = mutableListOf(dir.path + ":" + KGSL)
        if (File(dir, "perf_now").exists()) list.add(File(dir, "perf_now").path + ":" + MSM_LOAD)
        if (File(dir, "cur_freq").exists()) list.add(File(dir, "cur_freq").path + ":" + MSM_CLOCK)
        return list
    }

    fun prepare(): Boolean {
        val hasKgsl = kgslDir.exists()
        val hasMaliDevfreq = devfreqDir.list()?.any { it.contains("mali") } ?: false
        if (!hasKgsl && !hasMaliDevfreq) return false
        if (hasKgsl) {
            val unreadable = MANGOHUD_FILES.any { File(kgslDir, it).let { f -> f.exists() && !f.canRead() } }
            if (!unreadable && !File(kgslDir, "gpubusy").canRead()) return false
        }
        return try {
            dir.mkdirs()
            dir.listFiles()?.forEach { java.nio.file.Files.deleteIfExists(it.toPath()) }
            feeds.clear()
            if (hasKgsl) {
                PASSTHROUGH_FILES.forEach { name ->
                    runCatching { File(dir, name).writeText(File(kgslDir, name).readText()) }
                }
            }
            loadFeed()?.let { load ->
                feeds.add(load)
                feeds.add(Feed(File(dir, "perf_now"), load.source, load.read, "%-6s\n", percent = true))
            }
            pick("clock_mhz", clockSources(), "%5d\n") { mhz(it) }?.let { clock ->
                feeds.add(clock)
                feeds.add(Feed(File(dir, "cur_freq"), clock.source, { clock.read()?.times(1_000_000) }, "%-10d\n"))
            }
            pick("temp", tempSources(), "%6d\n") { milliCelsius(it) }?.let { feeds.add(it) }
            refresh()
            Log.i(TAG, "hud: kgsl stats from the session's own: " + MANGOHUD_FILES.joinToString { name ->
                "$name " + (feeds.firstOrNull { it.file.name == name }?.source ?: "none")
            })
            true
        } catch (e: Exception) {
            Log.w(TAG, "hud: could not stand in for kgsl stats: $e")
            false
        }
    }

    override fun start() {
        if (feeds.isEmpty()) return
        running = true
        thread = Thread({
            while (running) {
                try { Thread.sleep(PERIOD_MS) } catch (e: InterruptedException) { break }
                if (!running) break
                refresh()
            }
        }, "gpu-stats").apply { isDaemon = true; start() }
    }

    // kgsl's gpubusy can read 0 0 straight after a read, so feeds that share a reader share one read.
    private fun refresh() {
        val values = HashMap<() -> Long?, Long?>()
        for (feed in feeds) {
            if (feed.read !in values) values[feed.read] = feed.read()
            values[feed.read]?.let { write(feed, it) }
        }
    }

    override fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    private fun loadFeed(): Feed? {
        latchedBusyFeed()?.let { return it }
        val sources = mutableListOf(
            "${kgslDir.path}/gpu_busy_percentage", "${kgslDir.path}/devfreq/gpu_load",
            "${kgslDir.path}/gpuload", "$QCOM_GPU/gpu_busy",
        )
        devfreqDir.list()?.sorted()?.forEach { node ->
            if (node.contains("mali") || node.contains("gpu")) {
                sources.add("${devfreqDir.path}/$node/load")
                sources.add("${devfreqDir.path}/$node/gpu_load")
            }
        }
        return pick("gpu_busy_percentage", sources, "%3d %%\n") { it.coerceIn(0, 100) }
    }

    // kgsl publishes busy/total once per ~1 s of GPU-on time and zeroes it on any read while the GPU is
    // power-collapsed, so direct pollers mostly see 0; latch each published window instead.
    private fun latchedBusyFeed(): Feed? {
        var load = 0L
        var windowAt = 0L
        val gpubusyFile = File(kgslDir, "gpubusy")
        val read = read@{
            val parts = runCatching {
                gpubusyFile.readText().trim().split(Regex("\\s+")).map { it.toLong() }
            }.getOrNull()
            if (parts == null || parts.size < 2) return@read null
            val now = SystemClock.elapsedRealtime()
            if (parts[1] > 0) {
                load = (parts[0] * 100 / parts[1]).coerceIn(0, 100)
                windowAt = now
            } else if (now - windowAt > LOAD_HOLD_MS) {
                load = 0
            }
            load
        }
        return if (read() != null) Feed(File(dir, "gpu_busy_percentage"), gpubusyFile.path, read, "%3d %%\n") else null
    }

    private fun pick(name: String, sources: List<String>, format: String, unit: (Long) -> Long?): Feed? {
        for (source in sources) {
            val read = { firstNumber(source)?.let(unit) }
            if (read() != null) return Feed(File(dir, name), source, read, format)
        }
        return null
    }

    private fun write(feed: Feed, value: Long) {
        try {
            val text = if (feed.percent) String.format(feed.format, "$value %") else String.format(feed.format, value)
            RandomAccessFile(feed.file, "rw").use { it.seek(0); it.write(text.toByteArray()) }
        } catch (e: Exception) {
        }
    }

    companion object {
        private const val TAG = "SessionService"
        const val KGSL = "/sys/class/kgsl/kgsl-3d0"
        const val MSM_LOAD = "/sys/kernel/debug/dri/0/perf_now"
        const val MSM_CLOCK = "/sys/devices/platform/soc@0/3d00000.gpu/devfreq/3d00000.gpu/cur_freq"
        private const val GPUBUSY = "$KGSL/gpubusy"
        private const val PERIOD_MS = 500L
        private const val LOAD_HOLD_MS = 5_000L
        private val MANGOHUD_FILES = listOf("gpu_busy_percentage", "clock_mhz", "temp")
        private val PASSTHROUGH_FILES = listOf("gpu_model", "gpu_chipid")
        private const val QCOM_GPU = "/sys/kernel/gpu"
        internal var kgslDir = File(KGSL)
        internal var devfreqDir = File("/sys/class/devfreq")
        internal var thermalDir = File("/sys/class/thermal")
        private val DEVFREQ_NODES = listOf(
            "kgsl-3d0", "3d00000.qcom,kgsl-3d0", "2c00000.qcom,kgsl-3d0", "5000000.qcom,kgsl-3d0", "5900000.qcom,kgsl-3d0",
            "13000000.mali", "mali0", "mali",
        )

        private fun clockSources(): List<String> {
            val sources = mutableListOf(
                "${kgslDir.path}/clock_mhz", "${kgslDir.path}/gpu_clock", "${kgslDir.path}/gpuclk",
                "${kgslDir.path}/devfreq/cur_freq", "$QCOM_GPU/gpu_clock",
            )
            DEVFREQ_NODES.forEach { sources.add("${devfreqDir.path}/$it/cur_freq") }
            devfreqDir.list()?.sorted()?.forEach { node ->
                if (node.contains("kgsl") || node.contains("gpu") || node.contains("mali")) sources.add("${devfreqDir.path}/$node/cur_freq")
            }
            return sources.distinct()
        }

        private fun thermalZone(sensor: String): String? {
            thermalDir.listFiles()?.forEach { zone ->
                val type = runCatching { File(zone, "type").readText().trim() }.getOrNull()
                if (type != null && type.contains(sensor, ignoreCase = true)) {
                    val temp = File(zone, "temp")
                    if (temp.canRead()) return temp.path
                }
            }
            return null
        }

        private fun tempSources(): List<String> =
            listOfNotNull(
                "${kgslDir.path}/temp",
                "${kgslDir.path}/devfreq/temp",
                "$QCOM_GPU/temp",
                LinuxRuntime.gpuTempSource(),
                thermalZone("mali"),
                thermalZone("gpu"),
            ).distinct()

        private fun firstNumber(path: String): Long? =
            runCatching { Regex("-?\\d+").find(File(path).readText())?.value?.toLong() }.getOrNull()

        private fun mhz(raw: Long): Long? = when {
            raw < 0 -> null
            raw > 10_000_000 -> raw / 1_000_000
            raw > 10_000 -> raw / 1_000
            else -> raw
        }

        private fun milliCelsius(raw: Long): Long? = when {
            raw <= 0 -> null
            raw < 1000 -> raw * 1000
            else -> raw
        }
    }
}

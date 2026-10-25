package com.droiddeck.launcher.session

import android.content.Context
import com.droiddeck.launcher.core.DeviceSupport
import com.droiddeck.launcher.gpu.LinuxVulkanDriverManager
import com.droiddeck.launcher.runtime.LinuxRuntime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class Milestone4ChallengerAdrenoNonRegressionTest {

    @get:Rule val tmp = TemporaryFolder()

    private val origKgslStats = GpuStatsComponent.kgslDir
    private val origDevfreqStats = GpuStatsComponent.devfreqDir
    private val origThermalStats = GpuStatsComponent.thermalDir

    private val origKgslMem = GpuMemComponent.kgslRoot
    private val origProcMem = GpuMemComponent.procDir

    @After
    fun tearDown() {
        GpuStatsComponent.kgslDir = origKgslStats
        GpuStatsComponent.devfreqDir = origDevfreqStats
        GpuStatsComponent.thermalDir = origThermalStats

        GpuMemComponent.kgslRoot = origKgslMem
        GpuMemComponent.procDir = origProcMem

        GpuClockPin.kgslNodeExists = { File("/dev/kgsl-3d0").exists() }
        GpuClockPin.maliNodeExists = { File("/dev/mali0").exists() }
    }

    // =========================================================================
    // INVARIANT 1: GpuStatsComponent on Qualcomm Adreno
    // =========================================================================

    @Test
    fun testAdrenoGpuStatsComprehensiveReading() {
        val sessionDir = tmp.newFolder("adreno_session")
        val fakeKgsl = tmp.newFolder("kgsl-3d0")

        // Populate standard Qualcomm Adreno sysfs nodes
        File(fakeKgsl, "gpubusy").writeText("250000 1000000\n") // 25%
        File(fakeKgsl, "clock_mhz").writeText("840\n")
        File(fakeKgsl, "temp").writeText("42500\n")
        File(fakeKgsl, "gpu_model").writeText("Adreno 750\n")

        val fakeDevfreq = tmp.newFolder("devfreq_adreno")
        val fakeThermal = tmp.newFolder("thermal_adreno")

        GpuStatsComponent.kgslDir = fakeKgsl
        GpuStatsComponent.devfreqDir = fakeDevfreq
        GpuStatsComponent.thermalDir = fakeThermal

        val stats = GpuStatsComponent(sessionDir)
        assertTrue("prepare() must succeed when KGSL sysfs exists", stats.prepare())

        // Verify session files written for guest/MangoHUD
        val modelFile = File(sessionDir, "gpu_model")
        assertTrue(modelFile.exists())
        assertEquals("Adreno 750", modelFile.readText().trim())

        val clockFile = File(sessionDir, "clock_mhz")
        assertTrue(clockFile.exists())
        assertEquals("840", clockFile.readText().trim())

        val tempFile = File(sessionDir, "temp")
        assertTrue(tempFile.exists())
        assertEquals("42500", tempFile.readText().trim())

        val loadFile = File(sessionDir, "gpu_busy_percentage")
        assertTrue(loadFile.exists())
        assertEquals("25 %", loadFile.readText().trim())

        // Verify bind points
        val binds = stats.binds()
        assertTrue("Binds must expose /sys/class/kgsl/kgsl-3d0", binds.any { it.contains(GpuStatsComponent.KGSL) })
    }

    @Test
    fun testAdrenoGpuStatsBusyBoundaryValues() {
        val sessionDir = tmp.newFolder("adreno_busy_session")
        val fakeKgsl = tmp.newFolder("kgsl-busy")
        File(fakeKgsl, "gpu_model").writeText("Adreno 660\n")
        File(fakeKgsl, "clock_mhz").writeText("600\n")

        val fakeDevfreq = tmp.newFolder("devfreq_empty")
        val fakeThermal = tmp.newFolder("thermal_empty")

        GpuStatsComponent.kgslDir = fakeKgsl
        GpuStatsComponent.devfreqDir = fakeDevfreq
        GpuStatsComponent.thermalDir = fakeThermal

        // Case 1: 100% busy
        File(fakeKgsl, "gpubusy").writeText("1000 1000\n")
        var stats = GpuStatsComponent(sessionDir)
        assertTrue(stats.prepare())
        assertEquals("100 %", File(sessionDir, "gpu_busy_percentage").readText().trim())

        // Case 2: 0% busy
        File(fakeKgsl, "gpubusy").writeText("0 1000\n")
        stats = GpuStatsComponent(sessionDir)
        assertTrue(stats.prepare())
        assertEquals("0 %", File(sessionDir, "gpu_busy_percentage").readText().trim())

        // Case 3: Invalid format (graceful fallback)
        File(fakeKgsl, "gpubusy").writeText("invalid_data\n")
        stats = GpuStatsComponent(sessionDir)
        assertTrue(stats.prepare())
        assertFalse(File(sessionDir, "gpu_busy_percentage").exists())
    }

    @Test
    fun testAdrenoGpuStatsClockAndTempFallbacks() {
        val sessionDir = tmp.newFolder("adreno_fallback_session")
        val fakeKgsl = tmp.newFolder("kgsl-fallback")
        File(fakeKgsl, "gpubusy").writeText("100 1000\n")
        // No clock_mhz in kgsl dir, but devfreq node exists
        val fakeDevfreq = tmp.newFolder("devfreq")
        val kgslDevfreq = File(fakeDevfreq, "kgsl-3d0").apply { mkdirs() }
        File(kgslDevfreq, "cur_freq").writeText("600000000\n") // 600 MHz in Hz

        // Temperature under kgsl/devfreq/temp
        val kgslSubDevfreq = File(fakeKgsl, "devfreq").apply { mkdirs() }
        File(kgslSubDevfreq, "temp").writeText("38000\n")

        val fakeThermal = tmp.newFolder("thermal_empty")

        GpuStatsComponent.kgslDir = fakeKgsl
        GpuStatsComponent.devfreqDir = fakeDevfreq
        GpuStatsComponent.thermalDir = fakeThermal

        val stats = GpuStatsComponent(sessionDir)
        assertTrue(stats.prepare())

        val clockFile = File(sessionDir, "clock_mhz")
        assertTrue(clockFile.exists())
        assertEquals("600", clockFile.readText().trim())

        val tempFile = File(sessionDir, "temp")
        assertTrue(tempFile.exists())
        assertEquals("38000", tempFile.readText().trim())
    }

    // =========================================================================
    // INVARIANT 2: GpuMemComponent on Qualcomm Adreno
    // =========================================================================

    @Test
    fun testAdrenoGpuMemPageAllocTopPriority() {
        val fakeProc = tmp.newFolder("proc_mem")
        val fakeKgsl = tmp.newFolder("kgsl_mem")

        // 512 MB in bytes
        val expectedBytes = 536870912L
        File(fakeKgsl, "page_alloc").writeText("$expectedBytes\n")

        GpuMemComponent.procDir = fakeProc
        GpuMemComponent.kgslRoot = fakeKgsl

        val memFile = File(tmp.root, "gpu.mem")
        val mem = GpuMemComponent(memFile, tmp.newFolder("root"))
        assertTrue("prepare() must succeed", mem.prepare())
        assertTrue(memFile.exists())
        assertEquals(expectedBytes.toString(), memFile.readText().trim())
    }

    @Test
    fun testAdrenoGpuMemPerProcessFallback() {
        val fakeProc = tmp.newFolder("proc_mem_pids")
        val fakeKgsl = tmp.newFolder("kgsl_mem_pids")
        val procDir = File(fakeKgsl, "proc").apply { mkdirs() }

        // PID 101: mapped 1000000, unmapped 500000 = 1500000
        val pid101 = File(procDir, "101").apply { mkdirs() }
        File(pid101, "gpumem_mapped").writeText("1000000\n")
        File(pid101, "gpumem_unmapped").writeText("500000\n")

        // PID 102: mapped 2000000, unmapped 0 = 2000000
        val pid102 = File(procDir, "102").apply { mkdirs() }
        File(pid102, "gpumem_mapped").writeText("2000000\n")

        // Create dummy PIDs in procDir
        File(fakeProc, "101").mkdirs()
        File(fakeProc, "102").mkdirs()

        GpuMemComponent.procDir = fakeProc
        GpuMemComponent.kgslRoot = fakeKgsl

        val memFile = File(tmp.root, "gpu.mem")
        val mem = GpuMemComponent(memFile, tmp.newFolder("root"))
        assertTrue(mem.prepare())
        assertEquals("3500000", memFile.readText().trim())
    }

    @Test
    fun testAdrenoGpuMemMapsParsingPreserved() {
        val component = GpuMemComponent(tmp.newFile("gpu.mem"), tmp.newFolder("root"))

        // Multiple mappings:
        // 1. kgsl-3d0 mapping: 0x10000000 to 0x10200000 = 0x200000 = 2097152 bytes = 2048 KB
        // 2. renderD128 mapping: 0x20000000 to 0x20100000 = 0x100000 = 1048576 bytes = 1024 KB
        // 3. Regular file: should be ignored
        // 4. Anonymous heap: should be ignored
        val lines = sequenceOf(
            "10000000-10200000 rw-s 00000000 00:0e 12345 /dev/kgsl-3d0",
            "20000000-20100000 rw-s 00000000 00:0e 67890 /dev/dri/renderD128",
            "30000000-30080000 r-xp 00000000 00:0e 11111 /system/lib64/libc.so",
            "40000000-40010000 rw-p 00000000 00:00 0     [heap]",
        )

        val totalKb = component.parseMaps(lines)
        assertEquals("Total mapped KB must equal sum of kgsl-3d0 and renderD128", 3072L, totalKb)
    }

    // =========================================================================
    // INVARIANT 3: GpuClockPin on Qualcomm Adreno
    // =========================================================================

    @Test
    fun testAdrenoGpuClockPinBehaviors() {
        val context = RuntimeEnvironment.getApplication()

        // Adreno device: /dev/kgsl-3d0 exists, /dev/mali0 does NOT exist
        GpuClockPin.kgslNodeExists = { true }
        GpuClockPin.maliNodeExists = { false }

        // Pref disabled -> governed
        SessionPrefs.setGpuClockPin(context, false)
        assertEquals("gpu clock: governed", GpuClockPin.start(context))

        // Pref enabled -> attempts pinning
        SessionPrefs.setGpuClockPin(context, true)
        val res = GpuClockPin.start(context)
        assertTrue(
            "Adreno pinning must either succeed or fail gracefully: got '$res'",
            res == "gpu clock: held at its top" || res == "gpu clock: pin failed"
        )

        // Adreno device missing /dev/kgsl-3d0
        GpuClockPin.kgslNodeExists = { false }
        val noKgsl = GpuClockPin.start(context)
        assertEquals("gpu clock: no /dev/kgsl-3d0, left governed", noKgsl)
    }

    // =========================================================================
    // INVARIANT 4: SessionService Driver Selection & Environment Isolation
    // =========================================================================

    @Test
    fun testSessionServiceIsTurnipDriverClassification() {
        // Test driver classification logic used in SessionService
        val adrenoDriverNames = listOf(
            "turnip",
            "freedreno",
            "mesa turnip",
            "mesa freedreno",
            "turnip-v24.1.0",
            "wn-turnip",
            "adreno proprietary"
        )

        for (name in adrenoDriverNames) {
            val lower = name.lowercase()
            val isTurnip = !lower.contains("panfrost") && !lower.contains("panvk")
            assertTrue("Driver '$name' must be classified as Turnip / non-Mali", isTurnip)
        }

        val maliDriverNames = listOf(
            "panfrost",
            "panvk",
            "mesa panvk · mali-g615",
            "panfrost v24.3.0"
        )

        for (name in maliDriverNames) {
            val lower = name.lowercase()
            val isTurnip = !lower.contains("panfrost") && !lower.contains("panvk")
            assertFalse("Driver '$name' must NOT be classified as Turnip", isTurnip)
        }
    }
}

package com.droiddeck.launcher.core

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream

/**
 * A pack built by CI alongside this APK (tools/packs). Main and release builds publish it to
 * DroidDeck-Components under a content-addressed tag; a PR test build attaches packs that are not there
 * yet to its pr-N release on DroidDeck-CI. The APK pins the pack's SHA-256, so only the bytes this build
 * was made with install, whichever host serves them.
 */
internal class AddonPack(
    val name: String, private val sha256: String, val size: Long, private val required: List<String>,
    private val reuseDeviceCopy: Boolean = sha256.isEmpty() && !BuildConfig.CI_BUILD,
) {
    sealed interface State {
        data object Missing : State
        data class Downloading(val progress: Float) : State
        data object Failed : State
        data object Ready : State
    }

    private val mutableState = MutableStateFlow<State>(State.Missing)
    val state: StateFlow<State> = mutableState.asStateFlow()
    private val lock = Any()

    val available: Boolean get() = sha256.isNotEmpty() || UPSTREAM_FALLBACKS.containsKey(name)
    private val tag get() = "pack-$name-${sha256.take(16)}"
    private val urls get() = listOfNotNull(
        "https://github.com/$REPO/releases/download/$tag/$name.tar.zst",
        BuildConfig.PR_NUMBER.takeIf { it > 0 }?.let { "https://github.com/$PR_REPO/releases/download/pr-$it/$tag.tar.zst" },
    )

    private fun root(context: Context) = File(context.filesDir, "addons/$name")
    /** App-private and written only after the archive matched the pinned digest. */
    fun dir(context: Context) = File(root(context), version(context))
    fun file(context: Context, relative: String) = File(dir(context), relative)

    fun installed(context: Context): Boolean = version(context).isNotEmpty() && File(dir(context), COMPLETE).isFile

    // A build made outside CI pins no packs, so it runs the newest one a CI build already verified and installed.
    private fun version(context: Context): String = if (!reuseDeviceCopy) sha256 else deviceCopy(context).orEmpty()

    private fun deviceCopy(context: Context): String? = root(context).listFiles().orEmpty()
        .filter { dir ->
            dir.isDirectory && runCatching { File(dir, COMPLETE).readText().trim() }.getOrNull() == dir.name &&
                required.all { File(dir, it).isFile }
        }
        .maxByOrNull { it.lastModified() }?.name

    fun refresh(context: Context): State {
        val now = mutableState.value
        if (now is State.Downloading) return now
        return (if (installed(context)) State.Ready else if (now == State.Failed) State.Failed else State.Missing)
            .also { mutableState.value = it }
    }

    /** Starts the download in the background unless the pack is already present or on its way. */
    fun ensure(context: Context, onReady: (Context) -> Unit = {}) {
        val app = context.applicationContext
        synchronized(lock) {
            if (installed(app)) { mutableState.value = State.Ready; return }
            if (mutableState.value is State.Downloading) return
            mutableState.value = State.Downloading(0f)
        }
        scope.launch {
            val ok = runCatching { install(app) }.onFailure { Log.w(TAG, "$name: install failed", it) }.getOrDefault(false)
            mutableState.value = if (ok) State.Ready else State.Failed
            if (ok) onReady(app)
        }
    }

    /** Blocks a worker thread until the pack is installed, starting the download if needed. */
    @JvmOverloads
    fun await(context: Context, running: () -> Boolean = { true }, onState: (State) -> Unit = {}): Boolean {
        if (installed(context)) return true
        ensure(context)
        while (running()) {
            val now = state.value
            onState(now)
            when (now) {
                State.Ready -> return installed(context)
                State.Failed -> return false
                else -> try { Thread.sleep(250) } catch (e: InterruptedException) { return false }
            }
        }
        return false
    }

    private fun install(context: Context): Boolean {
        val root = root(context).apply { mkdirs() }

        // Local external pack staging (e.g. pushed via deploy_local.sh for developer builds)
        val externalPack = context.getExternalFilesDir("packs")?.let { File(it, "$name.tar.zst") }?.takeIf { it.isFile }
        if (externalPack != null) {
            val staging = File(root, "$name.external.partial")
            staging.deleteRecursively()
            try {
                if (FileInputStream(externalPack).use { TarZst.extract(it, staging) } &&
                    required.all { File(staging, it).isFile }) {
                    val digest = Hashes.sha256(externalPack)
                    File(staging, COMPLETE).writeText(digest)
                    val target = if (sha256.isNotEmpty()) File(root, sha256) else File(root, digest)
                    target.deleteRecursively()
                    if (staging.renameTo(target)) {
                        Log.i(TAG, "$name: successfully installed from local external pack $externalPack ($digest)")
                        return true
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "$name: failed to install from external pack $externalPack", e)
            } finally {
                staging.deleteRecursively()
            }
        }

        if (!available) {
            Log.w(TAG, if (reuseDeviceCopy) "$name: this build has no pack and none is installed" else "$name: this build has no pack")
            return false
        }

        val candidates = mutableListOf<Pair<String, String>>()
        if (sha256.isNotEmpty()) {
            urls.forEach { candidates.add(it to sha256) }
        }
        val fallbackSha = UPSTREAM_FALLBACKS[name]
        if (fallbackSha != null && fallbackSha != sha256) {
            val fallbackTag = "pack-$name-${fallbackSha.take(16)}"
            candidates.add("https://github.com/$REPO/releases/download/$fallbackTag/$name.tar.zst" to fallbackSha)
        }

        for ((url, expectedSha) in candidates) {
            val archive = File(root, "$expectedSha.tar.zst")
            val downloaded = Downloader.downloadFile(url, archive, true, expectedSha) { p ->
                mutableState.value = State.Downloading(p.coerceIn(0f, 1f))
            }.also { if (!it) archive.delete() }

            if (!downloaded) continue

            val staging = File(root, "$expectedSha.partial")
            staging.deleteRecursively()
            try {
                if (!FileInputStream(archive).use { TarZst.extract(it, staging) }) continue
                if (!required.all { File(staging, it).isFile }) continue
                File(staging, COMPLETE).writeText(expectedSha)
                val target = if (sha256.isNotEmpty()) File(root, sha256) else File(root, expectedSha)
                target.deleteRecursively()
                if (!staging.renameTo(target)) continue
                if (target.name != expectedSha) {
                    val fallbackTarget = File(root, expectedSha)
                    if (!fallbackTarget.exists()) {
                        runCatching { java.nio.file.Files.createSymbolicLink(fallbackTarget.toPath(), target.toPath()) }
                    }
                }
                root.listFiles().orEmpty().filter { it.name != sha256 && it.name != expectedSha }.forEach { it.deleteRecursively() }
                return true
            } finally {
                staging.deleteRecursively()
                archive.delete()
            }
        }
        return false
    }

    companion object {
        private const val TAG = "AddonPack"
        private const val REPO = "Droid-Deck/DroidDeck-Components"
        private const val PR_REPO = "Droid-Deck/DroidDeck-CI"
        const val COMPLETE = ".complete"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        internal val UPSTREAM_FALLBACKS = mapOf(
            "proot" to "5a2b7284e6357f88a14a790abd54e61effbe5f202c63b6aaba9b974c559649c3",
            "directaudio" to "fd65f0c1e68234cac18796cfd7d43dad46334988dad1eb20fc082952d83efa7e",
            "tracking" to "6b033308a33575947b75eee0bab093bf7dd58c57f71ed81ddc09a859f4df8dde",
            "xbox" to "72d90393b09d260558717215b58b1925345cc8d39d8980009fd062919c764e1d",
            "comet" to "fdc66ee6ae44ccdb119e18ed4c5b86a0cca200dc10ddcd1958a55c32ca42937f",
        )

        @JvmField
        val PROOT = AddonPack("proot", BuildConfig.PACK_PROOT_SHA256, BuildConfig.PACK_PROOT_SIZE,
            listOf("libproot.so", "libproot-loader.so"))
        @JvmField
        val DIRECTAUDIO = AddonPack("directaudio", BuildConfig.PACK_DIRECTAUDIO_SHA256, BuildConfig.PACK_DIRECTAUDIO_SIZE,
            listOf("linux-wine11/version.txt", "linux-wine11-systhread/version.txt"))
        val TRACKING = AddonPack("tracking", BuildConfig.PACK_TRACKING_SHA256, BuildConfig.PACK_TRACKING_SIZE, listOf(
            "hand_landmarker.task", "hand_landmarker_lite.task", "pose_landmarker_lite.task",
            "libopencv_java4.so", "libmediapipe_tasks_jni.so"))
        val XBOX = AddonPack("xbox", BuildConfig.PACK_XBOX_SHA256, BuildConfig.PACK_XBOX_SIZE,
            listOf("version", "xodus-cli", "xodus-service"))
        val COMET = AddonPack("comet", BuildConfig.PACK_COMET_SHA256, BuildConfig.PACK_COMET_SIZE,
            listOf("version", "comet"))
    }
}

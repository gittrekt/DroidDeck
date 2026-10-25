package com.droiddeck.launcher.session

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.wayland.WaylandCompositor
import java.io.File

object GpuClockPin {
    private const val TAG = "GpuClockPin"
    private const val KGSL_NODE = "/dev/kgsl-3d0"
    private const val MALI_NODE = "/dev/mali0"

    private const val MARKER = "gpu-clock-pinned"

    @Volatile
    private var pinned = false

    internal var kgslNodeExists: () -> Boolean = { File(KGSL_NODE).exists() }
    internal var maliNodeExists: () -> Boolean = { File(MALI_NODE).exists() }

    private val supported: Boolean get() = kgslNodeExists()
    private val isMali: Boolean get() = maliNodeExists()

    @Synchronized
    fun start(context: Context): String {
        if (!SessionPrefs.gpuClockPin(context)) return "gpu clock: governed"
        if (isMali) return "gpu clock: governed (Mali devfreq)"
        if (!supported) return "gpu clock: no $KGSL_NODE, left governed"
        return try {
            File(context.filesDir, MARKER).createNewFile()
            WaylandCompositor.nativeSetGpuTurbo(true)
            pinned = true
            "gpu clock: held at its top"
        } catch (t: Throwable) {
            File(context.filesDir, MARKER).delete()
            Log.w(TAG, "pin", t)
            "gpu clock: pin failed"
        }
    }

    @Synchronized
    fun stop(context: Context) {
        if (pinned) clear(context)
    }

    @Synchronized
    fun clearLeftover(context: Context) {
        if (File(context.filesDir, MARKER).exists()) clear(context)
    }

    private fun clear(context: Context) {
        if (supported) {
            try {
                WaylandCompositor.nativeSetGpuTurbo(false)
            } catch (t: Throwable) {
                Log.w(TAG, "clear", t)
            }
        }
        pinned = false
        File(context.filesDir, MARKER).delete()
    }
}

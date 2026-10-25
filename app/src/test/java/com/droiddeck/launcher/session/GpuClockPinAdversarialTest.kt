package com.droiddeck.launcher.session

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class GpuClockPinAdversarialTest {

    @After
    fun tearDown() {
        GpuClockPin.kgslNodeExists = { File("/dev/kgsl-3d0").exists() }
        GpuClockPin.maliNodeExists = { File("/dev/mali0").exists() }
    }

    @Test
    fun testMaliPriorityOverKgsl() {
        val context = RuntimeEnvironment.getApplication()
        SessionPrefs.setGpuClockPin(context, true)
        // Both Mali and KGSL nodes simulated as true
        GpuClockPin.maliNodeExists = { true }
        GpuClockPin.kgslNodeExists = { true }

        val result = GpuClockPin.start(context)
        assertEquals("gpu clock: governed (Mali devfreq)", result)
        // Ensure marker file was NOT created on Mali
        assertFalse(File(context.filesDir, "gpu-clock-pinned").exists())
    }

    @Test
    fun testMaliDisabledPrefTakesPrecedence() {
        val context = RuntimeEnvironment.getApplication()
        SessionPrefs.setGpuClockPin(context, false)
        GpuClockPin.maliNodeExists = { true }
        GpuClockPin.kgslNodeExists = { false }

        val result = GpuClockPin.start(context)
        assertEquals("gpu clock: governed", result)
        assertFalse(File(context.filesDir, "gpu-clock-pinned").exists())
    }

    @Test
    fun testMaliLeftoverMarkerFileCleanup() {
        val context = RuntimeEnvironment.getApplication()
        GpuClockPin.maliNodeExists = { true }
        GpuClockPin.kgslNodeExists = { false }

        // Create synthetic leftover marker
        val marker = File(context.filesDir, "gpu-clock-pinned")
        marker.createNewFile()
        assertTrue(marker.exists())

        // clearLeftover should remove the marker even on Mali without touching native kgsl
        GpuClockPin.clearLeftover(context)
        assertFalse(marker.exists())
    }

    @Test
    fun testConcurrentPinAndStopOnMali() {
        val context = RuntimeEnvironment.getApplication()
        SessionPrefs.setGpuClockPin(context, true)
        GpuClockPin.maliNodeExists = { true }
        GpuClockPin.kgslNodeExists = { false }

        val threads = (1..10).map {
            Thread {
                for (i in 0 until 50) {
                    val status = GpuClockPin.start(context)
                    assertEquals("gpu clock: governed (Mali devfreq)", status)
                    GpuClockPin.stop(context)
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertFalse(File(context.filesDir, "gpu-clock-pinned").exists())
    }
}

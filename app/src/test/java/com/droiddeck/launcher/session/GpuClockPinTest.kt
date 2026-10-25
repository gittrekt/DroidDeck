package com.droiddeck.launcher.session

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class GpuClockPinTest {

    @After
    fun tearDown() {
        GpuClockPin.kgslNodeExists = { File("/dev/kgsl-3d0").exists() }
        GpuClockPin.maliNodeExists = { File("/dev/mali0").exists() }
    }

    @Test
    fun testGovernedWhenPrefDisabled() {
        val context = RuntimeEnvironment.getApplication()
        SessionPrefs.setGpuClockPin(context, false)
        val result = GpuClockPin.start(context)
        assertEquals("gpu clock: governed", result)
    }

    @Test
    fun testMaliGovernedWhenPrefEnabled() {
        val context = RuntimeEnvironment.getApplication()
        SessionPrefs.setGpuClockPin(context, true)
        GpuClockPin.maliNodeExists = { true }
        GpuClockPin.kgslNodeExists = { false }

        val result = GpuClockPin.start(context)
        assertEquals("gpu clock: governed (Mali devfreq)", result)
    }

    @Test
    fun testAdrenoNoKgslNodeLeftGoverned() {
        val context = RuntimeEnvironment.getApplication()
        SessionPrefs.setGpuClockPin(context, true)
        GpuClockPin.maliNodeExists = { false }
        GpuClockPin.kgslNodeExists = { false }

        val result = GpuClockPin.start(context)
        assertEquals("gpu clock: no /dev/kgsl-3d0, left governed", result)
    }

    @Test
    fun testAdrenoKgslAvailableAttemptsPin() {
        val context = RuntimeEnvironment.getApplication()
        SessionPrefs.setGpuClockPin(context, true)
        GpuClockPin.maliNodeExists = { false }
        GpuClockPin.kgslNodeExists = { true }

        // In test environment without native waylandcomp loaded, nativeSetGpuTurbo throws UnsatisfiedLinkError
        // and GpuClockPin catches it, returning "gpu clock: pin failed" while cleaning up.
        val result = GpuClockPin.start(context)
        assertTrue(result == "gpu clock: held at its top" || result == "gpu clock: pin failed")
    }

    @Test
    fun testStopAndClearLeftoverSafeOnMali() {
        val context = RuntimeEnvironment.getApplication()
        GpuClockPin.maliNodeExists = { true }
        GpuClockPin.kgslNodeExists = { false }

        GpuClockPin.stop(context)
        GpuClockPin.clearLeftover(context)
    }
}

package com.droiddeck.launcher.session

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class GpuStatsComponentTest {

    @get:Rule val tmp = TemporaryFolder()

    private val origKgsl = GpuStatsComponent.kgslDir
    private val origDevfreq = GpuStatsComponent.devfreqDir
    private val origThermal = GpuStatsComponent.thermalDir

    @After
    fun tearDown() {
        GpuStatsComponent.kgslDir = origKgsl
        GpuStatsComponent.devfreqDir = origDevfreq
        GpuStatsComponent.thermalDir = origThermal
    }

    @Test
    fun testPrepareFailsWhenNeitherKgslNorMaliDevfreq() {
        val sessionDir = tmp.newFolder("stats")
        val fakeDevfreq = tmp.newFolder("devfreq_empty")
        val fakeKgsl = File(tmp.root, "non_existent_kgsl")

        GpuStatsComponent.kgslDir = fakeKgsl
        GpuStatsComponent.devfreqDir = fakeDevfreq

        val component = GpuStatsComponent(sessionDir)
        assertFalse(component.prepare())
    }

    @Test
    fun testPrepareSucceedsWithMaliDevfreq() {
        val sessionDir = tmp.newFolder("stats")
        val fakeDevfreq = tmp.newFolder("devfreq_mali")
        val maliDevfreq = File(fakeDevfreq, "13000000.mali").apply { mkdirs() }
        File(maliDevfreq, "cur_freq").writeText("800000000\n")
        File(maliDevfreq, "load").writeText("42\n")

        val fakeThermal = tmp.newFolder("thermal")
        val maliThermal = File(fakeThermal, "thermal_zone0").apply { mkdirs() }
        File(maliThermal, "type").writeText("mali_thermal\n")
        File(maliThermal, "temp").writeText("48000\n")

        val fakeKgsl = File(tmp.root, "non_existent_kgsl")

        GpuStatsComponent.kgslDir = fakeKgsl
        GpuStatsComponent.devfreqDir = fakeDevfreq
        GpuStatsComponent.thermalDir = fakeThermal

        val component = GpuStatsComponent(sessionDir)
        assertTrue(component.prepare())

        // Check feeds created in sessionDir
        val clockFile = File(sessionDir, "clock_mhz")
        assertTrue(clockFile.exists())
        assertEquals("800", clockFile.readText().trim())

        val tempFile = File(sessionDir, "temp")
        assertTrue(tempFile.exists())
        assertEquals("48000", tempFile.readText().trim())

        val loadFile = File(sessionDir, "gpu_busy_percentage")
        assertTrue(loadFile.exists())
        assertEquals("42 %", loadFile.readText().trim())

        val binds = component.binds()
        assertTrue(binds.any { it.contains(GpuStatsComponent.KGSL) })
    }

    @Test
    fun testAdrenoKgslTelemetryPreserved() {
        val sessionDir = tmp.newFolder("stats_adreno")
        val fakeKgsl = tmp.newFolder("kgsl-3d0")
        File(fakeKgsl, "gpubusy").writeText("500 1000\n")
        File(fakeKgsl, "clock_mhz").writeText("600\n")
        File(fakeKgsl, "temp").writeText("39000\n")
        File(fakeKgsl, "gpu_model").writeText("Adreno 730\n")

        val fakeDevfreq = tmp.newFolder("devfreq_adreno")
        val fakeThermal = tmp.newFolder("thermal_adreno")

        GpuStatsComponent.kgslDir = fakeKgsl
        GpuStatsComponent.devfreqDir = fakeDevfreq
        GpuStatsComponent.thermalDir = fakeThermal

        val component = GpuStatsComponent(sessionDir)
        assertTrue(component.prepare())

        val modelFile = File(sessionDir, "gpu_model")
        assertTrue(modelFile.exists())
        assertEquals("Adreno 730", modelFile.readText().trim())

        val clockFile = File(sessionDir, "clock_mhz")
        assertTrue(clockFile.exists())
        assertEquals("600", clockFile.readText().trim())
    }
}

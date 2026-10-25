package com.droiddeck.launcher.session

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class GpuMemComponentTest {

    @get:Rule val tmp = TemporaryFolder()

    private val origKgslRoot = GpuMemComponent.kgslRoot
    private val origProcDir = GpuMemComponent.procDir

    @After
    fun tearDown() {
        GpuMemComponent.kgslRoot = origKgslRoot
        GpuMemComponent.procDir = origProcDir
    }

    @Test
    fun testParseMapsWithMaliNode() {
        val component = GpuMemComponent(tmp.newFile("gpu.mem"), tmp.newFolder("root"))
        // 0x10000000 to 0x10100000 = 0x100000 bytes = 1048576 bytes = 1024 KB
        val lines = sequenceOf(
            "10000000-10100000 rw-s 00000000 00:0e 12345 /dev/mali0",
            "20000000-20010000 rw-p 00000000 00:00 0     [heap]",
            "30000000-30200000 r-xp 00000000 00:0e 54321 /lib/libc.so",
        )
        val kb = component.parseMaps(lines)
        assertEquals(1024L, kb)
    }

    @Test
    fun testParseMapsWithAdrenoAndRenderD() {
        val component = GpuMemComponent(tmp.newFile("gpu.mem"), tmp.newFolder("root"))
        // 0x10000000-0x10080000 = 0x80000 = 512 KB
        // 0x20000000-0x20080000 = 0x80000 = 512 KB
        val lines = sequenceOf(
            "10000000-10080000 rw-s 00000000 00:0e 12345 /dev/kgsl-3d0",
            "20000000-20080000 rw-s 00000000 00:0e 67890 /dev/dri/renderD128",
        )
        val kb = component.parseMaps(lines)
        assertEquals(1024L, kb)
    }

    @Test
    fun testPrepareWritesMaliMapsMemory() {
        val fakeProc = tmp.newFolder("proc")
        val fakePid = File(fakeProc, "4321").apply { mkdirs() }
        File(fakePid, "maps").writeText(
            "10000000-10100000 rw-s 00000000 00:0e 12345 /dev/mali0\n"
        )
        val fakeKgsl = File(tmp.root, "empty_kgsl")

        GpuMemComponent.procDir = fakeProc
        GpuMemComponent.kgslRoot = fakeKgsl

        val memFile = File(tmp.root, "mali_gpu.mem")
        val component = GpuMemComponent(memFile, tmp.newFolder("root"))
        assertTrue(component.prepare())

        assertTrue(memFile.exists())
        assertEquals("1048576", memFile.readText().trim())
    }

    @Test
    fun testAdrenoPageAllocPrioritized() {
        val fakeProc = tmp.newFolder("proc_adreno")
        val fakeKgsl = tmp.newFolder("kgsl_adreno")
        File(fakeKgsl, "page_alloc").writeText("209715200\n")

        GpuMemComponent.procDir = fakeProc
        GpuMemComponent.kgslRoot = fakeKgsl

        val memFile = File(tmp.root, "adreno_gpu.mem")
        val component = GpuMemComponent(memFile, tmp.newFolder("root"))
        assertTrue(component.prepare())

        assertTrue(memFile.exists())
        assertEquals("209715200", memFile.readText().trim())
    }
}

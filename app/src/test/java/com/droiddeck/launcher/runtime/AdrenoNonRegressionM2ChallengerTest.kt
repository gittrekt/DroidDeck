package com.droiddeck.launcher.runtime

import android.content.ContextWrapper
import android.os.Environment
import com.droiddeck.launcher.core.DeviceSupport
import com.droiddeck.launcher.session.SessionPhase
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.SessionService
import com.droiddeck.launcher.session.SessionState
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

/**
 * Empirical Challenger Verification Suite for Milestone 2 Adreno Non-Regression Invariant.
 *
 * Verifies that when /dev/kgsl-3d0 is present:
 * 1. LinuxRuntime.bindGpuNode() executes exact Adreno logic and does NOT bind /dev/mali0.
 * 2. LinuxRuntime.vulkanIcd() prioritizes freedreno ICD manifests.
 * 3. BwrapSpawner.gpu() binds libvulkan_freedreno.so and generates freedreno_icd.json.
 * 4. SessionService.kt injects TU_DEBUG and DROIDDECK_GAME_TU_DEBUG for Turnip.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@LooperMode(LooperMode.Mode.PAUSED)
class AdrenoNonRegressionM2ChallengerTest {

    private val root = Files.createTempDirectory("adreno-m2-challenger").toFile()
    private val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getFilesDir() = File(root, "files").apply { mkdirs() }
        override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        override fun getNoBackupFilesDir() = File(root, "no_backup").apply { mkdirs() }
    }

    private val savedKgsl = LinuxRuntime.kgslDevice
    private val savedMali = LinuxRuntime.maliDevice
    private val savedBwrapKgsl = BwrapSpawner.kgslDevice
    private val savedBwrapMali = BwrapSpawner.maliDevice

    @Before
    fun setUp() {
        SessionState.running = false
        SessionState.phase = SessionPhase.IDLE
    }

    @After
    fun tearDown() {
        LinuxRuntime.kgslDevice = savedKgsl
        LinuxRuntime.maliDevice = savedMali
        BwrapSpawner.kgslDevice = savedBwrapKgsl
        BwrapSpawner.maliDevice = savedBwrapMali
        SessionState.running = false
        SessionState.phase = SessionPhase.IDLE
        root.deleteRecursively()
    }

    // -------------------------------------------------------------------------
    // 1. LinuxRuntime.bindGpuNode() Adreno Invariant & Precedence
    // -------------------------------------------------------------------------

    @Test
    fun testAdrenoGpuNodeBindingExecutesExactAdrenoLogicAndExcludesMali() {
        val fakeKgsl = File(root, "fake-kgsl-3d0").apply { createNewFile() }
        LinuxRuntime.kgslDevice = fakeKgsl.path
        LinuxRuntime.maliDevice = File(root, "nonexistent-mali").path

        val binds = LinuxRuntime.binds(context, null, null, null, null)

        // 1. Must bind Adreno DRI node and dev/dri
        assertTrue("Adreno DRI node must be bound", binds.any { it.startsWith(fakeKgsl.path + ":/dev/dri/renderD") })
        assertTrue("DRI directory must be bound", binds.any { it.endsWith(":/dev/dri") })
        assertTrue("Sys dev char must be bound", binds.any { it.endsWith(":/sys/dev/char") })

        // 2. Must NEVER bind Mali node
        assertFalse("/dev/mali0 must NOT be bound", binds.any { it.contains("mali0") })

        // 3. Inspect sysfs DRM synthesized hierarchy
        val live = LinuxRuntime.liveDir(context)
        val driDir = File(live, "drm/dri")
        assertTrue("drm/dri must exist", driDir.isDirectory)
        val renderNodes = driDir.listFiles { _, name -> name.startsWith("renderD") }
        assertNotNull(renderNodes)
        assertTrue("renderD file must be created", renderNodes!!.isNotEmpty())

        val charDevs = File(live, "drm/sys").listFiles()
        assertNotNull(charDevs)
        assertTrue(charDevs!!.isNotEmpty())
        val charDev = charDevs.first()

        val deviceDir = File(charDev, "device")
        val devUevent = File(deviceDir, "uevent")
        assertTrue("device/uevent must exist", devUevent.isFile)
        val devUeventText = devUevent.readText()
        assertTrue("uevent must specify DRIVER=kgsl-3d0", devUeventText.contains("DRIVER=kgsl-3d0"))
        assertTrue("uevent must specify MODALIAS=platform:kgsl-3d0", devUeventText.contains("MODALIAS=platform:kgsl-3d0"))
        assertFalse("uevent must NOT specify DRIVER=mali", devUeventText.contains("DRIVER=mali"))

        // 5. Verify dev numbers file in drm/renderD*
        val renderNode = renderNodes.first()
        val drmDevFile = File(deviceDir, "drm/${renderNode.name}/dev")
        assertTrue("drm dev file must exist", drmDevFile.isFile)
        val devNumbers = drmDevFile.readText().trim()
        assertTrue("drm dev file must contain device numbers", devNumbers.matches(Regex("\\d+:\\d+")))
    }

    @Test
    fun testAdrenoPrecedenceInBindGpuNodeWhenBothKgslAndMaliExist() {
        val fakeKgsl = File(root, "fake-kgsl-3d0").apply { createNewFile() }
        val fakeMali = File(root, "fake-mali0").apply { createNewFile() }
        LinuxRuntime.kgslDevice = fakeKgsl.path
        LinuxRuntime.maliDevice = fakeMali.path

        val binds = LinuxRuntime.binds(context, null, null, null, null)

        // KGSL must take precedence
        assertTrue("KGSL DRI node must be bound", binds.any { it.startsWith(fakeKgsl.path + ":/dev/dri/renderD") })
        assertFalse("Mali device must NOT be bound when KGSL is present", binds.any { it.contains("mali0") })

        val charDev = File(LinuxRuntime.liveDir(context), "drm/sys").listFiles()!!.first()
        val ueventText = File(charDev, "device/uevent").readText()
        assertTrue("uevent must have DRIVER=kgsl-3d0", ueventText.contains("DRIVER=kgsl-3d0"))
        assertFalse("uevent must NOT have DRIVER=mali", ueventText.contains("DRIVER=mali"))
    }

    // -------------------------------------------------------------------------
    // 2. LinuxRuntime.vulkanIcd() Adreno Prioritization
    // -------------------------------------------------------------------------

    @Test
    fun testVulkanIcdPrioritizesFreedrenoOnAdrenoDevice() {
        LinuxRuntime.maliDevice = File(root, "nonexistent-mali").path
        val icdDir = File(LinuxRuntime.rootDir(context), "usr/share/vulkan/icd.d").apply { mkdirs() }

        val freedreno = File(icdDir, "freedreno_icd.json").apply { writeText("{}") }
        val panfrost = File(icdDir, "panfrost_icd.json").apply { writeText("{}") }
        val panvk = File(icdDir, "panvk_icd.json").apply { writeText("{}") }

        try {
            val selected = LinuxRuntime.vulkanIcd(context)
            assertNotNull(selected)
            assertEquals("freedreno manifest must be selected over panfrost/panvk", freedreno, selected)
        } finally {
            freedreno.delete()
            panfrost.delete()
            panvk.delete()
        }
    }

    @Test
    fun testVulkanIcdFallbackWhenFreedrenoManifestAbsent() {
        LinuxRuntime.maliDevice = File(root, "nonexistent-mali").path
        val icdDir = File(LinuxRuntime.rootDir(context), "usr/share/vulkan/icd.d").apply { mkdirs() }
        val generic = File(icdDir, "zink_icd.json").apply { writeText("{}") }

        try {
            val selected = LinuxRuntime.vulkanIcd(context)
            assertNotNull(selected)
            assertEquals("Fallback manifest must be returned if freedreno absent", generic, selected)
        } finally {
            generic.delete()
        }
    }

    // -------------------------------------------------------------------------
    // 3. BwrapSpawner.gpu() Adreno Binding & Precedence
    // -------------------------------------------------------------------------

    @Test
    fun testBwrapSpawnerGpuBindsFreedrenoAndGeneratesFreedrenoIcd() {
        val fakeKgsl = File(root, "fake-kgsl").apply { createNewFile() }
        BwrapSpawner.kgslDevice = fakeKgsl.path
        BwrapSpawner.maliDevice = File(root, "nonexistent-mali").path

        val rootfs = File(root, "rootfs").apply { mkdirs() }
        val lib = File(rootfs, "usr/lib").apply { mkdirs() }
        listOf("libvulkan_freedreno.so", "libdisplay-info.so.3", "libSPIRV-Tools.so", "libSPIRV-Tools-opt.so").forEach {
            File(lib, it).createNewFile()
        }
        val sandRoot = File(root, "sandbox-root").apply { mkdirs() }
        val env = mutableMapOf<String, String>()

        val binds = BwrapSpawner.gpu(rootfs.path, sandRoot, env)

        assertNotNull("BwrapSpawner.gpu must return binds for Adreno", binds)
        assertTrue("libvulkan_freedreno.so must be bound", binds!!.any { it.guest == "/run/droiddeck-gpu/libvulkan_freedreno.so" })
        assertTrue("freedreno_icd.json must be bound", binds.any { it.guest == "/run/droiddeck-gpu/freedreno_icd.json" })
        assertFalse("panfrost must NOT be bound", binds.any { it.guest.contains("panfrost") })

        assertEquals("/run/droiddeck-gpu/freedreno_icd.json", env["VK_DRIVER_FILES"])
        assertEquals("/run/droiddeck-gpu/freedreno_icd.json", env["VK_ICD_FILENAMES"])
        assertEquals("zink", env["MESA_LOADER_DRIVER_OVERRIDE"])
        assertEquals("zink", env["GALLIUM_DRIVER"])

        val icdFile = File(sandRoot.parentFile, "freedreno_icd.json")
        assertTrue("freedreno_icd.json must exist on host", icdFile.isFile)
        val content = icdFile.readText()
        assertTrue("ICD file must point to freedreno library", content.contains("/run/droiddeck-gpu/libvulkan_freedreno.so"))
    }

    @Test
    fun testBwrapSpawnerPrecedenceWhenBothKgslAndMaliExist() {
        val fakeKgsl = File(root, "fake-kgsl").apply { createNewFile() }
        val fakeMali = File(root, "fake-mali").apply { createNewFile() }
        BwrapSpawner.kgslDevice = fakeKgsl.path
        BwrapSpawner.maliDevice = fakeMali.path

        val rootfs = File(root, "rootfs").apply { mkdirs() }
        val lib = File(rootfs, "usr/lib").apply { mkdirs() }
        listOf(
            "libvulkan_freedreno.so",
            "libvulkan_panfrost.so",
            "libdisplay-info.so.3",
            "libSPIRV-Tools.so",
            "libSPIRV-Tools-opt.so"
        ).forEach {
            File(lib, it).createNewFile()
        }
        val sandRoot = File(root, "sandbox-root").apply { mkdirs() }
        val env = mutableMapOf<String, String>()

        val binds = BwrapSpawner.gpu(rootfs.path, sandRoot, env)

        assertNotNull(binds)
        assertTrue("freedreno must take precedence over panfrost", binds!!.any { it.guest == "/run/droiddeck-gpu/libvulkan_freedreno.so" })
        assertTrue("freedreno_icd.json must be bound", binds.any { it.guest == "/run/droiddeck-gpu/freedreno_icd.json" })
        assertFalse("panfrost must NOT be bound", binds.any { it.guest.contains("panfrost") })
        assertEquals("/run/droiddeck-gpu/freedreno_icd.json", env["VK_DRIVER_FILES"])
    }

    @Test
    fun testBwrapSpawnerReturnsNullWhenFreedrenoLibraryMissingOnAdreno() {
        val fakeKgsl = File(root, "fake-kgsl").apply { createNewFile() }
        BwrapSpawner.kgslDevice = fakeKgsl.path
        BwrapSpawner.maliDevice = File(root, "nonexistent-mali").path

        val rootfs = File(root, "rootfs").apply { mkdirs() }
        val lib = File(rootfs, "usr/lib").apply { mkdirs() }
        listOf("libdisplay-info.so.3", "libSPIRV-Tools.so", "libSPIRV-Tools-opt.so").forEach {
            File(lib, it).createNewFile()
        }
        val sandRoot = File(root, "sandbox-root").apply { mkdirs() }
        val env = mutableMapOf<String, String>()

        val binds = BwrapSpawner.gpu(rootfs.path, sandRoot, env)
        assertNull("Missing libvulkan_freedreno.so must return null (fallback)", binds)
    }

    // -------------------------------------------------------------------------
    // 4. SessionService.kt TU_DEBUG Injection & Mali Isolation
    // -------------------------------------------------------------------------

    @Test
    fun testSessionServiceInjectsTuDebugOnAdrenoByDefault() {
        val service = Robolectric.buildService(SessionService::class.java).get()
        SessionPrefs.setLinuxDriver(service, "")
        SessionPrefs.setTuSysmem(service, true)

        val guest = mutableListOf<String>()
        ReflectionHelpers.callInstanceMethod<Void>(
            service,
            "addClientEnvironment",
            ReflectionHelpers.ClassParameter.from(MutableList::class.java, guest),
            ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, false)
        )

        assertTrue("TU_DEBUG=sysmem must be injected on Adreno", guest.contains("TU_DEBUG=sysmem"))
        assertTrue("DROIDDECK_GAME_TU_DEBUG key must be injected", guest.any { it.startsWith("DROIDDECK_GAME_TU_DEBUG=") })
        assertFalse("PAN_MESA_DEBUG must NOT be injected on Adreno", guest.any { it.startsWith("PAN_MESA_DEBUG=") })
    }

    @Test
    fun testSessionServiceTuDebugOverrideFileRespectedOnAdreno() {
        val service = Robolectric.buildService(SessionService::class.java).get()
        SessionPrefs.setLinuxDriver(service, "")

        val overrideFile = File(Environment.getExternalStorageDirectory(), "Download/droiddeck-tu-debug").apply {
            parentFile?.mkdirs()
            writeText("sysmem,noconform")
        }

        try {
            val guest = mutableListOf<String>()
            ReflectionHelpers.callInstanceMethod<Void>(
                service,
                "addClientEnvironment",
                ReflectionHelpers.ClassParameter.from(MutableList::class.java, guest),
                ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, false)
            )

            assertTrue("TU_DEBUG override must be respected", guest.contains("TU_DEBUG=sysmem,noconform"))
            assertTrue("DROIDDECK_GAME_TU_DEBUG override must be respected", guest.contains("DROIDDECK_GAME_TU_DEBUG=sysmem,noconform"))
        } finally {
            overrideFile.delete()
        }
    }

    @Test
    fun testSessionServiceTurnipExplicitDriverInjectsTuDebug() {
        val service = Robolectric.buildService(SessionService::class.java).get()
        SessionPrefs.setLinuxDriver(service, "turnip-v24")

        val guest = mutableListOf<String>()
        ReflectionHelpers.callInstanceMethod<Void>(
            service,
            "addClientEnvironment",
            ReflectionHelpers.ClassParameter.from(MutableList::class.java, guest),
            ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, false)
        )

        assertTrue("Turnip driver must inject TU_DEBUG", guest.contains("TU_DEBUG=sysmem"))
        assertFalse("Turnip driver must NOT inject PAN_MESA_DEBUG", guest.any { it.startsWith("PAN_MESA_DEBUG=") })
    }

    @Test
    fun testSessionServicePanfrostDriverDoesNotInjectTuDebug() {
        val service = Robolectric.buildService(SessionService::class.java).get()
        SessionPrefs.setLinuxDriver(service, "panfrost-v24")

        val panDebugFile = File(Environment.getExternalStorageDirectory(), "Download/droiddeck-pan-debug").apply {
            parentFile?.mkdirs()
            writeText("dump,deqp")
        }

        try {
            val guest = mutableListOf<String>()
            ReflectionHelpers.callInstanceMethod<Void>(
                service,
                "addClientEnvironment",
                ReflectionHelpers.ClassParameter.from(MutableList::class.java, guest),
                ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, false)
            )

            assertFalse("Panfrost driver must NOT inject TU_DEBUG", guest.any { it.startsWith("TU_DEBUG=") })
            assertFalse("Panfrost driver must NOT inject DROIDDECK_GAME_TU_DEBUG", guest.any { it.startsWith("DROIDDECK_GAME_TU_DEBUG=sysmem") })
            assertTrue("Panfrost driver must inject PAN_MESA_DEBUG", guest.contains("PAN_MESA_DEBUG=dump,deqp"))
        } finally {
            panDebugFile.delete()
        }
    }
}

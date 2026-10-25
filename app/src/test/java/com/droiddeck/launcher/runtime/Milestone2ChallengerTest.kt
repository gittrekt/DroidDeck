package com.droiddeck.launcher.runtime

import android.content.ContextWrapper
import android.os.Environment
import android.util.Base64
import com.droiddeck.launcher.core.DeviceSupport
import com.droiddeck.launcher.gpu.LinuxVulkanDriver
import com.droiddeck.launcher.gpu.LinuxVulkanDriverManager
import com.droiddeck.launcher.runtime.BwrapSpawner.Bind
import com.droiddeck.launcher.session.SessionPhase
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.SessionService
import com.droiddeck.launcher.session.SessionState
import org.json.JSONArray
import org.json.JSONObject
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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import android.system.Os
import android.system.StructUtsname
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Milestone2ChallengerTest {

    private lateinit var root: File
    private lateinit var bwrapDir: File
    private lateinit var runtimeDir: File

    private val context get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        root = Files.createTempDirectory("m2-challenger-root").toFile()
        bwrapDir = Files.createTempDirectory("m2-challenger-bwrap").toFile()
        runtimeDir = File(LinuxRuntime.rootDir(context), "runtime-files").apply { mkdirs() }
        SessionState.running = false
        SessionState.phase = SessionPhase.IDLE
    }

    @After
    fun tearDown() {
        SessionState.running = false
        SessionState.phase = SessionPhase.IDLE
        root.deleteRecursively()
        bwrapDir.deleteRecursively()
        runtimeDir.deleteRecursively()
        File(LinuxRuntime.rootDir(context), "usr").deleteRecursively()
        File(context.filesDir, LinuxVulkanDriverManager.DIR_NAME).deleteRecursively()
        val download = File(Environment.getExternalStorageDirectory(), "Download")
        File(download, "droiddeck-pan-debug").delete()
        File(download, "droiddeck-tu-debug").delete()
    }

    private fun op(vararg pairs: Pair<String, Any>) = JSONObject().apply {
        pairs.forEach { (k, v) -> put(k, v) }
    }

    private fun request(vararg ops: JSONObject, envPairs: Map<String, String> = emptyMap()) = JSONObject()
        .put("argv", JSONArray().put("/app/bin/game"))
        .put("env", JSONObject().apply {
            put("FLATPAK_ID", "org.example.Game")
            envPairs.forEach { (k, v) -> put(k, v) }
        })
        .put("cwd", "/app")
        .put("ops", JSONArray().apply { ops.forEach { put(it) } })

    // =========================================================================
    // 1. PRoot Command Generation & Sysfs Binding Stress Tests (LinuxRuntime.java)
    // =========================================================================

    @Test
    fun challengeProotCommandGenerationWithMaliDevice() {
        val prevMali = LinuxRuntime.maliDevice
        val prevKgsl = LinuxRuntime.kgslDevice
        val fakeMali = File(root, "fake-mali0").apply { createNewFile() }
        val nonexistentKgsl = File(root, "nonexistent-kgsl")

        try {
            LinuxRuntime.maliDevice = fakeMali.path
            LinuxRuntime.kgslDevice = nonexistentKgsl.path

            val binds = LinuxRuntime.binds(context, null, null, null, null)

            // 1. Verify /dev/mali0:/dev/mali0 in binds
            assertTrue("Expected /dev/mali0:/dev/mali0 in command binds", binds.contains("/dev/mali0:/dev/mali0"))

            // 2. Verify sys/dev/char in binds
            assertTrue("Expected :/sys/dev/char bind in command binds", binds.any { it.endsWith(":/sys/dev/char") })

            // 3. Verify PRoot arguments translation: PRoot receives -b <spec>
            val prootArgs = mutableListOf<String>()
            for (spec in binds) {
                prootArgs.add("-b")
                prootArgs.add(spec)
            }
            val maliBindIndex = prootArgs.indexOf("/dev/mali0:/dev/mali0")
            assertTrue("Expected /dev/mali0:/dev/mali0 in prootArgs", maliBindIndex > 0)
            assertEquals("Expected -b flag preceding /dev/mali0:/dev/mali0", "-b", prootArgs[maliBindIndex - 1])

            val sysCharBindIndex = prootArgs.indexOfFirst { it.endsWith(":/sys/dev/char") }
            assertTrue("Expected :/sys/dev/char bind in prootArgs", sysCharBindIndex > 0)
            assertEquals("Expected -b flag preceding sys/dev/char bind", "-b", prootArgs[sysCharBindIndex - 1])

            // 4. Verify Adreno DRM render nodes and kgsl are completely absent
            assertFalse("Adreno render node must not be bound on Mali", binds.any { it.contains("renderD") })
            assertFalse("KGSL device must not be bound on Mali", binds.any { it.contains("kgsl-3d0") })
            assertFalse("sys/class/drm must not be bound on Mali", binds.any { it.endsWith(":/sys/class/drm") })

            // 5. Verify sysfs synthetic uevent content
            val live = LinuxRuntime.liveDir(context)
            val charBase = File(live, "drm/sys")
            val charDevs = charBase.listFiles()
            assertNotNull("drm/sys must be created", charDevs)
            assertTrue("drm/sys must contain major:minor directory", charDevs!!.isNotEmpty())

            val charDev = charDevs.first()
            val uevent = File(charDev, "uevent")
            assertTrue("uevent file must exist in sys/dev/char/<dev>", uevent.isFile)
            val ueventText = uevent.readText()
            assertTrue("uevent must contain DEVNAME=mali0", ueventText.contains("DEVNAME=mali0"))
            assertTrue("uevent must contain MAJOR=", ueventText.contains("MAJOR="))
            assertTrue("uevent must contain MINOR=", ueventText.contains("MINOR="))

            val devUevent = File(charDev, "device/uevent")
            assertTrue("device/uevent file must exist", devUevent.isFile)
            val devUeventText = devUevent.readText()
            assertEquals(
                "device/uevent must declare driver mali and platform alias",
                "DRIVER=mali\nMODALIAS=platform:mali\n",
                devUeventText
            )
        } finally {
            LinuxRuntime.maliDevice = prevMali
            LinuxRuntime.kgslDevice = prevKgsl
        }
    }

    @Test
    fun challengeProotCommandWithoutGpuNodesDoesNotBindGpu() {
        val prevMali = LinuxRuntime.maliDevice
        val prevKgsl = LinuxRuntime.kgslDevice
        try {
            LinuxRuntime.maliDevice = File(root, "nonexistent-mali").path
            LinuxRuntime.kgslDevice = File(root, "nonexistent-kgsl").path

            val binds = LinuxRuntime.binds(context, null, null, null, null)

            assertFalse("Must not bind /dev/mali0 when device absent", binds.contains("/dev/mali0:/dev/mali0"))
            assertFalse("Must not bind sys/dev/char when no GPU present", binds.any { it.endsWith(":/sys/dev/char") })
            assertFalse("Must not bind renderD", binds.any { it.contains("renderD") })
            assertFalse("Must not bind kgsl", binds.any { it.contains("kgsl-3d0") })
        } finally {
            LinuxRuntime.maliDevice = prevMali
            LinuxRuntime.kgslDevice = prevKgsl
        }
    }

    @Test
    fun challengeProotCommandAdrenoPrecedenceOverMali() {
        val prevMali = LinuxRuntime.maliDevice
        val prevKgsl = LinuxRuntime.kgslDevice
        val fakeMali = File(root, "fake-mali0").apply { createNewFile() }
        val fakeKgsl = File(root, "fake-kgsl").apply { createNewFile() }

        try {
            LinuxRuntime.maliDevice = fakeMali.path
            LinuxRuntime.kgslDevice = fakeKgsl.path

            val binds = LinuxRuntime.binds(context, null, null, null, null)

            // When both are present, Adreno path takes precedence
            assertTrue("Adreno render node must be bound", binds.any { it.contains("renderD") })
            assertFalse("/dev/mali0 must not be bound when Adreno is present", binds.contains("/dev/mali0:/dev/mali0"))
        } finally {
            LinuxRuntime.maliDevice = prevMali
            LinuxRuntime.kgslDevice = prevKgsl
        }
    }

    @Test
    fun challengeProotVulkanIcdSelectionPriorities() {
        val prevMali = LinuxRuntime.maliDevice
        val fakeMali = File(root, "fake-mali0").apply { createNewFile() }
        val icdDir = File(LinuxRuntime.rootDir(context), "usr/share/vulkan/icd.d").apply { mkdirs() }
        val freedreno = File(icdDir, "freedreno_icd.json").apply { writeText("{\"ICD\":{}}") }
        val panfrost = File(icdDir, "panfrost_icd.json").apply { writeText("{\"ICD\":{}}") }
        val panvk = File(icdDir, "panvk_icd.x86_64.json").apply { writeText("{\"ICD\":{}}") }

        try {
            // Case 1: Mali present with both panfrost and freedreno
            LinuxRuntime.maliDevice = fakeMali.path
            val selectedOnMali = LinuxRuntime.vulkanIcd(context)
            assertNotNull(selectedOnMali)
            assertTrue(
                "On Mali, ICD must be panfrost or panvk",
                selectedOnMali!!.name.contains("panfrost") || selectedOnMali.name.contains("panvk")
            )

            // Case 2: Mali present with only panvk and freedreno
            panfrost.delete()
            val selectedPanvk = LinuxRuntime.vulkanIcd(context)
            assertEquals("PanVK manifest must be picked when panfrost is absent on Mali", panvk, selectedPanvk)

            // Case 3: Mali absent with freedreno and panvk
            LinuxRuntime.maliDevice = File(root, "nonexistent-mali").path
            val selectedAdreno = LinuxRuntime.vulkanIcd(context)
            assertEquals("Freedreno manifest must be picked on Adreno", freedreno, selectedAdreno)

            // Case 4: No matching names falls back to first manifest
            freedreno.delete()
            panvk.delete()
            val custom = File(icdDir, "custom_icd.json").apply { writeText("{\"ICD\":{}}") }
            val fallback = LinuxRuntime.vulkanIcd(context)
            assertEquals("Fallback must return available manifest", custom, fallback)
        } finally {
            LinuxRuntime.maliDevice = prevMali
            icdDir.deleteRecursively()
        }
    }

    // =========================================================================
    // 2. Bwrap Plan Generation Stress Tests (BwrapSpawner.kt)
    // =========================================================================

    @Test
    fun challengeBwrapPlanPanfrostBindingAndEnvironment() {
        val prevMali = BwrapSpawner.maliDevice
        val prevKgsl = BwrapSpawner.kgslDevice
        val fakeMali = File(bwrapDir, "fake-mali0").apply { createNewFile() }
        val lib = File(LinuxRuntime.rootDir(context), "usr/lib").apply { mkdirs() }

        val files = listOf(
            "libvulkan_panfrost.so",
            "libdisplay-info.so.3",
            "libSPIRV-Tools.so",
            "libSPIRV-Tools-opt.so"
        )
        files.forEach { File(lib, it).writeText("ELF-DUMMY") }

        try {
            BwrapSpawner.maliDevice = fakeMali.path
            BwrapSpawner.kgslDevice = File(bwrapDir, "nonexistent-kgsl").path

            val plan = BwrapSpawner.plan(context, request(op("t" to "dev", "dest" to "/dev")), bwrapDir)

            // 1. Verify all expected binds
            assertTrue(
                "Driver bind must exist",
                plan.binds.any { it.guest == "/run/droiddeck-gpu/libvulkan_panfrost.so" }
            )
            assertTrue(
                "ICD manifest bind must exist",
                plan.binds.any { it.guest == "/run/droiddeck-gpu/panfrost_icd.json" }
            )
            files.forEach { name ->
                assertTrue("Companion lib bind must exist for $name", plan.binds.any { it.guest == "/run/droiddeck-gpu/$name" })
            }

            // 2. Verify environment exports
            assertEquals("/run/droiddeck-gpu/panfrost_icd.json", plan.env["VK_DRIVER_FILES"])
            assertEquals("/run/droiddeck-gpu/panfrost_icd.json", plan.env["VK_ICD_FILENAMES"])
            assertEquals("zink", plan.env["MESA_LOADER_DRIVER_OVERRIDE"])
            assertEquals("zink", plan.env["GALLIUM_DRIVER"])
            assertTrue("LD_LIBRARY_PATH must include /run/droiddeck-gpu", plan.env["LD_LIBRARY_PATH"]!!.contains("/run/droiddeck-gpu"))

            // 3. Verify panfrost_icd.json syntax and content
            val icdFile = File(plan.root.parentFile, "panfrost_icd.json")
            assertTrue("panfrost_icd.json must be written to disk", icdFile.isFile)
            val json = JSONObject(icdFile.readText())
            assertEquals("1.0.0", json.getString("file_format_version"))
            val icdObj = json.getJSONObject("ICD")
            assertEquals("/run/droiddeck-gpu/libvulkan_panfrost.so", icdObj.getString("library_path"))
            assertEquals("1.4.0", icdObj.getString("api_version"))
        } finally {
            BwrapSpawner.maliDevice = prevMali
            BwrapSpawner.kgslDevice = prevKgsl
        }
    }

    @Test
    fun challengeBwrapPlanPreservesExistingVkEnvironment() {
        val prevMali = BwrapSpawner.maliDevice
        val prevKgsl = BwrapSpawner.kgslDevice
        val fakeMali = File(bwrapDir, "fake-mali0").apply { createNewFile() }
        val lib = File(LinuxRuntime.rootDir(context), "usr/lib").apply { mkdirs() }
        listOf("libvulkan_panfrost.so", "libdisplay-info.so.3", "libSPIRV-Tools.so", "libSPIRV-Tools-opt.so").forEach {
            File(lib, it).writeText("ELF-DUMMY")
        }

        try {
            BwrapSpawner.maliDevice = fakeMali.path
            BwrapSpawner.kgslDevice = File(bwrapDir, "nonexistent-kgsl").path

            // Caller provides VK_DRIVER_FILES
            val planDriverFiles = BwrapSpawner.plan(
                context,
                request(op("t" to "dev", "dest" to "/dev"), envPairs = mapOf("VK_DRIVER_FILES" to "/custom/custom_icd.json")),
                bwrapDir
            )
            assertFalse(
                "Should not inject GPU binds when VK_DRIVER_FILES is already specified",
                planDriverFiles.binds.any { it.guest.startsWith("/run/droiddeck-gpu") }
            )
            assertEquals("/custom/custom_icd.json", planDriverFiles.env["VK_DRIVER_FILES"])

            // Caller provides VK_ICD_FILENAMES
            val planIcdFilenames = BwrapSpawner.plan(
                context,
                request(op("t" to "dev", "dest" to "/dev"), envPairs = mapOf("VK_ICD_FILENAMES" to "/custom/custom_icd.json")),
                bwrapDir
            )
            assertFalse(
                "Should not inject GPU binds when VK_ICD_FILENAMES is already specified",
                planIcdFilenames.binds.any { it.guest.startsWith("/run/droiddeck-gpu") }
            )
            assertEquals("/custom/custom_icd.json", planIcdFilenames.env["VK_ICD_FILENAMES"])
        } finally {
            BwrapSpawner.maliDevice = prevMali
            BwrapSpawner.kgslDevice = prevKgsl
        }
    }

    @Test
    fun challengeBwrapPlanDesktopWithoutGamescopeDisablesGpu() {
        val prevMali = BwrapSpawner.maliDevice
        val prevKgsl = BwrapSpawner.kgslDevice
        val fakeMali = File(bwrapDir, "fake-mali0").apply { createNewFile() }
        val lib = File(LinuxRuntime.rootDir(context), "usr/lib").apply { mkdirs() }
        listOf("libvulkan_panfrost.so", "libdisplay-info.so.3", "libSPIRV-Tools.so", "libSPIRV-Tools-opt.so").forEach {
            File(lib, it).writeText("ELF-DUMMY")
        }

        try {
            BwrapSpawner.maliDevice = fakeMali.path
            BwrapSpawner.kgslDevice = File(bwrapDir, "nonexistent-kgsl").path

            val plan = BwrapSpawner.plan(
                context,
                request(op("t" to "dev", "dest" to "/dev"), envPairs = mapOf("BL_DESKTOP" to "1")),
                bwrapDir
            )
            assertFalse(
                "Desktop session without Gamescope wayland display must not bind GPU",
                plan.binds.any { it.guest.startsWith("/run/droiddeck-gpu") }
            )
        } finally {
            BwrapSpawner.maliDevice = prevMali
            BwrapSpawner.kgslDevice = prevKgsl
        }
    }

    @Test
    fun challengeBwrapPlanMissingCompanionLibFallsBackCleanly() {
        val prevMali = BwrapSpawner.maliDevice
        val prevKgsl = BwrapSpawner.kgslDevice
        val fakeMali = File(bwrapDir, "fake-mali0").apply { createNewFile() }
        val lib = File(LinuxRuntime.rootDir(context), "usr/lib").apply { mkdirs() }
        // Panfrost exists, but libdisplay-info.so.3 is missing
        File(lib, "libvulkan_panfrost.so").writeText("ELF-DUMMY")
        File(lib, "libSPIRV-Tools.so").writeText("ELF-DUMMY")
        File(lib, "libSPIRV-Tools-opt.so").writeText("ELF-DUMMY")

        try {
            BwrapSpawner.maliDevice = fakeMali.path
            BwrapSpawner.kgslDevice = File(bwrapDir, "nonexistent-kgsl").path

            val plan = BwrapSpawner.plan(context, request(op("t" to "dev", "dest" to "/dev")), bwrapDir)

            assertFalse(
                "Missing companion lib must cause fallback (no GPU binds)",
                plan.binds.any { it.guest.startsWith("/run/droiddeck-gpu") }
            )
            assertNull(plan.env["VK_DRIVER_FILES"])
            assertNull(plan.env["VK_ICD_FILENAMES"])
        } finally {
            BwrapSpawner.maliDevice = prevMali
            BwrapSpawner.kgslDevice = prevKgsl
        }
    }

    // =========================================================================
    // 3. SessionService.kt Environment Flag Stress Tests
    // =========================================================================

    private fun invokeAddClientEnvironment(service: SessionService, guest: MutableList<String>, steamHere: Boolean = false) {
        val method = SessionService::class.java.getDeclaredMethod(
            "addClientEnvironment",
            MutableList::class.java,
            Boolean::class.javaPrimitiveType
        )
        method.isAccessible = true
        method.invoke(service, guest, steamHere)
    }

    @Test
    fun challengeSessionServiceExcludesTuDebugAndIncludesPanDebugForPanvk() {
        val service = Robolectric.buildService(SessionService::class.java).get()
        val download = File(Environment.getExternalStorageDirectory(), "Download").apply { mkdirs() }
        val panDebugFile = File(download, "droiddeck-pan-debug").apply { writeText("sync,dump") }
        val tuDebugFile = File(download, "droiddeck-tu-debug").apply { writeText("noconform") }

        // Configure driver with "panvk" in name
        val driverId = "test_panvk_driver"
        val driverDir = File(File(service.filesDir, LinuxVulkanDriverManager.DIR_NAME), driverId).apply { mkdirs() }
        File(driverDir, LinuxVulkanDriverManager.META_NAME).writeText(
            JSONObject().put("name", "Mesa PanVK Mali Driver 24.2").toString()
        )
        SessionPrefs.setLinuxDriver(service, driverId)
        SessionPrefs.setTuSysmem(service, true)

        try {
            val guest = mutableListOf<String>()
            invokeAddClientEnvironment(service, guest)

            // TU_DEBUG must be completely excluded
            assertFalse("TU_DEBUG must not be in guest env when driver is PanVK", guest.any { it.startsWith("TU_DEBUG=") })
            assertFalse("DROIDDECK_GAME_TU_DEBUG must not be in guest env when driver is PanVK", guest.any { it.startsWith("DROIDDECK_GAME_TU_DEBUG=") })

            // PAN_MESA_DEBUG must be included with file override
            assertTrue("PAN_MESA_DEBUG must be in guest env", guest.contains("PAN_MESA_DEBUG=sync,dump"))
        } finally {
            panDebugFile.delete()
            tuDebugFile.delete()
            SessionPrefs.setLinuxDriver(service, "")
        }
    }

    @Test
    fun challengeSessionServiceCaseInsensitivePanfrostDriverName() {
        val service = Robolectric.buildService(SessionService::class.java).get()

        // Configure driver with uppercase "PANFROST" in name
        val driverId = "upper_panfrost"
        val driverDir = File(File(service.filesDir, LinuxVulkanDriverManager.DIR_NAME), driverId).apply { mkdirs() }
        File(driverDir, LinuxVulkanDriverManager.META_NAME).writeText(
            JSONObject().put("name", "ARM PANFROST V24.1").toString()
        )
        SessionPrefs.setLinuxDriver(service, driverId)
        SessionPrefs.setTuSysmem(service, true)

        try {
            val guest = mutableListOf<String>()
            invokeAddClientEnvironment(service, guest)

            assertFalse("TU_DEBUG must be excluded for uppercase PANFROST", guest.any { it.startsWith("TU_DEBUG=") })
            assertFalse("DROIDDECK_GAME_TU_DEBUG must be excluded for uppercase PANFROST", guest.any { it.startsWith("DROIDDECK_GAME_TU_DEBUG=") })
        } finally {
            SessionPrefs.setLinuxDriver(service, "")
        }
    }

    @Test
    fun challengeSessionServiceDriverFallbackToIdWhenMetaMissing() {
        val service = Robolectric.buildService(SessionService::class.java).get()

        // Driver ID itself contains "panfrost" without meta.json
        val driverId = "custom-panfrost-experimental"
        File(File(service.filesDir, LinuxVulkanDriverManager.DIR_NAME), driverId).apply { mkdirs() }
        SessionPrefs.setLinuxDriver(service, driverId)
        SessionPrefs.setTuSysmem(service, true)

        try {
            val guest = mutableListOf<String>()
            invokeAddClientEnvironment(service, guest)

            assertFalse("TU_DEBUG must be excluded when driver ID contains panfrost", guest.any { it.startsWith("TU_DEBUG=") })
            assertFalse("DROIDDECK_GAME_TU_DEBUG must be excluded when driver ID contains panfrost", guest.any { it.startsWith("DROIDDECK_GAME_TU_DEBUG=") })
        } finally {
            SessionPrefs.setLinuxDriver(service, "")
        }
    }

    @Test
    fun challengeSessionServiceTurnipDriverIncludesTuDebugAndExcludesPanDebug() {
        val service = Robolectric.buildService(SessionService::class.java).get()
        val download = File(Environment.getExternalStorageDirectory(), "Download").apply { mkdirs() }
        val panDebugFile = File(download, "droiddeck-pan-debug").apply { writeText("sync,dump") }
        val tuDebugFile = File(download, "droiddeck-tu-debug").apply { writeText("noconform") }

        // Configure driver with Turnip
        val driverId = "turnip_v24"
        val driverDir = File(File(service.filesDir, LinuxVulkanDriverManager.DIR_NAME), driverId).apply { mkdirs() }
        File(driverDir, LinuxVulkanDriverManager.META_NAME).writeText(
            JSONObject().put("name", "Freedreno Turnip 24.2.0").toString()
        )
        SessionPrefs.setLinuxDriver(service, driverId)
        SessionPrefs.setTuSysmem(service, true)

        try {
            val guest = mutableListOf<String>()
            invokeAddClientEnvironment(service, guest)

            // Turnip debug flags must be present
            assertTrue("TU_DEBUG must reflect tuDebug override", guest.contains("TU_DEBUG=noconform"))
            assertTrue("DROIDDECK_GAME_TU_DEBUG must reflect tuDebug override", guest.contains("DROIDDECK_GAME_TU_DEBUG=noconform"))

            // PAN_MESA_DEBUG must NOT be present
            assertFalse("PAN_MESA_DEBUG must not be injected for Turnip", guest.any { it.startsWith("PAN_MESA_DEBUG=") })
        } finally {
            panDebugFile.delete()
            tuDebugFile.delete()
            SessionPrefs.setLinuxDriver(service, "")
        }
    }

    @Test
    fun challengeSessionServicePanDebugWhitespaceAndEmptyHandling() {
        val service = Robolectric.buildService(SessionService::class.java).get()
        val download = File(Environment.getExternalStorageDirectory(), "Download").apply { mkdirs() }
        val panDebugFile = File(download, "droiddeck-pan-debug")

        val driverId = "panvk_debug_test"
        File(File(service.filesDir, LinuxVulkanDriverManager.DIR_NAME), driverId).apply { mkdirs() }
        SessionPrefs.setLinuxDriver(service, driverId)

        try {
            // Case 1: whitespace in file
            panDebugFile.writeText("  trace,dump \n ")
            var guest = mutableListOf<String>()
            invokeAddClientEnvironment(service, guest)
            assertTrue("PAN_MESA_DEBUG must be trimmed", guest.contains("PAN_MESA_DEBUG=trace,dump"))

            // Case 2: empty or only whitespace file
            panDebugFile.writeText("   \n\t  ")
            guest = mutableListOf<String>()
            invokeAddClientEnvironment(service, guest)
            assertFalse("PAN_MESA_DEBUG must not be added when file is blank", guest.any { it.startsWith("PAN_MESA_DEBUG=") })
        } finally {
            panDebugFile.delete()
            SessionPrefs.setLinuxDriver(service, "")
        }
    }

    @Test
    fun challengeSessionServiceDefaultDriverOnAdrenoDevice() {
        val service = Robolectric.buildService(SessionService::class.java).get()
        SessionPrefs.setLinuxDriver(service, "") // default runtime driver
        SessionPrefs.setTuSysmem(service, true)

        // On test host where /dev/mali0 and /vendor/lib64/hw/vulkan.mali.so do not exist,
        // isTurnip evaluates to true.
        if (!File(LinuxRuntime.MALI_DEVICE).exists() && !DeviceSupport.mali()) {
            val guest = mutableListOf<String>()
            invokeAddClientEnvironment(service, guest)

            assertTrue("Default driver on non-Mali device must have TU_DEBUG=sysmem", guest.contains("TU_DEBUG=sysmem"))
            assertTrue("Default driver on non-Mali device has DROIDDECK_GAME_TU_DEBUG key", guest.any { it.startsWith("DROIDDECK_GAME_TU_DEBUG=") })
            assertFalse("PAN_MESA_DEBUG must not be present on non-Mali device", guest.any { it.startsWith("PAN_MESA_DEBUG=") })
        }
    }
}

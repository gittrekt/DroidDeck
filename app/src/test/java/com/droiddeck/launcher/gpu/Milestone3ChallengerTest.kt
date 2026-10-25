package com.droiddeck.launcher.gpu

import android.content.Context
import android.net.Uri
import com.droiddeck.launcher.core.FileUtils
import com.droiddeck.launcher.gpu.GpuInfo.Family
import com.droiddeck.launcher.gpu.GpuInfo.Support
import com.droiddeck.launcher.session.SessionPrefs
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class Milestone3ChallengerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "graphics_driver").deleteRecursively()
        File(context.filesDir, LinuxVulkanDriverManager.DIR_NAME).deleteRecursively()
        context.getSharedPreferences("driver_bundles", Context.MODE_PRIVATE).edit().clear().commit()
        SessionPrefs.setAndroidDriver(context, "")
        SessionPrefs.setLinuxDriver(context, "")
    }

    private fun elfLibrary(libc: String): ByteArray {
        val bytes = ByteArray(64).also {
            byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 2, 1).copyInto(it)
            it[18] = 0xB7.toByte() // aarch64
        }
        return bytes + libc.toByteArray() + ByteArray(1)
    }

    private fun zip(entries: Map<String, ByteArray>): Uri {
        val file = File.createTempFile("challenger_bundle", ".zip", context.cacheDir)
        ZipOutputStream(file.outputStream()).use { z ->
            for ((name, data) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(data)
                z.closeEntry()
            }
        }
        return Uri.fromFile(file)
    }

    // =========================================================================
    // 1. DriverBundle & Panfrost Staging Oracle Tests
    // =========================================================================

    @Test
    fun challengeDriverBundlePanfrostInstallationLifecycle() {
        val panfrostManifest = JSONObject()
            .put("package", "PanVK-Mali")
            .put("version", "24.3.0")
            .put("tag", "panvk-g615-v24.3.0")
            .put("drivers", JSONObject()
                .put("android", JSONObject().put("path", "android"))
                .put("linux", JSONObject().put("path", "linux")))

        val bundleZip = zip(mapOf(
            "android/meta.json" to JSONObject()
                .put("name", "PanVK Android")
                .put("libraryName", "vulkan.panfrost.so")
                .toString().toByteArray(),
            "android/vulkan.panfrost.so" to elfLibrary("libc.so"),
            "linux/meta.json" to JSONObject()
                .put("name", "Mesa PanVK")
                .put("driverVersion", "24.3.0")
                .put("minGlibc", "2.35")
                .put("libraryName", "libvulkan_panfrost.so")
                .toString().toByteArray(),
            "linux/libvulkan_panfrost.so" to elfLibrary("libc.so.6"),
            "manifest.json" to panfrostManifest.toString().toByteArray(),
        ))

        val bundle = DriverBundle.install(context, bundleZip)
        assertEquals("panvk-g615-v24.3.0", bundle.id)
        assertEquals("PanVK-Mali 24.3.0", bundle.label)
        assertEquals("PanVK-Mali", bundle.name)
        assertEquals("24.3.0", bundle.version)

        val lm = LinuxVulkanDriverManager(context)
        val td = TurnipDriver(context)

        // 1. Verify installed state
        assertTrue(lm.isInstalled(bundle.linuxId))
        assertTrue(td.isInstalled(bundle.androidId))

        // 2. Verify driver directory layout
        val driverDir = lm.getDriverDir(bundle.linuxId)
        val panfrostSo = File(driverDir, LinuxVulkanDriverManager.PANFROST_LIB_NAME)
        val freedrenoSo = File(driverDir, LinuxVulkanDriverManager.LIB_NAME)
        val icdJsonFile = File(driverDir, LinuxVulkanDriverManager.ICD_NAME)
        val metaJsonFile = File(driverDir, LinuxVulkanDriverManager.META_NAME)

        assertTrue("libvulkan_panfrost.so must exist in driver directory", panfrostSo.isFile)
        assertFalse("libvulkan_freedreno.so must NOT exist in panfrost driver directory", freedrenoSo.exists())
        assertTrue("icd.json must exist in driver directory", icdJsonFile.isFile)
        assertTrue("meta.json must exist in driver directory", metaJsonFile.isFile)

        // 3. Verify icd.json contents
        val icdContent = JSONObject(FileUtils.readString(icdJsonFile)!!)
        assertEquals("1.0.0", icdContent.getString("file_format_version"))
        val icdBody = icdContent.getJSONObject("ICD")
        assertEquals(panfrostSo.absolutePath, icdBody.getString("library_path"))

        // 4. Verify meta.json contents
        val metaContent = JSONObject(FileUtils.readString(metaJsonFile)!!)
        assertEquals("linux-vulkan-icd", metaContent.getString("kind"))
        assertEquals("Mesa PanVK", metaContent.getString("name"))
        assertEquals("24.3.0", metaContent.getString("driverVersion"))
        assertEquals("2.35", metaContent.getString("minGlibc"))
        assertEquals("libvulkan_panfrost.so", metaContent.getString("sourceLibraryName"))

        // 5. Verify LinuxVulkanDriver ICD resolution
        val resolvedIcd = LinuxVulkanDriver.resolveIcdPath(context, bundle.linuxId)
        assertEquals(icdJsonFile.absolutePath, resolvedIcd)

        // 6. Verify selection and active bundle
        DriverBundle.select(context, bundle)
        assertEquals(bundle.androidId, SessionPrefs.androidDriver(context))
        assertEquals(bundle.linuxId, SessionPrefs.linuxDriver(context))
        assertEquals(bundle.id, DriverBundle.active(context)?.id)

        // 7. Verify deletion
        DriverBundle.remove(context, bundle)
        assertFalse(lm.isInstalled(bundle.linuxId))
        assertFalse(td.isInstalled(bundle.androidId))
        assertEquals("", SessionPrefs.androidDriver(context))
        assertEquals("", SessionPrefs.linuxDriver(context))
        assertNull(DriverBundle.active(context))
    }

    @Test
    fun challengeDriverBundlePanfrostCustomPrefixedLibraryName() {
        val panfrostManifest = JSONObject()
            .put("package", "PanVK-Custom")
            .put("version", "25.0.0")
            .put("tag", "panvk-custom-v25.0.0")
            .put("drivers", JSONObject()
                .put("android", JSONObject().put("path", "android"))
                .put("linux", JSONObject().put("path", "linux")))

        val bundleZip = zip(mapOf(
            "android/meta.json" to JSONObject()
                .put("name", "PanVK Android")
                .put("libraryName", "vulkan.panfrost.so")
                .toString().toByteArray(),
            "android/vulkan.panfrost.so" to elfLibrary("libc.so"),
            "linux/meta.json" to JSONObject()
                .put("name", "Mesa PanVK Custom")
                .put("driverVersion", "25.0.0")
                .put("libraryName", "libvulkan_panfrost_git.so")
                .toString().toByteArray(),
            "linux/libvulkan_panfrost_git.so" to elfLibrary("libc.so.6"),
            "manifest.json" to panfrostManifest.toString().toByteArray(),
        ))

        val bundle = DriverBundle.install(context, bundleZip)
        val lm = LinuxVulkanDriverManager(context)
        assertTrue(lm.isInstalled(bundle.linuxId))

        val driverDir = lm.getDriverDir(bundle.linuxId)
        val panfrostSo = File(driverDir, LinuxVulkanDriverManager.PANFROST_LIB_NAME)
        assertTrue("Custom libvulkan_panfrost_*.so should be staged as PANFROST_LIB_NAME", panfrostSo.isFile)

        val metaContent = JSONObject(FileUtils.readString(File(driverDir, LinuxVulkanDriverManager.META_NAME))!!)
        assertEquals("libvulkan_panfrost_git.so", metaContent.getString("sourceLibraryName"))
    }

    @Test
    fun challengeDriverBundlePanfrostRejectsCorruptElf() {
        val panfrostManifest = JSONObject()
            .put("package", "PanVK-Corrupt")
            .put("version", "1.0.0")
            .put("tag", "panvk-corrupt")
            .put("drivers", JSONObject()
                .put("android", JSONObject().put("path", "android"))
                .put("linux", JSONObject().put("path", "linux")))

        val bundleZip = zip(mapOf(
            "android/meta.json" to JSONObject().put("name", "PanVK Android").put("libraryName", "vulkan.panfrost.so").toString().toByteArray(),
            "android/vulkan.panfrost.so" to elfLibrary("libc.so"),
            "linux/meta.json" to JSONObject().put("name", "Mesa PanVK").put("libraryName", "libvulkan_panfrost.so").toString().toByteArray(),
            "linux/libvulkan_panfrost.so" to "NOT_AN_ELF_BINARY".toByteArray(),
            "manifest.json" to panfrostManifest.toString().toByteArray(),
        ))

        try {
            DriverBundle.install(context, bundleZip)
            fail("Installing a bundle with non-ELF linux library must fail")
        } catch (e: IllegalArgumentException) {
            assertTrue("Expected aarch64 rejection, got: ${e.message}", e.message!!.contains("aarch64", ignoreCase = true))
        }

        assertTrue(TurnipDriver(context).enumerateImported().isEmpty())
        assertTrue(LinuxVulkanDriverManager(context).enumerateInstalledDrivers().isEmpty())
    }

    @Test
    fun challengeDriverBundlePanfrostRejectsBionicInLinux() {
        val panfrostManifest = JSONObject()
            .put("package", "PanVK-Bionic")
            .put("version", "1.0.0")
            .put("tag", "panvk-bionic")
            .put("drivers", JSONObject()
                .put("android", JSONObject().put("path", "android"))
                .put("linux", JSONObject().put("path", "linux")))

        val bundleZip = zip(mapOf(
            "android/meta.json" to JSONObject().put("name", "PanVK Android").put("libraryName", "vulkan.panfrost.so").toString().toByteArray(),
            "android/vulkan.panfrost.so" to elfLibrary("libc.so"),
            "linux/meta.json" to JSONObject().put("name", "Mesa PanVK").put("libraryName", "libvulkan_panfrost.so").toString().toByteArray(),
            "linux/libvulkan_panfrost.so" to elfLibrary("libc.so"), // Bionic libc instead of glibc libc.so.6
            "manifest.json" to panfrostManifest.toString().toByteArray(),
        ))

        try {
            DriverBundle.install(context, bundleZip)
            fail("Installing a bundle with bionic linux library must fail")
        } catch (e: IllegalArgumentException) {
            assertTrue("Expected glibc rejection, got: ${e.message}", e.message!!.contains("glibc"))
        }

        assertTrue(TurnipDriver(context).enumerateImported().isEmpty())
        assertTrue(LinuxVulkanDriverManager(context).enumerateInstalledDrivers().isEmpty())
    }

    // =========================================================================
    // 2. LinuxVulkanDriverManager isInstalled Matrix & Standalone Zip Tests
    // =========================================================================

    @Test
    fun challengeLinuxVulkanDriverManagerIsInstalledExhaustiveMatrix() {
        val lm = LinuxVulkanDriverManager(context)

        // Null / empty / traversal guards
        assertFalse("null id", lm.isInstalled(null))
        assertFalse("empty id", lm.isInstalled(""))
        assertFalse("slash in id", lm.isInstalled("foo/bar"))
        assertFalse("absolute path", lm.isInstalled("/etc/passwd"))
        assertFalse("dotdot in id", lm.isInstalled("../foo"))
        assertFalse("dotdot suffix", lm.isInstalled("foo.."))
        assertFalse("non-existent id", lm.isInstalled("non_existent_id"))

        // Create various synthetic directories in rootDir to test partial vs complete states
        val testBase = "driver_state_test"

        // State A: Empty directory
        val dirA = lm.getDriverDir("${testBase}_empty").apply { mkdirs() }
        assertFalse("empty dir must not be installed", lm.isInstalled(dirA.name))

        // State B: Only icd.json, no library
        val dirB = lm.getDriverDir("${testBase}_only_icd").apply { mkdirs() }
        File(dirB, LinuxVulkanDriverManager.ICD_NAME).writeText("{}")
        assertFalse("dir with only icd.json must not be installed", lm.isInstalled(dirB.name))

        // State C: Only libvulkan_panfrost.so, no icd.json
        val dirC = lm.getDriverDir("${testBase}_only_panfrost").apply { mkdirs() }
        File(dirC, LinuxVulkanDriverManager.PANFROST_LIB_NAME).writeBytes(elfLibrary("libc.so.6"))
        assertFalse("dir with only panfrost lib must not be installed", lm.isInstalled(dirC.name))

        // State D: Only libvulkan_freedreno.so, no icd.json
        val dirD = lm.getDriverDir("${testBase}_only_freedreno").apply { mkdirs() }
        File(dirD, LinuxVulkanDriverManager.LIB_NAME).writeBytes(elfLibrary("libc.so.6"))
        assertFalse("dir with only freedreno lib must not be installed", lm.isInstalled(dirD.name))

        // State E: icd.json + a DIRECTORY named libvulkan_panfrost.so (not a file)
        val dirE = lm.getDriverDir("${testBase}_dir_as_lib").apply { mkdirs() }
        File(dirE, LinuxVulkanDriverManager.ICD_NAME).writeText("{}")
        File(dirE, LinuxVulkanDriverManager.PANFROST_LIB_NAME).mkdir()
        assertFalse("dir where panfrost lib is a directory must not be installed", lm.isInstalled(dirE.name))

        // State F: icd.json (directory) + libvulkan_panfrost.so (file)
        val dirF = lm.getDriverDir("${testBase}_dir_as_icd").apply { mkdirs() }
        File(dirF, LinuxVulkanDriverManager.ICD_NAME).mkdir()
        File(dirF, LinuxVulkanDriverManager.PANFROST_LIB_NAME).writeBytes(elfLibrary("libc.so.6"))
        assertFalse("dir where icd.json is a directory must not be installed", lm.isInstalled(dirF.name))

        // State G: icd.json (file) + libvulkan_panfrost.so (file) -> VALID PANFROST
        val dirG = lm.getDriverDir("${testBase}_valid_panfrost").apply { mkdirs() }
        File(dirG, LinuxVulkanDriverManager.ICD_NAME).writeText("{}")
        File(dirG, LinuxVulkanDriverManager.PANFROST_LIB_NAME).writeBytes(elfLibrary("libc.so.6"))
        assertTrue("dir with icd.json and libvulkan_panfrost.so must be installed", lm.isInstalled(dirG.name))

        // State H: icd.json (file) + libvulkan_freedreno.so (file) -> VALID FREEDRENO
        val dirH = lm.getDriverDir("${testBase}_valid_freedreno").apply { mkdirs() }
        File(dirH, LinuxVulkanDriverManager.ICD_NAME).writeText("{}")
        File(dirH, LinuxVulkanDriverManager.LIB_NAME).writeBytes(elfLibrary("libc.so.6"))
        assertTrue("dir with icd.json and libvulkan_freedreno.so must be installed", lm.isInstalled(dirH.name))

        // State I: icd.json (file) + BOTH libraries -> VALID
        val dirI = lm.getDriverDir("${testBase}_both_libs").apply { mkdirs() }
        File(dirI, LinuxVulkanDriverManager.ICD_NAME).writeText("{}")
        File(dirI, LinuxVulkanDriverManager.LIB_NAME).writeBytes(elfLibrary("libc.so.6"))
        File(dirI, LinuxVulkanDriverManager.PANFROST_LIB_NAME).writeBytes(elfLibrary("libc.so.6"))
        assertTrue("dir with icd.json and both libs must be installed", lm.isInstalled(dirI.name))

        // Enumerate verification
        val installed = lm.enumerateInstalledDrivers()
        assertTrue(installed.contains(dirG.name))
        assertTrue(installed.contains(dirH.name))
        assertTrue(installed.contains(dirI.name))
        assertFalse(installed.contains(dirA.name))
        assertFalse(installed.contains(dirB.name))
        assertFalse(installed.contains(dirC.name))
        assertFalse(installed.contains(dirD.name))
        assertFalse(installed.contains(dirE.name))
        assertFalse(installed.contains(dirF.name))
    }

    @Test
    fun challengeLinuxVulkanDriverManagerInstallStandalonePanfrostZip() {
        val standaloneZip = zip(mapOf(
            "meta.json" to JSONObject()
                .put("name", "Standalone Panfrost ICD")
                .put("driverVersion", "24.3.0")
                .put("minGlibc", "2.35")
                .put("libraryName", "libvulkan_panfrost.so")
                .toString().toByteArray(),
            "libvulkan_panfrost.so" to elfLibrary("libc.so.6"),
        ))

        val lm = LinuxVulkanDriverManager(context)
        val id = lm.installDriver(standaloneZip, "Panfrost-Standalone")
        assertNotNull(id)
        assertTrue(lm.isInstalled(id))

        assertEquals("Standalone Panfrost ICD", lm.getDriverName(id))
        assertEquals("24.3.0", lm.getDriverVersion(id))
        assertEquals("2.35", lm.getMinGlibc(id))

        val icdPath = lm.getIcdPath(id)
        assertNotNull(icdPath)
        assertTrue(File(icdPath!!).isFile)

        val driverDir = lm.getDriverDir(id)
        val panfrostSo = File(driverDir, LinuxVulkanDriverManager.PANFROST_LIB_NAME)
        assertTrue(panfrostSo.isFile)

        val icdContent = JSONObject(FileUtils.readString(File(icdPath))!!)
        assertEquals(panfrostSo.absolutePath, icdContent.getJSONObject("ICD").getString("library_path"))

        lm.removeDriver(id)
        assertFalse(lm.isInstalled(id))
    }

    // =========================================================================
    // 3. AutoPicks Preferences Oracle Tests
    // =========================================================================

    @Test
    fun challengeAutoPicksPreferencesMali5thGenExhaustiveCombinations() {
        // Mali 5th Gen (CSF) models
        val models = listOf(615, 620, 625, 715, 720, 725, 915, 920, 925)
        val sdks = listOf(21, 26, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37)
        val blobs = listOf(null, 0, 100, 500, 700, 800)
        val oneUiModes = listOf(false, true)

        for (model in models) {
            for (sdk in sdks) {
                for (blob in blobs) {
                    for (oneUi in oneUiModes) {
                        val gpu = GpuInfo(
                            name = "Mali-G$model",
                            model = model,
                            family = Family.MALI_5TH_GEN,
                            soc = "MediaTek MT6878",
                            oneUi8Gen2 = oneUi
                        )
                        val prefs = AutoPicks.preferences(gpu, AutoPicks.BUILT_IN, sdk, blob)
                        assertEquals(
                            "Mali 5th Gen model $model, sdk $sdk, blob $blob, oneUi $oneUi must recommend PANVK_G615",
                            listOf(DriverPairs.PANVK_G615),
                            prefs
                        )
                    }
                }
            }
        }
    }

    @Test
    fun challengeAutoPicksPreferencesGenericMaliExhaustiveCombinations() {
        // Generic Mali models
        val models = listOf(0, 52, 57, 71, 72, 76, 77, 78, 610, 710)
        val sdks = listOf(28, 30, 34, 36)
        val blobs = listOf(null, 100, 500)

        for (model in models) {
            for (sdk in sdks) {
                for (blob in blobs) {
                    val gpu = GpuInfo(
                        name = if (model > 0) "Mali-G$model" else "Mali",
                        model = model,
                        family = Family.MALI,
                        soc = "",
                        oneUi8Gen2 = false
                    )
                    val prefs = AutoPicks.preferences(gpu, AutoPicks.BUILT_IN, sdk, blob)
                    assertEquals(
                        "Generic Mali model $model, sdk $sdk, blob $blob must recommend PANVK_G615",
                        listOf(DriverPairs.PANVK_G615),
                        prefs
                    )
                }
            }
        }
    }

    @Test
    fun challengeAutoPicksRemoteJsonParsingWithMaliFamilies() {
        val json = """
            {
              "schemaVersion": 1,
              "rules": [
                {
                  "families": ["MALI_5TH_GEN", "MALI"],
                  "prefer": ["panvk-g615"]
                },
                {
                  "families": ["A7XX"],
                  "prefer": ["banner"]
                }
              ]
            }
        """.trimIndent()

        val parsedRules = AutoPicks.parse(json)
        assertNotNull("AutoPicks.parse must succeed for valid JSON containing MALI rules", parsedRules)
        assertEquals(2, parsedRules!!.size)

        val maliRule = parsedRules[0]
        assertTrue(maliRule.families.contains(Family.MALI_5TH_GEN))
        assertTrue(maliRule.families.contains(Family.MALI))
        assertEquals(listOf(DriverPairs.PANVK_G615), maliRule.prefer)

        val maliGpu = GpuInfo("Mali-G615", 615, Family.MALI_5TH_GEN, "MT6878", false)
        val prefs = AutoPicks.preferences(maliGpu, parsedRules, 34, null)
        assertEquals(listOf(DriverPairs.PANVK_G615), prefs)
    }

    // =========================================================================
    // 4. DriverPairs PanVK Suites & Non-Regression Tests
    // =========================================================================

    @Test
    fun challengeDriverPairsSuitsIsolation() {
        val panvkPair = DriverPairs.DriverPair(
            DriverPairs.PANVK_G615,
            "Mesa PanVK · Mali-G615 (CSF)",
            setOf(Family.MALI_5TH_GEN, Family.MALI),
            display = null,
            linux = null
        )

        // Mali families must suit
        assertTrue(panvkPair.suits(GpuInfo("Mali-G615", 615, Family.MALI_5TH_GEN, "", false)))
        assertTrue(panvkPair.suits(GpuInfo("Mali-G720", 720, Family.MALI_5TH_GEN, "", false)))
        assertTrue(panvkPair.suits(GpuInfo("Mali", 0, Family.MALI, "", false)))

        // Adreno and non-Adreno must NOT suit PanVK
        assertFalse(panvkPair.suits(GpuInfo("Adreno 650", 650, Family.A6XX, "", false)))
        assertFalse(panvkPair.suits(GpuInfo("Adreno 710", 710, Family.A7XX_LOW, "", false)))
        assertFalse(panvkPair.suits(GpuInfo("Adreno 740", 740, Family.A7XX, "", false)))
        assertFalse(panvkPair.suits(GpuInfo("Adreno 830", 830, Family.A8XX, "", false)))
        assertFalse(panvkPair.suits(GpuInfo("Adreno", 0, Family.ADRENO_UNKNOWN, "", false)))
        assertFalse(panvkPair.suits(GpuInfo("PowerVR", 0, Family.NOT_ADRENO, "", false)))
    }

    @Test
    fun challengeDriverPairsRecommendedKeyMaliVsAdreno() {
        val panvkBundle = TurnipReleases.Asset(
            "DroidDeck", "v24.3.0", "panvk-g615.zip", "https://example/panvk", 6_000_000,
            false, "Android + Linux", "0".repeat(64), DriverPairs.PANVK_G615, bundle = true
        )
        val bannerBundle = TurnipReleases.Asset(
            "DroidDeck", "v24.0.0", "banner.zip", "https://example/banner", 6_000_000,
            false, "Android + Linux", "0".repeat(64), DriverPairs.BANNER, bundle = true
        )
        val pairs = DriverPairs.from(TurnipReleases.Check(
            listOf(panvkBundle, bannerBundle),
            emptyList(), emptyList(), 0L
        ))

        // Mali targets
        val g615 = GpuInfo("Mali-G615", 615, Family.MALI_5TH_GEN, "MT6878", false)
        assertEquals(DriverPairs.PANVK_G615, DriverPairs.recommendedKey(g615, pairs))

        val g715 = GpuInfo("Mali-G715", 715, Family.MALI_5TH_GEN, "", false)
        assertEquals(DriverPairs.PANVK_G615, DriverPairs.recommendedKey(g715, pairs))

        val genericMali = GpuInfo("Mali", 0, Family.MALI, "", false)
        assertEquals(DriverPairs.PANVK_G615, DriverPairs.recommendedKey(genericMali, pairs))

        // Adreno non-regression targets
        val a740 = GpuInfo("Adreno 740", 740, Family.A7XX, "", false)
        assertEquals(DriverPairs.BANNER, DriverPairs.recommendedKey(a740, pairs))

        // Unsupported / NOT_ADRENO must return null
        val notAdreno = GpuInfo("PowerVR", 0, Family.NOT_ADRENO, "", false)
        assertNull(DriverPairs.recommendedKey(notAdreno, pairs))
    }
}

package com.droiddeck.launcher.gpu

import android.content.Context
import android.net.Uri
import com.droiddeck.launcher.gpu.GpuInfo.Family
import com.droiddeck.launcher.gpu.GpuInfo.Support
import com.droiddeck.launcher.session.SessionPrefs
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
class Milestone3ChallengerAdrenoNonRegressionTest {

    private lateinit var context: Context
    private lateinit var lm: LinuxVulkanDriverManager
    private lateinit var td: TurnipDriver

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        lm = LinuxVulkanDriverManager(context)
        td = TurnipDriver(context)
        File(context.filesDir, "graphics_driver").deleteRecursively()
        File(context.filesDir, LinuxVulkanDriverManager.DIR_NAME).deleteRecursively()
        context.getSharedPreferences("driver_bundles", Context.MODE_PRIVATE).edit().clear().commit()
        SessionPrefs.setAndroidDriver(context, "")
        SessionPrefs.setLinuxDriver(context, "")
    }

    private fun library(libc: String) = ByteArray(64).also {
        byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 2, 1).copyInto(it)
        it[18] = 0xB7.toByte()
    } + libc.toByteArray() + ByteArray(1)

    private fun zip(entries: Map<String, ByteArray>): Uri {
        val file = File.createTempFile("test-bundle", ".zip", context.cacheDir)
        ZipOutputStream(file.outputStream()).use { z ->
            for ((name, data) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(data)
                z.closeEntry()
            }
        }
        return Uri.fromFile(file)
    }

    private fun freedrenoBundle(
        soName: String = "libvulkan_freedreno.so",
        linuxLibc: String = "libc.so.6",
        version: String = "0.1.0",
        tag: String = "DD-Turnip-v0.1.0"
    ): Uri {
        val manifest = JSONObject()
            .put("package", "DD-Turnip")
            .put("version", version)
            .put("tag", tag)
            .put("drivers", JSONObject()
                .put("android", JSONObject().put("path", "android"))
                .put("linux", JSONObject().put("path", "linux")))

        return zip(mapOf(
            "android/meta.json" to JSONObject().put("name", "DD-Turnip").put("libraryName", "libvulkan_freedreno.so").toString().toByteArray(),
            "android/libvulkan_freedreno.so" to library("libc.so"),
            "linux/meta.json" to JSONObject().put("name", "DD-Turnip").put("driverVersion", version).put("libraryName", soName).toString().toByteArray(),
            "linux/$soName" to library(linuxLibc),
            "manifest.json" to manifest.toString().toByteArray(),
        ))
    }

    private fun gpu(model: Int, adreno: Boolean = true, oneUi: Boolean = false) =
        GpuInfo("Adreno $model", model, GpuInfo.familyOf(adreno, model), "", oneUi)

    // =========================================================================
    // INVARIANT 1: Standard Freedreno/Turnip driver bundle & zip installation
    // =========================================================================

    @Test
    fun verifyStandardFreedrenoBundleRegistersStrictlyAsFreedrenoSo() {
        val uri = freedrenoBundle(soName = "libvulkan_freedreno.so", version = "24.1.0", tag = "turnip-v24.1.0")
        val bundle = DriverBundle.install(context, uri)

        assertEquals("turnip-v24.1.0", bundle.id)
        assertTrue("Turnip Android driver must be installed", td.isInstalled(bundle.androidId))
        assertTrue("Freedreno Linux driver must be installed", lm.isInstalled(bundle.linuxId))

        val driverDir = lm.getDriverDir(bundle.linuxId)
        val freedrenoSo = File(driverDir, LinuxVulkanDriverManager.LIB_NAME)
        val panfrostSo = File(driverDir, LinuxVulkanDriverManager.PANFROST_LIB_NAME)
        val icdJson = File(driverDir, LinuxVulkanDriverManager.ICD_NAME)
        val metaJson = File(driverDir, LinuxVulkanDriverManager.META_NAME)

        assertTrue("libvulkan_freedreno.so must exist in installation directory", freedrenoSo.isFile)
        assertFalse("libvulkan_panfrost.so must NOT exist in freedreno installation directory", panfrostSo.exists())
        assertTrue("icd.json must exist", icdJson.isFile)
        assertTrue("meta.json must exist", metaJson.isFile)

        // Verify icd.json content points to libvulkan_freedreno.so
        val icdContent = JSONObject(icdJson.readText())
        val icdPath = icdContent.getJSONObject("ICD").getString("library_path")
        assertEquals(freedrenoSo.absolutePath, icdPath)

        // Verify meta.json records sourceLibraryName
        val metaContent = JSONObject(metaJson.readText())
        assertEquals("libvulkan_freedreno.so", metaContent.getString("sourceLibraryName"))
        assertEquals("glibc", metaContent.getString("libc"))
    }

    @Test
    fun verifyFreedrenoBundleWithVersionedSoNameRegistersAsLibvulkanFreedrenoSo() {
        // e.g., archive contains linux/libvulkan_freedreno_v24.so
        val uri = freedrenoBundle(soName = "libvulkan_freedreno_v24.so", version = "24.0.0", tag = "turnip-custom-v24")
        val bundle = DriverBundle.install(context, uri)

        val driverDir = lm.getDriverDir(bundle.linuxId)
        val freedrenoSo = File(driverDir, LinuxVulkanDriverManager.LIB_NAME)
        val panfrostSo = File(driverDir, LinuxVulkanDriverManager.PANFROST_LIB_NAME)
        val icdJson = File(driverDir, LinuxVulkanDriverManager.ICD_NAME)

        assertTrue("Target file must be standardized to libvulkan_freedreno.so", freedrenoSo.isFile)
        assertFalse("Must never register as panfrost", panfrostSo.exists())
        val icdPath = JSONObject(icdJson.readText()).getJSONObject("ICD").getString("library_path")
        assertEquals(freedrenoSo.absolutePath, icdPath)
    }

    @Test
    fun verifyStandaloneFreedrenoZipInstallRegistersAsLibvulkanFreedrenoSo() {
        val standaloneZip = zip(mapOf(
            "libvulkan_freedreno.so" to library("libc.so.6"),
            "meta.json" to JSONObject().put("name", "Mesa Freedreno standalone").put("driverVersion", "24.0").toString().toByteArray()
        ))

        val driverId = lm.installDriver(standaloneZip, "Mesa Freedreno Standalone")
        assertTrue(lm.isInstalled(driverId))

        val driverDir = lm.getDriverDir(driverId)
        val freedrenoSo = File(driverDir, LinuxVulkanDriverManager.LIB_NAME)
        val panfrostSo = File(driverDir, LinuxVulkanDriverManager.PANFROST_LIB_NAME)
        val icdJson = File(driverDir, LinuxVulkanDriverManager.ICD_NAME)

        assertTrue(freedrenoSo.isFile)
        assertFalse(panfrostSo.exists())
        val icdPath = JSONObject(icdJson.readText()).getJSONObject("ICD").getString("library_path")
        assertEquals(freedrenoSo.absolutePath, icdPath)
    }

    @Test
    fun verifyFreedrenoCorruptOrBionicLibraryFailsInstallation() {
        // Corrupt ELF (missing magic)
        val badElfZip = freedrenoBundle(linuxLibc = "libc.so.6").let {
            zip(mapOf(
                "linux/libvulkan_freedreno.so" to "not-an-elf-binary".toByteArray(),
                "linux/meta.json" to "{}".toByteArray(),
                "android/meta.json" to "{}".toByteArray(),
                "android/libvulkan_freedreno.so" to library("libc.so"),
                "manifest.json" to JSONObject().put("package", "Bad").put("drivers", JSONObject().put("android", JSONObject().put("path", "android")).put("linux", JSONObject().put("path", "linux"))).toString().toByteArray()
            ))
        }
        try {
            DriverBundle.install(context, badElfZip)
            fail("Corrupt ELF must fail installation")
        } catch (e: Exception) {
            // expected
        }

        // Bionic libc in linux driver must fail
        val bionicZip = freedrenoBundle(linuxLibc = "libc.so")
        try {
            DriverBundle.install(context, bionicZip)
            fail("Bionic libc in Linux driver must fail installation")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("glibc"))
        }
    }

    // =========================================================================
    // INVARIANT 2: AutoPicks.preferences() for all Adreno families
    // =========================================================================

    @Test
    fun verifyAutoPicksMaintainsIdenticalRecommendationsForAllAdrenoFamilies() {
        val allAdrenoModels = listOf(
            610, 616, 618, 620, 630, 640, 650, 660, // A6XX
            710, 720, 722,                          // A7XX_LOW
            725, 730, 732, 740, 750,                // A7XX
            810, 825, 830, 840, 850                 // A8XX
        )

        for (model in allAdrenoModels) {
            val family = GpuInfo.familyOf(true, model)
            val gpuStandard = GpuInfo("Adreno $model", model, family, "", false)
            val gpuOneUi = GpuInfo("Adreno $model", model, family, "", true)

            for (sdk in listOf(28, 30, 33, 34, 35, 36)) {
                for (blob in listOf(null, 600, 700, 800)) {
                    val prefs = AutoPicks.preferences(gpuStandard, AutoPicks.BUILT_IN, sdk, blob)
                    val prefsOneUi = AutoPicks.preferences(gpuOneUi, AutoPicks.BUILT_IN, sdk, blob)

                    // Invariant: PanVK must NEVER be recommended for Adreno
                    assertFalse("PanVK must never be recommended for Adreno $model (sdk=$sdk)", prefs.contains(DriverPairs.PANVK_G615))
                    assertFalse("PanVK must never be recommended for Adreno $model OneUI (sdk=$sdk)", prefsOneUi.contains(DriverPairs.PANVK_G615))

                    // Exact family-specific assertions
                    when (family) {
                        Family.A8XX -> {
                            if (model == 840) {
                                assertEquals("Adreno 840 built-in preference mismatch", listOf(AutoPicks.NEWEST_DD_TURNIP, DriverPairs.WN_BALANCED), prefs)
                            } else {
                                assertEquals("Adreno $model built-in preference mismatch", listOf(DriverPairs.WN_BALANCED), prefs)
                            }
                        }
                        Family.A7XX_LOW -> {
                            assertEquals("Adreno $model built-in preference mismatch", listOf(DriverPairs.BANNER_710), prefs)
                        }
                        Family.A7XX -> {
                            if (gpuOneUi.oneUi8Gen2) {
                                assertEquals("Adreno $model OneUI built-in preference mismatch", listOf(DriverPairs.BANNER_ONEUI), prefsOneUi)
                            }
                            assertEquals("Adreno $model standard built-in preference mismatch", listOf(DriverPairs.BANNER), prefs)
                        }
                        Family.A6XX -> {
                            assertEquals("Adreno $model built-in preference mismatch", listOf(DriverPairs.BANNER), prefs)
                        }
                        else -> fail("Unexpected family $family for model $model")
                    }
                }
            }
        }

        // ADRENO_UNKNOWN family verification
        val unknownGpu = GpuInfo("Adreno Unknown", 0, Family.ADRENO_UNKNOWN, "", false)
        val prefsSdk35 = AutoPicks.preferences(unknownGpu, AutoPicks.BUILT_IN, 35, null)
        val prefsSdk36 = AutoPicks.preferences(unknownGpu, AutoPicks.BUILT_IN, 36, null)

        assertEquals("ADRENO_UNKNOWN sdk < 36 preference mismatch", listOf(DriverPairs.BANNER), prefsSdk35)
        assertEquals("ADRENO_UNKNOWN sdk >= 36 preference mismatch", listOf(DriverPairs.WN_BALANCED), prefsSdk36)
        assertFalse("PanVK must never be in ADRENO_UNKNOWN preferences", prefsSdk35.contains(DriverPairs.PANVK_G615))
        assertFalse("PanVK must never be in ADRENO_UNKNOWN preferences", prefsSdk36.contains(DriverPairs.PANVK_G615))
    }

    // =========================================================================
    // INVARIANT 3: DriverPairs.recommendedKey() and suits() isolation
    // =========================================================================

    @Test
    fun verifyRecommendedKeyReturnsAdrenoTurnipPairsAndNeverPanvkForAdreno() {
        val panvkBundle = TurnipReleases.Asset("DroidDeck", "v24.3.0", "panvk-g615.zip", "https://example/panvk", 6_000_000,
            false, "Android + Linux", "0".repeat(64), DriverPairs.PANVK_G615, bundle = true)
        val bannerAssetDisplay = TurnipReleases.Asset("src", "r6", "Turnip-r6.zip", "https://example/r6", 3_000_000, false, "label", "0".repeat(64), DriverPairs.BANNER)
        val bannerAssetLinux = TurnipReleases.Asset("src", "r6", "Turnip-r6-Linux.zip", "https://example/r6-linux", 3_000_000, true, "label", "0".repeat(64), DriverPairs.BANNER)
        val wnAssetDisplay = TurnipReleases.Asset("src", "v1.19", "WN-Turnip-1.19.zip", "https://example/wn", 3_000_000, false, "label", "0".repeat(64), DriverPairs.WN_BALANCED)
        val wnAssetLinux = TurnipReleases.Asset("src", "linux-v0.1.2", "WN-Linux-0.1.2.zip", "https://example/wn-l", 3_000_000, true, "label", "0".repeat(64), DriverPairs.WN_BALANCED)

        val mixedPairs = DriverPairs.from(TurnipReleases.Check(
            listOf(bannerAssetDisplay, bannerAssetLinux, wnAssetDisplay, wnAssetLinux, panvkBundle),
            emptyList(), emptyList(), 0L
        ))

        // Adreno 830 -> WN_BALANCED
        val key830 = DriverPairs.recommendedKey(gpu(830), mixedPairs)
        assertEquals(DriverPairs.WN_BALANCED, key830)

        // Adreno 740 -> BANNER
        val key740 = DriverPairs.recommendedKey(gpu(740), mixedPairs)
        assertEquals(DriverPairs.BANNER, key740)

        // Adreno 650 -> BANNER
        val key650 = DriverPairs.recommendedKey(gpu(650), mixedPairs)
        assertEquals(DriverPairs.BANNER, key650)

        // Adreno 710 with empty list fallback
        val key710 = DriverPairs.recommendedKey(gpu(710), emptyList())
        assertEquals(DriverPairs.BANNER_710, key710)

        // Adreno 740 OneUI with empty list fallback
        val key740OneUi = DriverPairs.recommendedKey(gpu(740, oneUi = true), emptyList())
        assertEquals(DriverPairs.BANNER_ONEUI, key740OneUi)
    }

    @Test
    fun verifyPanvkPairNeverSuitsAnyAdrenoGpu() {
        val panvkPair = DriverPairs.DriverPair(
            key = DriverPairs.PANVK_G615,
            name = "Mesa PanVK · Mali-G615 (CSF)",
            families = setOf(Family.MALI_5TH_GEN, Family.MALI),
            display = null,
            linux = null,
            bundle = TurnipReleases.Asset("DroidDeck", "v24.3.0", "panvk-g615.zip", "https://example/panvk", 6_000_000,
                false, "Android + Linux", "0".repeat(64), DriverPairs.PANVK_G615, bundle = true)
        )

        val testModels = listOf(610, 650, 710, 725, 740, 830, 840, 850)
        for (m in testModels) {
            val adrenoGpu = gpu(m)
            assertFalse("panvk pair must NOT suit Adreno $m", panvkPair.suits(adrenoGpu))
        }

        val unknownAdreno = GpuInfo("Adreno Unknown", 0, Family.ADRENO_UNKNOWN, "", false)
        assertFalse("panvk pair must NOT suit ADRENO_UNKNOWN", panvkPair.suits(unknownAdreno))

        // But must suit Mali-G615 and Mali
        val maliG615 = GpuInfo("Mali-G615", 615, Family.MALI_5TH_GEN, "MediaTek Dimensity 7400 Ultra", false)
        assertTrue("panvk pair must suit Mali-G615", panvkPair.suits(maliG615))

        val genericMali = GpuInfo("Mali", 0, Family.MALI, "", false)
        assertTrue("panvk pair must suit Mali", panvkPair.suits(genericMali))
    }

    @Test
    fun verifyDriverPairsOrderPreservesAdrenoPrecedence() {
        val assets = listOf(
            TurnipReleases.Asset("src", "tag", "p1", "url", 100, false, "lbl", "hash", DriverPairs.PANVK_G615, bundle = true),
            TurnipReleases.Asset("src", "tag", "p2", "url", 100, false, "lbl", "hash", DriverPairs.BANNER, bundle = true),
            TurnipReleases.Asset("src", "tag", "p3", "url", 100, false, "lbl", "hash", DriverPairs.WN_BALANCED, bundle = true),
            TurnipReleases.Asset("src", "tag", "p4", "url", 100, false, "lbl", "hash", DriverPairs.BANNER_A8XX, bundle = true),
        )

        val pairs = DriverPairs.from(TurnipReleases.Check(assets, emptyList(), emptyList(), 0L))
        val keys = pairs.map { it.key }

        // Expected order: BANNER, BANNER_A8XX, WN_BALANCED, PANVK_G615
        assertEquals(listOf(DriverPairs.BANNER, DriverPairs.BANNER_A8XX, DriverPairs.WN_BALANCED, DriverPairs.PANVK_G615), keys)
    }
}

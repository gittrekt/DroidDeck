package com.droiddeck.launcher.gpu

import com.droiddeck.launcher.core.DeviceSupport
import com.droiddeck.launcher.gpu.GpuInfo.Family
import com.droiddeck.launcher.gpu.GpuInfo.Support
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Milestone1ChallengerTest {

    @Test
    fun challengeMaliModelDigitsExtraction() {
        // Exact target and 5th gen models
        assertEquals(615, GpuInfo.maliDigits("Mali-G615"))
        assertEquals(615, GpuInfo.maliDigits("Mali-G615 MC2"))
        assertEquals(615, GpuInfo.maliDigits("ARM Mali-G615 MC2"))
        assertEquals(720, GpuInfo.maliDigits("Mali-G720-Immortalis MC12"))
        assertEquals(715, GpuInfo.maliDigits("Mali-G715"))
        assertEquals(925, GpuInfo.maliDigits("Mali-G925"))
        assertEquals(925, GpuInfo.maliDigits("Mali-G925 Immortalis"))
        assertEquals(620, GpuInfo.maliDigits("Mali-G620"))
        assertEquals(625, GpuInfo.maliDigits("Mali-G625"))
        assertEquals(725, GpuInfo.maliDigits("Mali-G725"))
        assertEquals(920, GpuInfo.maliDigits("Mali-G920"))
        assertEquals(915, GpuInfo.maliDigits("Mali-G915"))

        // Case insensitivity
        assertEquals(615, GpuInfo.maliDigits("mali-g615"))
        assertEquals(615, GpuInfo.maliDigits("MALI-G615"))
        assertEquals(615, GpuInfo.maliDigits("Mali-g615 MC2"))

        // Legacy / non-3-digit-G Mali models (should return 0 from maliDigits)
        assertEquals(0, GpuInfo.maliDigits("Mali-T880"))
        assertEquals(0, GpuInfo.maliDigits("Mali-T760"))
        assertEquals(0, GpuInfo.maliDigits("Mali-G78"))
        assertEquals(0, GpuInfo.maliDigits("Mali-G52"))
        assertEquals(0, GpuInfo.maliDigits("Mali-G57"))
        assertEquals(0, GpuInfo.maliDigits("Mali-G76"))
        assertEquals(0, GpuInfo.maliDigits("Mali-G77"))
        assertEquals(0, GpuInfo.maliDigits("Mali-G71"))
        assertEquals(0, GpuInfo.maliDigits("Mali-G72"))
        assertEquals(0, GpuInfo.maliDigits("Mali-400"))

        // Empty / blank / invalid prefixes / invalid length
        assertEquals(0, GpuInfo.maliDigits(""))
        assertEquals(0, GpuInfo.maliDigits("   "))
        assertEquals(0, GpuInfo.maliDigits("PowerVR-G615"))
        assertEquals(0, GpuInfo.maliDigits("Adreno 740"))
        assertEquals(0, GpuInfo.maliDigits("Mali-G6150")) // 4 digits boundary
        assertEquals(0, GpuInfo.maliDigits("Mali-G61"))   // 2 digits boundary
        assertEquals(0, GpuInfo.maliDigits("Mali-G"))
        assertEquals(0, GpuInfo.maliDigits("Mali"))
    }

    @Test
    fun challengeAdrenoIsolationFromMaliDigits() {
        // Adreno parser must NEVER parse Mali
        assertEquals(0, GpuInfo.threeDigits("Mali-G615"))
        assertEquals(0, GpuInfo.threeDigits("Mali-G720"))
        assertEquals(0, GpuInfo.threeDigits("Mali-G850"))
        assertEquals(0, GpuInfo.threeDigits("Mali"))

        // Mali parser must NEVER parse Adreno
        assertEquals(0, GpuInfo.maliDigits("Adreno 650"))
        assertEquals(0, GpuInfo.maliDigits("Adreno 740"))
        assertEquals(0, GpuInfo.maliDigits("Adreno (TM) 830"))
        assertEquals(0, GpuInfo.maliDigits("Adreno 850"))
    }

    @Test
    fun challengeMaliFamilyClassification() {
        // 5th gen CSF models
        assertTrue(GpuInfo.isMali5thGen(615))
        assertTrue(GpuInfo.isMali5thGen(715))
        assertTrue(GpuInfo.isMali5thGen(915))
        assertTrue(GpuInfo.isMali5thGen(620))
        assertTrue(GpuInfo.isMali5thGen(720))
        assertTrue(GpuInfo.isMali5thGen(920))
        assertTrue(GpuInfo.isMali5thGen(625))
        assertTrue(GpuInfo.isMali5thGen(725))
        assertTrue(GpuInfo.isMali5thGen(925))

        // Non-5th-gen models
        assertFalse(GpuInfo.isMali5thGen(710)) // Valhall 3rd gen (JM)
        assertFalse(GpuInfo.isMali5thGen(610)) // Valhall 3rd gen (JM)
        assertFalse(GpuInfo.isMali5thGen(78))
        assertFalse(GpuInfo.isMali5thGen(57))
        assertFalse(GpuInfo.isMali5thGen(52))
        assertFalse(GpuInfo.isMali5thGen(0))

        // FamilyOf mapping
        assertEquals(Family.MALI_5TH_GEN, GpuInfo.familyOf(adreno = false, model = 615, mali = true))
        assertEquals(Family.MALI_5TH_GEN, GpuInfo.familyOf(adreno = false, model = 715, mali = true))
        assertEquals(Family.MALI_5TH_GEN, GpuInfo.familyOf(adreno = false, model = 720, mali = true))
        assertEquals(Family.MALI_5TH_GEN, GpuInfo.familyOf(adreno = false, model = 925, mali = true))

        assertEquals(Family.MALI, GpuInfo.familyOf(adreno = false, model = 710, mali = true))
        assertEquals(Family.MALI, GpuInfo.familyOf(adreno = false, model = 78, mali = true))
        assertEquals(Family.MALI, GpuInfo.familyOf(adreno = false, model = 0, mali = true))

        // Non-Mali, non-Adreno
        assertEquals(Family.NOT_ADRENO, GpuInfo.familyOf(adreno = false, model = 615, mali = false))
        assertEquals(Family.NOT_ADRENO, GpuInfo.familyOf(adreno = false, model = 0, mali = false))

        // Adreno caller compatibility (mali defaults to false)
        assertEquals(Family.A6XX, GpuInfo.familyOf(adreno = true, model = 650))
        assertEquals(Family.A7XX, GpuInfo.familyOf(adreno = true, model = 740))
        assertEquals(Family.A8XX, GpuInfo.familyOf(adreno = true, model = 830))
        assertEquals(Family.A7XX_LOW, GpuInfo.familyOf(adreno = true, model = 710))
        assertEquals(Family.ADRENO_UNKNOWN, GpuInfo.familyOf(adreno = true, model = 0))
    }

    @Test
    fun challengeSupportTiersAndText() {
        // Mali-G615 (target) is TESTED / Supported
        val g615 = GpuInfo("Mali-G615", 615, Family.MALI_5TH_GEN, "MediaTek Dimensity 7400 Ultra", false)
        assertEquals(Support.TESTED, g615.support)
        assertEquals("Supported", g615.supportText)

        // Other 5th Gen Mali models are UNTESTED with experimental PanVK text
        val g715 = GpuInfo("Mali-G715", 715, Family.MALI_5TH_GEN, "", false)
        assertEquals(Support.UNTESTED, g715.support)
        assertEquals("Experimental: Mali PanVK driver support", g715.supportText)

        val g925 = GpuInfo("Mali-G925", 925, Family.MALI_5TH_GEN, "", false)
        assertEquals(Support.UNTESTED, g925.support)
        assertEquals("Experimental: Mali PanVK driver support", g925.supportText)

        // Generic Mali models are UNTESTED with experimental PanVK text
        val g78 = GpuInfo("Mali", 0, Family.MALI, "", false)
        assertEquals(Support.UNTESTED, g78.support)
        assertEquals("Experimental: Mali PanVK driver support", g78.supportText)

        // Non-Adreno / Non-Mali is UNSUPPORTED
        val notAdreno = GpuInfo("PowerVR", 0, Family.NOT_ADRENO, "", false)
        assertEquals(Support.UNSUPPORTED, notAdreno.support)
        assertEquals("Not supported: DroidDeck needs an Adreno (Snapdragon) GPU", notAdreno.supportText)
    }

    @Test
    fun challengePlatformModelFallbacks() {
        // 1. "mt6878" as platform (lowercase and mixed case)
        assertEquals(615, GpuInfo.platformModel(platform = "mt6878", qtiSocName = "", socModel = ""))
        assertEquals(615, GpuInfo.platformModel(platform = "MT6878", qtiSocName = "", socModel = ""))
        assertEquals(615, GpuInfo.platformModel(platform = "Mt6878", qtiSocName = "", socModel = ""))

        // 2. "mt6878" as socModel
        assertEquals(615, GpuInfo.platformModel(platform = "unknown", qtiSocName = "", socModel = "mt6878"))
        assertEquals(615, GpuInfo.platformModel(platform = "unknown", qtiSocName = "", socModel = "MT6878"))

        // 3. "MT6878V/TZA" as socModel
        assertEquals(615, GpuInfo.platformModel(platform = "unknown", qtiSocName = "", socModel = "MT6878V/TZA"))

        // 4. "MT6878V/tza" (lowercase variant)
        assertEquals(615, GpuInfo.platformModel(platform = "unknown", qtiSocName = "", socModel = "MT6878V/tza"))
        assertEquals(615, GpuInfo.platformModel(platform = "unknown", qtiSocName = "", socModel = "mt6878v/tza"))

        // Prefixed variants
        assertEquals(615, GpuInfo.platformModel(platform = "unknown", qtiSocName = "", socModel = "MTK MT6878"))
        assertEquals(615, GpuInfo.platformModel(platform = "unknown", qtiSocName = "", socModel = "MEDIATEK MT6878"))
        assertEquals(615, GpuInfo.platformModel(platform = "unknown", qtiSocName = "", socModel = "MTK MT6878V/TZA"))
        assertEquals(615, GpuInfo.platformModel(platform = "unknown", qtiSocName = "", socModel = "MEDIATEK MT6878V/tza"))
    }

    @Test
    fun challengeMt6878vPlatformFallbackBehavior() {
        // Inspecting behavior for "mt6878v" when platform is "mt6878" vs "mt6878v"
        // On MediaTek Dimensity 7400, platform is "mt6878" while SoC variant might be "mt6878v"
        // If platform is mt6878:
        assertEquals(615, GpuInfo.platformModel(platform = "mt6878", qtiSocName = "", socModel = "mt6878v"))

        // What if platform itself is "mt6878v" and socModel is empty or unknown?
        // Let's document what platformModel currently yields:
        val modelFromPlatformMt6878v = GpuInfo.platformModel(platform = "mt6878v", qtiSocName = "", socModel = "")
        // Currently PLATFORMS map contains "mt6878" -> 615, so "mt6878v" alone without "mt6878" yields 0
        assertEquals(0, modelFromPlatformMt6878v)
    }

    @Test
    fun challengeSocNamesNormalizationAndLabeling() {
        assertEquals("MT6878", SocNames.normalizedModel("mt6878"))
        assertEquals("MT6878", SocNames.normalizedModel("MT6878"))
        assertEquals("MT6878", SocNames.normalizedModel("  mtk mt6878  "))
        assertEquals("MT6878", SocNames.normalizedModel("MEDIATEK MT6878"))
        assertEquals("MT6878V/TZA", SocNames.normalizedModel("MT6878V/TZA"))
        assertEquals("MT6878V/TZA", SocNames.normalizedModel("mt6878v/tza"))
        assertEquals("MT6878V/TZA", SocNames.normalizedModel("MTK MT6878V/TZA"))

        assertEquals("MediaTek Dimensity 7400 Ultra", SocNames.name("MT6878", ""))
        assertEquals("MediaTek Dimensity 7400 Ultra", SocNames.name("mt6878", ""))
        assertEquals("MediaTek Dimensity 7400 Ultra", SocNames.name("MT6878V/TZA", ""))
        assertEquals("MediaTek Dimensity 7400 Ultra", SocNames.name("MT6878V/tza", ""))
        assertEquals("MediaTek Dimensity 7400 Ultra", SocNames.name("MTK MT6878", ""))

        assertEquals("Dimensity 7400 family", SocNames.name("", "mt6878"))
        assertEquals("Dimensity 7400 family", SocNames.name("", "MT6878"))
    }

    @Test
    fun challengeModelFromNamesResolutionOrder() {
        // 1. Kernel name takes precedence
        val fromKernel = GpuInfo.modelFromNames(
            kernelName = "Mali-G615",
            vulkanName = { "Mali-G720" },
            fallback = { 925 }
        )
        assertEquals(615 to "kernel", fromKernel)

        // 2. Vulkan name takes second precedence
        val fromVulkan = GpuInfo.modelFromNames(
            kernelName = null,
            vulkanName = { "Mali-G720 MC12" },
            fallback = { 615 }
        )
        assertEquals(720 to "vulkan", fromVulkan)

        // 3. Fallback (platform) takes last precedence
        val fromPlatform = GpuInfo.modelFromNames(
            kernelName = null,
            vulkanName = { null },
            fallback = { 615 }
        )
        assertEquals(615 to "platform", fromPlatform)

        // 4. When nothing matches
        val fromNone = GpuInfo.modelFromNames(
            kernelName = "PowerVR Rogue",
            vulkanName = { "PowerVR Rogue" },
            fallback = { 0 }
        )
        assertEquals(0 to "", fromNone)
    }

    @Test
    fun challengeDeviceSupportBooleanLogic() {
        // Truth table verification of supported() == (adreno() || mali())
        assertTrue(DeviceSupport.supported() == (DeviceSupport.adreno() || DeviceSupport.mali()))
    }
}

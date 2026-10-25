package com.droiddeck.launcher.gpu

import com.droiddeck.launcher.core.DeviceSupport
import com.droiddeck.launcher.gpu.GpuInfo.Family
import com.droiddeck.launcher.gpu.GpuInfo.Support
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AdrenoNonRegressionEmpiricalTest {

    data class ExpectedAdrenoModel(
        val model: Int,
        val expectedFamily: Family,
        val expectedSupport: Support,
        val expectedSupportText: String
    )

    private val expectedModels = listOf(
        ExpectedAdrenoModel(610, Family.A6XX, Support.UNTESTED, "Outside tested hardware (Adreno 650, 725 and newer): it may not run"),
        ExpectedAdrenoModel(616, Family.A6XX, Support.UNTESTED, "Outside tested hardware (Adreno 650, 725 and newer): it may not run"),
        ExpectedAdrenoModel(618, Family.A6XX, Support.UNTESTED, "Outside tested hardware (Adreno 650, 725 and newer): it may not run"),
        ExpectedAdrenoModel(620, Family.A6XX, Support.UNTESTED, "Outside tested hardware (Adreno 650, 725 and newer): it may not run"),
        ExpectedAdrenoModel(630, Family.A6XX, Support.UNTESTED, "Outside tested hardware (Adreno 650, 725 and newer): it may not run"),
        ExpectedAdrenoModel(640, Family.A6XX, Support.UNTESTED, "Outside tested hardware (Adreno 650, 725 and newer): it may not run"),
        ExpectedAdrenoModel(650, Family.A6XX, Support.TESTED, "Supported"),
        ExpectedAdrenoModel(660, Family.A6XX, Support.UNTESTED, "Outside tested hardware (Adreno 650, 725 and newer): it may not run"),
        ExpectedAdrenoModel(710, Family.A7XX_LOW, Support.UNTESTED, "Experimental: its drivers are test builds"),
        ExpectedAdrenoModel(720, Family.A7XX_LOW, Support.UNTESTED, "Experimental: its drivers are test builds"),
        ExpectedAdrenoModel(725, Family.A7XX, Support.TESTED, "Supported"),
        ExpectedAdrenoModel(730, Family.A7XX, Support.TESTED, "Supported"),
        ExpectedAdrenoModel(732, Family.A7XX, Support.TESTED, "Supported"),
        ExpectedAdrenoModel(740, Family.A7XX, Support.TESTED, "Supported"),
        ExpectedAdrenoModel(750, Family.A7XX, Support.TESTED, "Supported"),
        ExpectedAdrenoModel(830, Family.A8XX, Support.TESTED, "Supported"),
        ExpectedAdrenoModel(850, Family.A8XX, Support.UNTESTED, "Experimental Adreno 850 support: requires compatible experimental display and game drivers; not verified on this device"),
    )

    @Test
    fun verifyAll17AdrenoModelsMaintainExactFamilySupportTierAndText() {
        for (item in expectedModels) {
            val family = GpuInfo.familyOf(adreno = true, model = item.model)
            assertEquals("Adreno ${item.model} family mismatch", item.expectedFamily, family)

            val info = GpuInfo(
                name = "Adreno ${item.model}",
                model = item.model,
                family = family,
                soc = "",
                oneUi8Gen2 = false
            )
            assertEquals("Adreno ${item.model} support tier mismatch", item.expectedSupport, info.support)
            assertEquals("Adreno ${item.model} support text mismatch", item.expectedSupportText, info.supportText)
        }
    }

    @Test
    fun verifyThreeDigitsParsesAll17AdrenoModelsAcrossFormats() {
        for (item in expectedModels) {
            val m = item.model
            assertEquals("Standard format failed for $m", m, GpuInfo.threeDigits("Adreno $m"))
            assertEquals("Trademark format failed for $m", m, GpuInfo.threeDigits("Adreno (TM) $m"))
            assertEquals("No-space format failed for $m", m, GpuInfo.threeDigits("Adreno$m"))
            assertEquals("v2 suffix format failed for $m", m, GpuInfo.threeDigits("Adreno${m}v2"))
            assertEquals("Lowercase format failed for $m", m, GpuInfo.threeDigits("  adreno $m  "))
            assertEquals("Trailing Qualcomm tag format failed for $m", m, GpuInfo.threeDigits("Adreno (TM) $m (Qualcomm)"))
        }
    }

    @Test
    fun verifyThreeDigitsRejectsMaliModelsIncludingMaliG850() {
        assertEquals(0, GpuInfo.threeDigits("Mali-G850"))
        assertEquals(0, GpuInfo.threeDigits("Mali-G850 MC12"))
        assertEquals(0, GpuInfo.threeDigits("ARM Mali-G850"))
        assertEquals(0, GpuInfo.threeDigits("mali-g850"))

        for (item in expectedModels) {
            assertEquals("Mali-G${item.model} must not be parsed by threeDigits", 0, GpuInfo.threeDigits("Mali-G${item.model}"))
        }
    }

    @Test
    fun verifyModelFromNamesDoesNotHijackAdrenoWithMaliNames() {
        // When kernel name has Adreno, it must resolve to Adreno kernel
        val res1 = GpuInfo.modelFromNames("Adreno (TM) 740", { "Mali-G615" }, { 0 })
        assertEquals(740 to "kernel", res1)

        // When vulkan name has Adreno, it must resolve to Adreno vulkan
        val res2 = GpuInfo.modelFromNames(null, { "Adreno (TM) 850" }, { 0 })
        assertEquals(850 to "vulkan", res2)

        // threeDigits("Mali-G850") returning 0 means Mali cannot hijack Adreno 850 via threeDigits
        assertEquals(0, GpuInfo.threeDigits("Mali-G850"))
    }

    @Test
    fun verifyQualcommPlatformCodeNamesUnchanged() {
        assertEquals(640, GpuInfo.platformModel("msmnile"))
        assertEquals(650, GpuInfo.platformModel("kona"))
        assertEquals(660, GpuInfo.platformModel("lahaina"))
        assertEquals(730, GpuInfo.platformModel("taro"))
        assertEquals(730, GpuInfo.platformModel("cape"))
        assertEquals(740, GpuInfo.platformModel("kalama"))
        assertEquals(750, GpuInfo.platformModel("pineapple"))
        assertEquals(830, GpuInfo.platformModel("sun"))
        assertEquals(850, GpuInfo.platformModel("unknown", "", "SM8975"))
        assertEquals(850, GpuInfo.platformModel("unknown", "SM8975", ""))
    }

    @Test
    fun verifyDeviceSupportAdrenoBehaviorUnchanged() {
        val expectedAdreno = File("/sys/class/kgsl/kgsl-3d0").exists() || File("/vendor/lib64/hw/vulkan.adreno.so").exists()
        assertEquals(expectedAdreno, DeviceSupport.adreno())

        val expectedMali = File("/dev/mali0").exists() || File("/vendor/lib64/hw/vulkan.mali.so").exists()
        assertEquals(expectedMali, DeviceSupport.mali())

        assertEquals(expectedAdreno || expectedMali, DeviceSupport.supported())
    }
}

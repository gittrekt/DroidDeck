package com.droiddeck.launcher.gpu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SocNamesTest {
    private fun props(vararg pairs: Pair<String, String>): (String) -> String = { pairs.toMap()[it].orEmpty() }

    @Test fun theModelIsFoundWhereVendorsPutIt() {
        assertEquals("SG8350P", SocNames.model(props("ro.vendor.qti.soc_model" to "SG8350P", "ro.fota.platform" to "MSM_13.0")))
        assertEquals("QCS8550", SocNames.model(props("ro.soc.model" to "QCS8550", "ro.vendor.qti.soc_model" to "other")))
        assertEquals("", SocNames.model(props("ro.fota.platform" to "MSM_13.0")))
    }

    @Test fun knownModelsAndPlatformsGetTheirNames() {
        assertEquals("Snapdragon 8 Gen 2", SocNames.name("QCS8550", ""))
        assertEquals("Snapdragon 8 Gen 3 family", SocNames.name("SG8350P", "pineapple"))
        assertNull(SocNames.name("XYZ123", "nowhere"))
        assertEquals("Snapdragon 8 Gen 2 (QCS8550)", SocNames.label("QCS8550", ""))
        assertEquals("Snapdragon 8 Gen 3 family (SG8350P)", SocNames.label("SG8350P", "pineapple"))
        assertEquals("XYZ123", SocNames.label("XYZ123", ""))
    }
    @Test fun qtiPrefixIsNormalizedWithoutGuessingOtherModelVariants() {
        assertEquals("Snapdragon 8 Elite Extreme Gen 6", SocNames.name("SM8975", ""))
        assertEquals("Snapdragon 8 Elite Extreme Gen 6", SocNames.name("QTI SM8975", ""))
        assertEquals("Snapdragon 8 Elite Extreme Gen 6", SocNames.name(" qti sm8975 ", ""))
        assertEquals("Snapdragon 8 Elite Extreme Gen 6 (QTI SM8975)", SocNames.label("QTI SM8975", ""))
        assertNull(SocNames.name("SM89750", ""))
        assertNull(SocNames.name("SM8975-PRO", ""))
        assertNull(SocNames.name("Qualcomm SM8975", ""))
    }

    @Test fun dimensity7400UltraIsIdentified() {
        assertEquals("MediaTek Dimensity 7400 Ultra", SocNames.name("MT6878", ""))
        assertEquals("MediaTek Dimensity 7400 Ultra", SocNames.name("MT6878V/TZA", ""))
        assertEquals("MediaTek Dimensity 7400 Ultra (MT6878)", SocNames.label("MT6878", ""))
        assertEquals("MediaTek Dimensity 7400 Ultra (MT6878V/TZA)", SocNames.label("MT6878V/TZA", ""))
        assertEquals("Dimensity 7400 family", SocNames.name("", "mt6878"))
        assertEquals("Dimensity 7400 family", SocNames.label("", "mt6878"))
        assertEquals("Dimensity 7400 family (MT6878_EXP)", SocNames.label("MT6878_EXP", "mt6878"))
        assertEquals("MediaTek Dimensity 7400 Ultra (MT6878)", SocNames.label("MT6878", "mt6878"))
    }

    @Test fun mediatekPropertyResolution() {
        val mtkProp = { key: String -> if (key == "ro.mediatek.platform") "MT6878" else "" }
        assertEquals("MT6878", SocNames.model(mtkProp))
        val mtkVendorProp = { key: String -> if (key == "ro.vendor.mediatek.platform") "MT6878V/TZA" else "" }
        assertEquals("MT6878V/TZA", SocNames.model(mtkVendorProp))
    }
}

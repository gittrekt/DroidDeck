package com.droiddeck.launcher.gpu

import android.content.Context
import android.os.Build
import com.droiddeck.launcher.R
import com.droiddeck.launcher.core.FileUtils
import java.io.File

data class GpuInfo(
    val name: String,
    val model: Int,
    val family: Family,
    val soc: String,
    val oneUi8Gen2: Boolean,
    val kgslName: String = "",
    val modelSource: String = "",
    val kgslChipId: String = "",
    val platformGpuModel: Int = 0,
) {
    enum class Family(val label: String) {
        A8XX("Adreno 8xx"),
        A7XX_LOW("Adreno 710/720/722"),
        A7XX("Adreno 7xx"),
        A6XX("Adreno 6xx"),
        MALI_5TH_GEN("Mali 5th Gen (CSF)"),
        MALI("Mali"),
        ADRENO_UNKNOWN("Adreno"),
        NOT_ADRENO("Not an Adreno GPU");

        fun label(context: Context): String = if (this == NOT_ADRENO) context.getString(R.string.gpuinfo_not_adreno) else label
    }

    enum class Support { TESTED, UNTESTED, UNSUPPORTED }

    private val isA850: Boolean get() = model == 850 || platformGpuModel == 850

    val support: Support
        get() = when {
            family == Family.NOT_ADRENO -> Support.UNSUPPORTED
            isA850 -> Support.UNTESTED
            family == Family.A6XX && model == 650 -> Support.TESTED
            family == Family.A8XX -> Support.TESTED
            family == Family.A7XX && model >= 725 -> Support.TESTED
            family == Family.MALI_5TH_GEN && model == 615 -> Support.TESTED
            family == Family.MALI_5TH_GEN -> Support.UNTESTED
            family == Family.MALI -> Support.UNTESTED
            else -> Support.UNTESTED
        }

    val supportText: String
        get() = when (support) {
            Support.TESTED -> "Supported"
            Support.UNTESTED -> if (isA850) "Experimental Adreno 850 support: requires compatible experimental display and game drivers; not verified on this device"
                else if (family == Family.A7XX_LOW) "Experimental: its drivers are test builds"
                else if (family == Family.MALI_5TH_GEN || family == Family.MALI) "Experimental: Mali PanVK driver support"
                else "Outside tested hardware (Adreno 650, 725 and newer): it may not run"
            Support.UNSUPPORTED -> "Not supported: DroidDeck needs an Adreno (Snapdragon) GPU"
        }

    fun supportText(context: Context): String = context.getString(when (support) {
        Support.TESTED -> R.string.gpuinfo_supported
        Support.UNTESTED -> when {
            isA850 -> R.string.gpuinfo_a850_experimental
            family == Family.A7XX_LOW -> R.string.gpuinfo_experimental
            else -> R.string.gpuinfo_below_tested
        }
        Support.UNSUPPORTED -> R.string.gpuinfo_unsupported
    })

    fun displayName(context: Context): String = if (name == UNNAMED) context.getString(R.string.gpu_this_gpu) else name

    companion object {
        private const val UNNAMED = "this GPU"

        internal var overrideGpu: GpuInfo? = null

        fun detect(): GpuInfo {
            overrideGpu?.let { return it }
            val adreno = File("/sys/class/kgsl/kgsl-3d0").exists() || File("/vendor/lib64/hw/vulkan.adreno.so").exists()
            val mali = !adreno && (File("/dev/mali0").exists() || File("/vendor/lib64/hw/vulkan.mali.so").exists())
            val raw = if (adreno) FileUtils.readString(File("/sys/class/kgsl/kgsl-3d0/gpu_model"))?.trim() else null
            val chipId = if (adreno) FileUtils.readString(File("/sys/class/kgsl/kgsl-3d0/gpu_chipid"))?.trim().orEmpty() else ""
            val soc = if (adreno || mali) SocNames.label() else ""
            val platformGpuModel = if (adreno || mali) platformModel() else 0
            val (model, source) = if (adreno || mali) modelFromNames(
                raw,
                { VulkanInfo.cachedOrNull()?.get("device") },
                { platformGpuModel },
            ) else 0 to ""
            val family = familyOf(adreno, model, mali)
            val samsung = Build.MANUFACTURER.equals("samsung", ignoreCase = true)
            return GpuInfo(
                name = when {
                    adreno -> if (model > 0) "Adreno $model" else "Adreno"
                    mali -> if (model > 0) "Mali-G$model" else "Mali"
                    else -> Build.HARDWARE.ifBlank { UNNAMED }
                },
                model = model, family = family, soc = soc,
                oneUi8Gen2 = samsung && adreno && model == 740,
                kgslName = raw.orEmpty(), modelSource = source, kgslChipId = chipId,
                platformGpuModel = platformGpuModel,
            )
        }

        internal fun threeDigits(text: String): Int = Regex("""(?i)\badreno\s*(?:\(TM\)\s*)?(\d{3})(?!\d)""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0

        internal fun maliDigits(text: String): Int = Regex("""(?i)\bmali-g(\d{3})(?!\d)""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0

        internal fun modelFromNames(
            kernelName: String?,
            vulkanName: () -> String?,
            fallback: () -> Int,
        ): Pair<Int, String> {
            kernelName?.let { threeDigits(it) }?.takeIf { it > 0 }?.let { return it to "kernel" }
            kernelName?.let { maliDigits(it) }?.takeIf { it > 0 }?.let { return it to "kernel" }
            vulkanName()?.let { threeDigits(it) }?.takeIf { it > 0 }?.let { return it to "vulkan" }
            vulkanName()?.let { maliDigits(it) }?.takeIf { it > 0 }?.let { return it to "vulkan" }
            return fallback().let { if (it > 0) it to "platform" else 0 to "" }
        }

        private val PLATFORMS = mapOf(
            "msmnile" to 640, "kona" to 650, "lahaina" to 660, "taro" to 730, "cape" to 730,
            "kalama" to 740, "pineapple" to 750, "sun" to 830,
            "mt6878" to 615,
        )

        internal fun platformModel(
            platform: String = systemProperty("ro.board.platform"),
            qtiSocName: String = systemProperty("ro.vendor.qti.soc_name"),
            socModel: String = SocNames.model(),
        ): Int = when {
            SocNames.normalizedModel(socModel) == "SM8975" || SocNames.normalizedModel(qtiSocName) == "SM8975" -> 850
            SocNames.normalizedModel(socModel) in setOf("MT6878", "MT6878V/TZA") -> 615
            else -> PLATFORMS[platform.lowercase()] ?: PLATFORMS[qtiSocName.lowercase()] ?: 0
        }

        fun systemProperty(name: String): String = try {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, name) as? String ?: ""
        } catch (_: Exception) {
            ""
        }

        internal fun isMali5thGen(model: Int): Boolean =
            model == 615 || model in setOf(715, 915, 620, 720, 920, 625, 725, 925)

        internal fun familyOf(adreno: Boolean, model: Int, mali: Boolean = false): Family = when {
            adreno -> when {
                model == 0 -> Family.ADRENO_UNKNOWN
                model >= 800 -> Family.A8XX
                model in listOf(710, 720, 722) -> Family.A7XX_LOW
                model >= 700 -> Family.A7XX
                model >= 600 -> Family.A6XX
                else -> Family.ADRENO_UNKNOWN
            }
            mali -> when {
                isMali5thGen(model) -> Family.MALI_5TH_GEN
                else -> Family.MALI
            }
            else -> Family.NOT_ADRENO
        }
    }
}

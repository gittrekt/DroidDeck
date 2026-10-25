#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "../../app/src/main/cpp/waylandcomp/src/vk_present.h"

// Verbatim copy of vkp_modifier_name from vk_present.c:859-872
const char *vkp_modifier_name(uint64_t modifier) {
    static char other[64];
    if (modifier == VKP_MOD_LINEAR) return "linear";
    if (modifier == VKP_MOD_QCOM_COMPRESSED) return "qcom_compressed";
    if (modifier == VKP_MOD_INVALID) return "implicit";
    if (modifier == VKP_MOD_ARM_AFBC_16x16) return "arm_afbc_16x16";
    if (modifier == VKP_MOD_ARM_AFBC_32x8) return "arm_afbc_32x8";
    if (DRM_FORMAT_MOD_ARM_IS_AFBC(modifier)) {
        snprintf(other, sizeof(other), "arm_afbc_%llx", (unsigned long long)(modifier & 0x000fffffffffffffULL));
        return other;
    }
    snprintf(other, sizeof(other), "modifier %#llx", (unsigned long long)modifier);
    return other;
}

// Replicate wl_dmabuf data structures and logic for exact boundary verification
#define DMABUF_NFMT_MAX 6
#define DMABUF_NFMT_SDR 4
#define DMABUF_NMOD 16
#define MOD_LINEAR VKP_MOD_LINEAR
#define MOD_INVALID VKP_MOD_INVALID

struct dmabuf_fmt_entry {
    uint32_t format;
    uint32_t pad;
    uint64_t modifier;
};

static struct {
    uint32_t fmt;
    uint64_t mods[DMABUF_NMOD];
    int n;
} test_dmabuf_fmts[DMABUF_NFMT_MAX];

// Simulated vkp_dmabuf_modifiers provider
static int sim_driver_mod_count = 0;
static uint64_t sim_driver_mods[64];

static int mock_vkp_dmabuf_modifiers(uint32_t drm_format, uint64_t *out, int max) {
    (void)drm_format;
    int count = sim_driver_mod_count;
    if (count > max) count = max;
    for (int i = 0; i < count; i++) {
        out[i] = sim_driver_mods[i];
    }
    return count;
}

// Logic copied verbatim from wl_dmabuf.c:dmabuf_build_formats for boundary verification
static int simulate_dmabuf_build_formats(int g_ubwc, int g_afbc, int g_dmabuf_nfmt) {
    int compressed = 0;
    for (int f = 0; f < g_dmabuf_nfmt; f++) {
        uint64_t got[DMABUF_NMOD];
        int n = mock_vkp_dmabuf_modifiers(test_dmabuf_fmts[f].fmt, got, DMABUF_NMOD), k = 0;
        test_dmabuf_fmts[f].mods[k++] = MOD_LINEAR;
        for (int i = 0; i < n && k < DMABUF_NMOD - 1; i++) {
            if (got[i] == MOD_LINEAR) continue;
            if (got[i] == VKP_MOD_QCOM_COMPRESSED && !g_ubwc) continue;
            if (DRM_FORMAT_MOD_ARM_IS_AFBC(got[i]) && !g_afbc) continue;
            test_dmabuf_fmts[f].mods[k++] = got[i];
            if (got[i] == VKP_MOD_QCOM_COMPRESSED || DRM_FORMAT_MOD_ARM_IS_AFBC(got[i])) compressed++;
        }
        test_dmabuf_fmts[f].mods[k++] = MOD_INVALID;
        test_dmabuf_fmts[f].n = k;

        // Invariant checks
        assert(k >= 2);
        assert(k <= DMABUF_NMOD);
        assert(test_dmabuf_fmts[f].mods[0] == MOD_LINEAR);
        assert(test_dmabuf_fmts[f].mods[k - 1] == MOD_INVALID);
    }
    return compressed;
}

// Logic copied verbatim from wl_dmabuf.c:dmabuf_build_feedback
static int simulate_dmabuf_build_feedback(int g_dmabuf_nfmt) {
    struct dmabuf_fmt_entry entries[DMABUF_NFMT_MAX * DMABUF_NMOD];
    int n = 0;
    for (int f = 0; f < g_dmabuf_nfmt; f++) {
        for (int m = 0; m < test_dmabuf_fmts[f].n; m++) {
            if (test_dmabuf_fmts[f].mods[m] == MOD_INVALID) continue;
            assert(n < DMABUF_NFMT_MAX * DMABUF_NMOD);
            entries[n].format = test_dmabuf_fmts[f].fmt;
            entries[n].pad = 0;
            entries[n].modifier = test_dmabuf_fmts[f].mods[m];
            n++;
        }
    }
    if (n > 0) {
        assert(entries[0].modifier == MOD_LINEAR);
    }
    return n;
}

void test_drm_format_mod_arm_is_afbc(void) {
    printf("[TEST] Verifying DRM_FORMAT_MOD_ARM_IS_AFBC macro...\n");

    // Standard defined AFBC modifiers
    assert(DRM_FORMAT_MOD_ARM_IS_AFBC(VKP_MOD_ARM_AFBC_16x16) == 1);
    assert(DRM_FORMAT_MOD_ARM_IS_AFBC(VKP_MOD_ARM_AFBC_32x8) == 1);

    // Arbitrary AFBC modifiers with category 0x00 and vendor 0x08
    assert(DRM_FORMAT_MOD_ARM_IS_AFBC(0x0800000000000000ULL) == 1);
    assert(DRM_FORMAT_MOD_ARM_IS_AFBC(0x0800000000000003ULL) == 1);
    assert(DRM_FORMAT_MOD_ARM_IS_AFBC(0x0800000000000010ULL) == 1);
    assert(DRM_FORMAT_MOD_ARM_IS_AFBC(0x0800ffffffffffffULL) == 1);
    assert(DRM_FORMAT_MOD_ARM_IS_AFBC(0x080f123456789abcULL) == 1);

    // Boundary: Non-ARM vendors with category 0
    assert(DRM_FORMAT_MOD_ARM_IS_AFBC(0x0000000000000000ULL) == 0); // Linear
    assert(DRM_FORMAT_MOD_ARM_IS_AFBC(0x0500000000000001ULL) == 0); // Qualcomm UBWC
    assert(DRM_FORMAT_MOD_ARM_IS_AFBC(0x0100000000000001ULL) == 0); // Intel
    assert(DRM_FORMAT_MOD_ARM_IS_AFBC(0x0200000000000001ULL) == 0); // AMD
    assert(DRM_FORMAT_MOD_ARM_IS_AFBC(0x0300000000000001ULL) == 0); // NVIDIA
    assert(DRM_FORMAT_MOD_ARM_IS_AFBC(0x0400000000000001ULL) == 0); // Samsung
    assert(DRM_FORMAT_MOD_ARM_IS_AFBC(0x0700000000000001ULL) == 0); // Broadcom
    assert(DRM_FORMAT_MOD_ARM_IS_AFBC(0x0900000000000001ULL) == 0); // Allwinner
    assert(DRM_FORMAT_MOD_ARM_IS_AFBC(0xff00000000000001ULL) == 0); // Vendor 0xff
    assert(DRM_FORMAT_MOD_ARM_IS_AFBC(0x00ffffffffffffffULL) == 0); // Invalid

    // Boundary: ARM vendor (0x08) with non-AFBC categories (bits 55:52 != 0x0)
    for (uint64_t cat = 1; cat <= 0xf; cat++) {
        uint64_t non_afbc_mod = (0x08ULL << 56) | (cat << 52) | 0x1ULL;
        assert(DRM_FORMAT_MOD_ARM_IS_AFBC(non_afbc_mod) == 0);
    }

    // 32-bit truncation safety test: pass 32-bit values
    uint32_t val32 = 0x08000001;
    assert(DRM_FORMAT_MOD_ARM_IS_AFBC(val32) == 0);

    printf("  -> DRM_FORMAT_MOD_ARM_IS_AFBC boundary tests PASSED.\n");
}

void test_vkp_modifier_name(void) {
    printf("[TEST] Verifying vkp_modifier_name()...\n");
    assert(strcmp(vkp_modifier_name(VKP_MOD_LINEAR), "linear") == 0);
    assert(strcmp(vkp_modifier_name(VKP_MOD_QCOM_COMPRESSED), "qcom_compressed") == 0);
    assert(strcmp(vkp_modifier_name(VKP_MOD_INVALID), "implicit") == 0);
    assert(strcmp(vkp_modifier_name(VKP_MOD_ARM_AFBC_16x16), "arm_afbc_16x16") == 0);
    assert(strcmp(vkp_modifier_name(VKP_MOD_ARM_AFBC_32x8), "arm_afbc_32x8") == 0);

    // Generic AFBC modifier name
    const char *name5 = vkp_modifier_name(0x0800000000000005ULL);
    assert(strcmp(name5, "arm_afbc_5") == 0);

    const char *name_flag = vkp_modifier_name(0x0800000000000100ULL);
    assert(strcmp(name_flag, "arm_afbc_100") == 0);

    // Non-AFBC modifier name
    const char *name_other = vkp_modifier_name(0x0100000000000001ULL);
    assert(strstr(name_other, "modifier") != NULL);

    printf("  -> vkp_modifier_name tests PASSED.\n");
}

void test_dmabuf_nmod_bounds(void) {
    printf("[TEST] Verifying DMABUF_NMOD = 16 bounds and overflow resistance...\n");

    // Case 1: Driver returns 0 modifiers
    sim_driver_mod_count = 0;
    int comp = simulate_dmabuf_build_formats(1, 1, DMABUF_NFMT_SDR);
    assert(comp == 0);
    for (int f = 0; f < DMABUF_NFMT_SDR; f++) {
        assert(test_dmabuf_fmts[f].n == 2);
        assert(test_dmabuf_fmts[f].mods[0] == MOD_LINEAR);
        assert(test_dmabuf_fmts[f].mods[1] == MOD_INVALID);
    }
    int entries = simulate_dmabuf_build_feedback(DMABUF_NFMT_SDR);
    assert(entries == DMABUF_NFMT_SDR * 1); // Only linear entered

    // Case 2: Driver returns exactly 14 modifiers (total k = 16 = DMABUF_NMOD)
    sim_driver_mod_count = 14;
    for (int i = 0; i < 14; i++) {
        sim_driver_mods[i] = (0x08ULL << 56) | (0x0ULL << 52) | (uint64_t)(i + 1);
    }
    comp = simulate_dmabuf_build_formats(1, 1, DMABUF_NFMT_SDR);
    assert(comp == DMABUF_NFMT_SDR * 14);
    for (int f = 0; f < DMABUF_NFMT_SDR; f++) {
        assert(test_dmabuf_fmts[f].n == 16);
        assert(test_dmabuf_fmts[f].mods[0] == MOD_LINEAR);
        assert(test_dmabuf_fmts[f].mods[15] == MOD_INVALID);
    }
    entries = simulate_dmabuf_build_feedback(DMABUF_NFMT_SDR);
    assert(entries == DMABUF_NFMT_SDR * 15);

    // Case 3: Stress test - Driver returns 50 modifiers (exceeding DMABUF_NMOD)
    sim_driver_mod_count = 50;
    for (int i = 0; i < 50; i++) {
        sim_driver_mods[i] = (0x08ULL << 56) | (0x0ULL << 52) | (uint64_t)(i + 1);
    }
    comp = simulate_dmabuf_build_formats(1, 1, DMABUF_NFMT_MAX);
    // Even with 50 returned by driver, vkp_dmabuf_modifiers limits count to DMABUF_NMOD (16).
    // And loop terminates at k < DMABUF_NMOD - 1 (15), so exactly 14 non-linear modifiers accepted.
    for (int f = 0; f < DMABUF_NFMT_MAX; f++) {
        assert(test_dmabuf_fmts[f].n == 16);
        assert(test_dmabuf_fmts[f].mods[0] == MOD_LINEAR);
        assert(test_dmabuf_fmts[f].mods[15] == MOD_INVALID);
    }
    entries = simulate_dmabuf_build_feedback(DMABUF_NFMT_MAX);
    assert(entries == DMABUF_NFMT_MAX * 15);
    assert(entries <= DMABUF_NFMT_MAX * DMABUF_NMOD);

    // Case 4: DROIDDECK_WAYLAND_AFBC=0 filtering
    sim_driver_mod_count = 4;
    sim_driver_mods[0] = VKP_MOD_ARM_AFBC_16x16;
    sim_driver_mods[1] = VKP_MOD_ARM_AFBC_32x8;
    sim_driver_mods[2] = VKP_MOD_QCOM_COMPRESSED;
    sim_driver_mods[3] = MOD_LINEAR;

    comp = simulate_dmabuf_build_formats(/*ubwc=*/1, /*afbc=*/0, DMABUF_NFMT_SDR);
    // AFBC modifiers should be filtered out, leaving only UBWC
    for (int f = 0; f < DMABUF_NFMT_SDR; f++) {
        assert(test_dmabuf_fmts[f].n == 3); // LINEAR, UBWC, INVALID
        assert(test_dmabuf_fmts[f].mods[0] == MOD_LINEAR);
        assert(test_dmabuf_fmts[f].mods[1] == VKP_MOD_QCOM_COMPRESSED);
        assert(test_dmabuf_fmts[f].mods[2] == MOD_INVALID);
    }
    assert(comp == DMABUF_NFMT_SDR * 1); // 1 compressed (UBWC) per format

    // Case 5: Both AFBC=0 and UBWC=0 (force pure linear)
    comp = simulate_dmabuf_build_formats(/*ubwc=*/0, /*afbc=*/0, DMABUF_NFMT_SDR);
    assert(comp == 0);
    for (int f = 0; f < DMABUF_NFMT_SDR; f++) {
        assert(test_dmabuf_fmts[f].n == 2);
        assert(test_dmabuf_fmts[f].mods[0] == MOD_LINEAR);
        assert(test_dmabuf_fmts[f].mods[1] == MOD_INVALID);
    }

    printf("  -> DMABUF_NMOD = 16 array bounds and filtering PASSED.\n");
}

int main(void) {
    printf("=== Milestone 4 Empirical C Stress Test ===\n");
    test_drm_format_mod_arm_is_afbc();
    test_vkp_modifier_name();
    test_dmabuf_nmod_bounds();
    printf("=== All Milestone 4 Empirical C tests PASSED successfully! ===\n");
    return 0;
}

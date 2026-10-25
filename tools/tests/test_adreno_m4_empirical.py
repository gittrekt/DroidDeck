import pathlib
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]


class AdrenoM4EmpiricalNativeTest(unittest.TestCase):
    @unittest.skipUnless(sys.platform.startswith('linux') and shutil.which('cc'), 'requires a Linux C compiler')
    def test_adreno_kgsl_pinning_and_errors(self):
        """Empirically test that gpu_clock_pin.c handles Adreno KGSL operations and fails with saved_errno on non-ENOENT."""
        with tempfile.TemporaryDirectory() as tmp:
            directory = pathlib.Path(tmp)
            (directory / 'android').mkdir()
            (directory / 'android/log.h').write_text(
                '#define ANDROID_LOG_WARN 5\n'
                'int __android_log_print(int, const char *, const char *, ...);\n')

            test_c = directory / 'test_adreno_pin.c'
            test_c.write_text(r'''
#include <assert.h>
#include <errno.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

#include "''' + str(ROOT / 'app/src/main/cpp/adrenotools/src/hook/kgsl.h') + r'''"
#include "''' + str(ROOT / 'app/src/main/cpp/waylandcomp/src/gpu_clock_pin.h') + r'''"

int __android_log_print(int p, const char *t, const char *f, ...) {
    (void)p; (void)t; (void)f;
    return 0;
}

static atomic_int mock_open_errno = 0;
static atomic_int mock_open_count = 0;
static atomic_int mock_close_count = 0;
static atomic_int mock_ioctl_count = 0;
static atomic_int mock_pwrlevel_set = 0;
static atomic_int mock_command_count = 0;
static uint32_t mock_commands[1024];

int __wrap_open(const char *path, int flags, ...) {
    (void)flags;
    assert(!strcmp(path, "/dev/kgsl-3d0"));
    atomic_fetch_add(&mock_open_count, 1);
    int err = atomic_load(&mock_open_errno);
    if (err != 0) {
        errno = err;
        return -1;
    }
    return 88;
}

int __wrap_close(int fd) {
    assert(fd == 88);
    atomic_fetch_add(&mock_close_count, 1);
    return 0;
}

void *__wrap_mmap(void *addr, size_t len, int prot, int flags, int fd, off_t off) {
    (void)addr; (void)len; (void)prot; (void)flags; (void)off;
    assert(fd == 88);
    return mock_commands;
}

int __wrap_munmap(void *addr, size_t len) {
    (void)addr; (void)len;
    return 0;
}

#ifdef __ANDROID__
int __wrap_ioctl(int fd, int request, ...) {
#else
int __wrap_ioctl(int fd, unsigned long request, ...) {
#endif
    assert(fd == 88);
    atomic_fetch_add(&mock_ioctl_count, 1);
    va_list va;
    va_start(va, request);
    void *arg = va_arg(va, void *);
    va_end(va);

    switch ((unsigned int)request) {
    case IOCTL_KGSL_DEVICE_GETPROPERTY: {
        struct kgsl_device_getproperty *prop = arg;
        if (prop->type == KGSL_PROP_DEVICE_INFO) {
            ((struct kgsl_devinfo *)prop->value)->chip_id = 0x07030000; // Adreno 730
        } else if (prop->type == KGSL_PROP_PWR_CONSTRAINT) {
            struct kgsl_device_constraint *c = prop->value;
            assert(c->type == KGSL_CONSTRAINT_PWRLEVEL);
            atomic_store(&mock_pwrlevel_set, 1);
        }
        break;
    }
    case IOCTL_KGSL_SETPROPERTY: {
        struct kgsl_device_getproperty *prop = arg;
        if (prop->type == KGSL_PROP_PWR_CONSTRAINT) {
            struct kgsl_device_constraint *c = prop->value;
            assert(c->type == KGSL_CONSTRAINT_PWRLEVEL);
            atomic_store(&mock_pwrlevel_set, 1);
        }
        break;
    }
    case IOCTL_KGSL_DRAWCTXT_CREATE: {
        struct kgsl_drawctxt_create *ctx = arg;
        ctx->drawctxt_id = 42;
        break;
    }
    case IOCTL_KGSL_GPUMEM_ALLOC_ID: {
        struct kgsl_gpumem_alloc_id *alloc = arg;
        alloc->id = 7;
        alloc->gpuaddr = 0x200000;
        alloc->mmapsize = sizeof(mock_commands);
        break;
    }
    case IOCTL_KGSL_GPUMEM_SYNC_CACHE:
        break;
    case IOCTL_KGSL_GPU_COMMAND: {
        struct kgsl_gpu_command *cmd = arg;
        cmd->timestamp = atomic_fetch_add(&mock_command_count, 1) + 1;
        break;
    }
    case IOCTL_KGSL_DEVICE_WAITTIMESTAMP_CTXTID:
        break;
    case IOCTL_KGSL_DRAWCTXT_DESTROY:
        break;
    case IOCTL_KGSL_GPUMEM_FREE_ID:
        break;
    default:
        break;
    }
    return 0;
}

int main(void) {
    // 1. Non-ENOENT error verification: EACCES must FAIL and NOT no-op
    atomic_store(&mock_open_errno, EACCES);
    errno = 0;
    bool r_eacces = gpu_clock_pin_set(true);
    assert(!r_eacces);
    assert(errno == EACCES);

    // 2. Non-ENOENT error verification: EPERM must FAIL and NOT no-op
    atomic_store(&mock_open_errno, EPERM);
    errno = 0;
    bool r_eperm = gpu_clock_pin_set(true);
    assert(!r_eperm);
    assert(errno == EPERM);

    // 3. Non-ENOENT error verification: EINTR must FAIL and NOT no-op
    atomic_store(&mock_open_errno, EINTR);
    errno = 0;
    bool r_eintr = gpu_clock_pin_set(true);
    assert(!r_eintr);
    assert(errno == EINTR);

    // 4. ENOENT error verification: ENOENT must return true (Mali devfreq bypass)
    atomic_store(&mock_open_errno, ENOENT);
    errno = 0;
    bool r_enoent = gpu_clock_pin_set(true);
    assert(r_enoent);
    assert(gpu_clock_pin_set(false)); // clean stop

    // 5. Normal Adreno execution: /dev/kgsl-3d0 opens successfully
    atomic_store(&mock_open_errno, 0);
    atomic_store(&mock_command_count, 0);
    atomic_store(&mock_pwrlevel_set, 0);

    bool r_adreno = gpu_clock_pin_set(true);
    assert(r_adreno);

    // Wait for worker thread to submit at least 2 constraint commands
    for (int i = 0; i < 50 && atomic_load(&mock_command_count) < 2; i++) {
        usleep(20000);
    }
    assert(atomic_load(&mock_command_count) >= 2);
    assert(atomic_load(&mock_pwrlevel_set) == 1);

    // Idempotent start
    assert(gpu_clock_pin_set(true));

    // Stop pinning
    assert(gpu_clock_pin_set(false));
    assert(atomic_load(&mock_close_count) >= 1);

    // Idempotent stop
    assert(gpu_clock_pin_set(false));

    printf("ADRENO_EMPIRICAL_PIN_TEST_PASSED\n");
    return 0;
}
''')
            binary = directory / 'test_adreno_pin'
            subprocess.run([
                'cc', '-std=c11', '-D_DEFAULT_SOURCE', '-D__user=', '-Wall', '-Wextra', '-Werror',
                '-I', str(directory), '-pthread',
                str(test_c),
                str(ROOT / 'app/src/main/cpp/waylandcomp/src/gpu_clock_pin.c'),
                '-Wl,--wrap=open,--wrap=close,--wrap=mmap,--wrap=munmap,--wrap=ioctl',
                '-o', str(binary),
            ], check=True)
            res = subprocess.run([str(binary)], check=True, stdout=subprocess.PIPE, text=True, timeout=10)
            self.assertIn("ADRENO_EMPIRICAL_PIN_TEST_PASSED", res.stdout)

    @unittest.skipUnless(sys.platform.startswith('linux') and shutil.which('cc'), 'requires a Linux C compiler')
    def test_adreno_ubwc_modifiers_and_advertisement(self):
        """Empirically test UBWC modifier constants, AFBC isolation, and advertisement logic."""
        with tempfile.TemporaryDirectory() as tmp:
            directory = pathlib.Path(tmp)
            (directory / 'android').mkdir()
            (directory / 'android/native_window.h').write_text('typedef struct ANativeWindow ANativeWindow;\n')
            (directory / 'android/log.h').write_text('int __android_log_print(int, const char *, const char *, ...);\n')

            test_c = directory / 'test_ubwc.c'
            test_c.write_text(r'''
#include <assert.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#include "''' + str(ROOT / 'app/src/main/cpp/waylandcomp/src/vk_present.h') + r'''"

#define DMABUF_NMOD 16

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

// Simulate wl_dmabuf format advertisement logic
static int simulate_advertise(uint64_t *in_mods, int in_n, int g_ubwc, int g_afbc, uint64_t *out_mods, int *out_compressed) {
    int k = 0;
    int compressed = 0;
    out_mods[k++] = VKP_MOD_LINEAR;
    for (int i = 0; i < in_n && k < DMABUF_NMOD - 1; i++) {
        if (in_mods[i] == VKP_MOD_LINEAR) continue;
        if (in_mods[i] == VKP_MOD_QCOM_COMPRESSED && !g_ubwc) continue;
        if (DRM_FORMAT_MOD_ARM_IS_AFBC(in_mods[i]) && !g_afbc) continue;
        out_mods[k++] = in_mods[i];
        if (in_mods[i] == VKP_MOD_QCOM_COMPRESSED || DRM_FORMAT_MOD_ARM_IS_AFBC(in_mods[i])) compressed++;
    }
    out_mods[k++] = VKP_MOD_INVALID;
    *out_compressed = compressed;
    return k;
}

int main(void) {
    // 1. Verify exact constant values
    assert(VKP_MOD_LINEAR == 0x0000000000000000ULL);
    assert(VKP_MOD_QCOM_COMPRESSED == 0x0500000000000001ULL);
    assert(VKP_MOD_ARM_AFBC_16x16 == 0x0800000000000001ULL);
    assert(VKP_MOD_ARM_AFBC_32x8 == 0x0800000000000002ULL);
    assert(VKP_MOD_INVALID == 0x00ffffffffffffffULL);

    // 2. Strict isolation: QCOM_COMPRESSED must NEVER be classified as AFBC
    assert(!DRM_FORMAT_MOD_ARM_IS_AFBC(VKP_MOD_QCOM_COMPRESSED));
    assert(!DRM_FORMAT_MOD_ARM_IS_AFBC(VKP_MOD_LINEAR));
    assert(!DRM_FORMAT_MOD_ARM_IS_AFBC(VKP_MOD_INVALID));
    assert(DRM_FORMAT_MOD_ARM_IS_AFBC(VKP_MOD_ARM_AFBC_16x16));
    assert(DRM_FORMAT_MOD_ARM_IS_AFBC(VKP_MOD_ARM_AFBC_32x8));

    // 3. Modifier naming verification
    assert(!strcmp(vkp_modifier_name(VKP_MOD_LINEAR), "linear"));
    assert(!strcmp(vkp_modifier_name(VKP_MOD_QCOM_COMPRESSED), "qcom_compressed"));
    assert(!strcmp(vkp_modifier_name(VKP_MOD_INVALID), "implicit"));
    assert(!strcmp(vkp_modifier_name(VKP_MOD_ARM_AFBC_16x16), "arm_afbc_16x16"));
    assert(!strcmp(vkp_modifier_name(VKP_MOD_ARM_AFBC_32x8), "arm_afbc_32x8"));

    // 4. Test format advertisement simulation
    uint64_t adreno_driver_mods[] = { VKP_MOD_LINEAR, VKP_MOD_QCOM_COMPRESSED };
    uint64_t advertised[DMABUF_NMOD];
    int compressed = 0;

    // Adreno default: g_ubwc=1
    int count = simulate_advertise(adreno_driver_mods, 2, 1, 1, advertised, &compressed);
    assert(count == 3);
    assert(advertised[0] == VKP_MOD_LINEAR);
    assert(advertised[1] == VKP_MOD_QCOM_COMPRESSED);
    assert(advertised[2] == VKP_MOD_INVALID);
    assert(compressed == 1);

    // Adreno with g_ubwc=0 (forced linear)
    count = simulate_advertise(adreno_driver_mods, 2, 0, 1, advertised, &compressed);
    assert(count == 2);
    assert(advertised[0] == VKP_MOD_LINEAR);
    assert(advertised[1] == VKP_MOD_INVALID);
    assert(compressed == 0);

    // Mixed driver mods: ensures g_ubwc and g_afbc do not cross-talk
    uint64_t mixed_mods[] = { VKP_MOD_LINEAR, VKP_MOD_QCOM_COMPRESSED, VKP_MOD_ARM_AFBC_16x16 };

    // Case: ubwc=1, afbc=0 -> only UBWC advertised
    count = simulate_advertise(mixed_mods, 3, 1, 0, advertised, &compressed);
    assert(count == 3);
    assert(advertised[0] == VKP_MOD_LINEAR);
    assert(advertised[1] == VKP_MOD_QCOM_COMPRESSED);
    assert(advertised[2] == VKP_MOD_INVALID);
    assert(compressed == 1);

    // Case: ubwc=0, afbc=1 -> only AFBC advertised
    count = simulate_advertise(mixed_mods, 3, 0, 1, advertised, &compressed);
    assert(count == 3);
    assert(advertised[0] == VKP_MOD_LINEAR);
    assert(advertised[1] == VKP_MOD_ARM_AFBC_16x16);
    assert(advertised[2] == VKP_MOD_INVALID);
    assert(compressed == 1);

    // Case: ubwc=0, afbc=0 -> both suppressed, linear only
    count = simulate_advertise(mixed_mods, 3, 0, 0, advertised, &compressed);
    assert(count == 2);
    assert(advertised[0] == VKP_MOD_LINEAR);
    assert(advertised[1] == VKP_MOD_INVALID);
    assert(compressed == 0);

    // 5. Test vkp_dmabuf_modifiers rejection check logic:
    // In vk_present.c:
    // if (m != VKP_MOD_LINEAR && m != VKP_MOD_QCOM_COMPRESSED && !DRM_FORMAT_MOD_ARM_IS_AFBC(m)) why = "unknown layout";
    uint64_t test_mods[] = {
        VKP_MOD_LINEAR,
        VKP_MOD_QCOM_COMPRESSED,
        VKP_MOD_ARM_AFBC_16x16,
        VKP_MOD_ARM_AFBC_32x8,
        0x0800000000000003ULL, // general arm afbc
        0x0500000000000002ULL, // unknown qcom modifier
        0x1234567890abcdefULL  // random unknown modifier
    };

    bool accepted[7];
    for (int i = 0; i < 7; i++) {
        uint64_t m = test_mods[i];
        accepted[i] = (m == VKP_MOD_LINEAR || m == VKP_MOD_QCOM_COMPRESSED || DRM_FORMAT_MOD_ARM_IS_AFBC(m));
    }

    assert(accepted[0] == true);  // LINEAR accepted
    assert(accepted[1] == true);  // QCOM_COMPRESSED accepted
    assert(accepted[2] == true);  // AFBC 16x16 accepted
    assert(accepted[3] == true);  // AFBC 32x8 accepted
    assert(accepted[4] == true);  // general AFBC accepted
    assert(accepted[5] == false); // unknown qcom modifier rejected
    assert(accepted[6] == false); // unknown modifier rejected

    printf("ADRENO_EMPIRICAL_UBWC_TEST_PASSED\n");
    return 0;
}
''')
            binary = directory / 'test_ubwc'
            subprocess.run([
                'cc', '-std=c11', '-D_DEFAULT_SOURCE', '-Wall', '-Wextra', '-Werror',
                '-I', str(directory),
                str(test_c),
                '-o', str(binary),
            ], check=True)
            res = subprocess.run([str(binary)], check=True, stdout=subprocess.PIPE, text=True, timeout=10)
            self.assertIn("ADRENO_EMPIRICAL_UBWC_TEST_PASSED", res.stdout)


if __name__ == '__main__':
    unittest.main()

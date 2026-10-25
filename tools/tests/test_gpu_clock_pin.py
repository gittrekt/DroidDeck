import pathlib
import shutil
import subprocess
import sys
import tempfile
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]


class GpuClockPinTest(unittest.TestCase):
    @unittest.skipUnless(sys.platform.startswith('linux') and shutil.which('cc'), 'requires a Linux C compiler')
    def test_constraint_lifecycle(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = pathlib.Path(tmp)
            (directory / 'android').mkdir()
            (directory / 'android/log.h').write_text(
                '#define ANDROID_LOG_WARN 5\n'
                'int __android_log_print(int, const char *, const char *, ...);\n')
            binary = directory / 'gpu-clock-pin-test'
            subprocess.run([
                'cc', '-std=c11', '-D_DEFAULT_SOURCE', '-D__user=', '-Wall', '-Wextra', '-Werror',
                '-I', str(directory), '-pthread',
                str(ROOT / 'tools/tests/test_gpu_clock_pin.c'),
                str(ROOT / 'app/src/main/cpp/waylandcomp/src/gpu_clock_pin.c'),
                '-Wl,--wrap=open,--wrap=close,--wrap=mmap,--wrap=munmap,--wrap=ioctl,--wrap=pthread_create',
                '-o', str(binary),
            ], check=True)
            subprocess.run([str(binary)], check=True, timeout=10)

    @unittest.skipUnless(sys.platform.startswith('linux') and shutil.which('cc'), 'requires a Linux C compiler')
    def test_mali_enoent_noop(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = pathlib.Path(tmp)
            (directory / 'android').mkdir()
            (directory / 'android/log.h').write_text(
                '#define ANDROID_LOG_WARN 5\n'
                'int __android_log_print(int, const char *, const char *, ...);\n')
            mali_test_c = directory / 'mali_test.c'
            mali_test_c.write_text(
                '#include <assert.h>\n'
                '#include <errno.h>\n'
                '#include <stdbool.h>\n'
                '#include <string.h>\n'
                '#include "' + str(ROOT / 'app/src/main/cpp/waylandcomp/src/gpu_clock_pin.h') + '"\n'
                'int __android_log_print(int p, const char *t, const char *f, ...) { (void)p; (void)t; (void)f; return 0; }\n'
                'int __wrap_open(const char *path, int flags, ...) {\n'
                '    (void)flags;\n'
                '    assert(!strcmp(path, "/dev/kgsl-3d0"));\n'
                '    errno = ENOENT;\n'
                '    return -1;\n'
                '}\n'
                'int __wrap_close(int fd) { (void)fd; assert(!"close should not be called"); return 0; }\n'
                'int __wrap_ioctl(int fd, unsigned long req, ...) { (void)fd; (void)req; assert(!"ioctl should not be called"); return 0; }\n'
                'int main(void) {\n'
                '    assert(gpu_clock_pin_set(true));\n'
                '    assert(gpu_clock_pin_set(false));\n'
                '    assert(gpu_clock_pin_set(true));\n'
                '    assert(gpu_clock_pin_set(true));\n'
                '    assert(gpu_clock_pin_set(false));\n'
                '    return 0;\n'
                '}\n'
            )
            binary = directory / 'gpu-clock-pin-mali-test'
            subprocess.run([
                'cc', '-std=c11', '-D_DEFAULT_SOURCE', '-D__user=', '-Wall', '-Wextra', '-Werror',
                '-I', str(directory), '-pthread',
                str(mali_test_c),
                str(ROOT / 'app/src/main/cpp/waylandcomp/src/gpu_clock_pin.c'),
                '-Wl,--wrap=open,--wrap=close,--wrap=ioctl',
                '-o', str(binary),
            ], check=True)
            subprocess.run([str(binary)], check=True, timeout=10)


if __name__ == '__main__':
    unittest.main()

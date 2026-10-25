import pathlib
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]


class Milestone4BoundaryTest(unittest.TestCase):
    @unittest.skipUnless(sys.platform.startswith('linux') and shutil.which('cc'), 'requires a Linux C compiler')
    def test_afbc_modifier_boundaries_and_dmabuf_nmod(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = pathlib.Path(tmp)
            (directory / 'android').mkdir()
            (directory / 'android/native_window.h').write_text(
                '#ifndef ANDROID_NATIVE_WINDOW_H\n'
                '#define ANDROID_NATIVE_WINDOW_H\n'
                'typedef struct ANativeWindow ANativeWindow;\n'
                '#endif\n'
            )
            binary = directory / 'm4-boundary-test'
            subprocess.run([
                'cc', '-std=c11', '-D_DEFAULT_SOURCE', '-Wall', '-Wextra', '-Werror',
                '-I', str(directory),
                str(ROOT / 'tools/tests/test_milestone4_afbc_boundary.c'),
                '-o', str(binary),
            ], check=True)
            result = subprocess.run([str(binary)], check=True, capture_output=True, text=True, timeout=10)
            print(result.stdout)
            self.assertIn("All Milestone 4 Empirical C tests PASSED successfully!", result.stdout)


if __name__ == '__main__':
    unittest.main()

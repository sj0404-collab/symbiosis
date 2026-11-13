# SPDX-License-Identifier: GPL-3.0-or-later
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]

class KenjiFfmpegContract(unittest.TestCase):
    def test_pinned_build_and_codecs(self):
        s = (ROOT / 'tools/build_kenji_ffmpeg.sh').read_text()
        for text in ('version=5.1.8', '56d4daf10c17330a45c8fe11bc260997677ca2432d3d5951dbeb5515c26028cb',
                     '--disable-autodetect', '--enable-shared', '--disable-everything',
                     '--enable-decoder=h264', '--enable-decoder=vp8', 'max-page-size=16384'):
            self.assertIn(text, s)
        self.assertNotIn('--enable-gpl', s)
        self.assertNotIn('--enable-nonfree', s)

    def test_preload_and_package(self):
        cpp = (ROOT / 'patch/symbiosis/kenji_bridge.cpp').read_text()
        part = cpp.split('void PreloadKenjiFfmpeg()',1)[1].split('void ConfigureCoreRuntimeEnv()',1)[0]
        for lib in ('libavutil.so','libswresample.so','libavcodec.so','libswscale.so'):
            self.assertIn(lib, part)
        self.assertIn('RTLD_NOW | RTLD_GLOBAL', part)
        self.assertLess(cpp.index('PreloadKenjiFfmpeg();'), cpp.index('void* handle = dlopen(path'))
        for flow in ('build.yml', 'kenji-player-runtime.yml'):
            s = (ROOT / '.github/workflows' / flow).read_text()
            self.assertIn('build_kenji_ffmpeg.sh', s)
            self.assertIn('audit_ffmpeg_apk.py', s)
        self.assertIn('ff_count', (ROOT / 'tools/apply_patch.sh').read_text())

    def test_real_android_test_not_release(self):
        s = (ROOT / 'tests/player_runtime_probe/ffmpeg_in_process.c').read_text()
        for term in ('offsetof(FFCodec, cb)', 'avcodec_find_decoder', 'ffcodec(h264)->cb.decode',
                     'frame->width == 16', 'AV_CODEC_ID_VP8'):
            self.assertIn(term, s)
        self.assertIn('instrument_ffmpeg.py', (ROOT / '.github/workflows/kenji-player-runtime.yml').read_text())
        self.assertNotIn('instrument_ffmpeg.py', (ROOT / '.github/workflows/build.yml').read_text())

if __name__ == '__main__':
    unittest.main()

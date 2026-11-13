#!/usr/bin/env python3
"""Require the actual APK to contain the four replaceable ARM64 FFmpeg libs."""
import sys
import zipfile
from pathlib import Path
from audit_apk_16k import elf_errors

apk = Path(sys.argv[1])
with zipfile.ZipFile(apk) as z:
    for name in ('avutil', 'swresample', 'avcodec', 'swscale'):
        path = f'lib/arm64-v8a/lib{name}.so'
        data = z.read(path)
        problems = elf_errors(data)
        if problems:
            raise SystemExit(f'{path} 16K alignment failed: {problems}')
        print('APK_FFMPEG_16K_OK', name, len(data))
    for name in ('ffmpeg-lgpl-2.1.txt', 'ffmpeg-5.1.8-notice.txt'):
        if not z.read('assets/' + name):
            raise SystemExit('FFmpeg license missing: ' + name)
        print('APK_FFMPEG_LICENSE_OK', name)
print('FFMPEG_DEVICE_16K_EXECUTION_NOT_TESTED')

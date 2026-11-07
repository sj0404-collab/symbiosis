#!/usr/bin/env python3
"""Run real ARM64 core loader probes via adb without installing an APK.

Fresh processes independently test native dlopen/dlsym and then real JNI
javaInitialize through Android app_process. No Activity or APK is installed.
There is no device initialization, rendering, input, firmware, keys or game.
The local paths are produced and SHA-checked by android_core_probe/build.sh.
"""
import argparse
from pathlib import Path
import shlex
import subprocess
import sys

REMOTE = '/data/local/tmp/symbiosis-standalone-probe'


def adb(*args, timeout=120, check=True):
    return subprocess.run(['adb', *map(str, args)], text=True, stdout=subprocess.PIPE,
                          stderr=subprocess.STDOUT, timeout=timeout, check=check)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('work', type=Path, help='disposable, previously built probe directory')
    args = parser.parse_args()
    work = args.work.resolve()
    files = {
        'probe': work / 'standalone-core-probe',
        'libstandalonejni.so': work / 'libstandalonejni.so',
        'standalone.jar': work / 'standalone.jar',
        'libcore_sym.so': work / 'core_sym.so',
        'libcore_space.so': work / 'core_space.so',
        'libkenjinxjni.so': work / 'apk/lib/arm64-v8a/libkenjinxjni.so',
        'libcrypto.so': work / 'apk/lib/arm64-v8a/libcrypto.so',
        'libssl.so': work / 'apk/lib/arm64-v8a/libssl.so',
    }
    for name, source in files.items():
        if not source.is_file():
            print(f'LOCAL_FILE_MISSING {name}: {source}', file=sys.stderr)
            return 2
    try:
        adb('shell', 'mkdir', '-p', REMOTE)
        for name, source in files.items():
            adb('push', source, f'{REMOTE}/{name}', timeout=300)
        adb('shell', 'chmod', '755', f'{REMOTE}/probe')
        outcomes = []
        for core in ('sym', 'space'):
            command = (f'LD_LIBRARY_PATH={shlex.quote(REMOTE + ":/system/lib64")} '
                       f'{shlex.quote(REMOTE + "/probe")} '
                       f'{shlex.quote(REMOTE + "/libcore_" + core + ".so")}')
            print(f'=== standalone {core}, no APK, no initialization ===', flush=True)
            try:
                result = adb('shell', command, timeout=180, check=False)
                text = result.stdout + f'EXIT_CODE={result.returncode}\n'
                passed = (result.returncode == 0 and 'DLOPEN_OK' in result.stdout and
                          'RESULT symbols=11/11 missing=0 initialization=not-attempted' in result.stdout)
            except subprocess.TimeoutExpired as exc:
                text = (exc.stdout or b'')
                if isinstance(text, bytes):
                    text = text.decode('utf-8', errors='replace')
                text += '\nTIMEOUT\n'
                passed = False
            (work / f'standalone-{core}.txt').write_text(text, encoding='utf-8')
            print(text, end='', flush=True)
            outcomes.append(passed)

        for core in ('sym', 'space'):
            data = f'{REMOTE}/data-{core}'
            adb('shell', 'mkdir', '-p', data, *(f'{data}/{x}' for x in ('Logs', 'Games', 'Profiles', 'Keys')))
            # Disposable emulator only. Clear first to keep each crash isolated.
            adb('logcat', '-c', check=False)
            command = (f'CLASSPATH={shlex.quote(REMOTE + "/standalone.jar")} '
                       f'LD_LIBRARY_PATH={shlex.quote(REMOTE + ":/system/lib64")} DOTNET_GCRegionRange=400000000 '
                       f'/system/bin/app_process /system/bin dev.symbiosis.standalone.Main '
                       f'{shlex.quote(REMOTE + "/libstandalonejni.so")} '
                       f'{shlex.quote(REMOTE + "/libcore_" + core + ".so")} {shlex.quote(data)}')
            print(f'=== standalone JNI {core}, real javaInitialize, no APK ===', flush=True)
            try:
                result = adb('shell', command, timeout=180, check=False)
                text = result.stdout + f'EXIT_CODE={result.returncode}\n'
                passed = result.returncode == 0 and 'JNI_RESULT javaInitialize=true' in result.stdout
            except subprocess.TimeoutExpired as exc:
                text = exc.stdout or b''
                if isinstance(text, bytes):
                    text = text.decode('utf-8', errors='replace')
                text += '\nTIMEOUT\n'
                passed = False
            (work / f'standalone-jni-{core}.txt').write_text(text, encoding='utf-8')
            print(text, end='', flush=True)
            try:
                logcat = adb('logcat', '-d', '-v', 'threadtime', timeout=60, check=False)
                (work / f'standalone-jni-{core}-logcat.txt').write_text(logcat.stdout, encoding='utf-8')
            except (OSError, subprocess.SubprocessError) as exc:
                print(f'LOGCAT_UNAVAILABLE {exc}', file=sys.stderr)
            outcomes.append(passed)
        return 0 if all(outcomes) else 1
    except (OSError, subprocess.SubprocessError) as exc:
        detail = getattr(exc, 'stdout', '') or ''
        if isinstance(detail, bytes):
            detail = detail.decode('utf-8', errors='replace')
        message = f'STANDALONE_SETUP_FAIL {exc}\n{detail}'
        (work / 'standalone-setup.txt').write_text(message, encoding='utf-8')
        print(message, file=sys.stderr)
        return 2
    finally:
        try:
            adb('shell', 'rm', '-rf', REMOTE, timeout=30, check=False)
        except (OSError, subprocess.SubprocessError):
            pass


if __name__ == '__main__':
    sys.exit(main())

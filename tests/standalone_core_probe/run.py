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
    parser.add_argument('--preload-tls', action='store_true',
                        help='preload the real bundled TLS files before real core initialization')
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
        # Android 14+ can reject dynamically loaded writable DEX files.
        adb('shell', 'chmod', '0444', f'{REMOTE}/standalone.jar')
        outcomes = []
        for core in ('sym', 'space'):
            command = (f'LD_LIBRARY_PATH={shlex.quote("/system/lib64:" + REMOTE)} '
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

        # System libraries must precede bundled TLS, otherwise libjavacore can
        # pick an incompatible libcrypto. Probe Java runtime independently.
        app_prefix = (f'CLASSPATH={shlex.quote(REMOTE + "/standalone.jar")} '
                      f'LD_LIBRARY_PATH={shlex.quote("/system/lib64:" + REMOTE)} DOTNET_GCRegionRange=400000000 '
                      f'/system/bin/app_process /system/bin dev.symbiosis.standalone.Main')
        smoke_file = f'{REMOTE}/smoke.txt'
        adb('shell', 'rm', '-f', smoke_file, check=False)
        adb('logcat', '-c', check=False)
        print('=== standalone ART smoke, no JNI and no core ===', flush=True)
        try:
            smoke_result = adb('shell', f'{app_prefix} --smoke {shlex.quote(smoke_file)}', timeout=180, check=False)
            smoke_marker = adb('shell', 'cat', smoke_file, check=False).stdout
            smoke_text = smoke_result.stdout + smoke_marker + f'EXIT_CODE={smoke_result.returncode}\n'
            smoke_ok = smoke_result.returncode == 0 and 'JAVA_SMOKE_OK' in smoke_marker
        except subprocess.TimeoutExpired:
            smoke_text, smoke_ok = 'ART_SMOKE_TIMEOUT\n', False
        (work / 'standalone-art-smoke.txt').write_text(smoke_text, encoding='utf-8')
        print(smoke_text, end='', flush=True)
        if not smoke_ok:
            logcat = adb('logcat', '-d', '-v', 'threadtime', timeout=60, check=False)
            (work / 'standalone-art-smoke-logcat.txt').write_text(logcat.stdout, encoding='utf-8')
            print('ART_SMOKE_FAIL: JNI/core initialization not attempted', flush=True)
            for core in ('sym', 'space'):
                (work / f'standalone-jni-{core}.txt').write_text(
                    'NOT_ATTEMPTED ART smoke failed\n', encoding='utf-8')
            return 1
        for core in ('sym', 'space'):
            data = f'{REMOTE}/data-{core}'
            adb('shell', 'mkdir', '-p', data, *(f'{data}/{x}' for x in ('Logs', 'Games', 'Profiles', 'Keys')))
            # Disposable emulator only. Clear first to keep each crash isolated.
            adb('logcat', '-c', check=False)
            adb('shell', 'rm', '-f', f'{data}/java-stages.txt', f'{data}/jni-stages.txt', check=False)
            command = (f'{app_prefix} {shlex.quote(REMOTE + "/libstandalonejni.so")} '
                       f'{shlex.quote(REMOTE + "/libcore_" + core + ".so")} {shlex.quote(data)}')
            if args.preload_tls:
                command += ' --preload-tls'
            print(f'=== standalone JNI {core}, real javaInitialize, no APK ===', flush=True)
            try:
                result = adb('shell', command, timeout=180, check=False)
                text = result.stdout + f'EXIT_CODE={result.returncode}\n'
                exit_code = result.returncode
            except subprocess.TimeoutExpired as exc:
                text = exc.stdout or b''
                if isinstance(text, bytes):
                    text = text.decode('utf-8', errors='replace')
                text += '\nTIMEOUT\n'
                exit_code = -1
            markers = []
            for name in ('java-stages.txt', 'jni-stages.txt'):
                marker = adb('shell', 'cat', f'{data}/{name}', check=False)
                if marker.returncode == 0:
                    markers.append(marker.stdout)
                    text += f'--- {name} ---\n{marker.stdout}'
                else:
                    text += f'--- {name}: unavailable ---\n'
            passed = exit_code == 0 and any('JNI_RESULT javaInitialize=true' in m for m in markers)
            if args.preload_tls:
                passed = passed and any('TLS_CONTEXT_SMOKE=ok' in m for m in markers)
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

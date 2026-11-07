#!/usr/bin/env python3
"""Host-only tests for the isolated, no-APK dlopen diagnostic."""
import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[1]
SYMBOLS = ('javaInitialize deviceInitialize deviceLoadDescriptor deviceCloseEmulation '
           'deviceSignalEmulationClose graphicsInitialize graphicsInitializeRenderer '
           'graphicsRendererRunLoop graphicsRendererSetSize inputInitialize deviceGetGameFrameRate').split()


class StandaloneProbeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.folder = Path(cls.temp.name)
        cls.executable = cls.folder / 'probe'
        subprocess.run(['g++', '-std=c++17', '-Wall', '-Wextra', '-Werror',
                        str(ROOT / 'tests/standalone_core_probe/probe.cpp'), '-ldl',
                        '-o', str(cls.executable)], check=True)
        src = cls.folder / 'fixture.cpp'
        src.write_text('\n'.join('extern "C" void ' + s + '() {}' for s in SYMBOLS))
        cls.fixture = cls.folder / 'fixture.so'
        subprocess.run(['g++', '-shared', '-fPIC', str(src), '-o', str(cls.fixture)], check=True)
        src.write_text('extern "C" void javaInitialize() {}\n')
        cls.incomplete = cls.folder / 'incomplete.so'
        subprocess.run(['g++', '-shared', '-fPIC', str(src), '-o', str(cls.incomplete)], check=True)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def invoke(self, *args):
        return subprocess.run([str(self.executable), *map(str, args)], capture_output=True, text=True)

    def test_usage(self):
        self.assertEqual(self.invoke().returncode, 2)

    def test_fixture_names_only_never_initialize(self):
        run = self.invoke(self.fixture)
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertIn('DLOPEN_OK (no JNI, no device, no game initialized)', run.stdout)
        self.assertIn('symbols=11/11 missing=0 initialization=not-attempted', run.stdout)

    def test_incomplete_symbols_fail(self):
        run = self.invoke(self.incomplete)
        self.assertEqual(run.returncode, 4)
        self.assertIn('SYMBOL deviceInitialize missing', run.stdout)
        self.assertIn('symbols=1/11 missing=10', run.stdout)

    def test_nonexistent_file_fails(self):
        run = self.invoke(self.folder / 'missing.so')
        self.assertEqual(run.returncode, 3)
        self.assertIn('DLOPEN_FAIL', run.stderr)


class OrchestrationTest(unittest.TestCase):
    def test_two_separate_processes_and_failure_not_hidden(self):
        spec = importlib.util.spec_from_file_location('standalone_run', ROOT / 'tests/standalone_core_probe/run.py')
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        with tempfile.TemporaryDirectory() as tmp:
            work = Path(tmp)
            for name in ('standalone-core-probe', 'core_sym.so', 'core_space.so',
                         'libstandalonejni.so', 'standalone.jar',
                         'apk/lib/arm64-v8a/libkenjinxjni.so',
                         'apk/lib/arm64-v8a/libcrypto.so', 'apk/lib/arm64-v8a/libssl.so'):
                path = work / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.touch()
            calls = []

            def fake_adb(*args, **kwargs):
                calls.append(args)
                cmd = args[1] if args[0] == 'shell' and len(args) == 2 else ''
                if 'app_process' in cmd:
                    return subprocess.CompletedProcess(args, 0, 'JNI_RESULT javaInitialize=true\n')
                if 'libcore_sym.so' in cmd:
                    return subprocess.CompletedProcess(args, 0, 'DLOPEN_OK\nRESULT symbols=11/11 missing=0 initialization=not-attempted\n')
                if 'libcore_space.so' in cmd:
                    return subprocess.CompletedProcess(args, 3, 'DLOPEN_FAIL missing library\n')
                return subprocess.CompletedProcess(args, 0, '')

            with mock.patch.object(module, 'adb', side_effect=fake_adb), mock.patch.object(sys, 'argv', ['run.py', tmp]):
                self.assertEqual(module.main(), 1)
            self.assertIn('EXIT_CODE=0', (work / 'standalone-sym.txt').read_text())
            self.assertIn('EXIT_CODE=3', (work / 'standalone-space.txt').read_text())
            self.assertIn('JNI_RESULT javaInitialize=true', (work / 'standalone-jni-sym.txt').read_text())
            commands = [a[1] for a in calls if a[0] == 'shell' and len(a) == 2 and 'LD_LIBRARY_PATH=' in a[1]]
            self.assertEqual(len(commands), 4)
            self.assertEqual(sum('app_process' in c for c in commands), 2)
            self.assertTrue(any('libcore_sym.so' in c for c in commands))
            self.assertTrue(any('libcore_space.so' in c for c in commands))
            self.assertFalse(any('install' in str(c) for c in calls))


if __name__ == '__main__':
    unittest.main()

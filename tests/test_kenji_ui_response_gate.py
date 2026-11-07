#!/usr/bin/env python3
"""Compile the proposed response lifetime gate with a synthetic core on Linux.

This is not a device test and does not call a real Kenji binary. The gate is
not wired into the Android player until the rest of the UI protocol is ready.
"""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class KenjiUiResponseGateTest(unittest.TestCase):
    @unittest.skipUnless(shutil.which('g++'), 'requires a C++ compiler')
    def test_real_gate_with_synthetic_responses_and_races(self):
        with tempfile.TemporaryDirectory() as directory:
            exe = Path(directory) / 'test-gate'
            subprocess.run([
                'g++', '-std=c++17', '-pthread', '-Wall', '-Wextra', '-Werror',
                str(ROOT / 'tests/kenji_ui_response_gate.cpp'), '-o', str(exe)
            ], check=True, timeout=30)
            subprocess.run([str(exe)], check=True, timeout=15)

    @unittest.skipUnless(shutil.which('g++'), 'requires a C++ compiler')
    def test_gate_against_dlopen_mock_then_refuse_after_dlclose(self):
        with tempfile.TemporaryDirectory() as directory:
            lib, exe = Path(directory) / 'mock.so', Path(directory) / 'test-dlopen'
            subprocess.run([
                'g++', '-std=c++17', '-fPIC', '-shared', '-Wall', '-Wextra', '-Werror',
                str(ROOT / 'tests/kenji_ui_mock_core.cpp'), '-o', str(lib)
            ], check=True, timeout=30)
            subprocess.run([
                'g++', '-std=c++17', '-pthread', '-Wall', '-Wextra', '-Werror',
                str(ROOT / 'tests/kenji_ui_mock_core_test.cpp'), '-ldl', '-o', str(exe)
            ], check=True, timeout=30)
            subprocess.run([str(exe), str(lib)], check=True, timeout=15)


if __name__ == '__main__':
    unittest.main()

#!/usr/bin/env python3
"""Host tests for debug-only TLS provenance instrumentation and APK audit."""
import importlib.util
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('apk_audit', ROOT / 'tests/player_runtime_probe/audit_apk_16k.py')
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)


def elf(align=16384, addr=0, offset=0):
    data = bytearray(4096)
    data[:6] = b'\x7fELF\x02\x01'
    struct.pack_into('<H', data, 18, 183)
    struct.pack_into('<Q', data, 32, 64)
    struct.pack_into('<HH', data, 54, 56, 1)
    struct.pack_into('<IIQQQQQQ', data, 64, 1, 5, offset, addr, 0, 128, 128, align)
    return data


class PlayerTlsAudit(unittest.TestCase):
    def test_elf_alignment_and_malformed(self):
        self.assertEqual(audit.elf_errors(elf()), [])
        self.assertTrue(audit.elf_errors(elf(align=4096)))
        self.assertTrue(audit.elf_errors(elf(addr=4096)))
        self.assertTrue(audit.elf_errors(b'\x7fELF'))

    def test_zip_candidate_alignment_and_other_libs_reported(self):
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / 'probe.apk'
            with zipfile.ZipFile(path, 'w', compression=zipfile.ZIP_DEFLATED) as z:
                for name in ('libcrypto.so', 'libssl.so'):
                    z.writestr('lib/arm64-v8a/' + name, elf())
                z.writestr('lib/arm64-v8a/libother.so', elf(align=4096))
            run = subprocess.run([sys.executable, audit.__file__, str(path)], capture_output=True, text=True)
            self.assertEqual(run.returncode, 0, run.stderr)
            self.assertIn('arm64_elf_bad=1', run.stdout)
            self.assertIn('APK_16K_DEVICE_EXECUTION_NOT_TESTED', run.stdout)
            with zipfile.ZipFile(path, 'w', compression=zipfile.ZIP_DEFLATED) as z:
                z.writestr('lib/arm64-v8a/libcrypto.so', elf(align=4096))
                z.writestr('lib/arm64-v8a/libssl.so', elf())
            run = subprocess.run([sys.executable, audit.__file__, str(path)], capture_output=True, text=True)
            self.assertNotEqual(run.returncode, 0)

    def test_tls_observer_retains_real_load_and_rejects_unexpected_source(self):
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / 'bridge.cpp'
            path.write_bytes((ROOT / 'patch/symbiosis/kenji_bridge.cpp').read_bytes())
            observer = ROOT / 'tests/player_runtime_probe/instrument_tls.py'
            subprocess.run([sys.executable, str(observer), str(path)], check=True)
            source = path.read_text()
            self.assertIn('CI_ONLY_TLS_OPENSSL_VERSION=0x%lx', source)
            self.assertIn('handle = ::dlopen(absolute.c_str(), RTLD_NOW | RTLD_GLOBAL)', source)
            path.write_text('unexpected source')
            self.assertNotEqual(subprocess.run([sys.executable, str(observer), str(path)], capture_output=True).returncode, 0)

if __name__ == '__main__':
    unittest.main()

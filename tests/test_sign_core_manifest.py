# SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
# SPDX-License-Identifier: GPL-3.0-or-later
import base64
import hashlib
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TOOL = ROOT / 'tools/sign_core_manifest.py'

class SignCoreManifestTest(unittest.TestCase):
    def test_sign_and_reject_bad_sizes_and_sequence(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            key, pub, core, output = (root / x for x in ('key.pem', 'pub.pem', 'core.so', 'manifest'))
            subprocess.run(['openssl', 'genpkey', '-algorithm', 'RSA', '-pkeyopt', 'rsa_keygen_bits:2048', '-out', str(key)], check=True, capture_output=True)
            subprocess.run(['openssl', 'pkey', '-in', str(key), '-pubout', '-out', str(pub)], check=True, capture_output=True)
            pubder = root / 'pub.der'
            subprocess.run(['openssl', 'pkey', '-in', str(key), '-pubout', '-outform', 'DER', '-out', str(pubder)], check=True, capture_output=True)
            core.write_bytes(b'x' * 1_000_000)
            command = ['python3', str(TOOL), '--sequence', '7', '--core', str(core), '--private-key', str(key), '--output', str(output)]
            subprocess.run(command, check=True, capture_output=True)
            verify = ['python3', str(ROOT / 'tools/verify_core_manifest.py'), '--manifest', str(output), '--public-key-der', str(pubder)]
            subprocess.run(verify, check=True, capture_output=True)
            result = output.read_bytes()
            payload, signature = result.split(b'signature=')
            self.assertEqual(payload, (f'SYMBIOSIS-KENJI-1\nsequence=7\nbytes=1000000\nsha256={hashlib.sha256(core.read_bytes()).hexdigest()}\n').encode())
            payload_file, sig_file = root / 'payload', root / 'signature'
            payload_file.write_bytes(payload)
            sig_file.write_bytes(base64.b64decode(signature.strip(), validate=True))
            subprocess.run(['openssl', 'dgst', '-sha256', '-verify', str(pub), '-signature', str(sig_file), str(payload_file)], check=True, capture_output=True)
            output.write_bytes(result.replace(b'sequence=7', b'sequence=8'))
            self.assertNotEqual(subprocess.run(verify, capture_output=True).returncode, 0)
            payload_file.write_bytes(payload.replace(b'sequence=7', b'sequence=8'))
            self.assertNotEqual(subprocess.run(['openssl', 'dgst', '-sha256', '-verify', str(pub), '-signature', str(sig_file), str(payload_file)], capture_output=True).returncode, 0)
            core.write_bytes(b'x')
            self.assertNotEqual(subprocess.run(command, capture_output=True).returncode, 0)
            core.write_bytes(b'x' * 1_000_000)
            command[command.index('7')] = '0'
            self.assertNotEqual(subprocess.run(command, capture_output=True).returncode, 0)

if __name__ == '__main__': unittest.main()

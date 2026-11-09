#!/usr/bin/env python3
"""Host checks of the standalone TLS tester against real system OpenSSL.
The temporary certificate is deliberately synthetic and never shipped in APKs.
"""
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class TlsHandshakeTest(unittest.TestCase):
    def test_real_tls_accepts_correct_hostname_and_rejects_wrong_one(self):
        with tempfile.TemporaryDirectory() as temp:
            work = Path(temp)
            binary, cert, key = work / 'probe', work / 'cert.pem', work / 'key.pem'
            subprocess.run(['g++', '-std=c++17', '-Wall', '-Wextra', '-Werror', '-O2',
                            str(ROOT / 'tests/standalone_core_probe/tls_handshake.cpp'),
                            '-ldl', '-o', str(binary)], check=True)
            subprocess.run(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes',
                            '-keyout', str(key), '-out', str(cert), '-days', '1',
                            '-subj', '/CN=localhost', '-addext', 'subjectAltName=DNS:localhost'],
                           check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            ssl = subprocess.check_output(['g++', '-print-file-name=libssl.so'], text=True).strip()
            libdir = Path(ssl).resolve().parent
            self.assertTrue((libdir / 'libssl.so').exists())
            self.assertTrue((libdir / 'libcrypto.so').exists())
            def invoke(host, ca=cert):
                return subprocess.run([str(binary), str(libdir), str(ca), str(key), host],
                                      text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=30)
            passed = invoke('localhost')
            self.assertEqual(passed.returncode, 0, passed.stdout)
            self.assertIn('TLS_VERIFIED_HANDSHAKE_OK protocol=TLSv1.', passed.stdout)
            self.assertIn('encrypted_data=ok', passed.stdout)
            self.assertIn('CRYPTO_ORIGIN=' + str(libdir / 'libcrypto.so'), passed.stdout)
            refused = invoke('wrong.invalid')
            self.assertNotEqual(refused.returncode, 0, refused.stdout)
            self.assertIn('TLS_HANDSHAKE_FAIL side=client', refused.stdout)
            self.assertNotIn('TLS_VERIFIED_HANDSHAKE_OK', refused.stdout)
            no_ca = invoke('localhost', work / 'missing-cert.pem')
            self.assertNotEqual(no_ca.returncode, 0, no_ca.stdout)
            self.assertIn('TLS_CERTIFICATE_SETUP_FAIL', no_ca.stdout)


if __name__ == '__main__':
    unittest.main()

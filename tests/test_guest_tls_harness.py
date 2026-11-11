"""Host test of local TLS fixture only, never evidence of a guest SSL pass."""
from pathlib import Path
import socket
import ssl
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]

class GuestTlsFixtureTest(unittest.TestCase):
    def test_local_server_trusted_name_wrong_name_and_untrusted(self):
        with tempfile.TemporaryDirectory() as d:
            cert, key = Path(d) / 'cert.pem', Path(d) / 'key.pem'
            subprocess.run(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes',
                            '-keyout', str(key), '-out', str(cert), '-days', '1',
                            '-subj', '/CN=localhost', '-addext', 'subjectAltName=DNS:localhost',
                            '-addext', 'basicConstraints=critical,CA:TRUE',
                            '-addext', 'keyUsage=critical,keyCertSign,cRLSign,digitalSignature,keyEncipherment'],
                           check=True, capture_output=True)
            # Server can bind an arbitrary port for host tests. Port 0 means
            # select one safely; the readiness line tells us which one.
            server = subprocess.Popen([sys.executable, str(ROOT / 'tests/guest_tls_probe/server.py'),
                                       str(cert), str(key), '0', '3'], stdout=subprocess.PIPE,
                                      stderr=subprocess.STDOUT, text=True)
            try:
                ready = server.stdout.readline().strip()
                self.assertTrue(ready.startswith('LOCAL_TLS_SERVER_READY port='), ready)
                port = int(ready.split('=', 1)[1])
                trusted = ssl.create_default_context(cafile=str(cert))
                untrusted = ssl.create_default_context()
                with socket.create_connection(('127.0.0.1', port), timeout=10) as raw:
                    with trusted.wrap_socket(raw, server_hostname='localhost') as tls:
                        tls.sendall(b'guest-ping')
                        self.assertEqual(tls.recv(32), b'guest-pong')
                for context, host in ((trusted, 'wrong.invalid'), (untrusted, 'localhost')):
                    with self.assertRaises(ssl.SSLCertVerificationError):
                        with socket.create_connection(('127.0.0.1', port), timeout=10) as raw:
                            context.wrap_socket(raw, server_hostname=host)
                output, _ = server.communicate(timeout=15)
                self.assertEqual(server.returncode, 0, output)
                self.assertIn('LOCAL_TLS_EXCHANGE_OK 0', output)
                for index in (1, 2):
                    self.assertIn(f'LOCAL_TLS_HANDSHAKE_OR_IO_FAILED {index} SSLError', output)
                    self.assertRegex(output, rf'LOCAL_TLS_HANDSHAKE_OR_IO_FAILED {index} SSLError .*ALERT_(BAD_CERTIFICATE|CERTIFICATE_UNKNOWN|UNKNOWN_CA)')
            finally:
                if server.poll() is None:
                    server.kill(); server.communicate()

    def test_signed_server_chain_date_and_purpose_fixture(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            cert, key = base / 'root.pem', base / 'root-key.pem'
            pki = base / 'pki'
            subprocess.run(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes',
                            '-keyout', str(key), '-out', str(cert), '-days', '1',
                            '-subj', '/CN=localhost', '-addext', 'subjectAltName=DNS:localhost',
                            '-addext', 'basicConstraints=critical,CA:TRUE',
                            '-addext', 'keyUsage=critical,keyCertSign,cRLSign,digitalSignature,keyEncipherment'],
                           check=True, capture_output=True)
            subprocess.run(['bash', str(ROOT / 'tests/guest_tls_probe/generate_chain_fixture.sh'),
                            str(pki), str(cert), str(key)], check=True, capture_output=True)
            server = subprocess.Popen([sys.executable, str(ROOT / 'tests/guest_tls_probe/server.py'),
                                       str(cert), str(key), '0', '3', str(pki), '7'],
                                      stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
            try:
                ready = server.stdout.readline().strip()
                self.assertTrue(ready.startswith('LOCAL_TLS_SERVER_READY port='), ready)
                port = int(ready.split('=', 1)[1])
                context = ssl.create_default_context(cafile=str(cert))
                for index, expected in ((7, None), (8, 10), (9, 26)):
                    with socket.create_connection(('127.0.0.1', port), timeout=10) as raw:
                        try:
                            with context.wrap_socket(raw, server_hostname='localhost') as tls:
                                self.assertIsNone(expected, f'case {index} unexpectedly verified')
                                tls.sendall(b'guest-ping')
                                self.assertEqual(tls.recv(32), b'guest-pong')
                        except ssl.SSLCertVerificationError as exc:
                            self.assertEqual(exc.verify_code, expected, f'case {index}: {exc}')
                output, _ = server.communicate(timeout=15)
                self.assertEqual(server.returncode, 0, output)
                for index, profile in ((7, 'server'), (8, 'expired'), (9, 'client')):
                    self.assertIn(f'LOCAL_TLS_CERT_PROFILE {index} {profile}', output)
                self.assertIn('LOCAL_TLS_EXCHANGE_OK 7', output)
                for index in (8, 9):
                    self.assertNotIn(f'LOCAL_TLS_EXCHANGE_OK {index}', output)
            finally:
                if server.poll() is None:
                    server.kill(); server.communicate()

if __name__ == '__main__': unittest.main()

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
                            '-subj', '/CN=localhost', '-addext', 'subjectAltName=DNS:localhost'],
                           check=True, capture_output=True)
            # Server can bind an arbitrary port for host tests. Port 0 means
            # select one safely; the readiness line tells us which one.
            server = subprocess.Popen([sys.executable, str(ROOT / 'tests/guest_tls_probe/server.py'),
                                       str(cert), str(key), '0'], stdout=subprocess.PIPE,
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

if __name__ == '__main__': unittest.main()

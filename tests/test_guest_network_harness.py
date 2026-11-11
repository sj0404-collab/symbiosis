"""Host fixture/evidence tests only; they do not exercise an emulator core."""
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
CHECKER = ROOT / 'tests/player_runtime_probe/check_guest_network_evidence.py'

class GuestNetworkHarnessTest(unittest.TestCase):
    def test_loopback_server_real_exchange(self):
        server = subprocess.Popen([sys.executable, str(ROOT / 'tests/guest_network_probe/server.py'), '0'],
                                  stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        try:
            ready = server.stdout.readline().strip()
            self.assertTrue(ready.startswith('LOCAL_NET_READY port='), ready)
            port = int(ready.split('=', 1)[1])
            with socket.create_connection(('127.0.0.1', port), timeout=5) as sock:
                sock.sendall(b'net-ping')
                self.assertEqual(sock.recv(8), b'net-pong')
            out, _ = server.communicate(timeout=10)
            self.assertEqual(server.returncode, 0, out)
            self.assertIn('LOCAL_NET_EXCHANGE_OK', out)
        finally:
            if server.poll() is None: server.kill(); server.communicate()

    def test_evidence_requires_order_pid_mode_and_server(self):
        with tempfile.TemporaryDirectory() as tmp:
            log, server = Path(tmp) / 'log', Path(tmp) / 'server'
            def write(lines): log.write_text(''.join(f'11-11 12:00:00.000  123  456 I Test: {x}\n' for x in lines))
            def run(mode='bound', pid='123', internet='true'):
                return subprocess.run([sys.executable, str(CHECKER), str(log), pid, mode, internet, str(server)], capture_output=True)
            lines = ['CI_ONLY_RESOLVED_INTERNET value=true', 'GUEST_NET_BEGIN bind=1', 'GUEST_NET_SOCKET_INIT rc=0x00000000', 'GUEST_NET_LOOPBACK_BOUND',
                     'GUEST_NET_CONNECT_BEGIN bind=1', 'GUEST_NET_CONNECTED bind=1', 'GUEST_NET_EXCHANGE_OK bind=1']
            server.write_text('LOCAL_NET_READY port=19444\nLOCAL_NET_EXCHANGE_OK\n')
            write(lines); self.assertEqual(run().returncode, 0)
            self.assertNotEqual(run(pid='1234').returncode, 0)
            self.assertNotEqual(run(internet='false').returncode, 0)
            self.assertNotEqual(run(mode='auto').returncode, 0)
            write(lines[:-1]); self.assertNotEqual(run().returncode, 0)
            write(lines + ['GUEST_NET_ERROR stage=recv']); self.assertNotEqual(run().returncode, 0)
            write(lines); server.write_text('LOCAL_NET_READY port=19444\n'); self.assertNotEqual(run().returncode, 0)
            server.write_text('LOCAL_NET_EXCHANGE_OK\n')
            auto = ['CI_ONLY_RESOLVED_INTERNET value=true', 'GUEST_NET_BEGIN bind=0', 'GUEST_NET_SOCKET_INIT rc=0x00000000',
                    'GUEST_NET_CONNECT_BEGIN bind=0', 'GUEST_NET_CONNECTED bind=0', 'GUEST_NET_EXCHANGE_OK bind=0']
            write(auto); self.assertEqual(run(mode='auto').returncode, 0)
            write(auto + ['GUEST_NET_LOOPBACK_BOUND']); self.assertNotEqual(run(mode='auto').returncode, 0)

if __name__ == '__main__': unittest.main()

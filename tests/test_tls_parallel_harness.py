"""Fixture and checker tests only; no emulation claims."""
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import socket
import ssl
import subprocess
import sys
import tempfile
import threading
import unittest
ROOT = Path(__file__).resolve().parents[1]
CHECKER = ROOT / 'tests/player_runtime_probe/check_tls_parallel_evidence.py'
SERVER = ROOT / 'tests/guest_tls_parallel_probe/server.py'

class ParallelFixture(unittest.TestCase):
    def test_server_requires_two_connections_and_accepts_only_trusted_exchange(self):
        with tempfile.TemporaryDirectory() as d:
            cert, key = Path(d)/'cert.pem', Path(d)/'key.pem'
            subprocess.run(['openssl','req','-x509','-newkey','rsa:2048','-nodes','-keyout',str(key),'-out',str(cert),'-days','1','-subj','/CN=localhost','-addext','subjectAltName=DNS:localhost','-addext','basicConstraints=critical,CA:TRUE'], check=True, capture_output=True)
            proc = subprocess.Popen([sys.executable,str(SERVER),str(cert),str(key),'0'],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
            try:
                line=proc.stdout.readline(); self.assertIn('PARALLEL_SERVER_READY port=',line); port=int(line.split('=')[1]); barrier=threading.Barrier(2)
                def client(good):
                    ctx=ssl.create_default_context(cafile=str(cert)) if good else ssl.create_default_context()
                    with socket.create_connection(('127.0.0.1',port),timeout=10) as raw:
                        barrier.wait(timeout=10)
                        try:
                            with ctx.wrap_socket(raw,server_hostname='localhost') as tls:
                                if not good: return False
                                tls.sendall(b'guest-ping'); return tls.recv(16)==b'guest-pong'
                        except ssl.SSLCertVerificationError: return not good
                with ThreadPoolExecutor(max_workers=2) as pool:
                    self.assertEqual(list(pool.map(client, [True,False])),[True,True])
                out,_=proc.communicate(timeout=20); self.assertEqual(proc.returncode,0,out)
                self.assertIn('PARALLEL_SERVER_BOTH_ACCEPTED',out); self.assertIn('PARALLEL_SERVER_DONE exchanges=1',out)
            finally:
                if proc.poll() is None: proc.kill(); proc.communicate()
    def test_checker_rejects_wrong_context_and_no_barrier(self):
        def row(tid, text): return f'11-12 08:00:00.000  123  {tid} I RyujinxLog: {text}\n'
        lines=[row(100,'TLS_PARALLEL_TEST_BEGIN'),row(101,'TLS_PARALLEL_READY role=trusted'),row(102,'TLS_PARALLEL_READY role=wrong_ca'),row(101,'TLS_PARALLEL_BEGIN role=trusted'),row(102,'TLS_PARALLEL_BEGIN role=wrong_ca'),row(101,'CI_ONLY_TLS_CUSTOM_CHAIN valid=True statuses='),row(102,'CI_ONLY_TLS_CUSTOM_CHAIN valid=False statuses=PartialChain'),row(101,'TLS_PARALLEL_EXCHANGE role=trusted'),row(102,'TLS_PARALLEL_REJECTED role=wrong_ca rc=0x123 rd=0x123 wr=0x123'),row(100,'TLS_PARALLEL_COMPLETE')]
        server='PARALLEL_SERVER_BOTH_ACCEPTED\nPARALLEL_SERVER_EXCHANGE index=0\nPARALLEL_SERVER_DONE exchanges=1\n'
        with tempfile.TemporaryDirectory() as d:
            p,s=Path(d)/'log',Path(d)/'server'; s.write_text(server)
            def check(lines):
                p.write_text(''.join(lines)); return subprocess.run([sys.executable,str(CHECKER),str(p),'123',str(s)],capture_output=True,text=True).returncode
            self.assertEqual(check(lines),0)
            self.assertNotEqual(check([x.replace('valid=False','valid=True') for x in lines]),0)
            bad=lines.copy(); bad[4],bad[7]=bad[7],bad[4]; self.assertNotEqual(check(bad),0)
            self.assertNotEqual(check([x.replace('  102 ', '  101 ') for x in lines]),0)
            s.write_text(server.replace('exchanges=1','exchanges=2')); self.assertNotEqual(check(lines),0)
if __name__ == '__main__': unittest.main()

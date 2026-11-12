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
        good, wrong = 'a'*64, 'b'*64
        def row(text): return f'11-12 08:00:00.000  123  100 I RyujinxLog: {text}\n'
        lines=[row('HLE.GuestThread.28 TLS_PARALLEL_TEST_BEGIN'),
               row(f'CI_TLS_STORE_IMPORT store=1 sha256={good}'), row(f'CI_TLS_STORE_IMPORT store=2 sha256={wrong}'),
               row('HLE.GuestThread.30 TLS_PARALLEL_READY role=trusted'), row('HLE.GuestThread.31 TLS_PARALLEL_READY role=wrong_ca'),
               row('HLE.GuestThread.30 TLS_PARALLEL_BEGIN role=trusted'), row('HLE.GuestThread.31 TLS_PARALLEL_BEGIN role=wrong_ca'),
               row('CI_TLS_STORE_VALIDATION store=1 valid=True'), row('CI_TLS_STORE_VALIDATION store=2 valid=False'),
               row('HLE.GuestThread.30 TLS_PARALLEL_EXCHANGE role=trusted'),
               row('HLE.GuestThread.31 TLS_PARALLEL_REJECTED role=wrong_ca rc=0x123 rd=0x123 wr=0x123'),
               row('HLE.GuestThread.28 TLS_PARALLEL_COMPLETE')]
        server='PARALLEL_SERVER_BOTH_ACCEPTED\nPARALLEL_SERVER_EXCHANGE index=0\nPARALLEL_SERVER_DONE exchanges=1\n'
        with tempfile.TemporaryDirectory() as d:
            p,s=Path(d)/'log',Path(d)/'server'; s.write_text(server)
            def check(lines, fingerprints=(good,wrong)):
                p.write_text(''.join(lines)); return subprocess.run([sys.executable,str(CHECKER),str(p),'123',str(s),*fingerprints],capture_output=True,text=True).returncode
            self.assertEqual(check(lines),0)
            self.assertNotEqual(check([x.replace('valid=False','valid=True') for x in lines]),0)
            bad=lines.copy(); bad[6],bad[9]=bad[9],bad[6]; self.assertNotEqual(check(bad),0)
            self.assertNotEqual(check([x.replace('GuestThread.31', 'GuestThread.30') for x in lines]),0)
            self.assertNotEqual(check(lines,(wrong,good)),0)
            self.assertNotEqual(check(lines,(good,good)),0)
            s.write_text(server.replace('exchanges=1','exchanges=2')); self.assertNotEqual(check(lines),0)

    def test_instrumentation_is_research_only_and_checks_input(self):
        script = ROOT/'tests/player_runtime_probe/instrument_tls_parallel_store.py'
        with tempfile.TemporaryDirectory() as d:
            p = Path(d)/'ServerTrustStore.cs'
            p.write_text('        private readonly object _sync = new();\n                    _imports.Add(id, certificates);\n                bool valid = chain.Build(leaf);\n')
            subprocess.run([sys.executable,str(script),str(p)],check=True)
            instrumented=p.read_text()
            self.assertIn('CI_TLS_STORE_IMPORT',instrumented); self.assertIn('CI_TLS_STORE_VALIDATION',instrumented)
            self.assertNotEqual(subprocess.run([sys.executable,str(script),str(p)],capture_output=True).returncode,0)
if __name__ == '__main__': unittest.main()

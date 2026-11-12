"""Synthetic tests of the CI evidence checker; not tests of an emulator core."""
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

CHECKER = Path(__file__).parent / 'player_runtime_probe/check_guest_tls_evidence.py'
MARKERS = [
 'GUEST_TLS_INVALID_CERT_REJECTED', 'GUEST_TLS_CLIENT_PKI_REJECTED', 'GUEST_TLS_LEAF_AS_CA_REJECTED',
 'GUEST_TLS_UNSUPPORTED_OPTIONS_REJECTED', 'GUEST_TLS_VERIFY_DISABLE_REJECTED', 'CI_ONLY_TLS_POLICY imported_groups=0 errors=RemoteCertificateChainErrors',
 'GUEST_TLS_NEGATIVE_HANDSHAKE_FAILED case=no_import', 'GUEST_TLS_FAILED_HANDSHAKE_IO_REJECTED case=no_import', 'GUEST_TLS_VERIFY_DETAILS_UNSUPPORTED case=no_import', 'GUEST_TLS_STAGE import_wrong_ca rc=0x00000000',
 'GUEST_TLS_UNSUPPORTED_OPTIONS_REJECTED', 'GUEST_TLS_VERIFY_DISABLE_REJECTED', 'CI_ONLY_TLS_POLICY imported_groups=1 errors=RemoteCertificateChainErrors',
 'GUEST_TLS_NEGATIVE_HANDSHAKE_FAILED case=wrong_ca', 'GUEST_TLS_FAILED_HANDSHAKE_IO_REJECTED case=wrong_ca', 'GUEST_TLS_VERIFY_DETAILS_UNSUPPORTED case=wrong_ca', 'GUEST_TLS_STAGE remove_wrong_ca rc=0x00000000',
 'GUEST_TLS_CA_IMPORT_RETURNED', 'GUEST_TLS_UNSUPPORTED_OPTIONS_REJECTED', 'GUEST_TLS_VERIFY_DISABLE_REJECTED',
 'CI_ONLY_TLS_POLICY imported_groups=1 errors=RemoteCertificateChainErrors', 'GUEST_TLS_VERIFIED_EXCHANGE_OK',
 'GUEST_TLS_UNSUPPORTED_OPTIONS_REJECTED', 'GUEST_TLS_VERIFY_DISABLE_REJECTED', 'CI_ONLY_TLS_POLICY imported_groups=0 errors=RemoteCertificateChainErrors',
 'GUEST_TLS_NEGATIVE_HANDSHAKE_FAILED case=other_context', 'GUEST_TLS_FAILED_HANDSHAKE_IO_REJECTED case=other_context', 'GUEST_TLS_VERIFY_DETAILS_UNSUPPORTED case=other_context', 'GUEST_TLS_UNSUPPORTED_OPTIONS_REJECTED', 'GUEST_TLS_VERIFY_DISABLE_REJECTED',
 'CI_ONLY_TLS_POLICY imported_groups=1 errors=RemoteCertificateNameMismatch, RemoteCertificateChainErrors',
 'GUEST_TLS_NEGATIVE_HANDSHAKE_FAILED case=wrong_host', 'GUEST_TLS_FAILED_HANDSHAKE_IO_REJECTED case=wrong_host', 'GUEST_TLS_VERIFY_DETAILS_UNSUPPORTED case=wrong_host', 'GUEST_TLS_STAGE remove_test_ca rc=0x00000000',
 'GUEST_TLS_REMOVED_ID_REJECTED', 'GUEST_TLS_REMOVE_FALLBACK_REJECTED', 'GUEST_TLS_UNSUPPORTED_OPTIONS_REJECTED', 'GUEST_TLS_VERIFY_DISABLE_REJECTED',
 'CI_ONLY_TLS_POLICY imported_groups=0 errors=RemoteCertificateChainErrors',
 'GUEST_TLS_NEGATIVE_HANDSHAKE_FAILED case=untrusted_after_remove', 'GUEST_TLS_FAILED_HANDSHAKE_IO_REJECTED case=untrusted_after_remove', 'GUEST_TLS_VERIFY_DETAILS_UNSUPPORTED case=untrusted_after_remove', 'GUEST_TLS_DER_CA_IMPORT_RETURNED',
 'GUEST_TLS_UNSUPPORTED_OPTIONS_REJECTED', 'GUEST_TLS_VERIFY_DISABLE_REJECTED', 'CI_ONLY_TLS_POLICY imported_groups=1 errors=RemoteCertificateChainErrors',
 'CI_ONLY_TLS_CUSTOM_CHAIN valid=True statuses=', 'GUEST_TLS_VERIFIED_EXCHANGE_OK',
 'GUEST_TLS_CHAIN_CASE valid_server', 'GUEST_TLS_UNSUPPORTED_OPTIONS_REJECTED', 'GUEST_TLS_VERIFY_DISABLE_REJECTED',
 'CI_ONLY_TLS_POLICY imported_groups=1 errors=RemoteCertificateChainErrors',
 'CI_ONLY_TLS_CUSTOM_CHAIN valid=True statuses=', 'GUEST_TLS_VERIFIED_EXCHANGE_OK',
 'GUEST_TLS_CHAIN_CASE expired_server', 'GUEST_TLS_UNSUPPORTED_OPTIONS_REJECTED', 'GUEST_TLS_VERIFY_DISABLE_REJECTED',
 'CI_ONLY_TLS_POLICY imported_groups=1 errors=RemoteCertificateChainErrors',
 'CI_ONLY_TLS_CUSTOM_CHAIN valid=False statuses=NotTimeValid',
 'GUEST_TLS_NEGATIVE_HANDSHAKE_FAILED case=expired_server', 'GUEST_TLS_FAILED_HANDSHAKE_IO_REJECTED case=expired_server', 'GUEST_TLS_VERIFY_DETAILS_UNSUPPORTED case=expired_server',
 'GUEST_TLS_CHAIN_CASE client_eku_only', 'GUEST_TLS_UNSUPPORTED_OPTIONS_REJECTED', 'GUEST_TLS_VERIFY_DISABLE_REJECTED',
 'CI_ONLY_TLS_POLICY imported_groups=1 errors=RemoteCertificateChainErrors',
 'CI_ONLY_TLS_CUSTOM_CHAIN valid=False statuses=NotValidForUsage',
 'GUEST_TLS_NEGATIVE_HANDSHAKE_FAILED case=client_eku_only', 'GUEST_TLS_FAILED_HANDSHAKE_IO_REJECTED case=client_eku_only', 'GUEST_TLS_VERIFY_DETAILS_UNSUPPORTED case=client_eku_only',
 'GUEST_TLS_CHAIN_CASE intermediate_chain', 'GUEST_TLS_UNSUPPORTED_OPTIONS_REJECTED', 'GUEST_TLS_VERIFY_DISABLE_REJECTED',
 'CI_ONLY_TLS_POLICY imported_groups=1 errors=RemoteCertificateChainErrors', 'CI_ONLY_TLS_CUSTOM_CHAIN valid=True statuses=', 'GUEST_TLS_VERIFIED_EXCHANGE_OK',
 'GUEST_TLS_CHAIN_CASE missing_intermediate', 'GUEST_TLS_UNSUPPORTED_OPTIONS_REJECTED', 'GUEST_TLS_VERIFY_DISABLE_REJECTED',
 'CI_ONLY_TLS_POLICY imported_groups=1 errors=RemoteCertificateChainErrors', 'CI_ONLY_TLS_CUSTOM_CHAIN valid=False statuses=PartialChain',
 'GUEST_TLS_NEGATIVE_HANDSHAKE_FAILED case=missing_intermediate', 'GUEST_TLS_FAILED_HANDSHAKE_IO_REJECTED case=missing_intermediate',
 'GUEST_TLS_VERIFY_DETAILS_UNSUPPORTED case=missing_intermediate',
 'GUEST_TLS_CHAIN_CASE not_yet_valid', 'GUEST_TLS_UNSUPPORTED_OPTIONS_REJECTED', 'GUEST_TLS_VERIFY_DISABLE_REJECTED',
 'CI_ONLY_TLS_POLICY imported_groups=1 errors=RemoteCertificateChainErrors', 'CI_ONLY_TLS_CUSTOM_CHAIN valid=False statuses=NotTimeValid',
 'GUEST_TLS_NEGATIVE_HANDSHAKE_FAILED case=not_yet_valid', 'GUEST_TLS_FAILED_HANDSHAKE_IO_REJECTED case=not_yet_valid',
 'GUEST_TLS_VERIFY_DETAILS_UNSUPPORTED case=not_yet_valid', 'GUEST_TLS_COMPLETE',
]
SERVER = ''.join(f'LOCAL_TLS_HANDSHAKE_OK {n} TLSv1.3\n' for n in range(13)) + 'LOCAL_TLS_EXCHANGE_OK 2\nLOCAL_TLS_EXCHANGE_OK 6\nLOCAL_TLS_EXCHANGE_OK 7\nLOCAL_TLS_EXCHANGE_OK 10\nLOCAL_TLS_CERT_PROFILE 7 server\nLOCAL_TLS_CERT_PROFILE 8 expired\nLOCAL_TLS_CERT_PROFILE 9 client\nLOCAL_TLS_CERT_PROFILE 10 intermediate_chain\nLOCAL_TLS_CERT_PROFILE 11 missing_intermediate\nLOCAL_TLS_CERT_PROFILE 12 future\n'


class EvidenceTest(unittest.TestCase):
 def check(self, markers=MARKERS, server=SERVER, pid='123', mode='bound', connection_markers=None, count='none'):
  with tempfile.TemporaryDirectory() as d:
   log=Path(d)/'log'; srv=Path(d)/'server'
   if connection_markers is None:
    connection_markers = [y for _ in range(13) for y in ((['GUEST_TLS_LOOPBACK_BOUND'] if mode == 'bound' else []) + [f'GUEST_TLS_CONNECT_BEGIN mode={mode}', 'GUEST_TLS_SOCKET_CONNECTED host=localhost'])]
   fixture = [f'GUEST_TLS_MODE mode={mode}'] + connection_markers + list(markers)
   log.write_text(''.join('11-11 05:53:08.000  123  456 I RyujinxLog: '+x+'\n' for x in fixture))
   srv.write_text(server)
   return subprocess.run([sys.executable,str(CHECKER),str(log),pid,str(srv),mode,count],capture_output=True,text=True)
 def test_success(self):
  self.assertEqual(self.check().returncode,0)
  self.assertEqual(self.check(mode='auto').returncode,0)
 def test_connection_count_evidence_required_and_ordered(self):
  phases = [('initial','A',0),('initial','B',0),('one','A',1),('two','A',2),('other_created','B',1),('other_created','A',2),('one_closed','A',1),('one_closed','B',1),('other_closed','B',0),('other_closed','A',1),('all_closed','A',0),('all_closed','B',0)]
  count = [f'GUEST_SSL_COUNT phase={p} context={c} count={n} rc=0x00000000' for p,c,n in phases] + ['GUEST_SSL_COUNT_LIFECYCLE_OK']
  after = ['GUEST_SSL_COUNT phase=after_tls context=A count=0 rc=0x00000000','GUEST_SSL_COUNT phase=after_tls context=B count=0 rc=0x00000000','GUEST_SSL_COUNT_AFTER_TLS_OK']
  sample = count + MARKERS[:-1] + after + MARKERS[-1:]
  self.assertEqual(self.check(sample,count='count').returncode,0)
  self.assertNotEqual(self.check(MARKERS,count='count').returncode,0)
  self.assertNotEqual(self.check(sample,count='none').returncode,0)
  self.assertNotEqual(self.check([m.replace('phase=one_closed context=A count=1','phase=one_closed context=A count=2') for m in sample],count='count').returncode,0)
  self.assertNotEqual(self.check(sample[:-2],count='count').returncode,0)
  self.assertNotEqual(self.check(MARKERS[:-1]+count+after+MARKERS[-1:],count='count').returncode,0)
 def test_bind_mode_and_order_fail_closed(self):
  auto = [y for _ in range(13) for y in ['GUEST_TLS_CONNECT_BEGIN mode=auto','GUEST_TLS_SOCKET_CONNECTED host=localhost']]
  self.assertNotEqual(self.check(mode='auto', connection_markers=auto+['GUEST_TLS_LOOPBACK_BOUND']).returncode,0)
  self.assertNotEqual(self.check(mode='auto', connection_markers=auto[:-1]).returncode,0)
  self.assertNotEqual(self.check(mode='auto', connection_markers=list(reversed(auto))).returncode,0)
  self.assertNotEqual(self.check(mode='bound', connection_markers=auto).returncode,0)
 def test_missing_policy_not_accepted(self): self.assertNotEqual(self.check([m for m in MARKERS if 'NameMismatch' not in m]).returncode,0)
 def test_missing_expiry_or_purpose_not_accepted(self):
  for status in ('NotTimeValid', 'NotValidForUsage', 'PartialChain'):
   self.assertNotEqual(self.check([m.replace(status, 'OtherError') for m in MARKERS]).returncode, 0)
  self.assertNotEqual(self.check(server=SERVER.replace('LOCAL_TLS_CERT_PROFILE 9 client', 'LOCAL_TLS_CERT_PROFILE 9 server')).returncode, 0)
 def test_wrong_order_not_accepted(self): self.assertNotEqual(self.check(MARKERS[:5]+list(reversed(MARKERS[5:]))).returncode,0)
 def test_wrong_pid_not_accepted(self): self.assertNotEqual(self.check(pid='1234').returncode,0)
 def test_server_missing_and_guest_error_rejected(self):
  self.assertNotEqual(self.check(server='LOCAL_TLS_EXCHANGE_OK 2\n').returncode,0)
  self.assertNotEqual(self.check(MARKERS+['GUEST_TLS_ERROR stage=anything']).returncode,0)
  self.assertNotEqual(self.check(server=SERVER+'LOCAL_TLS_EXCHANGE_OK 3\n').returncode,0)
if __name__ == '__main__': unittest.main()

#!/usr/bin/env python3
"""Strict CI-only evidence for the research SSL candidate, not a release gate.

The server can finish its side of TLS 1.3 before the client rejects its cert;
require actual client validation-policy observations and guest IPC results in
order, plus seven local server connections and encrypted payload exchanges.
"""
from pathlib import Path
import re
import sys

if len(sys.argv) != 4 or not sys.argv[2].isdigit():
    raise SystemExit('usage: check_guest_tls_evidence.py logcat PID server-log')
log = Path(sys.argv[1]).read_text(errors='replace')
pid = sys.argv[2]
server = Path(sys.argv[3]).read_text(errors='replace')
line = re.compile(rf'^[0-9-]+ +[0-9:.]+ +{pid} +[0-9]+ .*$', re.M)
rows = line.findall(log)
if not rows:
    raise SystemExit('GUEST_TLS_EVIDENCE_NO_PID_LOG')
markers = [
    r'GUEST_TLS_INVALID_CERT_REJECTED',
    r'GUEST_TLS_LEAF_AS_CA_REJECTED',
    r'GUEST_TLS_VERIFY_DISABLE_REJECTED',
    r'CI_ONLY_TLS_POLICY imported_groups=0 errors=.*RemoteCertificateChainErrors',
    r'GUEST_TLS_NEGATIVE_HANDSHAKE_FAILED case=no_import',
    r'GUEST_TLS_STAGE import_wrong_ca rc=0x00000000',
    r'GUEST_TLS_VERIFY_DISABLE_REJECTED',
    r'CI_ONLY_TLS_POLICY imported_groups=1 errors=.*RemoteCertificateChainErrors',
    r'GUEST_TLS_NEGATIVE_HANDSHAKE_FAILED case=wrong_ca',
    r'GUEST_TLS_STAGE remove_wrong_ca rc=0x00000000',
    r'GUEST_TLS_CA_IMPORT_RETURNED',
    r'GUEST_TLS_VERIFY_DISABLE_REJECTED',
    r'CI_ONLY_TLS_POLICY imported_groups=1 errors=.*RemoteCertificateChainErrors',
    r'GUEST_TLS_VERIFIED_EXCHANGE_OK',
    r'GUEST_TLS_VERIFY_DISABLE_REJECTED',
    r'CI_ONLY_TLS_POLICY imported_groups=0 errors=.*RemoteCertificateChainErrors',
    r'GUEST_TLS_NEGATIVE_HANDSHAKE_FAILED case=other_context',
    r'GUEST_TLS_VERIFY_DISABLE_REJECTED',
    r'CI_ONLY_TLS_POLICY imported_groups=1 errors=.*RemoteCertificateNameMismatch',
    r'GUEST_TLS_NEGATIVE_HANDSHAKE_FAILED case=wrong_host',
    r'GUEST_TLS_STAGE remove_test_ca rc=0x00000000',
    r'GUEST_TLS_REMOVED_ID_REJECTED',
    r'GUEST_TLS_VERIFY_DISABLE_REJECTED',
    r'CI_ONLY_TLS_POLICY imported_groups=0 errors=.*RemoteCertificateChainErrors',
    r'GUEST_TLS_NEGATIVE_HANDSHAKE_FAILED case=untrusted_after_remove',
    r'GUEST_TLS_DER_CA_IMPORT_RETURNED',
    r'GUEST_TLS_VERIFY_DISABLE_REJECTED',
    r'CI_ONLY_TLS_POLICY imported_groups=1 errors=.*RemoteCertificateChainErrors',
    r'GUEST_TLS_VERIFIED_EXCHANGE_OK',
    r'GUEST_TLS_COMPLETE',
]
position = 0
for pattern in markers:
    for i in range(position, len(rows)):
        if re.search(pattern, rows[i]):
            position = i + 1
            break
    else:
        raise SystemExit('GUEST_TLS_EVIDENCE_MISSING_OR_OUT_OF_ORDER ' + pattern)
if any(re.search(r'GUEST_TLS_ERROR|Unhandled exception in guest thread|FATAL EXCEPTION|Fatal signal', row) for row in rows):
    raise SystemExit('GUEST_TLS_EVIDENCE_HAS_ERROR')
for n in (2, 6):
    if not re.search(rf'^LOCAL_TLS_EXCHANGE_OK {n}$', server, re.M):
        raise SystemExit('GUEST_TLS_EVIDENCE_SERVER_EXCHANGE_MISSING ' + str(n))
for n in (0, 1, 3, 4, 5):
    # In TLS 1.3 the server can complete before client verification. Require
    # the ordered client policy result separately; server marker is not proof.
    ok = re.search(rf'^LOCAL_TLS_HANDSHAKE_OK {n} TLSv', server, re.M)
    alert = re.search(rf'^LOCAL_TLS_HANDSHAKE_OR_IO_FAILED {n} SSLError .*ALERT_(BAD_CERTIFICATE|CERTIFICATE_UNKNOWN|UNKNOWN_CA)', server, re.M)
    if not (ok or alert):
        raise SystemExit('GUEST_TLS_EVIDENCE_SERVER_NEGATIVE_CONNECTION_MISSING ' + str(n))
    if re.search(rf'^LOCAL_TLS_EXCHANGE_OK {n}$', server, re.M):
        raise SystemExit('GUEST_TLS_EVIDENCE_UNEXPECTED_EXCHANGE ' + str(n))
print('GUEST_TLS_RESEARCH_EVIDENCE_OK ordered_client_policy_and_server_exchange')

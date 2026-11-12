#!/usr/bin/env python3
"""Strict CI-only evidence for the research SSL candidate, not a release gate.

The server can finish its side of TLS 1.3 before the client rejects its cert;
require actual client validation-policy observations and guest IPC results in
order, plus thirteen local server connections and encrypted payload exchanges.
"""
from pathlib import Path
import re
import sys

if len(sys.argv) != 6 or not sys.argv[2].isdigit() or sys.argv[4] not in ('auto', 'bound') or sys.argv[5] not in ('count', 'none'):
    raise SystemExit('usage: check_guest_tls_evidence.py logcat PID server-log {auto|bound} {count|none}')
log = Path(sys.argv[1]).read_text(errors='replace')
pid = sys.argv[2]
server = Path(sys.argv[3]).read_text(errors='replace')
line = re.compile(rf'^[0-9-]+ +[0-9:.]+ +{pid} +[0-9]+ .*$', re.M)
rows = line.findall(log)
if not rows:
    raise SystemExit('GUEST_TLS_EVIDENCE_NO_PID_LOG')
mode = sys.argv[4]
mode_rows = [row for row in rows if 'GUEST_TLS_MODE mode=' in row]
begins = [row for row in rows if 'GUEST_TLS_CONNECT_BEGIN mode=' in row]
connections = [row for row in rows if 'GUEST_TLS_SOCKET_CONNECTED host=' in row]
binds = [row for row in rows if 'GUEST_TLS_LOOPBACK_BOUND' in row]
if len(mode_rows) != 1 or f'GUEST_TLS_MODE mode={mode}' not in mode_rows[0]:
    raise SystemExit('GUEST_TLS_EVIDENCE_MODE_MISMATCH')
if len(begins) != 13 or len(connections) != 13 or any(f'GUEST_TLS_CONNECT_BEGIN mode={mode}' not in row for row in begins):
    raise SystemExit('GUEST_TLS_EVIDENCE_CONNECT_COUNT_OR_MODE_MISMATCH')
if len(binds) != (13 if mode == 'bound' else 0):
    raise SystemExit('GUEST_TLS_EVIDENCE_BIND_COUNT_MISMATCH')
# Require each marker from the same PID and ordered within its own connection.
stream = [row for row in rows if any(tag in row for tag in ('GUEST_TLS_LOOPBACK_BOUND', 'GUEST_TLS_CONNECT_BEGIN mode=', 'GUEST_TLS_SOCKET_CONNECTED host='))]
expected = ([('GUEST_TLS_LOOPBACK_BOUND',) if mode == 'bound' else (), ('GUEST_TLS_CONNECT_BEGIN mode=',), ('GUEST_TLS_SOCKET_CONNECTED host=',)])
sequence = [tag for group in expected for tag in group] * 13
if len(stream) != len(sequence) or any(tag not in row for tag, row in zip(sequence, stream)):
    raise SystemExit('GUEST_TLS_EVIDENCE_CONNECTION_ORDER_MISMATCH')
markers = [
    r'GUEST_TLS_INVALID_CERT_REJECTED',
    r'GUEST_TLS_CLIENT_PKI_REJECTED',
    r'GUEST_TLS_LEAF_AS_CA_REJECTED',
    r'GUEST_TLS_UNSUPPORTED_OPTIONS_REJECTED',
    r'GUEST_TLS_VERIFY_DISABLE_REJECTED',
    r'CI_ONLY_TLS_POLICY imported_groups=0 errors=.*RemoteCertificateChainErrors',
    r'GUEST_TLS_NEGATIVE_HANDSHAKE_FAILED case=no_import',
    r'GUEST_TLS_FAILED_HANDSHAKE_IO_REJECTED case=no_import',
    r'GUEST_TLS_VERIFY_DETAILS_UNSUPPORTED case=no_import',
    r'GUEST_TLS_STAGE import_wrong_ca rc=0x00000000',
    r'GUEST_TLS_UNSUPPORTED_OPTIONS_REJECTED',
    r'GUEST_TLS_VERIFY_DISABLE_REJECTED',
    r'CI_ONLY_TLS_POLICY imported_groups=1 errors=.*RemoteCertificateChainErrors',
    r'GUEST_TLS_NEGATIVE_HANDSHAKE_FAILED case=wrong_ca',
    r'GUEST_TLS_FAILED_HANDSHAKE_IO_REJECTED case=wrong_ca',
    r'GUEST_TLS_VERIFY_DETAILS_UNSUPPORTED case=wrong_ca',
    r'GUEST_TLS_STAGE remove_wrong_ca rc=0x00000000',
    r'GUEST_TLS_CA_IMPORT_RETURNED',
    r'GUEST_TLS_UNSUPPORTED_OPTIONS_REJECTED',
    r'GUEST_TLS_VERIFY_DISABLE_REJECTED',
    r'CI_ONLY_TLS_POLICY imported_groups=1 errors=.*RemoteCertificateChainErrors',
    r'GUEST_TLS_VERIFIED_EXCHANGE_OK',
    r'GUEST_TLS_UNSUPPORTED_OPTIONS_REJECTED',
    r'GUEST_TLS_VERIFY_DISABLE_REJECTED',
    r'CI_ONLY_TLS_POLICY imported_groups=0 errors=.*RemoteCertificateChainErrors',
    r'GUEST_TLS_NEGATIVE_HANDSHAKE_FAILED case=other_context',
    r'GUEST_TLS_FAILED_HANDSHAKE_IO_REJECTED case=other_context',
    r'GUEST_TLS_VERIFY_DETAILS_UNSUPPORTED case=other_context',
    r'GUEST_TLS_UNSUPPORTED_OPTIONS_REJECTED',
    r'GUEST_TLS_VERIFY_DISABLE_REJECTED',
    r'CI_ONLY_TLS_POLICY imported_groups=1 errors=.*RemoteCertificateNameMismatch',
    r'GUEST_TLS_NEGATIVE_HANDSHAKE_FAILED case=wrong_host',
    r'GUEST_TLS_FAILED_HANDSHAKE_IO_REJECTED case=wrong_host',
    r'GUEST_TLS_VERIFY_DETAILS_UNSUPPORTED case=wrong_host',
    r'GUEST_TLS_STAGE remove_test_ca rc=0x00000000',
    r'GUEST_TLS_REMOVED_ID_REJECTED',
    r'GUEST_TLS_REMOVE_FALLBACK_REJECTED',
    r'GUEST_TLS_UNSUPPORTED_OPTIONS_REJECTED',
    r'GUEST_TLS_VERIFY_DISABLE_REJECTED',
    r'CI_ONLY_TLS_POLICY imported_groups=0 errors=.*RemoteCertificateChainErrors',
    r'GUEST_TLS_NEGATIVE_HANDSHAKE_FAILED case=untrusted_after_remove',
    r'GUEST_TLS_FAILED_HANDSHAKE_IO_REJECTED case=untrusted_after_remove',
    r'GUEST_TLS_VERIFY_DETAILS_UNSUPPORTED case=untrusted_after_remove',
    r'GUEST_TLS_DER_CA_IMPORT_RETURNED',
    r'GUEST_TLS_UNSUPPORTED_OPTIONS_REJECTED',
    r'GUEST_TLS_VERIFY_DISABLE_REJECTED',
    r'CI_ONLY_TLS_POLICY imported_groups=1 errors=.*RemoteCertificateChainErrors',
    r'CI_ONLY_TLS_CUSTOM_CHAIN valid=True',
    r'GUEST_TLS_VERIFIED_EXCHANGE_OK',
    r'GUEST_TLS_CHAIN_CASE valid_server',
    r'GUEST_TLS_UNSUPPORTED_OPTIONS_REJECTED',
    r'GUEST_TLS_VERIFY_DISABLE_REJECTED',
    r'CI_ONLY_TLS_POLICY imported_groups=1 errors=.*RemoteCertificateChainErrors',
    r'CI_ONLY_TLS_CUSTOM_CHAIN valid=True',
    r'GUEST_TLS_VERIFIED_EXCHANGE_OK',
    r'GUEST_TLS_CHAIN_CASE expired_server',
    r'GUEST_TLS_UNSUPPORTED_OPTIONS_REJECTED',
    r'GUEST_TLS_VERIFY_DISABLE_REJECTED',
    r'CI_ONLY_TLS_POLICY imported_groups=1 errors=.*RemoteCertificateChainErrors',
    r'CI_ONLY_TLS_CUSTOM_CHAIN valid=False statuses=.*NotTimeValid',
    r'GUEST_TLS_NEGATIVE_HANDSHAKE_FAILED case=expired_server',
    r'GUEST_TLS_FAILED_HANDSHAKE_IO_REJECTED case=expired_server',
    r'GUEST_TLS_VERIFY_DETAILS_UNSUPPORTED case=expired_server',
    r'GUEST_TLS_CHAIN_CASE client_eku_only',
    r'GUEST_TLS_UNSUPPORTED_OPTIONS_REJECTED',
    r'GUEST_TLS_VERIFY_DISABLE_REJECTED',
    r'CI_ONLY_TLS_POLICY imported_groups=1 errors=.*RemoteCertificateChainErrors',
    r'CI_ONLY_TLS_CUSTOM_CHAIN valid=False statuses=.*NotValidForUsage',
    r'GUEST_TLS_NEGATIVE_HANDSHAKE_FAILED case=client_eku_only',
    r'GUEST_TLS_FAILED_HANDSHAKE_IO_REJECTED case=client_eku_only',
    r'GUEST_TLS_VERIFY_DETAILS_UNSUPPORTED case=client_eku_only',
    r'GUEST_TLS_CHAIN_CASE intermediate_chain',
    r'GUEST_TLS_UNSUPPORTED_OPTIONS_REJECTED',
    r'GUEST_TLS_VERIFY_DISABLE_REJECTED',
    r'CI_ONLY_TLS_POLICY imported_groups=1 errors=.*RemoteCertificateChainErrors',
    r'CI_ONLY_TLS_CUSTOM_CHAIN valid=True',
    r'GUEST_TLS_VERIFIED_EXCHANGE_OK',
    r'GUEST_TLS_CHAIN_CASE missing_intermediate',
    r'GUEST_TLS_UNSUPPORTED_OPTIONS_REJECTED',
    r'GUEST_TLS_VERIFY_DISABLE_REJECTED',
    r'CI_ONLY_TLS_POLICY imported_groups=1 errors=.*RemoteCertificateChainErrors',
    r'CI_ONLY_TLS_CUSTOM_CHAIN valid=False statuses=.*PartialChain',
    r'GUEST_TLS_NEGATIVE_HANDSHAKE_FAILED case=missing_intermediate',
    r'GUEST_TLS_FAILED_HANDSHAKE_IO_REJECTED case=missing_intermediate',
    r'GUEST_TLS_VERIFY_DETAILS_UNSUPPORTED case=missing_intermediate',
    r'GUEST_TLS_CHAIN_CASE not_yet_valid',
    r'GUEST_TLS_UNSUPPORTED_OPTIONS_REJECTED',
    r'GUEST_TLS_VERIFY_DISABLE_REJECTED',
    r'CI_ONLY_TLS_POLICY imported_groups=1 errors=.*RemoteCertificateChainErrors',
    r'CI_ONLY_TLS_CUSTOM_CHAIN valid=False statuses=.*NotTimeValid',
    r'GUEST_TLS_NEGATIVE_HANDSHAKE_FAILED case=not_yet_valid',
    r'GUEST_TLS_FAILED_HANDSHAKE_IO_REJECTED case=not_yet_valid',
    r'GUEST_TLS_VERIFY_DETAILS_UNSUPPORTED case=not_yet_valid',
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
count_rows = [row for row in rows if 'GUEST_SSL_COUNT' in row]
if sys.argv[5] == 'count':
    expected = [
        ('initial', 'A', 0), ('initial', 'B', 0), ('one', 'A', 1), ('two', 'A', 2),
        ('other_created', 'B', 1), ('other_created', 'A', 2), ('one_closed', 'A', 1), ('one_closed', 'B', 1),
        ('other_closed', 'B', 0), ('other_closed', 'A', 1), ('all_closed', 'A', 0), ('all_closed', 'B', 0),
    ]
    expected_rows = [f'GUEST_SSL_COUNT phase={phase} context={ctx} count={n} rc=0x00000000' for phase, ctx, n in expected]
    expected_rows += ['GUEST_SSL_COUNT_LIFECYCLE_OK', 'GUEST_SSL_COUNT phase=after_tls context=A count=0 rc=0x00000000',
                      'GUEST_SSL_COUNT phase=after_tls context=B count=0 rc=0x00000000', 'GUEST_SSL_COUNT_AFTER_TLS_OK']
    if len(count_rows) != len(expected_rows) or any(expected not in row for expected, row in zip(expected_rows, count_rows)):
        raise SystemExit('GUEST_SSL_COUNT_EVIDENCE_MISMATCH')
    first_tls = next((i for i, row in enumerate(rows) if 'GUEST_TLS_INVALID_CERT_REJECTED' in row), -1)
    final_tls = next((i for i, row in enumerate(rows) if 'GUEST_TLS_COMPLETE' in row), -1)
    life = next((i for i, row in enumerate(rows) if 'GUEST_SSL_COUNT_LIFECYCLE_OK' in row), -1)
    after = next((i for i, row in enumerate(rows) if 'GUEST_SSL_COUNT_AFTER_TLS_OK' in row), -1)
    if not 0 <= life < first_tls < after < final_tls:
        raise SystemExit('GUEST_SSL_COUNT_EVIDENCE_ORDER')
elif count_rows:
    raise SystemExit('GUEST_SSL_COUNT_UNEXPECTED_FIXTURE')
if any(re.search(r'GUEST_TLS_ERROR|Unhandled exception in guest thread|FATAL EXCEPTION|Fatal signal', row) for row in rows):
    raise SystemExit('GUEST_TLS_EVIDENCE_HAS_ERROR')
for n in (2, 6, 7, 10):
    if not re.search(rf'^LOCAL_TLS_EXCHANGE_OK {n}$', server, re.M):
        raise SystemExit('GUEST_TLS_EVIDENCE_SERVER_EXCHANGE_MISSING ' + str(n))
for n in (0, 1, 3, 4, 5, 8, 9, 11, 12):
    # In TLS 1.3 the server can complete before client verification. Require
    # the ordered client policy result separately; server marker is not proof.
    ok = re.search(rf'^LOCAL_TLS_HANDSHAKE_OK {n} TLSv', server, re.M)
    alert = re.search(rf'^LOCAL_TLS_HANDSHAKE_OR_IO_FAILED {n} SSLError .*ALERT_(BAD_CERTIFICATE|CERTIFICATE_UNKNOWN|UNKNOWN_CA|CERTIFICATE_EXPIRED|UNSUPPORTED_CERTIFICATE)', server, re.M)
    if not (ok or alert):
        raise SystemExit('GUEST_TLS_EVIDENCE_SERVER_NEGATIVE_CONNECTION_MISSING ' + str(n))
    if re.search(rf'^LOCAL_TLS_EXCHANGE_OK {n}$', server, re.M):
        raise SystemExit('GUEST_TLS_EVIDENCE_UNEXPECTED_EXCHANGE ' + str(n))
for n, profile in ((7, 'server'), (8, 'expired'), (9, 'client'), (10, 'intermediate_chain'), (11, 'missing_intermediate'), (12, 'future')):
    if not re.search(rf'^LOCAL_TLS_CERT_PROFILE {n} {profile}$', server, re.M):
        raise SystemExit('GUEST_TLS_EVIDENCE_SERVER_CERT_PROFILE_MISSING ' + str(n))
print('GUEST_TLS_RESEARCH_EVIDENCE_OK ordered_client_policy_and_server_exchange')

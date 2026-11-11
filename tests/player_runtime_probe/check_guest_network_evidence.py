#!/usr/bin/env python3
"""CI-only ordered BSD evidence, filtered by the disposable guest process PID."""
from pathlib import Path
import re
import sys

if len(sys.argv) != 6 or not sys.argv[2].isdigit() or sys.argv[3] not in ('auto', 'bound', 'invalid-auto') or sys.argv[4] not in ('true', 'false'):
    raise SystemExit('usage: check_guest_network_evidence.py logcat PID {auto|bound|invalid-auto} {true|false} server-log')
log = Path(sys.argv[1]).read_text(errors='replace')
pid, mode, internet = sys.argv[2:5]
server = Path(sys.argv[5]).read_text(errors='replace')
rows = re.findall(rf'^[0-9-]+ +[0-9:.]+ +{pid} +[0-9]+ .*$', log, re.M)
bound = int(mode == 'bound')
patterns = [rf'CI_ONLY_RESOLVED_INTERNET value={internet}', rf'GUEST_NET_BEGIN bind={bound}', r'GUEST_NET_SOCKET_INIT rc=0x00000000']
if bound: patterns.append(r'GUEST_NET_LOOPBACK_BOUND')
patterns.append(rf'GUEST_NET_CONNECT_BEGIN bind={bound}')
if mode == 'invalid-auto':
    patterns.extend((r'GUEST_NET_ERROR stage=connect errno=\d+ address_unavailable=1', r'GUEST_NET_DONE'))
else:
    patterns.extend((rf'GUEST_NET_CONNECTED bind={bound}', rf'GUEST_NET_EXCHANGE_OK bind={bound}'))
pos = 0
for pattern in patterns:
    for i in range(pos, len(rows)):
        if re.search(pattern, rows[i]):
            pos = i + 1
            break
    else: raise SystemExit('GUEST_NET_EVIDENCE_MISSING_OR_OUT_OF_ORDER ' + pattern)
if mode != 'bound' and any('GUEST_NET_LOOPBACK_BOUND' in row for row in rows):
    raise SystemExit('GUEST_NET_EVIDENCE_AUTO_WAS_BOUND')
if any(re.search(r'Unhandled exception in guest thread|Fatal signal|FATAL EXCEPTION', row) for row in rows):
    raise SystemExit('GUEST_NET_EVIDENCE_HAS_CRASH')
errors = [row for row in rows if 'GUEST_NET_ERROR' in row]
if mode == 'invalid-auto':
    if len(errors) != 1 or any('GUEST_NET_CONNECTED' in row or 'GUEST_NET_EXCHANGE_OK' in row for row in rows):
        raise SystemExit('GUEST_NET_EVIDENCE_INVALID_INTERFACE_UNEXPECTED_GUEST_RESULT')
    if not re.search(r'^LOCAL_NET_READY port=', server, re.M) or 'LOCAL_NET_ACCEPTED' in server or 'LOCAL_NET_EXCHANGE_OK' in server:
        raise SystemExit('GUEST_NET_EVIDENCE_INVALID_INTERFACE_SERVER_ACCEPTED')
    print('GUEST_NET_MISSING_INTERFACE_REJECTED pid_scoped_no_server_accept')
else:
    if errors: raise SystemExit('GUEST_NET_EVIDENCE_HAS_ERROR')
    if not re.search(r'^LOCAL_NET_EXCHANGE_OK$', server, re.M) or 'LOCAL_NET_SERVER_ERROR' in server:
        raise SystemExit('GUEST_NET_EVIDENCE_SERVER_MISMATCH')
    print('GUEST_NET_EVIDENCE_OK pid_scoped_guest_and_local_server')

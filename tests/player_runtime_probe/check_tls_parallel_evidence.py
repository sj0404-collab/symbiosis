#!/usr/bin/env python3
"""PID-scoped guest barriers, actual guest-thread labels, and CA store IDs."""
from pathlib import Path
import re
import sys
if len(sys.argv) != 6 or not sys.argv[2].isdigit() or any(not re.fullmatch('[0-9a-f]{64}', x) for x in sys.argv[4:6]) or sys.argv[4] == sys.argv[5]:
    raise SystemExit('usage: check_tls_parallel_evidence.py logcat PID server-log trusted-CA-sha256 wrong-CA-sha256')
pid = sys.argv[2]
rows = [line for line in Path(sys.argv[1]).read_text(errors='replace').splitlines() if re.match(rf'^[0-9-]+ +[0-9:.]+ +{pid} +[0-9]+ ', line)]
server = Path(sys.argv[3]).read_text(errors='replace')
if not rows or any(re.search(r'TLS_PARALLEL_ERROR|Unhandled exception in guest thread|Fatal signal|FATAL EXCEPTION', row) for row in rows):
    raise SystemExit('PARALLEL_EVIDENCE_CRASH_OR_MISSING_PID')
def unique(pattern):
    found = [(i, row) for i, row in enumerate(rows) if re.search(pattern, row)]
    if len(found) != 1: raise SystemExit('PARALLEL_EVIDENCE_MISSING_OR_DUPLICATE ' + pattern)
    return found[0]
def guest_thread(row):
    match = re.search(r'HLE\.GuestThread\.([0-9]+)', row)
    if not match: raise SystemExit('PARALLEL_EVIDENCE_GUEST_THREAD_MISSING')
    return match.group(1)
begin, _ = unique(r'TLS_PARALLEL_TEST_BEGIN')
complete, _ = unique(r'TLS_PARALLEL_COMPLETE')
ready = {}; start = {}; results = {}; guests = {}; stores = {}
for role, fingerprint in zip(('trusted', 'wrong_ca'), sys.argv[4:6]):
    imported, import_row = unique(rf'CI_TLS_STORE_IMPORT store=[0-9]+ sha256={fingerprint}\b')
    stores[role] = re.search(r'CI_TLS_STORE_IMPORT store=([0-9]+)', import_row).group(1)
    ready[role], ready_row = unique(rf'TLS_PARALLEL_READY role={role}\b')
    start[role], start_row = unique(rf'TLS_PARALLEL_BEGIN role={role}\b')
    result = rf'TLS_PARALLEL_EXCHANGE role={role}\b' if role == 'trusted' else rf'TLS_PARALLEL_REJECTED role={role} rc=0x[0-9a-f]+ rd=0x[0-9a-f]+ wr=0x[0-9a-f]+'
    results[role], result_row = unique(result)
    guests[role] = guest_thread(ready_row)
    if any(guest_thread(row) != guests[role] for row in (start_row, result_row)):
        raise SystemExit('PARALLEL_EVIDENCE_GUEST_THREAD_MISMATCH ' + role)
    if not begin < imported < ready[role] < start[role] < results[role] < complete:
        raise SystemExit('PARALLEL_EVIDENCE_ORDER ' + role)
    expected = 'True' if role == 'trusted' else 'False'
    policy, _ = unique(rf'CI_TLS_STORE_VALIDATION store={stores[role]} valid={expected}\b')
    if not max(start.values(), default=0) < policy < results[role]:
        raise SystemExit('PARALLEL_EVIDENCE_POLICY_ORDER ' + role)
if guests['trusted'] == guests['wrong_ca'] or stores['trusted'] == stores['wrong_ca']:
    raise SystemExit('PARALLEL_EVIDENCE_NOT_DISTINCT')
if not max(ready.values()) < min(start.values()) or not max(start.values()) < min(results.values()):
    raise SystemExit('PARALLEL_EVIDENCE_BARRIER_NOT_OBSERVED')
for role in ('trusted', 'wrong_ca'):
    expected = 'True' if role == 'trusted' else 'False'
    policy, _ = unique(rf'CI_TLS_STORE_VALIDATION store={stores[role]} valid={expected}\b')
    if policy <= max(start.values()): raise SystemExit('PARALLEL_EVIDENCE_POLICY_BEFORE_BARRIER')
if not re.search(r'^PARALLEL_SERVER_BOTH_ACCEPTED$', server, re.M) or not re.search(r'^PARALLEL_SERVER_DONE exchanges=1$', server, re.M):
    raise SystemExit('PARALLEL_EVIDENCE_SERVER_INCOMPLETE')
if len(re.findall(r'^PARALLEL_SERVER_EXCHANGE index=[01]$', server, re.M)) != 1 or 'PARALLEL_SERVER_UNEXPECTED_PAYLOAD' in server:
    raise SystemExit('PARALLEL_EVIDENCE_SERVER_MISMATCH')
print('TLS_PARALLEL_EVIDENCE_OK distinct_guest_threads_and_CA_stores_one_server_exchange')

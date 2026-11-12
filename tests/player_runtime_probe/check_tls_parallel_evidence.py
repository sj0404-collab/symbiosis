#!/usr/bin/env python3
"""Check coordinated guest threads, per-thread trust policy and server result."""
from pathlib import Path
import re
import sys
if len(sys.argv) != 4 or not sys.argv[2].isdigit():
    raise SystemExit('usage: check_tls_parallel_evidence.py logcat PID server-log')
pid = sys.argv[2]
rows = []
for line in Path(sys.argv[1]).read_text(errors='replace').splitlines():
    m = re.match(rf'^[0-9-]+ +[0-9:.]+ +{pid} +([0-9]+) (.*)$', line)
    if m: rows.append((m.group(1), m.group(2)))
server = Path(sys.argv[3]).read_text(errors='replace')
if not rows or any(re.search(r'TLS_PARALLEL_ERROR|Unhandled exception in guest thread|Fatal signal|FATAL EXCEPTION', row) for _, row in rows):
    raise SystemExit('PARALLEL_EVIDENCE_CRASH_OR_MISSING_PID')
def unique(pattern):
    matches = [(i, tid) for i, (tid, text) in enumerate(rows) if re.search(pattern, text)]
    if len(matches) != 1: raise SystemExit('PARALLEL_EVIDENCE_MISSING_OR_DUPLICATE ' + pattern)
    return matches[0]
begin, _ = unique(r'TLS_PARALLEL_TEST_BEGIN')
complete, _ = unique(r'TLS_PARALLEL_COMPLETE')
ready = {}; start = {}; results = {}
for role in ('trusted', 'wrong_ca'):
    ready[role], tid = unique(rf'TLS_PARALLEL_READY role={role}\b')
    start[role], tid2 = unique(rf'TLS_PARALLEL_BEGIN role={role}\b')
    result = rf'TLS_PARALLEL_EXCHANGE role={role}\b' if role == 'trusted' else rf'TLS_PARALLEL_REJECTED role={role} rc=0x[0-9a-f]+ rd=0x[0-9a-f]+ wr=0x[0-9a-f]+'
    results[role], tid3 = unique(result)
    if tid != tid2 or tid != tid3: raise SystemExit('PARALLEL_EVIDENCE_THREAD_MISMATCH ' + role)
    if not begin < ready[role] < start[role] < results[role] < complete: raise SystemExit('PARALLEL_EVIDENCE_ORDER ' + role)
    policy = [(i, text) for i, (t, text) in enumerate(rows) if t == tid and start[role] < i < results[role] and 'CI_ONLY_TLS_CUSTOM_CHAIN' in text]
    expected = 'valid=True' if role == 'trusted' else 'valid=False'
    if len(policy) != 1 or expected not in policy[0][1]: raise SystemExit('PARALLEL_EVIDENCE_CHAIN_MISMATCH ' + role)
if not max(ready.values()) < min(start.values()) or not max(start.values()) < min(results.values()):
    raise SystemExit('PARALLEL_EVIDENCE_BARRIER_NOT_OBSERVED')
if unique(r'TLS_PARALLEL_READY role=trusted\b')[1] == unique(r'TLS_PARALLEL_READY role=wrong_ca\b')[1]:
    raise SystemExit('PARALLEL_EVIDENCE_SAME_THREAD')
if not re.search(r'^PARALLEL_SERVER_BOTH_ACCEPTED$', server, re.M) or not re.search(r'^PARALLEL_SERVER_DONE exchanges=1$', server, re.M):
    raise SystemExit('PARALLEL_EVIDENCE_SERVER_INCOMPLETE')
if len(re.findall(r'^PARALLEL_SERVER_EXCHANGE index=[01]$', server, re.M)) != 1 or 'PARALLEL_SERVER_UNEXPECTED_PAYLOAD' in server:
    raise SystemExit('PARALLEL_EVIDENCE_SERVER_MISMATCH')
print('TLS_PARALLEL_EVIDENCE_OK distinct_guest_threads_contexts_and_one_server_exchange')

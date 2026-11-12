#!/usr/bin/env python3
"""Validate PID-scoped synthetic account IPC evidence, not game compatibility."""
import re
import sys
from datetime import datetime
from pathlib import Path

def main(path, pid, mode):
    if not pid.isdigit() or mode not in ('normal', 'pid'):
        raise ValueError('invalid PID or mode')
    prefix = re.compile(r'^(\d\d-\d\d \d\d:\d\d:\d\d\.\d+)\s+' + re.escape(pid) + r'\s+\d+\s+\S\s+')
    rows = [(m.group(1), line) for line in Path(path).read_text(errors='replace').splitlines() if (m := prefix.match(line))]
    events = [(t, line.split('ACCOUNT_PROBE_', 1)[1]) for t, line in rows if 'ACCOUNT_PROBE_' in line]
    if any('Unhandled exception in guest thread' in line or 'Fatal signal' in line for _, line in rows):
        raise ValueError('guest exception in process')
    expected = [
        rf'^BEGIN mode={mode}$',
        rf'^OPEN mode={mode} rc=0x00000000$',
        rf'^USER_COUNT mode={mode} rc=0x00000000 count=\d+$',
        rf'^INIT mode={mode} rc=0x00000000$',
        rf'^CALL_BEGIN mode={mode} phase=early$',
        rf'^RESULT mode={mode} phase=early rc=0x00000000 locked=[01]$',
        rf'^CALL_BEGIN mode={mode} phase=late$',
        rf'^RESULT mode={mode} phase=late rc=0x00000000 locked=[01]$',
        rf'^COMPLETE mode={mode}$',
    ]
    if len(events) != len(expected) or any(re.fullmatch(p, event.strip()) is None for (_, event), p in zip(events, expected)):
        raise ValueError('unexpected, missing, duplicated or out-of-order IPC evidence')
    def stamp(t): return datetime.strptime('2024-' + t, '%Y-%m-%d %H:%M:%S.%f')
    elapsed = (stamp(events[6][0]) - stamp(events[5][0])).total_seconds()
    if not 17 <= elapsed <= 90:
        raise ValueError('late call interval out of range')
    print(f'ACCOUNT_EVIDENCE_OK mode={mode} elapsed={elapsed:.3f} normal_service_result_only_not_game_compatibility')

if __name__ == '__main__':
    if len(sys.argv) != 4:
        raise SystemExit('usage: check_guest_account_evidence.py log PID {normal|pid}')
    try: main(*sys.argv[1:])
    except (OSError, ValueError) as e: raise SystemExit('ACCOUNT_EVIDENCE_FAILED: ' + str(e))

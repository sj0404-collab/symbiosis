#!/usr/bin/env python3
"""Check ordered, PID-scoped HID observations; never claim game compatibility."""
from datetime import datetime
from pathlib import Path
import re
import sys

if len(sys.argv) != 3 or not sys.argv[2].isdigit():
    raise SystemExit('usage: check_hid_long_evidence.py logcat PID')
text = Path(sys.argv[1]).read_text(errors='replace')
pid = sys.argv[2]
rows = re.findall(rf'^([0-9][0-9]-[0-9][0-9] +[0-9:.]+) +{pid} +[0-9]+ .*$', text, re.M)
lines = [line for line in text.splitlines() if re.match(rf'^[0-9-]+ +[0-9:.]+ +{pid} +[0-9]+ ', line)]
if not rows or any(re.search(r'Unhandled exception in guest thread|FATAL EXCEPTION|Fatal signal|HID_LONG_ERROR', row) for row in lines):
    raise SystemExit('HID_LONG_CRASH_OR_MISSING_PID')
markers = [row for row in lines if 'HID_LONG_' in row]
if not markers or 'HID_LONG_BEGIN' not in markers[0] or 'HID_LONG_COMPLETE' not in markers[-1]:
    raise SystemExit('HID_LONG_BEGIN_OR_COMPLETE_MISSING')
samples = [row for row in markers if 'HID_LONG_SAMPLE ' in row]
if len(samples) != 23:
    raise SystemExit('HID_LONG_SAMPLE_COUNT')
for second, row in enumerate(samples):
    if not re.search(rf'HID_LONG_SAMPLE second={second} connected=\d+ pad_style=0x[0-9a-fA-F]+ style0=0x[0-9a-fA-F]+ buttons=0x[0-9a-fA-F]+\b', row):
        raise SystemExit('HID_LONG_SAMPLE_ORDER_OR_FORMAT')
    if second >= 3 and not re.search(r'connected=1 pad_style=0x0*1\b', row):
        raise SystemExit('HID_LONG_PLAYER1_NOT_STABLE second=' + str(second))
acquires = [row for row in markers if 'HID_LONG_ACQUIRE ' in row]
if len(acquires) != 5:
    raise SystemExit('HID_LONG_ACQUIRE_COUNT')
for slot, row in enumerate(acquires, 4):
    if not re.search(rf'HID_LONG_ACQUIRE slot={slot} rc=0x00000000 module=0 description=0\b', row):
        raise SystemExit('HID_LONG_ACQUIRE_FAILED slot=' + str(slot))
if not (markers.index(samples[18]) < markers.index(acquires[0]) < markers.index(acquires[-1]) < markers.index(samples[19])):
    raise SystemExit('HID_LONG_ACQUIRE_ORDER')
def stamp(row):
    return datetime.strptime(re.match(r'^([0-9-]+ +[0-9:.]+)', row).group(1), '%m-%d %H:%M:%S.%f')
elapsed = (stamp(samples[22]) - stamp(samples[0])).total_seconds()
if not 20 <= elapsed <= 90:
    raise SystemExit('HID_LONG_ELAPSED_OUT_OF_RANGE ' + str(elapsed))
print(f'HID_LONG_EVIDENCE_OK seconds=0..22 elapsed={elapsed:.3f} player1_stable=3..22 late_acquires=5')

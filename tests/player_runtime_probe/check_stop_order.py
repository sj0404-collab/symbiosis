#!/usr/bin/env python3
"""Verify ordered CI logs from the genuine player without claiming all threads stopped."""
import re
import sys

log, pid, delayed = sys.argv[1:]
with open(log, encoding='utf-8', errors='replace') as f:
    lines = [line for line in f if re.match(r'^\S+\s+\S+\s+' + re.escape(pid) + r'\s+', line)]


def position(text):
    for i, line in enumerate(lines):
        if text in line:
            return i
    raise SystemExit('STOP_ORDER_MISSING ' + text)

order = ['CI_UI_PRESENTED type=2', 'CI_STOP_TRIGGERED', 'CI_STOP_REQUESTED']
if delayed == 'true':
    order.append('CI_CANCEL_DELAY_BEGIN')
order += ['CI_REPLY_RETURNED accepted=false ok=true', 'CI_SIGNAL_BEGIN',
          'CI_RENDER_JOINED', 'CI_CLOSE_RETURNED ok=true', 'CI_ISOLATED_PROCESS_EXIT']
positions = [position(x) for x in order]
if positions != sorted(positions) or len(set(positions)) != len(positions):
    raise SystemExit('STOP_ORDER_WRONG ' + repr(list(zip(order, positions))))
print('STOP_ORDER_OK delayed=' + delayed + ' ' + ' < '.join(order))

#!/usr/bin/env python3
"""Require the real landscape exit button not to overlap Android navigation."""
import re
import sys
import xml.etree.ElementTree as ET

root = ET.parse(sys.argv[1])
nodes = list(root.iter('node'))
button = next((n for n in nodes if n.get('text', '').casefold() == 'выйти'), None)
nav = next((n for n in nodes if n.get('resource-id') == 'android:id/navigationBarBackground'), None)
if button is None or nav is None:
    raise SystemExit('EXIT_OR_SYSTEM_NAV_MISSING: cannot verify insets')

def rect(node):
    m = re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', node.get('bounds', ''))
    if not m:
        raise SystemExit('EXIT_OR_SYSTEM_NAV_BOUNDS_INVALID')
    return tuple(map(int, m.groups()))

x1, y1, x2, y2 = rect(button)
a1, b1, a2, b2 = rect(nav)
print(f'EXIT_BOUNDS={x1},{y1},{x2},{y2} NAV_BOUNDS={a1},{b1},{a2},{b2}')
if max(x1, a1) < min(x2, a2) and max(y1, b1) < min(y2, b2):
    raise SystemExit('EXIT_OVERLAPS_SYSTEM_NAV')
print('EXIT_CLEAR_OF_SYSTEM_NAV')

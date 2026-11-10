#!/usr/bin/env python3
"""Verify a composited deko_basic RGB shader triangle, not a guest return code.

Thresholds permit compositing variation and tolerate UI overlay. Colour group
centres must be distinct and match red-top/green-left/blue-right order.
"""
from PIL import Image
import sys

im = Image.open(sys.argv[1]).convert('RGB')
colors = {'red': [], 'green': [], 'blue': []}
for y in range(0, im.height, 2):
    for x in range(0, im.width, 2):
        r, g, b = im.getpixel((x, y))
        if r > 100 and r > g * 1.45 + 15 and r > b * 1.45 + 15:
            colors['red'].append((x, y))
        elif g > 100 and g > r * 1.45 + 15 and g > b * 1.45 + 15:
            colors['green'].append((x, y))
        elif b > 100 and b > r * 1.45 + 15 and b > g * 1.45 + 15:
            colors['blue'].append((x, y))
threshold = max(500, im.width * im.height // 1600)
centers = {}
for name, pixels in colors.items():
    print(f'TRIANGLE_{name.upper()}_PIXELS={len(pixels)} required={threshold}')
    if len(pixels) < threshold:
        sys.exit(1)
    centers[name] = (sum(x for x, y in pixels) / len(pixels), sum(y for x, y in pixels) / len(pixels))
print('TRIANGLE_CENTERS', centers)
r, g, b = centers['red'], centers['green'], centers['blue']
# The blue-grey background can add blue pixels; ordering still provides a
# meaningful independent cross-check of the vertex and fragment stages.
if not (g[0] < r[0] < b[0] and r[1] < g[1] and r[1] < b[1]):
    print('TRIANGLE_COLOR_ORDER_WRONG')
    sys.exit(1)
print('TRIANGLE_PIXELS_VISIBLE')

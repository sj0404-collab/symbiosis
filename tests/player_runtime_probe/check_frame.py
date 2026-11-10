#!/usr/bin/env python3
"""Look for the self-authored SDL NRO's blue frame in Android's screenshot.

This checks visible composited pixels, not merely guest SDL return codes. The
bounds allow modest RGB colour conversion, but do not accept a black screen.
"""
from PIL import Image
import sys

im = Image.open(sys.argv[1]).convert('RGB')
# Count coloured pixels across the entire composited display. The guest fills
# its whole surface with RGB (36,110,190) or (20,110,190), while other app UI
# is not expected to contain this colour at this scale.
matches = sum(1 for r, g, b in im.getdata()
              if 8 <= r <= 65 and 80 <= g <= 140 and 150 <= b <= 220 and b > g + 35)
needed = max(5000, im.width * im.height // 10)
print(f'FRAME_BLUE_PIXELS={matches} required={needed} size={im.width}x{im.height}')
# Red must be upper-left of yellow in screenshot space: unlike a uniform
# frame, these markers detect gross mirror or rotation errors. This deliberately
# does not claim to validate scaling, aspect ratio, colours or all transforms.
red = []
yellow = []
for y in range(0, im.height, 2):
    for x in range(0, im.width, 2):
        r, g, b = im.getpixel((x, y))
        if r >= 180 and g <= 75 and b <= 75:
            red.append((x, y))
        elif r >= 180 and g >= 155 and b <= 80:
            yellow.append((x, y))
if len(red) < 100 or len(yellow) < 100:
    print(f'FRAME_MARKERS_MISSING red={len(red)} yellow={len(yellow)}')
    sys.exit(1)
rx = sum(x for x, y in red) / len(red)
ry = sum(y for x, y in red) / len(red)
yx = sum(x for x, y in yellow) / len(yellow)
yy = sum(y for x, y in yellow) / len(yellow)
print(f'FRAME_MARKER_CENTRES red={rx:.1f},{ry:.1f} yellow={yx:.1f},{yy:.1f}')
if not (rx < yx and ry < yy):
    print('FRAME_MARKERS_FLIPPED_OR_ROTATED')
    sys.exit(1)
landscape_required = '--landscape' in sys.argv[2:]
if landscape_required and im.width <= im.height:
    print('FRAME_NOT_LANDSCAPE')
    sys.exit(1)
sys.exit(0 if matches >= needed else 1)

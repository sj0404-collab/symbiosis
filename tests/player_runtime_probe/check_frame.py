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
sys.exit(0 if matches >= needed else 1)

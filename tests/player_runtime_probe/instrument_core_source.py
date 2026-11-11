#!/usr/bin/env python3
"""Allow only this run's source-built hash in a disposable debug tree.

Not a production change: the output is a temporary Eden checkout. A hash is
passed explicitly from the runner after building a pinned source checkout.
"""
from pathlib import Path
import re
import sys

if len(sys.argv) != 3 or not re.fullmatch(r'[0-9a-f]{64}', sys.argv[2]):
    raise SystemExit('expected EngineLoader.kt and one lowercase SHA-256')
path = Path(sys.argv[1])
if path.resolve() == (Path(__file__).resolve().parents[2] / 'patch/android/utils/EngineLoader.kt').resolve():
    raise SystemExit('refusing to alter production EngineLoader')
s = path.read_text()
old = '            if (!got.equals(want, ignoreCase = true)) {\n'
if s.count(old) != 1:
    raise SystemExit('unexpected core hash check; refusing instrumentation')
new = ('            // CI ONLY: exactly one source-built candidate; production pin unchanged.\n'
       '            if (!got.equals(want, ignoreCase = true) &&\n'
       f'                !got.equals("{sys.argv[2]}", ignoreCase = true)) {{\n')
path.write_text(s.replace(old, new, 1))

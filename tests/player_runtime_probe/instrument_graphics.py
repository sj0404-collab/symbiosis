#!/usr/bin/env python3
"""Instrument a disposable patched debug source tree, never the fork sources.

This preserves callback behaviour: logs are evidence of calls, not proof of
correctness. The build must remain debug-only and unshipped.
"""
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit('expected path of copied symbiosis_kenji_bridge.cpp')
path = Path(sys.argv[1])
s = path.read_text()
changes = {
    'JNIEXPORT void setRenderingThread() {\n':
        'JNIEXPORT void setRenderingThread() {\n'
        '    LOGI("CI_ONLY_GRAPHICS setRenderingThread tid=%ld", (long)syscall(SYS_gettid));\n',
    'JNIEXPORT void setCurrentTransform(long native_window, int transform) {\n'
    '    (void)native_window;\n    (void)transform;\n}':
        'JNIEXPORT void setCurrentTransform(long native_window, int transform) {\n'
        '    LOGI("CI_ONLY_GRAPHICS setCurrentTransform window=%p value=%d tid=%ld", '
        '(void*)native_window, transform, (long)syscall(SYS_gettid));\n'
        '    (void)native_window;\n    (void)transform;\n}',
}
for old, new in changes.items():
    if s.count(old) != 1:
        raise SystemExit('unexpected source layout; refusing to instrument')
    s = s.replace(old, new, 1)
path.write_text(s)

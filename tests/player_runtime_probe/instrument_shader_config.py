#!/usr/bin/env python3
"""Log the real resolved shader-cache value only in disposable debug sources."""
from pathlib import Path
import sys
if len(sys.argv) != 2:
    raise SystemExit('expected copied KenjiBridge.kt')
p = Path(sys.argv[1]); s = p.read_text()
old = '''        nativeGraphicsInit(
            resScale = cfg.resScale,'''
new = '''        android.util.Log.i("PlayerRuntimeCI", "CI_ONLY_RESOLVED_SHADER_CACHE value=${cfg.enableShaderCache}")
        nativeGraphicsInit(
            resScale = cfg.resScale,'''
if s.count(old) != 1:
    raise SystemExit('unexpected source layout; refusing diagnostic instrumentation')
s = s.replace(old, new, 1)
old_device = '''        val err = runCatching {
            nativeDeviceInit(
                memoryManagerMode = cfg.memoryManagerMode,'''
new_device = '''        val err = runCatching {
            android.util.Log.i("PlayerRuntimeCI", "CI_ONLY_RESOLVED_INTERNET value=${cfg.enableInternetAccess}")
            nativeDeviceInit(
                memoryManagerMode = cfg.memoryManagerMode,'''
if s.count(old_device) != 1:
    raise SystemExit('unexpected device configuration layout; refusing diagnostic instrumentation')
p.write_text(s.replace(old_device, new_device, 1))

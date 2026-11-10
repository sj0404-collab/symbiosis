#!/usr/bin/env python3
"""Record the existing Eden Plus hitbox in the disposable debug tree only.

No event is synthesized or intercepted here; ADB taps Android screen coordinates
and the real guest must report its own HID state to prove end-to-end delivery.
"""
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit('expected path of copied patched InputOverlay.kt')
p = Path(sys.argv[1])
s = p.read_text()
old = '''    override fun draw(canvas: Canvas) {
        super.draw(canvas)
'''
new = '''    private var ciLastPlusGeometry = ""
    override fun draw(canvas: Canvas) {
        super.draw(canvas)
        if (inputSink != null) {
            overlayButtons.firstOrNull { it.button == NativeButton.Plus }?.let { button ->
                val location = IntArray(2)
                getLocationOnScreen(location)
                val b = button.bounds
                val geometry = "${location[0] + b.left},${location[1] + b.top},${location[0] + b.right},${location[1] + b.bottom}"
                if (geometry != ciLastPlusGeometry) {
                    ciLastPlusGeometry = geometry
                    android.util.Log.i("PlayerRuntimeCI", "CI_ONLY_PLUS_BOUNDS $geometry")
                }
            }
        }
'''
if s.count(old) != 1 or 'var inputSink: InputSink? = null' not in s:
    raise SystemExit('unexpected overlay layout; refusing to instrument')
p.write_text(s.replace(old, new, 1))

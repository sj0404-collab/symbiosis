#!/usr/bin/env python3
"""Inject an optional input sink into Eden v0.2.1's existing overlay.
Without a sink its original Eden paths stay unchanged. Fail closed on drift.
"""
import sys
from pathlib import Path
p = Path(sys.argv[1]); s = p.read_text(encoding='utf-8')
def once(old, new):
    global s
    n = s.count(old)
    if n != 1:
        raise SystemExit(f'overlay patch expected one match, got {n}: {old[:90]!r}')
    s = s.replace(old, new, 1)
once('    var touchEventListener: ((MotionEvent) -> Unit)? = null', '''    var touchEventListener: ((MotionEvent) -> Unit)? = null

    /** Optional recipient: Eden remains the default when no other core is selected. */
    interface InputSink {
        fun button(button: NativeButton, action: Int)
        fun stick(stick: NativeAnalog, x: Float, y: Float)
        fun touchDown(id: Int, x: Float, y: Float)
        fun touchMove(id: Int, x: Float, y: Float)
        fun touchUp(id: Int)
        fun cancel()
    }
    var inputSink: InputSink? = null

    private fun sendButton(player: Int, button: NativeButton, action: Int) {
        val sink = inputSink
        if (sink != null) sink.button(button, action)
        else NativeInput.onOverlayButtonEvent(player, button, action)
    }
    private fun sendStick(player: Int, stick: NativeAnalog, x: Float, y: Float) {
        val sink = inputSink
        if (sink != null) sink.stick(stick, x, y)
        else NativeInput.onOverlayJoystickEvent(player, stick, x, y)
    }
''')
once('''        var shouldUpdateView = false
        val playerIndex = when (NativeInput.getStyleIndex(0)) {''', '''        if (event.actionMasked == MotionEvent.ACTION_CANCEL && inputSink != null) {
            inputSink?.cancel()
            refreshControls()
            return true
        }
        var shouldUpdateView = false
        val playerIndex = when (if (inputSink != null) null else NativeInput.getStyleIndex(0)) {''')
# Limit replacement to onTouch, leaving the fallback wrappers intact.
start = s.index('    override fun onTouch(v: View, event: MotionEvent): Boolean {')
end = s.index('    private fun playHaptics', start)
part = s[start:end].replace('NativeInput.onOverlayButtonEvent(', 'sendButton(').replace('NativeInput.onOverlayJoystickEvent(', 'sendStick(')
part = part.replace('NativeInput.onTouchPressed(pointerId, xPosition.toFloat(), yPosition.toFloat())', 'inputSink?.touchDown(pointerId, xPosition.toFloat(), yPosition.toFloat())\n                ?: NativeInput.onTouchPressed(pointerId, xPosition.toFloat(), yPosition.toFloat())')
part = part.replace('NativeInput.onTouchMoved(fingerId, event.getX(i), event.getY(i))', 'inputSink?.touchMove(fingerId, event.getX(i), event.getY(i))\n                    ?: NativeInput.onTouchMoved(fingerId, event.getX(i), event.getY(i))')
part = part.replace('NativeInput.onTouchReleased(pointerId)', 'inputSink?.touchUp(pointerId) ?: NativeInput.onTouchReleased(pointerId)')
if part.count('sendButton(') != 6 or part.count('sendStick(') != 1 or part.count('inputSink?.touch') != 3:
    raise SystemExit('unexpected Eden overlay event call count')
s = s[:start] + part + s[end:]
# This process uses a launcher snapshot, not the authoritative Eden config.
# Default/missing control repair may update its in-memory copy but must not
# persist a stale snapshot or the deliberately hidden unsupported controls.
for start_marker, end_marker in [
    ('    private fun populateDefaultConfig()', '    private fun checkForNewControls('),
    ('    private fun checkForNewControls(', '    fun resetLayoutVisibilityAndPlacement()'),
]:
    start = s.index(start_marker)
    end = s.index(end_marker, start)
    part = s[start:end]
    if part.count('NativeConfig.saveGlobalConfig()') != 1:
        raise SystemExit('unexpected overlay config save count')
    part = part.replace('NativeConfig.saveGlobalConfig()',
                        'if (inputSink == null) NativeConfig.saveGlobalConfig()')
    s = s[:start] + part + s[end:]
p.write_text(s, encoding='utf-8')

#!/usr/bin/env python3
"""Overlay source integration checks; not a substitute for Android input tests."""
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
ACTIVITY = (ROOT / 'patch/android/activities/KenjiPlayerActivity.kt').read_text()
BRIDGE = (ROOT / 'patch/android/utils/KenjiBridge.kt').read_text()
CPP = (ROOT / 'patch/symbiosis/kenji_bridge.cpp').read_text()
SCRIPT = ROOT / 'tools/patch_eden_input_overlay.py'

class OverlayContract(unittest.TestCase):
    def test_patch_exact_upstream_and_reject_drift(self):
        # Downloaded v0.2.1 is supplied by CI from its checked-out upstream.
        import os
        upstream = os.environ.get('EDEN_OVERLAY_SOURCE')
        if not upstream:
            self.skipTest('EDEN_OVERLAY_SOURCE not provided')
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / 'InputOverlay.kt'
            path.write_text(Path(upstream).read_text())
            subprocess.run(['python3', str(SCRIPT), str(path)], check=True)
            s = path.read_text()
            self.assertIn('else NativeInput.onOverlayButtonEvent(player, button, action)', s)
            self.assertIn('else NativeInput.onOverlayJoystickEvent(player, stick, x, y)', s)
            self.assertIn('if (inputSink != null) null else NativeInput.getStyleIndex(0)', s)
            self.assertIn('MotionEvent.ACTION_CANCEL && inputSink != null', s)
            self.assertEqual(s.count('sendButton('), 7) # 6 calls, one declaration
            self.assertEqual(s.count('sendStick('), 2)
            self.assertIn('inputSink?.touchDown', s)
            self.assertIn('inputSink?.touchMove', s)
            self.assertIn('inputSink?.touchUp', s)
            self.assertEqual(s.count('if (inputSink == null) NativeConfig.saveGlobalConfig()'), 2)
            result = subprocess.run(['python3', str(SCRIPT), str(path)], capture_output=True)
            self.assertNotEqual(result.returncode, 0)

    def test_process_snapshot_and_orientation(self):
        self.assertIn('KenjiOverlaySnapshot.capture()', ACTIVITY)
        self.assertIn('KenjiOverlaySnapshot.restore(snapshot)', ACTIVITY)
        self.assertIn('overlay.layout = overlayLayout()', ACTIVITY)
        self.assertIn('OverlayLayout.Portrait else OverlayLayout.Landscape', ACTIVITY)
        self.assertIn('KenjiBridge.inputSize(width, height)', ACTIVITY)

    def test_cancel_and_stop_are_gated(self):
        self.assertIn('KenjiBridge.cancelInputEvents()', ACTIVITY)
        self.assertIn('private var touchPointer = -1', ACTIVITY)
        stop = ACTIVITY.split('private fun requestStop(): Thread', 1)[1].split('@Deprecated', 1)[0]
        self.assertLess(stop.index('disableInputEvents'), stop.index('updater?.join()'))
        self.assertLess(stop.index('updater?.join()'), stop.index('drainInputEvents'))
        self.assertLess(stop.index('drainInputEvents'), stop.index('stopPlay'))
        self.assertIn('stopThread != null || !KenjiBridge.activateInputEvents(padId)', ACTIVITY)
        self.assertIn('synchronized(eventLock)', BRIDGE)

    def test_exports_and_signatures(self):
        for name in ['inputSetButtonPressed', 'inputSetButtonReleased', 'inputSetStickAxis',
                     'inputSetTouchPoint', 'inputReleaseTouchPoint', 'inputSetClientSize']:
            self.assertIn('"' + name + '"', CPP)
        self.assertIn('void (*)(int, float, float, int)', CPP)
        self.assertIn('KenjiInputMap.button(edenButton)', BRIDGE)
        self.assertIn('KenjiInputMap.stick(edenStick)', BRIDGE)

if __name__ == '__main__': unittest.main()

#!/usr/bin/env python3
"""Wiring checks only; real pointer lifetime and JNI require Android tests."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
CALLBACK = (ROOT / 'patch/android/kenjinx/KenjinxNative.kt').read_text()
HARNESS = (ROOT / 'tests/android_core_probe/build.sh').read_text()
FIXTURE = (ROOT / 'tests/android_core_probe/src/dev/symbiosis/coreprobe/KeyboardFixture.java').read_text()

class UiCallbackWiring(unittest.TestCase):
    def test_callback_delegates_synchronously_and_uses_volatile_listener(self):
        self.assertIn('@Volatile var uiCallback: UiCallback? = null', CALLBACK)
        callback = CALLBACK.split('fun updateUiHandler(', 1)[1].split('/** Счётчики', 1)[0]
        self.assertIn('uiCallback?.onRequest(', callback)
        self.assertNotIn('Thread(', callback)
        self.assertIn('@JvmStatic external fun nativeCopyUiText(pointer: Long): ByteArray?', CALLBACK)

    def test_test_listener_does_not_patch_production_callback(self):
        self.assertIn('setUiCallback(new KenjinxNative.UiCallback()', FIXTURE)
        self.assertNotIn('source.replace(old, new)', HARNESS)
        self.assertIn('cp "$WORK/core-probe.apk" "$WORK/core-probe-ui-fixture.apk"', HARNESS)

if __name__ == '__main__': unittest.main()

"""Checks test-only stop observation; never substitutes for Android execution."""
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class PlayerStopObserverTest(unittest.TestCase):
    def test_instrumentation_keeps_real_stop_calls(self):
        with tempfile.TemporaryDirectory() as d:
            source = Path(d) / 'player.kt'
            source.write_bytes((ROOT / 'patch/android/activities/KenjiPlayerActivity.kt').read_bytes())
            subprocess.run([sys.executable, str(ROOT / 'tests/player_runtime_probe/instrument_stop_race.py'), str(source)], check=True)
            text = source.read_text()
            for name in ('leave()', 'uiSession?.stop()', 'KenjiBridge.signalPlayStop()', 'KenjiBridge.closePlay()', 'CI_CANCEL_DELAY_BEGIN'):
                self.assertIn(name, text)
            self.assertNotIn('CI_STOP_RECEIVER_READY', (ROOT / 'patch/android/activities/KenjiPlayerActivity.kt').read_text())

    def test_space_pin_only_in_disposable_copy(self):
        with tempfile.TemporaryDirectory() as d:
            source = Path(d) / 'EngineLoader.kt'
            original = ROOT / 'patch/android/utils/EngineLoader.kt'
            source.write_bytes(original.read_bytes())
            subprocess.run([sys.executable, str(ROOT / 'tests/player_runtime_probe/instrument_core_space.py'), str(source)], check=True)
            digest = 'd781048671e4cef1cde2ec15db8fe29b949df9949d83569ceaf776ad12901590'
            self.assertIn(digest, source.read_text())
            self.assertNotIn(digest, original.read_text())
            self.assertIn('!got.equals(want, ignoreCase = true)', source.read_text())

    def test_source_core_pin_is_exact_and_debug_only(self):
        script = ROOT / 'tests/player_runtime_probe/instrument_core_source.py'
        original = ROOT / 'patch/android/utils/EngineLoader.kt'
        with tempfile.TemporaryDirectory() as d:
            source = Path(d) / 'EngineLoader.kt'
            source.write_bytes(original.read_bytes())
            pin = 'a' * 64
            subprocess.run([sys.executable, str(script), str(source), pin], check=True)
            self.assertIn(f'!got.equals("{pin}", ignoreCase = true)', source.read_text())
            self.assertIn('!got.equals(want, ignoreCase = true)', source.read_text())
            self.assertNotIn(pin, original.read_text())
            self.assertNotEqual(subprocess.run([sys.executable, str(script), str(source), pin], capture_output=True).returncode, 0)
            source.write_bytes(original.read_bytes())
            for invalid in ('a' * 63, 'z' * 64, '"; arbitrary code', ''):
                self.assertNotEqual(subprocess.run([sys.executable, str(script), str(source), invalid], capture_output=True).returncode, 0)
            self.assertNotEqual(subprocess.run([sys.executable, str(script), str(original), pin], capture_output=True).returncode, 0)

    def test_order_fails_if_close_precedes_reply(self):
        script = ROOT / 'tests/player_runtime_probe/check_stop_order.py'
        events = ['CI_UI_PRESENTED type=2', 'CI_STOP_TRIGGERED', 'CI_STOP_REQUESTED',
                  'CI_CANCEL_DELAY_BEGIN', 'CI_REPLY_RETURNED accepted=false ok=true',
                  'CI_SIGNAL_BEGIN', 'CI_RENDER_JOINED', 'CI_CLOSE_RETURNED ok=true', 'CI_ISOLATED_PROCESS_EXIT']
        with tempfile.TemporaryDirectory() as d:
            log = Path(d) / 'log'
            def write(rows): log.write_text(''.join(f'11-10 12:00:00.000  123  456 I Test: {x}\n' for x in rows))
            write(events)
            self.assertEqual(subprocess.run([sys.executable, str(script), str(log), '123', 'true'], capture_output=True).returncode, 0)
            events[4], events[7] = events[7], events[4]
            write(events)
            self.assertNotEqual(subprocess.run([sys.executable, str(script), str(log), '123', 'true'], capture_output=True).returncode, 0)

if __name__ == '__main__': unittest.main()

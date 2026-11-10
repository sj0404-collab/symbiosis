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

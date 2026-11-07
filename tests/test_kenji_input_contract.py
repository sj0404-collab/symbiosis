#!/usr/bin/env python3
"""Source contract checks only; they do not replace an Android/core test."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
ACTIVITY = (ROOT / 'patch/android/activities/KenjiPlayerActivity.kt').read_text()
BRIDGE = (ROOT / 'patch/symbiosis/kenji_bridge.cpp').read_text()

class InputContract(unittest.TestCase):
    def test_order_is_init_connect_update_then_game_and_render(self):
        positions = [ACTIVITY.index(x) for x in (
            'KenjiBridge.inputInit(w, h)', 'KenjiBridge.connectVirtualPad()',
            'startInputUpdates(padId)', 'KenjiBridge.loadGame(fd', 'KenjiBridge.runLoop()')]
        self.assertEqual(positions, sorted(positions))

    def test_core_exports_have_exact_expected_signatures(self):
        connect = BRIDGE.split('KenjiBridge_nativeConnectPad', 1)[1].split('JNIEXPORT', 1)[0]
        update = BRIDGE.split('KenjiBridge_nativeUpdateInput', 1)[1].split('/**', 1)[0]
        self.assertIn('int (*)(int)', connect)
        self.assertIn('"inputConnectGamepad"', connect)
        self.assertIn('void (*)()', update)
        self.assertIn('"inputUpdate"', update)

    def test_stop_joins_input_before_stopping_core(self):
        stop = ACTIVITY.split('private fun requestStop(): Thread', 1)[1].split('@Deprecated', 1)[0]
        self.assertLess(stop.index('updater?.join()'), stop.index('KenjiBridge.stopPlay()'))
        destroy = ACTIVITY.split('override fun onDestroy()', 1)[1].split('private fun leave()', 1)[0]
        self.assertIn('!stopper.isAlive', destroy)
        self.assertIn('thread?.isAlive != true', destroy)

    def test_failed_connect_is_not_reported_as_success(self):
        self.assertIn('if (padId < 0)', ACTIVITY)
        self.assertIn('ядро не подключило', ACTIVITY)

if __name__ == '__main__':
    unittest.main()

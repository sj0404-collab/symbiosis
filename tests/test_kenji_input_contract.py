#!/usr/bin/env python3
"""Source contract checks only; they do not replace an Android/core test."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
ACTIVITY = (ROOT / 'patch/android/activities/KenjiPlayerActivity.kt').read_text()
BRIDGE = (ROOT / 'patch/symbiosis/kenji_bridge.cpp').read_text()

class InputContract(unittest.TestCase):
    def test_surface_starts_once_only_after_valid_sized_change(self):
        created = ACTIVITY.split('override fun surfaceCreated', 1)[1].split('private fun startGame', 1)[0]
        changed = ACTIVITY.split('override fun surfaceChanged', 1)[1].split('override fun surfaceDestroyed', 1)[0]
        destroyed = ACTIVITY.split('override fun surfaceDestroyed', 1)[1].split('private fun startInputUpdates', 1)[0]
        stop = ACTIVITY.split('private fun requestStop(): Thread', 1)[1].split('@Deprecated', 1)[0]
        self.assertIn('surfaceStart.created()', created)
        self.assertNotIn('startGame(', created)
        self.assertIn('surfaceStart.changed(width, height, holder.surface.isValid)', changed)
        self.assertIn('startGame(holder, width, height)', changed)
        self.assertIn('if (surfaceStart.destroyed()) requestStop()', destroyed)
        self.assertIn('surfaceStart.stop()', stop)
        self.assertIn('KenjiBridge.rendererKick(surfaceWidth, surfaceHeight, displayRotation())', ACTIVITY)
        self.assertNotIn('surfaceFrame.width().coerceAtLeast(128)', ACTIVITY)

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
        self.assertLess(destroy.index('stopper.join()'), destroy.index('thread?.join()'))
        self.assertLess(destroy.index('thread?.join()'), destroy.index('KenjiBridge.unload()'))
        self.assertLess(destroy.index('KenjiBridge.unload()'), destroy.index('lease.close()'))
        self.assertNotIn('join(3000)', destroy)

    def test_error_is_scrollable_and_does_not_expire(self):
        fail = ACTIVITY.split('private fun fail(msg: String)', 1)[1].split('private fun openRom', 1)[0]
        self.assertIn('ScrollView(this)', fail)
        self.assertIn('setContentView(screen)', fail)
        self.assertIn('requestStop()', fail)
        self.assertNotIn('postDelayed', fail)
        self.assertIn('if (isFinishing || isDestroyed || failed) return', ACTIVITY)

    def test_game_controls_are_below_status_bar(self):
        setup = ACTIVITY.split('override fun onCreate', 1)[1].split('override fun surfaceCreated', 1)[0]
        self.assertIn('root.addView(bar', setup)
        self.assertIn('gameArea.addView(surface', setup)
        self.assertIn('gameArea.addView(overlay', setup)
        self.assertIn('root.addView(gameArea', setup)
        self.assertIn('setOnClickListener { showStatusDetails() }', setup)

    def test_hid_calls_are_context_not_result(self):
        self.assertIn('KenjiCrash.mergeHidOperations(hidCalls, fresh)', ACTIVITY)
        self.assertIn('контекст, не доказательство причины', ACTIVITY)

    def test_open_status_dialog_dismissed_on_failure(self):
        fail = ACTIVITY.split('private fun fail(msg: String)', 1)[1].split('private fun openRom', 1)[0]
        self.assertLess(fail.index('detailsDialog?.dismiss()'), fail.index('setContentView(screen)'))
        self.assertIn('detailsText?.text = details', ACTIVITY)
        self.assertIn('if (!guestFailed && !failed) checkGuestFailure(appContext)', ACTIVITY)

    def test_no_frame_callback_does_not_stop_game(self):
        block = ACTIVITY.split('if (!noFramesWarned && frames == 0L', 1)[1].split('if (!guestFailed) checkGuestFailure', 1)[0]
        self.assertIn('noFramesWarned = true', block)
        self.assertNotIn('requestStop()', block)
        self.assertNotIn('setCoreFor', block)
        self.assertNotIn('fail(', block)
        self.assertNotIn('giveUpOnFrames', ACTIVITY)

    def test_stub_logging_is_requested_after_core_initialized_before_game(self):
        self.assertLess(ACTIVITY.index('KenjiBridge.preparePlay(appContext, config)'), ACTIVITY.index('KenjiBridge.enableHidStubLog()'))
        self.assertLess(ACTIVITY.index('KenjiBridge.enableHidStubLog()'), ACTIVITY.index('KenjiBridge.loadGame(fd'))
        call = BRIDGE.split('KenjiBridge_nativeEnableHidStubLog', 1)[1].split('JNIEXPORT', 1)[0]
        self.assertIn('"loggingSetEnabled"', call)
        self.assertIn('void (*)(int, bool)', call)
        self.assertIn('fn(1, true)', call)

    def test_failed_connect_is_not_reported_as_success(self):
        self.assertIn('if (padId < 0)', ACTIVITY)
        self.assertIn('ядро не подключило', ACTIVITY)

if __name__ == '__main__':
    unittest.main()

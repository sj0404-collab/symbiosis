"""Static contracts only: they do not replace compiling/running real Activity."""
from pathlib import Path
import unittest
R = Path(__file__).resolve().parents[1]
A = (R / 'patch/android/activities/KenjiPlayerActivity.kt').read_text()
class PlayerUiContract(unittest.TestCase):
    def test_session_owned_after_lock_and_before_start(self):
        create = A.split('override fun onCreate(', 1)[1].split('override fun surfaceCreated(', 1)[0]
        self.assertLess(create.index('GameSessionLock.tryAcquire'), create.index('uiSession = ui'))
        self.assertIn('install = { listener -> KenjinxNative.uiCallback = listener }', create)
        self.assertIn('KenjiBridge.respondUi(generation, accepted, bytes)', create)
    def test_setup_before_device_and_load(self):
        start = A.split('private fun startGame(', 1)[1].split('private fun startWatchdog(', 1)[0]
        self.assertLess(start.index('KenjiBridge.attachSurface('), start.index('uiSession?.start()'))
        self.assertLess(start.index('uiSession?.start()'), start.index('KenjiBridge.deviceInit()'))
        self.assertLess(start.index('KenjiBridge.deviceInit()'), start.index('KenjiBridge.loadGame('))
        self.assertIn('if (!uiReady)', start)
    def test_cancel_and_drain_before_close_without_unload(self):
        stop = A.split('private fun requestStop()', 1)[1].split('override fun onBackPressed()', 1)[0]
        self.assertLess(stop.index('uiSession?.stop()'), stop.index('startup.awaitDecision()'))
        self.assertLess(stop.index('uiSession?.awaitPendingReplies()'), stop.index('startup.awaitDecision()'))
        self.assertLess(stop.rindex('uiSession?.awaitPendingReplies()'), stop.index('KenjiBridge.closePlay()'))
        self.assertNotIn('closeAfterGuestStopped()', stop)
        self.assertNotIn('KenjiBridge.unload()', stop)
if __name__ == '__main__': unittest.main()

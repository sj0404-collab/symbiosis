#!/usr/bin/env python3
"""Source wiring only; real Android lifecycle/device behavior needs testing."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1] / 'patch/android'
EDEN = (ROOT / 'fragments/EmulationFragment.kt').read_text()
KENJI = (ROOT / 'activities/KenjiPlayerActivity.kt').read_text()
PROBE = (ROOT / 'utils/KenjiProbeService.kt').read_text()
LOCK = (ROOT / 'utils/GameSessionLock.kt').read_text()

class GameSessionContract(unittest.TestCase):
    def test_shared_private_file_and_before_eden_surface(self):
        self.assertIn('File(requireContext().filesDir, GameSessionLock.FILE_NAME)', EDEN)
        self.assertIn('File(filesDir, GameSessionLock.FILE_NAME)', KENJI)
        self.assertIn('File(filesDir, GameSessionLock.FILE_NAME)', PROBE)
        block = EDEN.split('State.STOPPED -> {', 1)[1].split('State.PAUSED -> {', 1)[0]
        self.assertLess(block.index('sessionLease()'), block.index('NativeLibrary.surfaceChanged'))
        self.assertLess(block.index('NativeLibrary.surfaceChanged'), block.index('runWithLease'))
        clear = EDEN.split('fun clearSurface()', 1)[1].split('private fun runWithValidSurface', 1)[0]
        self.assertIn('if (nativeSurfaceOwned) NativeLibrary.surfaceDestroyed()', clear)

    def test_eden_native_thread_releases_in_finally(self):
        run = EDEN.split('private fun runWithLease(', 1)[1].split('@Synchronized\n        fun changeProgram', 1)[0]
        self.assertLess(run.index('NativeLibrary.run('), run.index('finally'))
        self.assertLess(run.index('finally'), run.index('lease.close()'))
        change = EDEN.split('fun changeProgram(', 1)[1].split('// Surface callbacks', 1)[0]
        self.assertLess(change.index('emulationThread.join()'), change.index('sessionLease()'))
        self.assertLess(change.index('sessionLease()'), change.index('runWithLease('))

    def test_kenji_refusal_does_not_stop_the_other_session(self):
        start = KENJI.split('override fun onCreate(', 1)[1].split('val path = intent.getStringExtra', 1)[0]
        self.assertIn('GameSessionLock.tryAcquire', start)
        self.assertNotIn('requestStop()', start.replace('fail()/requestStop()', ''))
        self.assertIn('return', start)
        destroy = KENJI.split('override fun onDestroy()', 1)[1].split('private fun leave()', 1)[0]
        self.assertLess(destroy.index('if (lease == null)'), destroy.index('requestStop()'))
        self.assertLess(destroy.index('stopper.join()'), destroy.index('android.os.Process.killProcess('))
        self.assertLess(destroy.index('thread?.join()'), destroy.index('android.os.Process.killProcess('))
        self.assertIn('if (isIsolatedKenjiProcess())', destroy)
        self.assertNotIn('KenjiBridge.unload()', destroy)
        self.assertNotIn('lease.close()', destroy)

    def test_probe_does_not_unload_or_kill_player_on_service_destroy(self):
        work = PROBE.split('val lease = when (val result = GameSessionLock.tryAcquire(', 1)[1]
        self.assertLess(work.index('KenjiBridge.start('), work.index('KenjiBridge.unload()'))
        self.assertLess(work.index('KenjiBridge.unload()'), work.index('lease.close()'))
        self.assertIn('status = runCatching {\n                    KenjiBridge.armCrashReport', work)
        destroy = PROBE.split('override fun onDestroy()', 1)[1]
        self.assertNotIn('KenjiBridge.unload()', destroy)
        self.assertNotIn('Process.killProcess(', destroy)

    def test_same_process_check_precedes_open_and_keeps_lease_alive(self):
        self.assertLess(LOCK.index('if (key in held)'), LOCK.index('RandomAccessFile(path, "rw")'))
        self.assertIn('mutableMapOf<String, GameSessionLock>()', LOCK)
        self.assertIn('held[key] = lease', LOCK)

if __name__ == '__main__':
    unittest.main()

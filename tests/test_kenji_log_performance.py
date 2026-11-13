# SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
# SPDX-License-Identifier: GPL-3.0-or-later
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]

class PlayerLogPerformance(unittest.TestCase):
    def test_watchdog_status_uses_bounded_log_tail(self):
        activity = (ROOT / 'patch/android/activities/KenjiPlayerActivity.kt').read_text()
        start = activity.index('private fun coreLogTail(')
        end = activity.index('private fun onMainAndWait(', start)
        body = activity[start:end]
        self.assertIn('KernelLogFormat.statusTail(file, lines)', body)
        self.assertNotIn('readLines()', body)
        self.assertNotIn('readText()', body)
        log = (ROOT / 'patch/android/utils/KernelLogFormat.kt').read_text()
        start = log.index('fun statusTail(')
        end = log.index('fun completeLines(', start)
        self.assertIn('tailOf(file, TAIL_BYTES)', log[start:end])

    def test_watchdog_is_background_and_preserves_exit_synchronization(self):
        activity = (ROOT / 'patch/android/activities/KenjiPlayerActivity.kt').read_text()
        watch = activity.split('private fun startWatchdog(', 1)[1].split('private fun checkGuestFailure(', 1)[0]
        self.assertIn('HandlerThread("kenji-log-watch")', watch)
        self.assertIn('background.postDelayed', watch)
        self.assertIn('runOnUiThread {', watch)
        self.assertIn('synchronized(watchdogLock)', watch)
        stop = activity.split('private fun requestStop(): Thread', 1)[1].split('override fun onBackPressed', 1)[0]
        self.assertIn('watchdogThread?.quitSafely()', stop)
        self.assertIn('synchronized(watchdogLock)', stop)

    def test_verbose_is_opt_in_and_passed_across_processes(self):
        settings = (ROOT / 'patch/android/utils/KenjiLogSettings.kt').read_text()
        self.assertIn('getBoolean(VERBOSE, false)', settings)
        activity = (ROOT / 'patch/android/activities/KenjiPlayerActivity.kt').read_text()
        self.assertIn('putExtra(EXTRA_VERBOSE_LOG,', activity)
        self.assertIn('getBooleanExtra(EXTRA_VERBOSE_LOG, false)', activity)
        ui = (ROOT / 'patch/android/fragments/KernelLogFragment.kt').read_text()
        self.assertIn('KenjiLogSettings.setVerbose', ui)

if __name__ == '__main__':
    unittest.main()

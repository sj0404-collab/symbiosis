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

    def test_report_log_reads_are_bounded(self):
        probe = (ROOT / 'patch/android/utils/KenjiProbeService.kt').read_text()
        self.assertNotIn('file.readLines()', probe)
        self.assertNotIn('file?.takeIf { it.isFile }?.readText()', probe)
        self.assertIn('KernelLogFormat.tailOf(it, KernelLogFormat.TAIL_BYTES)', probe)

    def test_player_metrics_and_compact_controls(self):
        activity = (ROOT / 'patch/android/activities/KenjiPlayerActivity.kt').read_text()
        watch = activity.split('private fun startWatchdog(', 1)[1].split('private fun checkGuestFailure(', 1)[0]
        self.assertIn('rate * 1000.0 / intervalMs', watch)
        self.assertIn('FPS ядра:', watch)
        self.assertIn('callback/с:', watch)
        self.assertIn('updateStatus(if (frames > 0 && showPlayerFps) fpsText', watch)
        self.assertIn('/proc/self/status', watch)
        self.assertIn('setNegativeButton("Выйти из игры")', activity)
        self.assertIn('root.addView(bar, LinearLayout.LayoutParams', activity)
        self.assertNotIn('gameArea.addView(bar', activity)

    def test_exit_query_is_pid_filtered_and_time_checked(self):
        code = (ROOT / 'patch/android/utils/SysExit.kt').read_text()
        self.assertIn('getHistoricalProcessExitReasons(context.packageName, expectedPid, 0)', code)
        self.assertIn('ProcessExitEvidence.matches(expectedPid, expectedStart', code)

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

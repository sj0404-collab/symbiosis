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

if __name__ == '__main__':
    unittest.main()

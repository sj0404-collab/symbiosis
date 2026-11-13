# SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
# SPDX-License-Identifier: GPL-3.0-or-later
from pathlib import Path
import unittest
R=Path(__file__).resolve().parents[1]
class ProcessExitWiring(unittest.TestCase):
 def test_only_matching_exit_record_and_full_description(self):
  system=(R/'patch/android/utils/SysExit.kt').read_text()
  caller=(R/'patch/android/utils/KenjiProbeService.kt').read_text()
  self.assertIn('ProcessExitEvidence.matches(expectedPid, expectedStart, it.pid, it.timestamp, now)',system)
  self.assertIn('SysExit.read(context, fallenPid, pidStart)',caller)
  self.assertIn('метка может относиться к прежнему запуску',caller)
  self.assertIn('она может относиться к прежнему запуску',system)
  self.assertNotIn('snapshot.description.take(120)',system)
 def test_fallback_read_failure_cannot_be_success(self):
  f=(R/'patch/android/utils/CoreFallback.kt').read_text()
  self.assertIn('.getOrNull() == EngineHybrid.Core.EDEN',f)
  self.assertNotIn('.getOrDefault(EngineHybrid.Core.EDEN) == EngineHybrid.Core.EDEN',f)
  self.assertIn('Следующий запуск и работа игры на Eden ещё не проверены',f)
 def test_firmware_summary_is_not_duplicated(self):
  b=(R/'patch/android/utils/KenjiBridge.kt').read_text()
  self.assertIn('firmware.isNotEmpty() && firmware != bridgeReport',b)
  self.assertNotIn('else "профиль Eden пуст"',b)
if __name__=='__main__':unittest.main()

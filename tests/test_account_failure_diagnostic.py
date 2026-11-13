"""Wiring checks: screenshot's guest-exception branch must show evidence."""
from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[1]
ACTIVITY = (ROOT / 'patch/android/activities/KenjiPlayerActivity.kt').read_text()
class AccountFailureDiagnosticTest(unittest.TestCase):
    def test_log_evidence_does_not_stop_live_game_or_mark_fallback(self):
        block = ACTIVITY.split('private fun checkGuestFailure(',1)[1].split('private fun displayRotation(',1)[0]
        self.assertIn('KenjiCrash.diagnosticSummary(fresh)', block)
        self.assertIn('KenjiCrash.panicEvidence(fresh)', block)
        self.assertIn('LOG_OBSERVATION_NO_AUTO_STOP', block)
        self.assertIn('Не подтверждено', block)
        self.assertNotIn('requestStop()', block)
        self.assertNotIn('CoreFallback.note(', block)
        self.assertNotIn('fail(', block)
    def test_apk_audit_checks_current_text_not_removed_claim(self):
        workflow = (ROOT / '.github/workflows/build.yml').read_text()
        self.assertIn('"Скопировать описание"', workflow)
        self.assertIn('"В стеке указана служба учётной записи"', workflow)
        self.assertIn('"причина отсутствия данных не установлена"', workflow)
        self.assertNotIn('"от которого пришёл запрос"', workflow)
    def test_failure_can_be_copied_without_raw_stack(self):
        fail = ACTIVITY.split('private fun fail(msg: String)',1)[1].split('private fun openRom',1)[0]
        self.assertIn('Скопировать описание', fail)
        self.assertIn('newPlainText("Описание остановки", msg)', fail)
if __name__ == '__main__': unittest.main()

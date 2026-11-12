"""Host fixtures verify the account probe rejects incomplete/wrong-process logs."""
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
CHECKER = ROOT / 'tests/player_runtime_probe/check_guest_account_evidence.py'
def lines(mode='normal'):
    return [
        f'BEGIN mode={mode}', f'OPEN mode={mode} rc=0x00000000',
        f'USER_COUNT mode={mode} rc=0x00000000 count=1', f'INIT mode={mode} rc=0x00000000',
        f'CALL_BEGIN mode={mode} phase=early', f'RESULT mode={mode} phase=early rc=0x00000000 locked=0',
        f'CALL_BEGIN mode={mode} phase=late', f'RESULT mode={mode} phase=late rc=0x00000000 locked=1',
        f'COMPLETE mode={mode}',
    ]
class AccountEvidence(unittest.TestCase):
    def check(self, events=None, pid='123', mode='normal', fast=False):
        events = lines(mode) if events is None else events
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'log'; p.write_text(''.join(f'11-12 12:00:{(1 if i < 6 or fast else 19):02d}.000  123  456 W RyujinxLog: ACCOUNT_PROBE_{e}\n' for i,e in enumerate(events)))
            return subprocess.run([sys.executable,str(CHECKER),str(p),pid,mode],capture_output=True,text=True)
    def test_both_modes(self):
        self.assertEqual(self.check().returncode,0)
        self.assertEqual(self.check(mode='pid').returncode,0)
    def test_reject_missing_wrong_process_wrong_order_or_error(self):
        self.assertNotEqual(self.check(pid='999').returncode,0)
        self.assertNotEqual(self.check(lines()[:-1]).returncode,0)
        a=lines(); a[5]=a[5].replace('rc=0x00000000','rc=0x00000412')
        self.assertNotEqual(self.check(a).returncode,0)
        a=lines(); a[4],a[5]=a[5],a[4]
        self.assertNotEqual(self.check(a).returncode,0)
        self.assertNotEqual(self.check(fast=True).returncode,0)
        a=lines(); a[5]=a[5].replace('locked=0','locked=255')
        self.assertNotEqual(self.check(a).returncode,0)
    def test_guest_uses_real_ipc_without_return_shortcut(self):
        src=(ROOT/'tests/guest_account_probe/main.c').read_text()
        self.assertIn('serviceDispatchIn(&service, 140, placeholder, .in_send_pid = true)',src)
        self.assertIn('serviceDispatchOut(&service, 150, locked)',src)
        self.assertIn('serviceDispatchOut(&service, 150, locked, .in_send_pid = true)',src)
        self.assertIn('svcSleepThread(18000000000LL)',src)
if __name__ == '__main__': unittest.main()

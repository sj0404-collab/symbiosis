"""Only the HID evidence parser is tested here; this is not a core test."""
from datetime import datetime, timedelta
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

CHECKER = Path(__file__).parent / 'player_runtime_probe/check_hid_long_evidence.py'

def fixture():
    start = datetime(2026, 11, 12, 8, 0, 0)
    lines = []
    def row(second, message, pid='123'):
        stamp = (start + timedelta(seconds=second)).strftime('%m-%d %H:%M:%S.%f')[:-3]
        lines.append(f'{stamp}  {pid}  456 I RyujinxLog: {message}\n')
    row(0, 'HID_LONG_BEGIN')
    for n in range(23):
        row(n, f'HID_LONG_SAMPLE second={n} connected=1 pad_style=0x00000001 style0=0x00000001 buttons=0x0000000000000000')
        if n == 18:
            for slot in range(4, 9):
                row(n, f'HID_LONG_ACQUIRE slot={slot} rc=0x00000000 module=0 description=0')
    row(22, 'HID_LONG_COMPLETE')
    return lines

class HidEvidenceTest(unittest.TestCase):
    def check(self, lines, pid='123'):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / 'log'; p.write_text(''.join(lines))
            return subprocess.run([sys.executable, str(CHECKER), str(p), pid], capture_output=True, text=True)
    def test_complete(self): self.assertEqual(self.check(fixture()).returncode, 0)
    def test_wrong_pid_or_missing_sample_fails(self):
        self.assertNotEqual(self.check(fixture(), '321').returncode, 0)
        self.assertNotEqual(self.check([x for x in fixture() if 'second=18 ' not in x]).returncode, 0)
    def test_unstable_or_failed_acquire_fails(self):
        self.assertNotEqual(self.check([x.replace('second=18 connected=1', 'second=18 connected=0') for x in fixture()]).returncode, 0)
        self.assertNotEqual(self.check([x.replace('slot=7 rc=0x00000000', 'slot=7 rc=0x00058cca') for x in fixture()]).returncode, 0)
    def test_short_or_wrong_order_fails(self):
        lines = fixture()
        lines = [x.replace('08:00:22.000', '08:00:02.000') for x in lines]
        self.assertNotEqual(self.check(lines).returncode, 0)
        lines = fixture(); index = next(i for i, x in enumerate(lines) if 'HID_LONG_ACQUIRE slot=4' in x)
        lines[index], lines[index+1] = lines[index+1], lines[index]
        self.assertNotEqual(self.check(lines).returncode, 0)

if __name__ == '__main__': unittest.main()

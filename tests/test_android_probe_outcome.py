#!/usr/bin/env python3
"""Regression tests for repeated Activity starts in the disposable probe."""
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = (Path(__file__).resolve().parents[1] / 'tests/android_core_probe/run.sh').read_text()


class ProbeRestartTest(unittest.TestCase):
    def test_restarts_are_not_silently_reported_as_timeout(self):
        start = SCRIPT.index('    starts=$(grep -c "START core=$core " "$log" || true)')
        end = SCRIPT.index('    if grep -q "PROBE_OK core=$core " "$log";', start)
        snippet = SCRIPT[start:end]
        with tempfile.TemporaryDirectory() as tmp:
            log = Path(tmp) / 'log.txt'
            for count, expected in ((0, 'timeout'), (1, 'timeout'), (2, 'probe-restarted')):
                log.write_text(('I/SymCoreProbe: START core=sym supportedAbis=arm64\n' * count), encoding='utf-8')
                shell = ('set -eu; core=sym; log=$1; outcome=timeout; '
                         'for i in 1; do\n' + snippet + '\ndone; echo "$outcome"')
                got = subprocess.check_output(['bash', '-c', shell, 'probe-test', str(log)], text=True).strip()
                self.assertEqual(got, expected)

    def test_only_real_crash_report_upgrades_restart_to_native_crash(self):
        start = SCRIPT.index('  if [ "$outcome" = probe-restarted ] && grep -q')
        end = SCRIPT.index('\n  if [ "$core" = mock ]', start)
        snippet = SCRIPT[start:end]
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'sym-probe-crash.txt'
            for data, expected in (('no signal report\n', 'probe-restarted'),
                                   ('crash signal=6 name=SIGABRT\n', 'native-crash')):
                path.write_text(data, encoding='utf-8')
                shell = ('set -eu; PROBE_WORK=$1; core=sym; outcome=probe-restarted;\n'
                         + snippet + '\necho "$outcome"')
                got = subprocess.check_output(['bash', '-c', shell, 'probe-test', tmp], text=True).strip()
                self.assertEqual(got, expected)


if __name__ == '__main__':
    unittest.main()

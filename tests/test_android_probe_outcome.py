#!/usr/bin/env python3
"""Check standalone Android probe correctly detects process death."""
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
HELPER = ROOT / 'tests/android_core_probe/outcome.sh'


class OutcomeTest(unittest.TestCase):
    def classify(self, text):
        with tempfile.NamedTemporaryFile(mode='w', encoding='utf-8') as log:
            log.write(text)
            log.flush()
            return subprocess.run(['bash', '-c', 'source "$1"; probe_terminal_failure "$2"',
                                   'test', str(HELPER), log.name], capture_output=True, text=True)

    def test_process_death_distinct_from_cause(self):
        line = '11-07 19:29:40.895 I/ActivityManager( 1272): Process dev.symbiosis.coreprobe (pid 5908) has died: fore TOP\n'
        result = self.classify(line)
        self.assertEqual(result.returncode, 0)
        self.assertEqual(result.stdout.strip(), 'process-death')

    def test_native_and_java_failures(self):
        self.assertEqual(self.classify('F libc: Fatal signal 6 (SIGABRT) in tid 44 (iosis.coreprobe)\n').stdout.strip(), 'native-crash')
        self.assertEqual(self.classify('E SymCoreProbe: PROBE_FAIL class missing\n').stdout.strip(), 'fail')

    def test_unrelated_process_and_success_are_not_failure(self):
        self.assertNotEqual(self.classify('I/ActivityManager: Process dev.other (pid 1) has died\n').returncode, 0)
        self.assertNotEqual(self.classify('I/SymCoreProbe: PROBE_OK core=mock javaInitialize=returned-success\n').returncode, 0)


class RestartTest(unittest.TestCase):
    def test_repeated_activity_start_is_not_silently_reported_as_timeout(self):
        script = (ROOT / 'tests/android_core_probe/run.sh').read_text()
        start = script.index('    starts=$(grep -c "START core=$core " "$log" || true)')
        end = script.index('    if grep -q "PROBE_OK core=$core " "$log";', start)
        snippet = script[start:end]
        with tempfile.TemporaryDirectory() as tmp:
            log = Path(tmp) / 'log.txt'
            for count, expected in ((0, 'timeout'), (1, 'timeout'), (2, 'probe-restarted')):
                log.write_text('I/SymCoreProbe: START core=sym supportedAbis=arm64\n' * count)
                shell = ('set -eu; core=sym; log=$1; outcome=timeout; for i in 1; do\n'
                         + snippet + '\ndone; echo "$outcome"')
                got = subprocess.check_output(['bash', '-c', shell, 'test', str(log)], text=True).strip()
                self.assertEqual(got, expected)

    def test_only_real_crash_report_upgrades_restart_to_native_crash(self):
        script = (ROOT / 'tests/android_core_probe/run.sh').read_text()
        start = script.index('  if [ "$outcome" = probe-restarted ] && grep -q')
        end = script.index('\n  if [ "$core" = mock ]', start)
        snippet = script[start:end]
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'sym-probe-crash.txt'
            for data, expected in (('no signal report\n', 'probe-restarted'),
                                   ('crash signal=6 name=SIGABRT\n', 'native-crash')):
                path.write_text(data)
                shell = ('set -eu; PROBE_WORK=$1; core=sym; outcome=probe-restarted;\n'
                         + snippet + '\necho "$outcome"')
                got = subprocess.check_output(['bash', '-c', shell, 'test', tmp], text=True).strip()
                self.assertEqual(got, expected)


if __name__ == '__main__':
    unittest.main()

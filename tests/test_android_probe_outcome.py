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


if __name__ == '__main__':
    unittest.main()

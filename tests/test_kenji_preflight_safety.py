#!/usr/bin/env python3
"""Regression: diagnostics must not invoke seccomp-risky raw syscall 283."""
from pathlib import Path
import re
import unittest


class PreflightSafetyTest(unittest.TestCase):
    def test_no_raw_syscall_in_preflight_and_skipped_is_explicit(self):
        source = (Path(__file__).resolve().parents[1] / 'patch/symbiosis/kenji_bridge.cpp').read_text()
        start = source.index('void PalPreflight() {')
        end = source.index('// ── журнал вызовов окружения', start)
        body = source[start:end]
        # Match calls, not the explanation comment mentioning syscall.
        self.assertNotRegex(body, r'::\s*syscall\s*\(')
        self.assertIn('membarrier=not-probed', body)
        self.assertIn('__system_property_get("ro.build.version.sdk"', body)


if __name__ == '__main__':
    unittest.main()

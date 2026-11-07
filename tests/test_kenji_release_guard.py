#!/usr/bin/env python3
"""Tests for the release safety gate, using synthetic source snippets."""
from pathlib import Path
import importlib.util
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('release_guard', ROOT / 'tools/check_kenji_release_ready.py')
guard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guard)


class ReleaseGuardTest(unittest.TestCase):
    def test_known_empty_patterns_are_rejected(self):
        cpp = '''void setRenderingThread() { /* original placeholder */ }
                 void setCurrentTransform(long p, int v) { (void)p; (void)v; }'''
        kotlin = 'fun updateUiHandler(a: Long) { /* software keyboard — ignore */ }'
        self.assertEqual(len(guard.check_sources(cpp, kotlin)), 3)

    def test_implemented_bodies_are_not_mistaken_for_empty(self):
        cpp = '''void setRenderingThread() { doRealWork(); }
                 void setCurrentTransform(long p, int v) { if (p) { realTransform(p,v); } }'''
        kotlin = 'fun updateUiHandler(a: Long) { deliver(a) }'
        self.assertEqual(guard.check_sources(cpp, kotlin), [])

    def test_nonempty_callback_does_not_remove_explicit_integration_blockers(self):
        cpp = 'void setRenderingThread() { doWork(); } void setCurrentTransform() { doWork(); }'
        kotlin = 'fun updateUiHandler() { partialWork() }'
        self.assertEqual(guard.check_sources(cpp, kotlin), [])
        self.assertTrue(any('UI ядра' in problem for problem in guard.UNRESOLVED_INTEGRATION))
        self.assertTrue(any('графические callback' in problem for problem in guard.UNRESOLVED_INTEGRATION))
        # Even if all shallow empty-body checks pass, main must fail closed.
        with patch.object(guard, 'check_sources', return_value=[]), patch('sys.stderr'):
            self.assertEqual(guard.main(), 1)

    def test_missing_functions_fail_closed(self):
        self.assertEqual(len(guard.check_sources('', '')), 3)

    def test_comment_only_nested_markers_not_code(self):
        cpp = 'void setRenderingThread() { // nothing\n } void setCurrentTransform() { /* nothing */ }'
        kotlin = 'fun updateUiHandler() { // nothing\n }'
        self.assertEqual(len(guard.check_sources(cpp, kotlin)), 3)


if __name__ == '__main__':
    unittest.main()

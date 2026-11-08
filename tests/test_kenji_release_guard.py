#!/usr/bin/env python3
"""Tests for the release safety gate, using synthetic source snippets."""
from pathlib import Path
import importlib.util
import struct
import tempfile
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

    def test_bundled_real_tls_has_4k_segments_and_is_blocked(self):
        errors = guard.check_tls_alignment(ROOT / 'patch/libs/arm64-v8a')
        self.assertEqual(len(errors), 2)
        self.assertTrue(all('16 КБ' in error for error in errors))

    def test_elf_alignment_requires_real_16k_arm64_load_segments(self):
        def elf(alignment, offset=0, address=0, kind=1):
            header = bytearray(64)
            header[:6] = b'\x7fELF\x02\x01'
            struct.pack_into('<H', header, 18, 183)
            struct.pack_into('<Q', header, 32, 64)
            struct.pack_into('<HH', header, 54, 56, 1)
            return bytes(header) + struct.pack('<IIQQQQQQ', kind, 5, offset, address, 0, 1, 1, alignment)
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            a, b = directory / 'libcrypto.so', directory / 'libssl.so'
            # Missing/malformed files cannot pass.
            self.assertEqual(len(guard.check_tls_alignment(directory)), 2)
            a.write_bytes(elf(16384)); b.write_bytes(elf(16384))
            self.assertEqual(guard.check_tls_alignment(directory), [])
            a.write_bytes(elf(4096))
            b.write_bytes(elf(16384, offset=4096, address=0))
            self.assertEqual(len(guard.check_tls_alignment(directory)), 2)
            a.write_bytes(elf(16384, kind=2))
            b.write_bytes(b'not ELF')
            self.assertEqual(len(guard.check_tls_alignment(directory)), 2)

    def test_missing_functions_fail_closed(self):
        self.assertEqual(len(guard.check_sources('', '')), 3)

    def test_comment_only_nested_markers_not_code(self):
        cpp = 'void setRenderingThread() { // nothing\n } void setCurrentTransform() { /* nothing */ }'
        kotlin = 'fun updateUiHandler() { // nothing\n }'
        self.assertEqual(len(guard.check_sources(cpp, kotlin)), 3)


if __name__ == '__main__':
    unittest.main()

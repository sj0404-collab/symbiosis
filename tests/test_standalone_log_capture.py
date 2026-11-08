#!/usr/bin/env python3
"""Tests for the Linux-only Android log datagram receiver."""
import importlib.util
from pathlib import Path
import socket
import struct
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / 'tests/standalone_core_probe/capture_android_log.py'
spec = importlib.util.spec_from_file_location('capture', SCRIPT)
capture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(capture)


class CaptureTest(unittest.TestCase):
    def test_parse_and_malformed(self):
        packet = struct.pack('<BHII', 0, 123, 456, 789) + b'\x06ART\0Missing property area\0'
        result = capture.describe(packet)
        self.assertIn('tid=123', result)
        self.assertIn("tag='ART'", result)
        self.assertIn('Missing property area', result)
        self.assertTrue(capture.describe(b'\x00').startswith('MALFORMED_LOG'))

    def test_real_local_datagram_and_no_existing_replacement(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'logdw'
            process = subprocess.Popen([sys.executable, '-u', str(SCRIPT), str(path)], stdout=subprocess.PIPE, text=True)
            try:
                self.assertEqual(process.stdout.readline().strip(), 'LOG_RECEIVER_READY')
                again = subprocess.run([sys.executable, str(SCRIPT), str(path)], capture_output=True, text=True)
                self.assertNotEqual(again.returncode, 0)
                with socket.socket(socket.AF_UNIX, socket.SOCK_DGRAM) as writer:
                    writer.sendto(struct.pack('<BHII', 0, 1, 2, 3) + b'\x04TAG\0message\0', str(path))
                self.assertIn("tag='TAG' message='message'", process.stdout.readline())
            finally:
                process.terminate()
                process.wait(timeout=5)
                process.stdout.close()


if __name__ == '__main__':
    unittest.main()

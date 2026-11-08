#!/usr/bin/env python3
"""Host tests for binderfs ioctl packing; no Binder emulation in runtime."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('binderfs_create', ROOT / 'tests/standalone_core_probe/binderfs_create.py')
binder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(binder)


class BinderFsTest(unittest.TestCase):
    def test_ioctl_input_and_real_result_parsing(self):
        with tempfile.NamedTemporaryFile() as control:
            def fake_ioctl(fd, command, buffer, mutate):
                self.assertEqual(command, 0xc1086201)
                self.assertTrue(mutate)
                name, major, minor = binder.DEVICE_STRUCT.unpack(buffer)
                self.assertEqual(name.split(b'\0')[0], b'binder')
                self.assertEqual((major, minor), (0, 0))
                buffer[:] = binder.DEVICE_STRUCT.pack(b'binder', 511, 7)
                return 0
            with mock.patch.object(binder.fcntl, 'ioctl', side_effect=fake_ioctl):
                self.assertEqual(binder.allocate(control.name, 'binder'), (511, 7))

    def test_bad_name_and_bad_ioctl_response_rejected(self):
        with tempfile.NamedTemporaryFile() as control:
            for name in ('../binder', '', 'b' * 256, 'é'):
                with self.assertRaises(ValueError):
                    binder.allocate(control.name, name)
            def invalid(fd, command, buffer, mutate):
                buffer[:] = binder.DEVICE_STRUCT.pack(b'binder', 0, 0)
            with mock.patch.object(binder.fcntl, 'ioctl', side_effect=invalid):
                with self.assertRaises(ValueError):
                    binder.allocate(control.name, 'binder')


if __name__ == '__main__':
    unittest.main()

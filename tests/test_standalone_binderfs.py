#!/usr/bin/env python3
"""Host tests for binderfs ioctl packing; no Binder emulation in runtime."""
import importlib.util
import os
from pathlib import Path
import stat
import tempfile
from types import SimpleNamespace
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

    def test_existing_kernel_character_node_may_be_reused(self):
        with tempfile.NamedTemporaryFile() as control:
            node = SimpleNamespace(st_mode=stat.S_IFCHR | 0o600, st_rdev=os.makedev(511, 7))
            with mock.patch.object(binder.fcntl, 'ioctl', side_effect=FileExistsError(17, 'exists')):
                with mock.patch.object(binder.os, 'stat', return_value=node) as read_node:
                    self.assertEqual(binder.allocate(control.name, 'binder'), (511, 7))
                    read_node.assert_called_once_with(Path(control.name).parent / 'binder', follow_symlinks=False)

    def test_existing_regular_file_or_symlink_rejected(self):
        with tempfile.NamedTemporaryFile() as control:
            for mode in (stat.S_IFREG | 0o600, stat.S_IFLNK | 0o777, stat.S_IFCHR | 0o600):
                node = SimpleNamespace(st_mode=mode, st_rdev=os.makedev(0, 1))
                with mock.patch.object(binder.fcntl, 'ioctl', side_effect=FileExistsError(17, 'exists')):
                    with mock.patch.object(binder.os, 'stat', return_value=node):
                        with self.assertRaises(ValueError):
                            binder.allocate(control.name, 'binder')

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

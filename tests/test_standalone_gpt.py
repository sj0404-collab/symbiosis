#!/usr/bin/env python3
"""Synthetic GPT inputs for the diagnostic image partition locator."""
import importlib.util
from pathlib import Path
import struct
import tempfile
import unittest
import zlib

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('gpt', ROOT / 'tests/standalone_core_probe/gpt_offset.py')
gpt = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gpt)


def image(path, names=('system',)):
    total_sectors = 4096
    entries = bytearray(128 * 8)
    for i, name in enumerate(names):
        base = i * 128
        entries[base:base + 16] = bytes([i + 1]) * 16
        struct.pack_into('<QQQ', entries, base + 32, 256 + i * 100, 300 + i * 100, 0)
        label = name.encode('utf-16le')
        entries[base + 56:base + 56 + len(label)] = label
    header = bytearray(512)
    struct.pack_into('<8sIIIIQQQQ16sQIII', header, 0, b'EFI PART', 0x10000, 92, 0, 0,
                     1, total_sectors - 1, 34, total_sectors - 34, bytes(16),
                     2, 8, 128, zlib.crc32(entries))
    struct.pack_into('<I', header, 16, zlib.crc32(header[:92]))
    with open(path, 'wb') as f:
        f.truncate(total_sectors * 512)
        f.seek(512)
        f.write(header)
        f.write(entries)


class GptOffsetTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.path = Path(self.tmp.name) / 'disk.img'

    def test_valid_system_partition(self):
        image(self.path, ('other', 'system'))
        self.assertEqual(gpt.system_offset(self.path), 356 * 512)

    def test_bad_header_crc_rejected(self):
        image(self.path)
        with self.path.open('r+b') as f:
            f.seek(512 + 20)
            f.write(b'\x01')
        with self.assertRaises(ValueError):
            gpt.system_offset(self.path)

    def test_bad_table_crc_rejected(self):
        image(self.path)
        with self.path.open('r+b') as f:
            f.seek(1024 + 56)
            f.write(b'\x01')
        with self.assertRaisesRegex(ValueError, 'table CRC'):
            gpt.system_offset(self.path)

    def test_missing_or_duplicate_system_rejected(self):
        for names in (('vendor',), ('system', 'system')):
            image(self.path, names)
            with self.assertRaisesRegex(ValueError, 'exactly one'):
                gpt.system_offset(self.path)


if __name__ == '__main__':
    unittest.main()

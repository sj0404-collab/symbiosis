#!/usr/bin/env python3
"""Check CPU-check instruction fingerprints of two hash-pinned ARM64 cores.

Offsets and fingerprints refer only to these files. This does not execute the
instructions, bypass their check, identify the real phone's HWCAP, or prove a
game works. The mapping is independently exercised against synthetic bitsets.
"""
import hashlib
import os
from pathlib import Path
import struct
import unittest
import zipfile

SYM_SHA = '969431b3962408dd02ce38bd2cd4b2a954d11e5212ddef3f65c80a0b419918b8'
SPACE_SHA = 'd781048671e4cef1cde2ec15db8fe29b949df9949d83569ceaf776ad12901590'
APK_SHA = 'f9eb3ecfaf5700df1b5d575984ab1588848c0eb6a70b2acb19b9e6243705f95e'


def read_vaddr(data, address, size):
    if data[:6] != b'\x7fELF\x02\x01' or struct.unpack_from('<H', data, 18)[0] != 183:
        raise ValueError('not ELF64 AArch64')
    phoff = struct.unpack_from('<Q', data, 32)[0]
    entsize, count = struct.unpack_from('<HH', data, 54)
    for i in range(count):
        ptype, _, offset, vaddr, _, filesz, _, _ = struct.unpack_from('<IIQQQQQQ', data, phoff + i * entsize)
        if ptype == 1 and vaddr <= address and address + size <= vaddr + filesz:
            return data[offset + address - vaddr:offset + address - vaddr + size]
    raise ValueError('address not in a file-backed load segment')


def candidate_required_features(hwcap):
    # Individual result bits extracted from the two pinned ARM64 functions;
    # 0x95 is the constant compared with their full computed feature mask.
    return (((hwcap >> 1) & 0x01) | ((hwcap >> 5) & 0x04) |
            ((hwcap >> 8) & 0x10) | ((hwcap >> 1) & 0x80))


class SyntheticCapabilitiesTest(unittest.TestCase):
    def test_required_bits_and_one_missing_at_a_time(self):
        needed = (1 << 1) | (1 << 7) | (1 << 8) | (1 << 12)
        self.assertEqual(candidate_required_features(needed), 0x95)
        for bit in (1, 7, 8, 12):
            self.assertNotEqual(candidate_required_features(needed & ~(1 << bit)), 0x95)
        for bit in set(range(32)) - {1, 7, 8, 12}:
            self.assertEqual(candidate_required_features(needed | (1 << bit)), 0x95)


@unittest.skipUnless(os.environ.get('SYMBEK_APK') and os.environ.get('SYMBIOSIS_CORE'),
                     'set SYMBEK_APK and SYMBIOSIS_CORE to pinned artifacts')
class BinaryIsaEvidenceTest(unittest.TestCase):
    def test_fingerprints_required_mask_and_vector_constants(self):
        apk = Path(os.environ['SYMBEK_APK']).read_bytes()
        sym = Path(os.environ['SYMBIOSIS_CORE']).read_bytes()
        self.assertEqual(hashlib.sha256(apk).hexdigest(), APK_SHA)
        self.assertEqual(hashlib.sha256(sym).hexdigest(), SYM_SHA)
        with zipfile.ZipFile(os.environ['SYMBEK_APK']) as archive:
            space = archive.read('lib/arm64-v8a/libkenjinx.so')
        self.assertEqual(hashlib.sha256(space).hexdigest(), SPACE_SHA)
        refs = (
            (sym, 0x173c060, '105edfdf39928b185d41d595021e29eca3ad5294450b3e65db66d14aab4ba5a9',
             0x1704470, 'ba0c2650d3aa29c882ba74a9d4e3c5e605cff0d94d9d25a4e75f381cbd088b45',
             0x9c2d68, 0xefde70, 0xefdf40),
            (space, 0x1347540, 'd43ed0c9af96dde8cba79564c3af70ac813e3bc5fdee42d72cf80a8502f3e885',
             0x130f950, 'db21537dc1fa849577ce99c5a352ac7e3ab67b1521c3ec8a108ec365aacd6640',
             0x89a92c, 0xdce720, 0xdce7f0),
        )
        for data, function, function_sha, check, check_sha, mask, shifts, ands in refs:
            self.assertEqual(hashlib.sha256(read_vaddr(data, function, 132)).hexdigest(), function_sha)
            self.assertEqual(hashlib.sha256(read_vaddr(data, check, 56)).hexdigest(), check_sha)
            self.assertEqual(struct.unpack('<I', read_vaddr(data, mask, 4))[0], 0x95)
            self.assertEqual(struct.unpack('<4i', read_vaddr(data, shifts, 16)), (-2, -5, -17, -7))
            self.assertEqual(struct.unpack('<4I', read_vaddr(data, ands, 16)), (2, 4, 8, 256))


if __name__ == '__main__':
    unittest.main()

#!/usr/bin/env python3
"""Inspect direct DEX references in the user's hash-pinned SymbEK APK.

A method-id/code-unit scan is deliberately conservative: every code unit is
considered a potential invoke, so false positives are possible. An empty result
means no conventional direct invoke was found, not that reflection, JNI or
invoke-custom cannot reach the method. The APK is never redistributed here.
"""
import functools
import hashlib
import os
from pathlib import Path
import struct
import unittest
import zipfile

APK_SHA256 = 'f9eb3ecfaf5700df1b5d575984ab1588848c0eb6a70b2acb19b9e6243705f95e'
HELPERS = 'Lorg/kenjinx/android/NativeHelpers;'
NATIVE = 'Lorg/kenjinx/android/KenjinxNative;'


def direct_invoke_candidates(data, wanted):
    if not data.startswith(b'dex\n'):
        raise ValueError('not a DEX file')
    u16 = lambda offset: struct.unpack_from('<H', data, offset)[0]
    u32 = lambda offset: struct.unpack_from('<I', data, offset)[0]
    strings, types = u32(0x3c), u32(0x44)
    method_count, method_offset = u32(0x58), u32(0x5c)
    class_count, class_offset = u32(0x60), u32(0x64)

    @functools.lru_cache(None)
    def string(index):
        offset = u32(strings + index * 4)
        while data[offset] & 0x80:
            offset += 1
        offset += 1
        return data[offset:data.index(b'\0', offset)].decode('utf-8', 'replace')

    @functools.lru_cache(None)
    def type_name(index):
        return string(u32(types + index * 4))

    def method(index):
        owner, _, method_name = struct.unpack_from('<HHI', data, method_offset + index * 8)
        return type_name(owner), string(method_name)

    targets = {i: method(i) for i in range(method_count) if method(i) in wanted}
    hits = []
    if not targets:
        return hits

    def uleb(offset):
        value, shift = 0, 0
        while True:
            byte = data[offset]
            offset += 1
            value |= (byte & 0x7f) << shift
            if not byte & 0x80:
                return value, offset
            shift += 7
            if shift >= 35:
                raise ValueError('bad ULEB128')

    for i in range(class_count):
        class_def = class_offset + 32 * i
        owner = type_name(u32(class_def))
        pos = u32(class_def + 24)
        if not pos:
            continue
        counts = []
        for _ in range(4):
            n, pos = uleb(pos)
            counts.append(n)
        for _ in range(counts[0] + counts[1]):
            _, pos = uleb(pos)
            _, pos = uleb(pos)
        for count in counts[2:]:
            method_index = 0
            for _ in range(count):
                delta, pos = uleb(pos)
                method_index += delta
                _, pos = uleb(pos)  # access flags
                code, pos = uleb(pos)
                if not code:
                    continue
                size = u32(code + 12)
                start = code + 16
                for unit in range(size - 1):
                    op = data[start + unit * 2]
                    # invoke-kind and invoke-kind/range, all 3 code units.
                    if op in (*range(0x6e, 0x73), *range(0x74, 0x79)):
                        called = u16(start + unit * 2 + 2)
                        if called in targets:
                            hits.append((owner, method(method_index)[1], targets[called]))
    return hits


@unittest.skipUnless(os.environ.get('SYMBEK_APK'), 'set SYMBEK_APK to the pinned APK')
class SymbekGraphicsDexTest(unittest.TestCase):
    def test_no_conventional_orientation_setter_call_and_positive_control(self):
        apk = Path(os.environ['SYMBEK_APK'])
        self.assertEqual(hashlib.sha256(apk.read_bytes()).hexdigest(), APK_SHA256)
        orientation = (HELPERS, 'setIsInitialOrientationFlipped')
        ui_setup = (NATIVE, 'uiHandlerSetup')
        hits = []
        with zipfile.ZipFile(apk) as z:
            for name in ('classes.dex', 'classes2.dex', 'classes3.dex', 'classes4.dex'):
                hits += direct_invoke_candidates(z.read(name), {orientation, ui_setup})
        self.assertFalse([hit for hit in hits if hit[2] == orientation])
        self.assertIn(('Lorg/kenjinx/android/UiHandler;', '<init>', ui_setup), hits)


if __name__ == '__main__':
    unittest.main()

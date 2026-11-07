#!/usr/bin/env python3
"""Inspect pinned SymbEK APK and Symbiosis core; does not execute Android code.

Usage: SYMBEK_APK=/path/to/Kenji-Space-1.0.121.apk \
       SYMBIOSIS_CORE=/path/to/libkenji.so python3 tests/test_symbek_reference.py
Artifacts are intentionally not committed to the repository.
"""
import functools
import hashlib
import os
from pathlib import Path
import struct
import subprocess
import tempfile
import unittest
import zipfile

APK_HASH = 'f9eb3ecfaf5700df1b5d575984ab1588848c0eb6a70b2acb19b9e6243705f95e'
SPACE_CORE_HASH = 'd781048671e4cef1cde2ec15db8fe29b949df9949d83569ceaf776ad12901590'
SYMBIOSIS_CORE_HASH = '969431b3962408dd02ce38bd2cd4b2a954d11e5212ddef3f65c80a0b419918b8'
REQUIRED_EXPORTS = {
    'javaInitialize', 'deviceInitialize', 'graphicsInitialize',
    'graphicsInitializeRenderer', 'deviceLoadDescriptor', 'graphicsRendererRunLoop',
    'deviceSignalEmulationClose', 'deviceCloseEmulation',
    'detachWindow', 'graphicsSetPresentEnabled', 'deviceSetWindowHandle',
    'deviceRecreateSwapchain', 'graphicsRendererSetSize',
    'inputInitialize', 'inputConnectGamepad', 'inputUpdate',
    'inputSetButtonPressed', 'inputSetButtonReleased', 'inputSetStickAxis',
    'inputSetTouchPoint', 'inputReleaseTouchPoint', 'inputSetClientSize',
    'deviceSetSurfaceRotation', 'deviceGetGameFrameRate', 'deviceGetInstalledFirmwareVersion',
    'graphicsSetFullscreenStretch', 'userGetOpenedUser',
    'loggingSetEnabled', 'loggingEnabledGraphicsLog',
    'uiHandlerSetup', 'uiHandlerSetResponse',
}


def digest(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()


def dex_classes_and_methods(data, wanted):
    """Read DEX class definitions and their declared method signatures."""
    assert data.startswith(b'dex\n')
    u32 = lambda off: struct.unpack_from('<I', data, off)[0]
    strings_off = u32(0x3c)
    types_off = u32(0x44)
    protos_off = u32(0x4c)
    methods_size, methods_off = u32(0x58), u32(0x5c)
    classes_size, classes_off = u32(0x60), u32(0x64)

    @functools.lru_cache(maxsize=None)
    def string(index):
        offset = u32(strings_off + index * 4)
        while data[offset] & 0x80:
            offset += 1
        offset += 1
        return data[offset:data.index(b'\0', offset)].decode('utf-8', errors='replace')

    @functools.lru_cache(maxsize=None)
    def type_name(index):
        return string(u32(types_off + index * 4))

    @functools.lru_cache(maxsize=None)
    def prototype(index):
        _, result, parameters = struct.unpack_from('<III', data, protos_off + index * 12)
        args = ''
        if parameters:
            args = ''.join(type_name(struct.unpack_from('<H', data, parameters + 4 + i * 2)[0])
                           for i in range(u32(parameters)))
        return '(' + args + ')' + type_name(result)

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
                raise ValueError('invalid DEX uleb128')

    defined, methods = set(), set()
    for i in range(classes_size):
        class_def = classes_off + 32 * i
        cls = type_name(u32(class_def))
        if cls not in wanted:
            continue
        defined.add(cls)
        pos = u32(class_def + 24)  # class_data_off
        if not pos:
            continue
        counts = []
        for _ in range(4):
            n, pos = uleb(pos)
            counts.append(n)
        static_fields, instance_fields, direct_methods, virtual_methods = counts
        for _ in range(static_fields + instance_fields):
            _, pos = uleb(pos)  # field_idx_diff
            _, pos = uleb(pos)  # access_flags
        for count in (direct_methods, virtual_methods):
            method_index = 0
            for _ in range(count):
                delta, pos = uleb(pos)
                method_index += delta
                _, pos = uleb(pos)  # access_flags
                _, pos = uleb(pos)  # code_off
                if method_index >= methods_size:
                    raise ValueError('invalid DEX method index')
                class_id, proto_id, name_id = struct.unpack_from(
                    '<HHI', data, methods_off + 8 * method_index)
                if type_name(class_id) != cls:
                    raise ValueError('method does not belong to its class')
                methods.add((cls, string(name_id), prototype(proto_id)))
    return defined, methods


def elf_check(test, path):
    with open(path, 'rb') as f:
        header = f.read(64)
        test.assertEqual(header[:6], b'\x7fELF\x02\x01')  # ELF64, little endian
        test.assertEqual(struct.unpack_from('<H', header, 18)[0], 183)  # AArch64
        phoff = struct.unpack_from('<Q', header, 32)[0]
        entsize, count = struct.unpack_from('<HH', header, 54)
        f.seek(phoff)
        ph = f.read(entsize * count)
    loads = []
    for i in range(count):
        ptype, _, offset, vaddr, _, _, _, align = struct.unpack_from('<IIQQQQQQ', ph, i * entsize)
        if ptype == 1:
            loads.append((offset, vaddr, align))
    test.assertTrue(loads)
    for offset, vaddr, align in loads:
        test.assertGreaterEqual(align, 0x4000)
        test.assertEqual(offset % 0x4000, vaddr % 0x4000)
    result = subprocess.run(['nm', '-D', '--defined-only', str(path)],
                            check=True, capture_output=True, text=True)
    names = {line.split()[-1].split('@')[0] for line in result.stdout.splitlines() if line.split()}
    test.assertFalse(REQUIRED_EXPORTS - names, REQUIRED_EXPORTS - names)
    # The SymbEK wrapper catches missing JNA methods. On these pinned cores,
    # an unconditional GPU-wait call would not resolve.
    test.assertNotIn('deviceWaitForGpuDone', names)

    # In both pinned AArch64 binaries the response entry copies argument w0 to
    # w19, x1 to x20, and later zero-extends w19's low byte. This checks the
    # machine-code evidence for two arguments and bool-like first argument;
    # it does not establish the encoding or prove the whole runtime ABI.
    symbols = {line.split()[-1].split('@')[0]: int(line.split()[0], 16)
               for line in result.stdout.splitlines() if len(line.split()) >= 3}
    addr = symbols['uiHandlerSetResponse']
    with open(path, 'rb') as f:
        f.seek(phoff)
        programs = f.read(entsize * count)
        for i in range(count):
            ptype, _, offset, vaddr, _, filesz, _, _ = struct.unpack_from(
                '<IIQQQQQQ', programs, i * entsize)
            if ptype == 1 and vaddr <= addr and addr + 44 <= vaddr + filesz:
                f.seek(offset + addr - vaddr)
                code = f.read(44)
                break
        else:
            test.fail('response export is not in a file-backed LOAD segment')
    test.assertEqual(code[12:16], bytes.fromhex('f303002a'))  # mov w19, w0
    test.assertEqual(code[16:20], bytes.fromhex('f40301aa'))  # mov x20, x1
    test.assertEqual(code[40:44], bytes.fromhex('601e0053'))  # uxtb w0, w19


@unittest.skipUnless(os.environ.get('SYMBEK_APK') and os.environ.get('SYMBIOSIS_CORE'),
                     'set SYMBEK_APK and SYMBIOSIS_CORE to run binary checks')
class ReferenceArtifacts(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.apk = Path(os.environ['SYMBEK_APK'])
        cls.sym_core = Path(os.environ['SYMBIOSIS_CORE'])

    def test_release_and_both_core_hashes(self):
        self.assertEqual(digest(self.apk), APK_HASH)
        self.assertEqual(digest(self.sym_core), SYMBIOSIS_CORE_HASH)
        with zipfile.ZipFile(self.apk) as z:
            self.assertEqual(hashlib.sha256(z.read('lib/arm64-v8a/libkenjinx.so')).hexdigest(),
                             SPACE_CORE_HASH)
        self.assertNotEqual(SPACE_CORE_HASH, SYMBIOSIS_CORE_HASH)

    def test_class_definitions_and_relevant_dex_signatures(self):
        game = 'Lorg/kenjinx/android/GameHost;'
        native = 'Lorg/kenjinx/android/KenjinxNative;'
        wanted = {game, native, 'Lorg/kenjinx/android/KenjinxNativeJna;',
                  'Lorg/kenjinx/android/NativeWindow;', 'Ldev/symbiosis/kenji/PadDeck;',
                  'Ldev/symbiosis/kenji/PadBridge;', 'Ldev/symbiosis/kenji/SettingsBank;',
                  'Ldev/symbiosis/kenji/LoadOverlay;', 'Lorg/kenjinx/android/NativeHelpers;',
                  'Lorg/kenjinx/android/UiHandler;'}
        classes, methods = set(), set()
        with zipfile.ZipFile(self.apk) as z:
            for name in ('classes.dex', 'classes2.dex', 'classes3.dex', 'classes4.dex'):
                found, signatures = dex_classes_and_methods(z.read(name), wanted)
                classes.update(found)
                methods.update(signatures)
        self.assertEqual(classes, wanted)
        for signature in (
            (game, 'surfaceChanged', '(Landroid/view/SurfaceHolder;III)V'),
            (native, 'deviceInitialize', '(IZIIIIZZZZZZILjava/lang/String;Z)Z'),
            (native, 'graphicsInitialize', '(FFZZZZZZI)Z'),
            (native, 'graphicsInitializeRenderer', '([Ljava/lang/String;IJ)Z'),
            (native, 'deviceLoadDescriptor', '(III)Z'),
            (native, 'deviceSignalEmulationClose', '()V'),
            (native, 'deviceCloseEmulation', '()V'),
            (native, 'deviceWaitForGpuDone', '(I)V'),
            (native, 'detachWindow', '()V'),
            (native, 'inputConnectGamepad', '(I)I'),
            (native, 'inputInitialize', '(II)V'),
            (native, 'inputUpdate', '()V'),
            (native, 'inputSetButtonPressed', '(II)V'),
            (native, 'inputSetButtonReleased', '(II)V'),
            (native, 'inputSetStickAxis', '(IFFI)V'),
            (native, 'inputSetTouchPoint', '(II)V'),
            (native, 'inputReleaseTouchPoint', '()V'),
            (native, 'inputSetClientSize', '(II)V'),
            (native, 'deviceSetSurfaceRotation', '(I)V'),
            (native, 'deviceSetWindowHandle', '(J)V'),
            (native, 'deviceRecreateSwapchain', '()V'),
            (native, 'deviceGetGameFrameRate', '()D'),
            (native, 'deviceGetInstalledFirmwareVersion', '()Ljava/lang/String;'),
            (native, 'graphicsRendererSetSize', '(II)V'),
            (native, 'graphicsSetFullscreenStretch', '(Z)V'),
            (native, 'graphicsSetPresentEnabled', '(Z)V'),
            (native, 'userGetOpenedUser', '()Ljava/lang/String;'),
            (native, 'loggingSetEnabled', '(IZ)V'),
            (native, 'loggingEnabledGraphicsLog', '(Z)V'),
            (native, 'uiHandlerSetup', '()V'),
            (native, 'uiHandlerSetResponse', '(ZLjava/lang/String;)V'),
            (native, 'updateUiHandler', '(JJJIIIIJJ)V'),
            ('Lorg/kenjinx/android/NativeHelpers;', 'getStringJava', '(J)Ljava/lang/String;'),
            ('Lorg/kenjinx/android/UiHandler;', 'update',
             '(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;IIILorg/kenjinx/android/KeyboardMode;Ljava/lang/String;Ljava/lang/String;)V'),
            ('Ldev/symbiosis/kenji/PadBridge;', 'press', '(Ljava/lang/String;)V'),
        ):
            self.assertIn(signature, methods)

    def test_arm64_exports_and_16k_alignment(self):
        elf_check(self, self.sym_core)
        with tempfile.TemporaryDirectory() as d, zipfile.ZipFile(self.apk) as z:
            path = Path(d) / 'libkenjinx.so'
            with z.open('lib/arm64-v8a/libkenjinx.so') as src, path.open('wb') as dst:
                for chunk in iter(lambda: src.read(1024 * 1024), b''):
                    dst.write(chunk)
            elf_check(self, path)


if __name__ == '__main__':
    unittest.main()

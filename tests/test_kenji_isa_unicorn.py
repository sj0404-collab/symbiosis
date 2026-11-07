#!/usr/bin/env python3
"""Emulate only pinned CPU-detection functions with synthetic HWCAP inputs.

This uses Unicorn on the host, not Android or a game. The emulated getauxval
return is deliberately supplied by the test; no binary is changed and no
runtime CPU check is bypassed in any APK. Requires SYMBEK_APK, SYMBIOSIS_CORE
and the test-only Unicorn Python package.
"""
import hashlib
import os
import unittest
import zipfile

from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM, UC_HOOK_CODE
from unicorn.arm64_const import UC_ARM64_REG_X0, UC_ARM64_REG_X30, UC_ARM64_REG_SP, UC_ARM64_REG_PC
from test_kenji_isa_reference import APK_SHA, SYM_SHA, SPACE_SHA, read_vaddr, candidate_required_features


@unittest.skipUnless(os.environ.get('SYMBEK_APK') and os.environ.get('SYMBIOSIS_CORE'), 'pinned artifacts required')
class IsaFunctionEmulationTest(unittest.TestCase):
    def test_cpu_detection_functions_match_required_capabilities(self):
        with open(os.environ['SYMBEK_APK'], 'rb') as file:
            apk = file.read()
        with open(os.environ['SYMBIOSIS_CORE'], 'rb') as file:
            sym = file.read()
        self.assertEqual(hashlib.sha256(apk).hexdigest(), APK_SHA)
        self.assertEqual(hashlib.sha256(sym).hexdigest(), SYM_SHA)
        with zipfile.ZipFile(os.environ['SYMBEK_APK']) as archive:
            space = archive.read('lib/arm64-v8a/libkenjinx.so')
        self.assertEqual(hashlib.sha256(space).hexdigest(), SPACE_SHA)
        configs = (
            (space, 0x1347540, 0xdce720, 0xdce7f0, 0x2d50fc0),
            (sym, 0x173c060, 0xefde70, 0xefdf40, 0x362b6f0),
        )
        for data, entry, shifts, masks, aux_plt in configs:
            engine = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
            mapped = set()

            def put(address, blob):
                for page in range(address & ~4095, (address + len(blob) + 4095) & ~4095, 4096):
                    if page not in mapped:
                        engine.mem_map(page, 4096)
                        mapped.add(page)
                engine.mem_write(address, blob)

            put(entry, read_vaddr(data, entry, 132))
            put(shifts, read_vaddr(data, shifts, 16))
            put(masks, read_vaddr(data, masks, 16))
            put(aux_plt, bytes.fromhex('c0035fd6'))  # trap point, never real core
            engine.mem_map(0x20000000, 0x10000)
            value = [0]
            calls = [0]

            def fake_getauxval(uc, address, size, _user):
                self.assertEqual(uc.reg_read(UC_ARM64_REG_X0), 16)  # AT_HWCAP
                calls[0] += 1
                uc.reg_write(UC_ARM64_REG_X0, value[0])
                uc.reg_write(UC_ARM64_REG_PC, uc.reg_read(UC_ARM64_REG_X30))

            engine.hook_add(UC_HOOK_CODE, fake_getauxval, begin=aux_plt, end=aux_plt)
            needed = (1 << 1) | (1 << 7) | (1 << 8) | (1 << 12)
            cases = [0, needed, 0xffffffff] + [1 << i for i in range(32)]
            cases += [(i * 0x9e3779b1) & 0xffffffff for i in range(512)]
            for caps in cases:
                value[0] = caps
                engine.reg_write(UC_ARM64_REG_SP, 0x2000f000)
                engine.reg_write(UC_ARM64_REG_X30, 0x10000000)
                engine.emu_start(entry, 0x10000000, count=100)
                self.assertEqual(engine.reg_read(UC_ARM64_REG_PC), 0x10000000)
                available = engine.reg_read(UC_ARM64_REG_X0)
                self.assertEqual(available & 0x95, candidate_required_features(caps))
            self.assertEqual(calls[0], len(cases))


if __name__ == '__main__':
    unittest.main()

#!/usr/bin/env python3
"""Host test for the production dlopen error path, not an Android/core test."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
CPP = (ROOT / 'patch/symbiosis/kenji_bridge.cpp').read_text()

class KenjiLoaderError(unittest.TestCase):
    def test_no_forced_success_or_fixed_offset_init_hook(self):
        self.assertNotIn('GuardedInitCallback', CPP)
        self.assertNotIn('ArmInitGuard', CPP)
        self.assertNotIn('0x4091000 + 0x290', CPP)
        self.assertNotIn('SymbiosisMlock', CPP)
        self.assertNotIn('ArmMlockTrap', CPP)
        init = CPP.split('Java_org_yuzu_yuzu_1emu_utils_KenjiBridge_nativeInitialize(', 1)[1]
        init = init.split('Java_org_yuzu_yuzu_1emu_utils_KenjiBridge_nativeUnload(', 1)[0]
        self.assertIn('bool ok = fn(', init)
        self.assertIn('if (!ok)', init)
        self.assertIn('ядро отказалось инициализироваться', init)

    @unittest.skipUnless(shutil.which('g++'), 'requires g++')
    def test_actual_loader_statements_use_one_error_and_keep_message(self):
        body = CPP.split('Java_org_yuzu_yuzu_1emu_utils_KenjiBridge_nativeLoad(', 1)[1]
        body = body.split('Java_org_yuzu_yuzu_1emu_utils_KenjiBridge_nativeSymbolCount(', 1)[0]
        start = body.index('    dlerror();\n    void* handle = dlopen(')
        end = body.index('    env->ReleaseStringUTFChars(jpath, path);', start)
        snippet = body[start:end]
        self.assertEqual(snippet.count('dlerror()'), 3)  # one in comment, clear, get
        source = r'''#include <dlfcn.h>
#include <cassert>
#include <string>
std::string probe(const char* path, bool* loaded) {
''' + snippet + r'''
    *loaded = handle != nullptr;
    if (handle) dlclose(handle);
    return err;
}
int main() {
    bool loaded = true;
    std::string err = probe("/definitely-not-present/symbiosis-core-does-not-exist.so", &loaded);
    assert(!loaded);
    assert(!err.empty());
    assert(err.find("symbiosis-core-does-not-exist.so") != std::string::npos);
    std::string success = probe("libc.so.6", &loaded);
    assert(loaded && success.empty());
}
'''
        with tempfile.TemporaryDirectory() as d:
            cpp, exe = Path(d) / 'test.cpp', Path(d) / 'test'
            cpp.write_text(source)
            subprocess.run(['g++', '-std=c++17', '-Wall', '-Wextra', '-Werror', str(cpp), '-ldl', '-o', str(exe)], check=True)
            subprocess.run([str(exe)], check=True)

if __name__ == '__main__':
    unittest.main()

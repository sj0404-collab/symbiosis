#!/usr/bin/env python3
"""Host-level ownership checks; mocked native window is not an Android test."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
ACTIVITY = (ROOT / 'patch/android/activities/KenjiPlayerActivity.kt').read_text()
BRIDGE_KT = (ROOT / 'patch/android/utils/KenjiBridge.kt').read_text()
BRIDGE_CPP = (ROOT / 'patch/symbiosis/kenji_bridge.cpp').read_text()

class NativeWindowOwnership(unittest.TestCase):
    def test_acquired_window_is_kept_and_invalid_window_is_rejected(self):
        init = ACTIVITY.split('private fun startGame(', 1)[1].split('private fun startWatchdog(', 1)[0]
        self.assertLess(init.index('KenjiBridge.nativeWindow(holder.surface)'), init.index('if (nw <= 0L)'))
        self.assertLess(init.index('if (nw <= 0L)'), init.index('ownedNativeWindow = nw'))
        self.assertLess(init.index('ownedNativeWindow = nw'), init.index('KenjiBridge.preparePlay('))
        self.assertIn('return@Thread', init.split('if (nw <= 0L)', 1)[1].split('ownedNativeWindow = nw', 1)[0])

    def test_release_happens_after_join_and_unload_before_unlock(self):
        destroy = ACTIVITY.split('override fun onDestroy()', 1)[1].split('private fun leave()', 1)[0]
        for before, after in [('stopper.join()', 'KenjiBridge.unload()'),
                              ('thread?.join()', 'KenjiBridge.unload()'),
                              ('KenjiBridge.unload()', 'nativeSurface = -1L'),
                              ('nativeSurface = -1L', 'KenjiBridge.releaseNativeWindow(owned)'),
                              ('KenjiBridge.releaseNativeWindow(owned)', 'lease.close()')]:
            self.assertLess(destroy.index(before), destroy.index(after))
        self.assertIn('nativeWindow = -1L', destroy)
        self.assertIn('if (!KenjiBridge.releaseNativeWindow(owned))', destroy)
        self.assertIn('android.os.Process.killProcess', destroy)
        self.assertIn('private external fun nativeReleaseWindow(handle: Long)', BRIDGE_KT)

    @unittest.skipUnless(shutil.which('g++'), 'host C++ compiler not installed')
    def test_release_function_on_mock_window(self):
        signature = 'Java_org_yuzu_yuzu_1emu_utils_KenjiBridge_nativeReleaseWindow'
        body = BRIDGE_CPP.split('JNIEXPORT void JNICALL\n' + signature, 1)[1].split('\n}', 1)[0]
        source = '''#include <cassert>
#include <cstdint>
#define JNIEXPORT
#define JNICALL
struct JNIEnv {};
using jobject = void*;
using jlong = long long;
struct ANativeWindow {};
static int calls = 0;
static ANativeWindow* last = nullptr;
void ANativeWindow_release(ANativeWindow* w) { calls++; last = w; }
JNIEXPORT void JNICALL
''' + signature + body + '''
}
int main() {
  ANativeWindow obj;
  ''' + signature + '''(nullptr, nullptr, 0);
  ''' + signature + '''(nullptr, nullptr, -1);
  assert(calls == 0);
  auto handle = reinterpret_cast<jlong>(&obj);
  assert(handle > 0);
  ''' + signature + '''(nullptr, nullptr, handle);
  assert(calls == 1 && last == &obj);
}
'''
        with tempfile.TemporaryDirectory() as d:
            cpp = Path(d) / 'test.cpp'
            exe = Path(d) / 'test'
            cpp.write_text(source)
            subprocess.run(['g++', '-std=c++17', '-Wall', '-Wextra', '-Werror', str(cpp), '-o', str(exe)], check=True)
            subprocess.run([str(exe)], check=True)

if __name__ == '__main__':
    unittest.main()

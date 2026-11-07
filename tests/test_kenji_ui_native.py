#!/usr/bin/env python3
"""Compile actual UI JNI function bodies with a synthetic JNIEnv and host .so.

This does not execute ART, Android, or a real core; the fixture never ships.
"""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
CPP = (ROOT / 'patch/symbiosis/kenji_bridge.cpp').read_text()


class NativeUiTest(unittest.TestCase):
    def test_no_loader_lock_in_response_and_disable_before_teardown(self):
        response = CPP.split('Java_org_yuzu_yuzu_1emu_utils_KenjiBridge_nativeUiRespond(', 1)[1]
        response = response.split('Java_org_kenjinx_android_KenjinxNative_nativeCopyUiText(', 1)[0]
        self.assertNotIn('g_lock', response)
        self.assertIn('g_uiResponse.send', response)
        unload = CPP.split('Java_org_yuzu_yuzu_1emu_utils_KenjiBridge_nativeUnload(', 1)[1]
        unload = unload.split('Java_org_yuzu_yuzu_1emu_utils_KenjiBridge_nativeUiActivate(', 1)[0]
        self.assertLess(unload.index('g_uiResponse.disable()'), unload.index('dlclose(g_core)'))
        close = CPP.split('Java_org_yuzu_yuzu_1emu_utils_KenjiBridge_nativeClosePlay(', 1)[1]
        close = close.split('Java_org_yuzu_yuzu_1emu_utils_KenjiBridge_nativeWindowOf(', 1)[0]
        self.assertLess(close.index('g_uiResponse.disable()'), close.index('fn();'))

    @unittest.skipUnless(shutil.which('g++'), 'requires a C++ compiler')
    def test_actual_native_ui_functions_with_fake_jni_and_real_dlopen(self):
        start = CPP.index('JNIEXPORT jlong JNICALL\nJava_org_yuzu_yuzu_1emu_utils_KenjiBridge_nativeUiActivate')
        end = CPP.index('\n/**\n * Версия установленной прошивки', start)
        bodies = CPP[start:end]
        source = r'''
#include <cassert>
#include <cstdint>
#include <cstring>
#include <dlfcn.h>
#include <mutex>
#include <string>
#include <vector>
#include "GATE_HEADER"
#define JNIEXPORT
#define JNICALL
#define JNI_TRUE 1
#define JNI_FALSE 0
using jlong = int64_t;
using jint = int32_t;
using jsize = int32_t;
using jbyte = int8_t;
using jboolean = uint8_t;
using jobject = void*;
using jclass = void*;
using jbyteArray = std::vector<jbyte>*;
struct JNIEnv {
  bool ExceptionCheck() { return false; }
  jsize GetArrayLength(jbyteArray a) { return static_cast<jsize>(a->size()); }
  void GetByteArrayRegion(jbyteArray a, jsize start, jsize n, jbyte* out) {
    if (n) std::memcpy(out, a->data()+start, n);
  }
  jbyteArray NewByteArray(jsize n) { return new std::vector<jbyte>(n); }
  void SetByteArrayRegion(jbyteArray a, jsize start, jsize n, const jbyte* in) {
    if (n) std::memcpy(a->data()+start, in, n);
  }
};
std::mutex g_lock;
Symbiosis::KenjiUiResponseGate g_uiResponse;
void* g_core = nullptr;
''' + bodies + r'''
int main(int argc, char** argv) {
  assert(argc == 2);
  JNIEnv env;
  using namespace std;
  assert(Java_org_yuzu_yuzu_1emu_utils_KenjiBridge_nativeUiActivate(&env, nullptr)==0);
  assert(Java_org_yuzu_yuzu_1emu_utils_KenjiBridge_nativeUiSetup(&env, nullptr)==JNI_FALSE);
  g_core = dlopen(argv[1], RTLD_NOW|RTLD_LOCAL);
  assert(g_core);
  auto setupCount = reinterpret_cast<int(*)()>(dlsym(g_core, "testSetupCount"));
  auto last = reinterpret_cast<const char*(*)()>(dlsym(g_core, "testLastText"));
  auto accepted = reinterpret_cast<bool(*)()>(dlsym(g_core, "testLastAccepted"));
  assert(setupCount && last && accepted);
  jlong id=Java_org_yuzu_yuzu_1emu_utils_KenjiBridge_nativeUiActivate(&env, nullptr);
  assert(id > 0);
  assert(Java_org_yuzu_yuzu_1emu_utils_KenjiBridge_nativeUiSetup(&env, nullptr)==JNI_TRUE);
  assert(setupCount()==1);
  const string text=u8"Привет 🎮";
  vector<jbyte> bytes(text.begin(),text.end());
  assert(Java_org_yuzu_yuzu_1emu_utils_KenjiBridge_nativeUiRespond(&env, nullptr,id,JNI_TRUE,&bytes));
  assert(accepted() && last()==text);
  vector<jbyte> zero={'a',0,'b'};
  assert(!Java_org_yuzu_yuzu_1emu_utils_KenjiBridge_nativeUiRespond(&env,nullptr,id,JNI_TRUE,&zero));
  assert(last()==text);
  vector<jbyte> empty;
  assert(Java_org_yuzu_yuzu_1emu_utils_KenjiBridge_nativeUiRespond(&env,nullptr,id,JNI_FALSE,&empty));
  assert(!accepted() && strcmp(last(), "")==0);
  assert(!Java_org_yuzu_yuzu_1emu_utils_KenjiBridge_nativeUiRespond(&env,nullptr,0,JNI_TRUE,&bytes));
  auto* copy=Java_org_kenjinx_android_KenjinxNative_nativeCopyUiText(&env,nullptr,reinterpret_cast<jlong>(text.c_str()));
  assert(copy && *copy==bytes); delete copy;
  auto* nullCopy=Java_org_kenjinx_android_KenjinxNative_nativeCopyUiText(&env,nullptr,0);
  assert(nullCopy && nullCopy->empty()); delete nullCopy;
  const string oversized(1024*1024+1, 'x');
  assert(!Java_org_kenjinx_android_KenjinxNative_nativeCopyUiText(&env,nullptr,reinterpret_cast<jlong>(oversized.c_str())));
  g_uiResponse.disable();
  assert(dlclose(g_core)==0); g_core=nullptr;
  assert(!Java_org_yuzu_yuzu_1emu_utils_KenjiBridge_nativeUiRespond(&env,nullptr,id,JNI_TRUE,&bytes));
}
'''
        source = source.replace('GATE_HEADER', str(ROOT / 'patch/symbiosis/kenji_ui_response_gate.h'))
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            (tmp / 'test.cpp').write_text(source)
            lib, exe = tmp / 'mock.so', tmp / 'test'
            subprocess.run(['g++', '-std=c++17', '-fPIC', '-shared',
                            str(ROOT / 'tests/kenji_ui_mock_core.cpp'), '-o', str(lib)], check=True, timeout=30)
            subprocess.run(['g++', '-std=c++17', '-pthread', '-Wall', '-Wextra', '-Werror',
                            str(tmp / 'test.cpp'), '-ldl', '-o', str(exe)], check=True, timeout=30)
            subprocess.run([str(exe), str(lib)], check=True, timeout=15)


if __name__ == '__main__':
    unittest.main()

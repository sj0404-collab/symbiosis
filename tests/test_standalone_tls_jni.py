#!/usr/bin/env python3
"""Host-only JNI test; all tiny fixture libraries stay in a temp directory.

The actual ARM/Bionic/OpenSSL outcome requires the separate Android runner.
"""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'tests/standalone_core_probe'


class TlsJniFixtureTest(unittest.TestCase):
    def test_real_host_jvm_logs_preload_origin_and_rejects_missing_library_in_output(self):
        javac = Path(shutil.which('javac')).resolve()
        jdk = javac.parent.parent
        with tempfile.TemporaryDirectory() as tmp:
            work = Path(tmp)
            def build(name, text, extra=()):
                source = work / (name + '.cpp')
                source.write_text(text)
                library = work / (name + '.so')
                subprocess.run(['g++', '-std=c++17', '-shared', '-fPIC', str(source), '-o', str(library), *extra], check=True)
                return library
            build('libcrypto', 'extern "C" unsigned long OpenSSL_version_num(){return 0x101010cfUL;}')
            build('libssl', '''static int fixture;
                extern "C" const void* TLS_method(){return &fixture;}
                extern "C" void* SSL_CTX_new(const void* m){return m ? &fixture : nullptr;}
                extern "C" void SSL_CTX_free(void*){}''')
            core = build('core', 'extern "C" bool javaInitialize(void* path, void* env){return path && env;}')
            jni = work / 'libstandalonejni.so'
            subprocess.run(['g++', '-std=c++17', '-shared', '-fPIC', '-Wall', '-Wextra', '-Werror',
                            '-I' + str(jdk / 'include'), '-I' + str(jdk / 'include/linux'),
                            str(SRC / 'jni.cpp'), '-ldl', '-o', str(jni)], check=True)
            subprocess.run([str(javac), '-d', str(work), str(SRC / 'Main.java')], check=True)
            java = str(jdk / 'bin/java')

            def invoke(mode):
                data = work / mode
                data.mkdir()
                cmd = [java, '-cp', str(work), 'dev.symbiosis.standalone.Main', str(jni), str(core), str(data)]
                if mode != 'baseline':
                    cmd.append('--preload-tls')
                result = subprocess.run(cmd, text=True, capture_output=True, timeout=15)
                self.assertEqual(result.returncode, 8 if mode in ('missing', 'null-context') else 0, result.stderr)
                return (data / 'jni-stages.txt').read_text()

            base = invoke('baseline')
            self.assertIn('TLS_PRELOAD not-attempted', base)
            self.assertIn('JNI_RESULT javaInitialize=true', base)
            preloaded = invoke('preloaded')
            self.assertIn('TLS_PRELOAD libcrypto.so ok', preloaded)
            self.assertIn('TLS_PRELOAD libssl.so ok', preloaded)
            self.assertIn('TLS_SYMBOL OpenSSL_version_num ok origin=' + str(work / 'libcrypto.so'), preloaded)
            self.assertIn('TLS_SYMBOL SSL_CTX_new ok origin=' + str(work / 'libssl.so'), preloaded)
            self.assertIn('TLS_OPENSSL_VERSION=0x101010cf', preloaded)
            self.assertIn('TLS_CONTEXT_SMOKE=ok', preloaded)
            build('libssl', '''extern "C" const void* TLS_method(){static int x; return &x;}
                extern "C" void* SSL_CTX_new(const void*){return nullptr;}
                extern "C" void SSL_CTX_free(void*){}''')
            broken = invoke('null-context')
            self.assertIn('JNI_RESULT javaInitialize=true', broken)
            self.assertIn('TLS_CONTEXT_SMOKE=fail', broken)
            (work / 'libssl.so').unlink()
            missing = invoke('missing')
            self.assertIn('TLS_PRELOAD libssl.so fail:', missing)
            # Even if the fixture core initializes, the missing TLS check fails.
            self.assertIn('JNI_RESULT javaInitialize=true', missing)
            self.assertIn('TLS_CONTEXT_SMOKE=fail', missing)


if __name__ == '__main__':
    unittest.main()

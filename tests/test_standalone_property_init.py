#!/usr/bin/env python3
"""Test-only synthetic Bionic exports; production APK never includes them."""
from pathlib import Path
import os
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class PropertyInitTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.base = Path(cls.tmp.name)
        (cls.base / 'sys').mkdir()
        (cls.base / 'sys/system_properties.h').write_text('#define PROP_VALUE_MAX 92\n')
        cls.probe = cls.base / 'property-init'
        subprocess.run(['g++', '-std=c++17', '-Wall', '-Wextra', '-Werror', '-I', str(cls.base),
                        str(ROOT / 'tests/standalone_core_probe/property_init.cpp'), '-ldl', '-o', str(cls.probe)], check=True)
        fixture = cls.base / 'fixture.cpp'
        fixture.write_text('''#include <cstdlib>
extern "C" int __system_property_area_init() { return std::getenv("MOCK_INIT_WARN") ? -2 : 0; }
extern "C" int __system_property_add(const char*, unsigned, const char*, unsigned) { return std::getenv("MOCK_ADD_FAIL") ? -1 : 0; }
''')
        cls.lib = cls.base / 'fixture.so'
        subprocess.run(['g++', '-shared', '-fPIC', str(fixture), '-o', str(cls.lib)], check=True)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def run_probe(self, content, **extra):
        source = self.base / 'image-build.prop'
        source.write_text(content)
        env = os.environ.copy()
        env.update(extra)
        env['LD_PRELOAD'] = str(self.lib)
        return subprocess.run([str(self.probe), str(source)], capture_output=True, text=True, env=env)

    def test_uses_image_properties_not_other_values(self):
        result = self.run_probe('ro.product.cpu.abilist64=arm64-v8a\nro.build.version.sdk=27\nro.build.version.all_codenames=REL\nfoo=ignored\n')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('IMAGE_PROPERTY ro.product.cpu.abilist64=arm64-v8a', result.stdout)
        self.assertIn('IMAGE_PROPERTY ro.build.version.all_codenames=REL', result.stdout)
        self.assertNotIn('foo=', result.stdout)
        self.assertIn('PROPERTY_IMAGE_VALUES_ADDED=3', result.stdout)

    def test_fsetxattr_warning_is_not_silent(self):
        result = self.run_probe('ro.product.cpu.abilist64=arm64-v8a\nro.build.version.sdk=27\n', MOCK_INIT_WARN='1')
        self.assertEqual(result.returncode, 0)
        self.assertIn('PROPERTY_AREA_INIT=-2', result.stdout)

    def test_missing_required_and_add_failure(self):
        self.assertEqual(self.run_probe('ro.build.version.sdk=27\n').returncode, 8)
        result = self.run_probe('ro.product.cpu.abilist64=arm64-v8a\n', MOCK_ADD_FAIL='1')
        self.assertEqual(result.returncode, 7)
        self.assertIn('PROPERTY_ADD_FAIL', result.stderr)


if __name__ == '__main__':
    unittest.main()

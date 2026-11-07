#!/usr/bin/env python3
"""Host checks of the staged transform table, no Android or real core."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class TransformCandidateTest(unittest.TestCase):
    @unittest.skipUnless(shutil.which('g++'), 'requires a C++ compiler')
    def test_mapping_domain_and_boundaries(self):
        source = r'''
#include <cassert>
#include <cstdint>
#include <limits>
#include "kenji_transform_map.h"
int main() {
    using Symbiosis::SymbekTransformCandidate;
    struct Row { int input; int normal; int flipped; };
    const Row rows[] = {{0,0,0}, {1,0,0}, {2,0,0}, {3,0,0},
        {4,4,4}, {8,3,0}, {16,7,7}, {32,1,1}, {64,5,5},
        {128,2,2}, {256,6,6}, {512,0,0}};
    for (auto r : rows) {
        assert(SymbekTransformCandidate(r.input, false) == r.normal);
        assert(SymbekTransformCandidate(r.input, true) == r.flipped);
        if (r.input >= 4) {
            assert(SymbekTransformCandidate(r.input + 1, false) == r.normal);
            assert(SymbekTransformCandidate(r.input + 1, true) == r.flipped);
        }
    }
    for (int i = -10000; i < 0; ++i) {
        assert(SymbekTransformCandidate(i, false) == 0);
        assert(SymbekTransformCandidate(i, true) == 0);
    }
    assert(SymbekTransformCandidate(std::numeric_limits<std::int32_t>::min(), false) == 0);
    assert(SymbekTransformCandidate(std::numeric_limits<std::int32_t>::max(), true) == 0);
}
'''
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            (path / 'test.cpp').write_text(source, encoding='utf-8')
            subprocess.run(['g++', '-std=c++17', '-Wall', '-Wextra', '-Werror',
                            '-I' + str(ROOT / 'patch/symbiosis'), str(path / 'test.cpp'),
                            '-o', str(path / 'test')], check=True, timeout=30)
            subprocess.run([str(path / 'test')], check=True, timeout=15)


if __name__ == '__main__':
    unittest.main()

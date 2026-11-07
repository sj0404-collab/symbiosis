#!/usr/bin/env python3
"""Synthetic HID experiments, NOT an Android or real Kenji runtime test.

EDEN_NPAD_RESOURCE must be the pinned Eden v0.2.1 npad_resource.cpp for the
optional isolated-source experiment. Guest request bytes are synthesized:
no claim is made that we captured the request in the user's application.
"""
from itertools import product
from pathlib import Path
import hashlib
import os
import shutil
import struct
import subprocess
import tempfile
import unittest

PINNED_SHA = '28c9fe700dec7d5d0cea697c0263b2ba13b1b49ba88ccd1481634cf9b012c771'
OBSERVED = [
    -5343767660796575744, -5343712479056756736, -5343657297316937728,
    -5343602115577118720, -5343546933837299712,
]
LOW_WORDS = [0xb5d71fa0, 0xb5d751d0, 0xb5d78400, 0xb5d7b630, 0xb5d7e860]
MASK64 = (1 << 64) - 1


def signed64(value):
    value &= MASK64
    return value if value < 1 << 63 else value - (1 << 64)


def wire(npad_id, padding, aruid, event_pointer):
    # HID command 106: libnx declares u32 id, u32 padding, u64 ARUID, u64 ptr.
    return struct.pack('<IIQQ', npad_id, padding, aruid, event_pointer)


def source_functions(source):
    start = source.index('std::size_t NPadResource::GetIndexFromAruid(u64 aruid) const {')
    end = source.index('\nResult NPadResource::ApplyNpadSystemCommonPolicy', start)
    find = source[start:end]
    start = source.index('Result NPadResource::AcquireNpadStyleSetUpdateEventHandle(u64 aruid,')
    end = source.index('\nResult NPadResource::SignalStyleSetUpdateEvent', start)
    acquire = source[start:end]
    return find + '\n' + acquire


class SyntheticHidExperiment(unittest.TestCase):
    def test_five_observed_lines_can_be_reproduced_by_unaligned_reads(self):
        # We infer only a possible low 32-bit word; the real pointer, its high
        # word, actual IPC bytes and the binary's reader are NOT known.
        for player, low, observed in zip(range(3, 8), LOW_WORDS, OBSERVED):
            b = wire(player, 0, 1, (0x12345678 << 32) | low)
            self.assertEqual(struct.unpack_from('<I', b, 0)[0], player)
            self.assertEqual(struct.unpack_from('<Q', b, 8)[0], 1)
            self.assertEqual(struct.unpack_from('<q', b, 4)[0], 4294967296)
            self.assertEqual(struct.unpack_from('<q', b, 12)[0], observed)
        self.assertTrue(all(b - a == 0x3230 for a, b in zip(LOW_WORDS, LOW_WORDS[1:])))

    def test_240_padding_aruid_and_pointer_combinations(self):
        ids = (0, 3, 7, 0x20)
        paddings = (0, 1, 0xffffffff)
        aru_ids = (0, 1, 2, 0x100000001)
        pointers = (0, 1, 0xb5d71fa0, 0x12345678b5d71fa0, 0xffffffffffffffff)
        checked = 0
        for npad, padding, aruid, ptr in product(ids, paddings, aru_ids, pointers):
            b = wire(npad, padding, aruid, ptr)
            wrong_aruid = struct.unpack_from('<Q', b, 4)[0]
            wrong_last = struct.unpack_from('<q', b, 12)[0]
            self.assertEqual(wrong_aruid, (aruid & 0xffffffff) << 32 | padding)
            self.assertEqual(wrong_last, signed64((ptr & 0xffffffff) << 32 | (aruid >> 32)))
            self.assertEqual(struct.unpack_from('<Q', b, 8)[0], aruid)
            self.assertEqual(struct.unpack_from('<Q', b, 16)[0], ptr)
            checked += 1
        self.assertEqual(checked, 240)

    @unittest.skipUnless(os.environ.get('EDEN_NPAD_RESOURCE') and shutil.which('g++'),
                         'set EDEN_NPAD_RESOURCE and install g++ for isolated Eden source experiment')
    def test_real_eden_functions_with_synthetic_registered_resources(self):
        path = Path(os.environ['EDEN_NPAD_RESOURCE'])
        data = path.read_bytes()
        self.assertEqual(hashlib.sha256(data).hexdigest(), PINNED_SHA)
        functions = source_functions(data.decode('utf-8'))
        code = r'''#include <array>
#include <cassert>
#include <cstdint>
#include <cstddef>
using u64 = std::uint64_t;
using Result = int;
constexpr int ResultSuccess = 0;
constexpr int ResultNpadNotConnected = 0x58cca;
constexpr std::size_t AruidIndexMax = 4;
enum class RegistrationStatus { None, Initialized };
namespace Core::HID { enum class NpadIdType : unsigned {}; }
namespace Kernel { struct KReadableEvent {}; }
struct Event {
  Kernel::KReadableEvent readable;
  bool signaled = false;
  Kernel::KReadableEvent& GetReadableEvent() { return readable; }
  void Signal() { signaled = true; }
};
struct ControllerState { bool is_styleset_update_event_initialized = false; Event* style_set_update_event = nullptr; };
struct ResourceState { ControllerState controller_state[8]; };
struct RegistrationList { RegistrationStatus flag[AruidIndexMax]{}; u64 aruid[AruidIndexMax]{}; };
struct Context { std::array<Event, 8> events{}; std::size_t count = 0; Event* CreateEvent(const char*) { return &events.at(count++); } };
std::size_t NpadIdTypeToIndex(Core::HID::NpadIdType id) { return static_cast<unsigned>(id); }
struct NPadResource {
  RegistrationList registration_list;
  ResourceState state[AruidIndexMax];
  Context service_context;
  bool synthetic_controller_connected = false; // deliberately not used by the tested method
  std::size_t GetIndexFromAruid(u64 aruid) const;
  Result AcquireNpadStyleSetUpdateEventHandle(u64 aruid, Kernel::KReadableEvent**, Core::HID::NpadIdType);
};
''' + functions + r'''
int main() {
  int good=0, not_connected=0;
  for (bool registered : {false, true}) for (bool connected : {false, true}) {
    for (u64 aruid : {0ULL, 1ULL, 2ULL, 4294967296ULL}) for (unsigned player=0; player<8; ++player) {
      NPadResource resource;
      resource.synthetic_controller_connected = connected;
      if (registered) { resource.registration_list.flag[0]=RegistrationStatus::Initialized; resource.registration_list.aruid[0]=1; }
      Kernel::KReadableEvent* event=nullptr;
      Result result=resource.AcquireNpadStyleSetUpdateEventHandle(aruid, &event, static_cast<Core::HID::NpadIdType>(player));
      if (registered && aruid==1) { assert(result==ResultSuccess && event && resource.service_context.events[0].signaled); ++good; }
      else { assert(result==ResultNpadNotConnected && !event); ++not_connected; }
    }
  }
  assert(good==16 && not_connected==112);
}
'''
        with tempfile.TemporaryDirectory() as d:
            source, exe = Path(d) / 'case.cpp', Path(d) / 'case'
            source.write_text(code)
            subprocess.run(['g++', '-std=c++17', '-Wall', '-Wextra', '-Werror', str(source), '-o', str(exe)], check=True)
            subprocess.run([str(exe)], check=True)

if __name__ == '__main__':
    unittest.main()

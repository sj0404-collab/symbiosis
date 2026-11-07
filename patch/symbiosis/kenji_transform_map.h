// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <cstdint>

namespace Symbiosis {
// Candidate mapping for the pinned SymbEK 1.0.121 JNI callback only. This is
// not connected to production yet. The incoming values and orientation flag
// of the other core still need proof. Returns public ANativeWindow bit values.
constexpr std::int32_t SymbekTransformCandidate(std::int32_t input,
                                                 bool initiallyFlipped) {
    if (input < 0) return 0;
    switch (input / 2) {
        case 2:   return 4; // clockwise 90 degrees
        case 4:   return initiallyFlipped ? 0 : 3; // 180 or identity
        case 8:   return 7; // clockwise 270 degrees
        case 16:  return 1; // horizontal mirror
        case 32:  return 5; // horizontal mirror then 90 degrees
        case 64:  return 2; // vertical mirror
        case 128: return 6; // vertical mirror then 90 degrees
        default:  return 0;
    }
}
} // namespace Symbiosis

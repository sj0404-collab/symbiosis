// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <cstdint>
#include <mutex>

namespace Symbiosis {

// Lifetime gate for the core's uiHandlerSetResponse. Independent from the
// loader's mutex: the core can be waiting for an answer inside a call that
// already holds that mutex. The owner must enable only after it has resolved
// the function and before guest execution, and must disable and drain before
// closing the device or unloading the library. Calls to activate/disable must
// themselves be ordered by the owning session lifecycle.
class KenjiUiResponseGate {
public:
    using Response = void (*)(bool, const char*);
    using Generation = std::uint64_t;

    Generation activate(Response fn) {
        std::lock_guard<std::mutex> guard(lock_);
        ++generation_;
        response_ = fn;
        return generation_;
    }

    bool send(Generation generation, bool accepted, const char* utf8) {
        std::lock_guard<std::mutex> guard(lock_);
        if (generation != generation_ || response_ == nullptr || utf8 == nullptr) return false;
        // Hold the gate while executing: disable() cannot return while an
        // in-flight response is still using code from the dynamic library.
        response_(accepted, utf8);
        return true;
    }

    void disable() {
        std::lock_guard<std::mutex> guard(lock_);
        response_ = nullptr;
        ++generation_;
    }

private:
    std::mutex lock_;
    Response response_ = nullptr;
    Generation generation_ = 0;
};

} // namespace Symbiosis

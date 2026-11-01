// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

#include <array>
#include <atomic>
#include <cmath>
#include <cstdio>
#include <string>

#include "common/symbiosis/frame_metrics.h"

namespace Symbiosis::FrameMetrics {

namespace {

/// Сколько последних кадров хранится: 1024 кадра — около 17 секунд при 60 в
/// секунду, 8 КБ памяти.
constexpr std::size_t kRingSize = 1024;

/// Самая длинная пауза, которую считаем осмысленной: 5 секунд. Всё, что
/// дольше, — это уже сон процесса или своп, и включать его в статистику
/// значит испортить среднюю одним событием.
constexpr u64 kMaxFrameMicroseconds = 5'000'000;

/// Сколько кадров попадает в снимок: минута при 60 в секунду.
constexpr std::size_t kSnapshotFrames = 600;

std::array<std::atomic<u64>, kRingSize> g_ring{};
std::atomic<u64> g_written{0};
std::atomic<u64> g_underruns{0};
std::atomic<u64> g_shaders{0};

} // namespace

void RecordFrame(u64 microseconds) {
    // Обрезаем вместо того, чтобы отбрасывать: долгая пауза — это тоже
    // факт, и выкинув её мы получим «всё ровно» у человека, у которого
    // картинка замирает.
    if (microseconds > kMaxFrameMicroseconds) {
        microseconds = kMaxFrameMicroseconds;
    }
    const u64 index = g_written.fetch_add(1, std::memory_order_relaxed);
    g_ring[index % kRingSize].store(microseconds, std::memory_order_relaxed);
}

void NoteAudioUnderrun() {
    g_underruns.fetch_add(1, std::memory_order_relaxed);
}

void NoteShaderCompile() {
    g_shaders.fetch_add(1, std::memory_order_relaxed);
}

void Reset() {
    g_written.store(0, std::memory_order_relaxed);
    g_underruns.store(0, std::memory_order_relaxed);
    g_shaders.store(0, std::memory_order_relaxed);
    for (auto& slot : g_ring) {
        slot.store(0, std::memory_order_relaxed);
    }
}

std::string Snapshot() {
    const u64 total = g_written.load(std::memory_order_relaxed);
    const std::size_t wanted = static_cast<std::size_t>(
        total < kSnapshotFrames ? total : kSnapshotFrames);
    const u64 first = total - wanted;

    std::string out;
    out.reserve(wanted * 7 + 96);

    char line[64];
    std::snprintf(line, sizeof(line), "total=%llu\n", static_cast<unsigned long long>(total));
    out += line;
    std::snprintf(line, sizeof(line), "count=%zu\n", wanted);
    out += line;
    std::snprintf(line, sizeof(line), "underruns=%llu\n",
                  static_cast<unsigned long long>(g_underruns.load(std::memory_order_relaxed)));
    out += line;
    std::snprintf(line, sizeof(line), "shaders=%llu\n",
                  static_cast<unsigned long long>(g_shaders.load(std::memory_order_relaxed)));
    out += line;
    out += "samples=";

    bool first_value = true;
    for (u64 i = first; i < total; ++i) {
        const u64 micros = g_ring[i % kRingSize].load(std::memory_order_relaxed);
        if (micros == 0) {
            continue;
        }
        // Миллисекунды с одним знаком: больше точности у кадра нет смысла,
        // а строка должна оставаться короткой — её читают по радио.
        std::snprintf(line, sizeof(line), "%.1f", static_cast<double>(micros) / 1000.0);
        if (!first_value) {
            out += ',';
        }
        out += line;
        first_value = false;
    }
    out += '\n';
    return out;
}

} // namespace Symbiosis::FrameMetrics

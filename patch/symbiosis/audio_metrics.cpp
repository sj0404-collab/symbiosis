// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

#include "common/symbiosis/audio_metrics.h"

#include <atomic>
#include <mutex>
#include <string>

namespace Symbiosis {

namespace {

std::mutex g_lock;
AudioReport g_report{};
std::atomic<unsigned long long> g_callbacks{0};

std::string Number(unsigned long long value) {
    return std::to_string(value);
}

} // namespace

void SetAudioStreamInfo(const AudioReport& report) {
    std::lock_guard<std::mutex> guard(g_lock);
    // Счётчик вызовов не сбрасывается сменой потока: он про то, сколько раз
    // драйвер вообще звал нас, а не про конкретное открытие.
    g_report = report;
    g_report.callbacks = g_callbacks.load();
}

void NoteAudioCallback() {
    ++g_callbacks;
}

void SetAudioXruns(unsigned long long xruns) {
    std::lock_guard<std::mutex> guard(g_lock);
    g_report.xruns = xruns;
}

void SetAudioError(const std::string& error) {
    std::lock_guard<std::mutex> guard(g_lock);
    g_report.last_error = error;
}

AudioReport GetAudioReport() {
    std::lock_guard<std::mutex> guard(g_lock);
    AudioReport copy = g_report;
    copy.callbacks = g_callbacks.load();
    return copy;
}

unsigned int AudioBufferMillis(const AudioReport& report) {
    if (report.device_rate == 0 || report.buffer_frames == 0) {
        return 0;
    }
    const unsigned long long millis =
        static_cast<unsigned long long>(report.buffer_frames) * 1000ULL / report.device_rate;
    return static_cast<unsigned int>(millis);
}

std::string DescribeAudio(const AudioReport& report) {
    std::string out;

    if (!report.opened()) {
        out = "Звуковой поток не открывался в этом запуске.";
        if (!report.last_error.empty()) {
            out += " Последняя ошибка: " + report.last_error + ".";
        }
        return out;
    }

    // Цифры — одной строкой, чтобы их можно было сравнить до и после смены
    // настройки, а не вспоминать на слух.
    out = "Звук: " + std::to_string(report.device_rate) + " Гц, " +
          std::to_string(report.channels) + " кан., буфер " +
          std::to_string(report.buffer_frames) + " кадров (" +
          std::to_string(AudioBufferMillis(report)) + " мс)";
    if (!report.backend.empty()) {
        out += ", драйвер " + report.backend;
    }
    out += ".";

    // Пересчёт частоты — самый вероятный источник хрипа в музыке: он грузит
    // процессор на каждом кадре и на слабых ядрах не успевает.
    if (report.requested_rate != 0 && report.device_rate != report.requested_rate) {
        out += " Просили " + std::to_string(report.requested_rate) +
               " Гц, а драйвер отдал другую частоту: звук пересчитывается на ходу, "
               "и это стоит процессорного времени.";
    } else if (report.requested_rate != 0) {
        out += " Частота совпадает с запрошенной — пересчёта нет.";
    } else {
        out += " Частоту выбирал драйвер: пересчёта нет, если он открыл поток на родной "
               "частоте телефона.";
    }

    if (report.xruns > 0) {
        out += " Опустошений буфера: " + Number(report.xruns) +
               " — каждое слышно как щелчок или обрыв. Это измеренный хрип, а не "
               "ощущение: увеличьте «Буфер звука».";
    } else if (report.callbacks > 0) {
        out += " Опустошений буфера нет — если хрип слышен, причина не в буфере: "
               "смотрите частоту (пересчёт) и нагрузку на процессор.";
    }

    return out;
}

} // namespace Symbiosis

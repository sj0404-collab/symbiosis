// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Замеры звука и их объяснение.
//
// ПОЧЕМУ НА ХОСТЕ. Жалоба «какую настройку ни применю, не помогает» не
// проверяется на слух: нужно, чтобы по цифрам было видно, дошла ли настройка
// до драйвера. Значит проверяем именно цифры и текст — без телефона и без
// oboe.

#include <cstdio>
#include <string>
#include "common/symbiosis/audio_metrics.h"

using namespace Symbiosis;

static int passed = 0;
static int failed = 0;

static void check(const char* what, bool ok, const std::string& detail = "") {
    if (ok) {
        ++passed;
    } else {
        ++failed;
        std::printf("FAIL  %s%s%s\n", what, detail.empty() ? "" : " -> ", detail.c_str());
    }
}

static bool has(const std::string& text, const char* part) {
    return text.find(part) != std::string::npos;
}

int main() {
    // 1. Поток не открывался — так и говорим, без догадок про хрип.
    {
        const auto text = DescribeAudio(AudioReport{});
        check("не открывался назван", has(text, "не открывался"), text);
        check("о хрипе не выдумываем", !has(text, "Опустошений"), text);
    }

    // 2. Всё сошлось: частота та же, опустошений нет.
    {
        AudioReport r;
        r.requested_rate = 48000;
        r.device_rate = 48000;
        r.channels = 2;
        r.buffer_frames = 960;      // 20 мс при 48 кГц
        r.capacity_frames = 1920;
        r.frames_per_burst = 192;
        r.backend = "OpenSLES";
        const auto text = DescribeAudio(r);
        check("частота в строке", has(text, "48000 Гц"), text);
        check("буфер в миллисекундах", has(text, "20 мс"), text);
        check("драйвер назван", has(text, "OpenSLES"), text);
        check("пересчёта нет — сказано", has(text, "пересчёта нет"), text);
    }

    // 3. Драйвер отдал другую частоту: это и есть пересчёт, источник хрипа.
    {
        AudioReport r;
        r.requested_rate = 48000;
        r.device_rate = 44100;
        r.channels = 2;
        r.buffer_frames = 882;
        r.callbacks = 100;
        const auto text = DescribeAudio(r);
        check("обе частоты названы", has(text, "48000") && has(text, "44100"), text);
        check("сказано про пересчёт", has(text, "пересчитывается"), text);
    }

    // 3б. «Как решит драйвер» — это не 48000 и не 44100, и так и говорится.
    {
        AudioReport r;
        r.requested_rate = 0;
        r.device_rate = 48000;
        r.channels = 2;
        r.buffer_frames = 960;
        r.callbacks = 10;
        const auto text = DescribeAudio(r);
        check("сказано, что частоту выбрал драйвер", has(text, "выбирал драйвер"), text);
        check("про пересчёт не врём", !has(text, "Просили"), text);
    }

    // 4. Опустошения буфера — измеренный хрип, а не ощущение.
    {
        AudioReport r;
        r.requested_rate = 48000;
        r.device_rate = 48000;
        r.channels = 2;
        r.buffer_frames = 480;
        r.xruns = 37;
        r.callbacks = 5000;
        const auto text = DescribeAudio(r);
        check("число опустошений названо", has(text, "37"), text);
        check("сказано, что это хрип", has(text, "хрип"), text);
        check("совет про буфер", has(text, "Буфер звука"), text);
    }

    // 5. Буфер без опустошений: честно говорим, что дело не в нём.
    {
        AudioReport r;
        r.requested_rate = 48000;
        r.device_rate = 48000;
        r.buffer_frames = 960;
        r.xruns = 0;
        r.callbacks = 900;
        const auto text = DescribeAudio(r);
        check("причина не в буфере — сказано", has(text, "причина не в буфере"), text);
    }

    // 6. Миллисекунды считаются из фактических кадров и частоты.
    {
        AudioReport r;
        r.device_rate = 48000;
        r.buffer_frames = 2400;   // 50 мс
        check("50 мс из кадров", AudioBufferMillis(r) == 50,
              std::to_string(AudioBufferMillis(r)));
        r.device_rate = 44100;
        r.buffer_frames = 441;    // 10 мс
        check("10 мс при 44.1", AudioBufferMillis(r) == 10,
              std::to_string(AudioBufferMillis(r)));
        r.device_rate = 0;
        check("нет частоты — ноль", AudioBufferMillis(r) == 0);
    }

    // 7. Состояние читается и обновляется через слой.
    {
        AudioReport r;
        r.requested_rate = 48000;
        r.device_rate = 44100;
        r.channels = 2;
        r.buffer_frames = 882;
        r.backend = "AAudio";
        SetAudioStreamInfo(r);
        NoteAudioCallback();
        NoteAudioCallback();
        SetAudioXruns(5);
        const auto got = GetAudioReport();
        check("поток сохранён", got.device_rate == 44100, std::to_string(got.device_rate));
        check("каналы сохранены", got.channels == 2);
        check("драйвер сохранён", got.backend == "AAudio", got.backend);
        check("опустошения сохранены", got.xruns == 5, std::to_string(got.xruns));
        check("вызовы посчитаны", got.callbacks == 2, std::to_string(got.callbacks));
    }

    // 8. Ошибка открытия называется, а не теряется.
    {
        AudioReport r;
        SetAudioStreamInfo(r);
        SetAudioError("AudioStreamBuilder::openStream() failed");
        const auto text = DescribeAudio(GetAudioReport());
        check("ошибка в объяснении", has(text, "openStream"), text);
    }

    std::printf("passed=%d failed=%d\n", passed, failed);
    return failed == 0 ? 0 : 1;
}

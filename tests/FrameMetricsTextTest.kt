// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * Разбор снимка, который отдаёт нативная часть.
 *
 * ПОЧЕМУ ОТДЕЛЬНО ОТ FramePacingTest
 *   Тут проверяется не «что значит ряд», а «не сломается ли экран на плохом
 *   ответе»: нативная часть может не загрузиться, вернуть пустоту, обрезать
 *   строку или прислать мусор. Экран диагностики обязан в этом случае
 *   сказать «данных нет», а не упасть.
 */

import org.yuzu.yuzu_emu.utils.FrameMetricsText

private var passed = 0
private val failures = mutableListOf<String>()

private fun check(what: String, ok: Boolean, detail: String = "") {
    if (ok) passed++ else failures.add("$what${if (detail.isEmpty()) "" else " → $detail"}")
}

private fun eq(what: String, got: Any?, want: Any?) =
    check(what, got == want, "получено [$got], ожидалось [$want]")

fun main() {
    val full = FrameMetricsText.parse(
        "total=601\ncount=600\nunderruns=3\nshaders=12\nsamples=16.7,16.6,210.5,16.8\n"
    )
    eq("всего кадров", full.totalFrames, 601L)
    eq("провалы звука", full.audioUnderruns, 3L)
    eq("сборки шейдеров", full.shaderCompiles, 12L)
    eq("кадров в снимке", full.frameMs.size, 4)
    eq("первый кадр", full.frameMs.first(), 16.7)
    eq("худший кадр", full.frameMs.max(), 210.5)
    check("снимок не пуст", !full.isEmpty)

    // Пустой ответ — это не ошибка, а «игра ещё не запускалась».
    val empty = FrameMetricsText.parse("")
    check("пустой ответ не падает", empty.isEmpty)
    eq("пустой ответ: кадров нет", empty.frameMs.size, 0)
    check("подпись просит поиграть",
        FrameMetricsText.headline(empty).contains("запустите игру"),
        FrameMetricsText.headline(empty))

    // Мусор: строки не по формату, нечисла, отрицательные, гигантские.
    val dirty = FrameMetricsText.parse(
        "total=abc\nunderruns=?\nshaders\nsamples=1,,x,-5,0,16.6,NaN,Infinity,16.7;17,999999\nлишняя строка\n"
    )
    eq("мусор в total не ломает разбор", dirty.totalFrames, 0L)
    eq("мусор в underruns не ломает разбор", dirty.audioUnderruns, 0L)
    eq("в снимке только правдоподобные кадры", dirty.frameMs, listOf(1.0, 16.6))

    // Повторяющийся ключ: побеждает последний, список не склеивается.
    val twice = FrameMetricsText.parse("samples=16.6\nsamples=33.3\n")
    eq("повтор ключа перезаписывает", twice.frameMs, listOf(33.3))

    // Лишние пробелы и \r\n на конце строк — обычное дело для Android.
    val spaces = FrameMetricsText.parse("  total = 42 \r\n samples = 16.6 , 16.7 \r\n")
    eq("пробелы вокруг ключа и значения не мешают", spaces.totalFrames, 42L)
    eq("пробелы в списке не мешают", spaces.frameMs, listOf(16.6, 16.7))

    // Подпись: без сборок шейдеров не упоминает их вовсе.
    val headline = FrameMetricsText.headline(
        FrameMetricsText.Snapshot(totalFrames = 600, audioUnderruns = 0, shaderCompiles = 0,
            frameMs = List(600) { 16.6 })
    )
    check("подпись называет число кадров", headline.contains("600 кадров"), headline)
    check("подпись называет провалы звука", headline.contains("провалов звука: 0"), headline)
    check("без сборок шейдеров о них молчит", !headline.contains("шейдер"), headline)

    // И полноценный сценарий: снимок → разбор → причина.
    val report = org.yuzu.yuzu_emu.utils.FramePacing.analyse(full.frameMs)
    check("из снимка получается отчёт", report.samples == 4, report.samples.toString())
    check("на четырёх кадрах честно сказано «мало данных»",
        report.causes == listOf(org.yuzu.yuzu_emu.utils.FramePacing.Cause.UNKNOWN),
        report.causes.toString())

    eq("подпись числа кадров", org.yuzu.yuzu_emu.utils.FramePacing.formatFps(59.94), "59.9")
    eq("подпись больших чисел без дробей", org.yuzu.yuzu_emu.utils.FramePacing.formatFps(144.2), "144")
    eq("подпись нуля", org.yuzu.yuzu_emu.utils.FramePacing.formatFps(0.0), "—")

    println()
    println("passed=$passed failed=${failures.size}")
    failures.forEach { println("FAIL  $it") }
    if (failures.isNotEmpty()) kotlin.system.exitProcess(1)
}

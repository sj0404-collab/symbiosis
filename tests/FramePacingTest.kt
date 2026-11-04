// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * Разбор «кадры прыгают 60 → 15 → 45 → 5».
 *
 * ПОЧЕМУ ЭТИ ПРОВЕРКИ ВАЖНЫ
 *   Экран диагностики говорит человеку, что менять. Ошибка здесь — не
 *   «неудобно», а «поставил турбо-режим и стало хуже». Поэтому каждый
 *   вывод проверяется на ряде, который действительно так выглядит:
 *   ровный ряд не должен называться перегревом, деления частоты экрана —
 *   называться «нехваткой производительности», а шум сцены — сбросом частот.
 */

import org.yuzu.yuzu_emu.utils.FramePacing

private var passed = 0
private val failures = mutableListOf<String>()

private fun check(what: String, ok: Boolean, detail: String = "") {
    if (ok) passed++ else failures.add("$what${if (detail.isEmpty()) "" else " → $detail"}")
}

private fun eq(what: String, got: Any?, want: Any?) =
    check(what, got == want, "получено [$got], ожидалось [$want]")

/** Ряд кадров с заданной частотой и шумом ±[noise] мс. */
private fun steady(fps: Double, count: Int, noiseMs: Double = 0.4, seed: Int = 1): List<Double> {
    val random = java.util.Random(seed.toLong())
    val frame = 1000.0 / fps
    return (0 until count).map { frame + (random.nextDouble() - 0.5) * 2 * noiseMs }
}

fun main() {
    // --- ровные 60 кадров ------------------------------------------------
    val smooth = FramePacing.analyse(steady(60.0, 600))
    check("ровные 60 не называют перегревом", FramePacing.Cause.THERMAL !in smooth.causes)
    check("ровные 60 не называют сборкой шейдеров", FramePacing.Cause.SHADER_COMPILE !in smooth.causes)
    check("ровные 60 не называют упором в синхронизацию", FramePacing.Cause.VSYNC_STEP !in smooth.causes)
    check("средняя около 60", smooth.averageFps in 58.0..62.0, smooth.averageFps.toString())
    check("прыгучесть мала", smooth.jitter < 0.1, smooth.jitter.toString())
    check("факты есть", smooth.facts.isNotEmpty())
    check("худший кадр не больше 20 мс", smooth.worstMs < 20.0, smooth.worstMs.toString())

    // --- упор в синхронизацию: ровно 30 при экране 60 --------------------
    val half = FramePacing.analyse(steady(30.0, 600))
    check("30 при экране 60 названо упором в синхронизацию",
        FramePacing.Cause.VSYNC_STEP in half.causes, half.causes.toString())
    check("факт про долю частоты экрана есть",
        half.facts.any { it.contains("доля частоты экрана") }, half.facts.toString())
    check("ровные 30 не называют перегревом", FramePacing.Cause.THERMAL !in half.causes)

    // 45 — это три четверти от 60 и ровно половина от 90. Тоже деление.
    val threeQuarters = FramePacing.analyse(steady(45.0, 600), refreshHint = 90.0)
    check("45 при экране 90 названо упором в синхронизацию",
        FramePacing.Cause.VSYNC_STEP in threeQuarters.causes, threeQuarters.causes.toString())

    // --- тепловой обрыв: ровно, потом вдвое хуже -------------------------
    val throttled = steady(60.0, 300) + steady(30.0, 300, noiseMs = 1.0)
    val thermal = FramePacing.analyse(throttled)
    check("обрыв вдвое по времени назван перегревом",
        FramePacing.Cause.THERMAL in thermal.causes, thermal.causes.toString())
    check("совет сначала проверить нагрев, а не менять настройки",
        thermal.advice.first().contains("остыть"), thermal.advice.toString())
    check("факт называет время обрыва",
        thermal.facts.any { it.contains("после") }, thermal.facts.toString())

    // Шум сцены (случайные просадки без обрыва) НЕ должен стать перегревом.
    val random = java.util.Random(7)
    val noisy = (0 until 600).map { 16.7 + random.nextDouble() * 12.0 }
    check("шум без обрыва не называют перегревом",
        FramePacing.Cause.THERMAL !in FramePacing.analyse(noisy).causes)

    // --- сборка шейдеров: ровная медиана + редкие длинные кадры ----------
    val withHitches = steady(60.0, 600).toMutableList()
    listOf(30, 120, 250, 400).forEach { index -> withHitches[index] = 95.0 }
    val shaders = FramePacing.analyse(withHitches)
    check("редкие длинные кадры названы сборкой шейдеров",
        FramePacing.Cause.SHADER_COMPILE in shaders.causes, shaders.causes.toString())
    check("совет включает асинхронные шейдеры",
        shaders.advice.any { it.contains("Асинхронные шейдеры") }, shaders.advice.toString())
    check("медиана при этом остаётся 60",
        shaders.medianFps in 58.0..62.0, shaders.medianFps.toString())
    // Худший кадр важен в отчёте, а не в «худших 1%»: четыре паузы на
    // шестьсот кадров — это меньше одного процента.
    check("худший кадр попал в отчёт", shaders.worstMs > 90.0, shaders.worstMs.toString())

    // --- ровно и медленно (25 кадров стабильно) --------------------------
    val slow = FramePacing.analyse(steady(25.0, 600), refreshHint = 60.0)
    check("ровные 25 названы нехваткой производительности",
        FramePacing.Cause.UNIFORM_SLOW in slow.causes, slow.causes.toString())
    check("совет начинается с разрешения",
        slow.advice.first().contains("Разрешение"), slow.advice.toString())

    // --- ряд «60 → 15 → 45 → 5» ------------------------------------------
    // Ровно та картина из жалобы: кусками. Скачки без единой закономерности
    // должны получить честное «нужно больше данных оверлея», а не диагноз.
    val jumping = steady(60.0, 150) + steady(15.0, 150) + steady(45.0, 150) + steady(5.0, 150, 2.0)
    val jitter = FramePacing.analyse(jumping)
    check("прыгающий ряд даёт хотя бы одну причину", jitter.causes.isNotEmpty())
    check("прыгучесть высокая", jitter.jitter > 0.5, jitter.jitter.toString())
    check("худший кадр около 200 мс", jitter.worstMs > 150.0, jitter.worstMs.toString())
    check("средняя между крайностями",
        jitter.averageFps > 10 && jitter.averageFps < 45, jitter.averageFps.toString())
    check("медиана названа в фактах", jitter.facts.any { it.contains("медиана") })

    // --- мало данных -----------------------------------------------------
    val few = FramePacing.analyse(listOf(16.0, 17.0, 16.5))
    eq("мало данных — причина «мало данных»", few.causes, listOf(FramePacing.Cause.UNKNOWN))
    check("мало данных — советов нет", few.advice.isEmpty())
    check("мало данных — просят поиграть минуту",
        few.facts.any { it.contains("минуту") }, few.facts.toString())

    // Мусор в ряду (нули, отрицательные, абсурд) не должен ломать разбор.
    val dirty = steady(60.0, 200) + listOf(0.0, -5.0, 99999.0, Double.NaN)
    val cleaned = FramePacing.analyse(dirty)
    check("мусор отброшен", cleaned.samples == 200, cleaned.samples.toString())
    check("мусор не испортил среднюю", cleaned.averageFps in 55.0..65.0, cleaned.averageFps.toString())

    // --- про звук ---------------------------------------------------------
    check("ровные кадры + рваный звук → виноват буфер",
        FramePacing.audioVerdict(frameStable = true, underruns = 3, bufferMs = 30)
            .contains("буфер звука"), "")
    check("рваные кадры + целый звук → виновата отрисовка",
        FramePacing.audioVerdict(frameStable = false, underruns = 0, bufferMs = 30)
            .contains("тормозит отрисовка"), "")
    check("и то и другое неровное → не хватает целиком",
        FramePacing.audioVerdict(frameStable = false, underruns = 5, bufferMs = 30)
            .contains("не хватает производительности"), "")
    check("всё ровно → не на что жаловаться",
        FramePacing.audioVerdict(frameStable = true, underruns = 0, bufferMs = 30)
            .contains("ровные"), "")

    println()
    println("passed=$passed failed=${failures.size}")
    failures.forEach { println("FAIL  $it") }
    if (failures.isNotEmpty()) kotlin.system.exitProcess(1)
}

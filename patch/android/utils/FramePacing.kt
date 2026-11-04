// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import kotlin.math.abs

/**
 * Почему кадры прыгают: 60 → 15 → 45 → 5, хотя «всё остальное стабильно».
 *
 * ЧТО ПОКАЗЫВАЕТ САМА ЖАЛОБА
 *   Две разные картины в одном сообщении, и их легко перепутать:
 *
 *   1. Аркадные игры: звуковые эффекты и музыка с тормозами, а картинка
 *      стабильна. Это звук отстаёт от кадров, а не наоборот.
 *   2. Легенда Зельды: звук ровный, а кадры скачут 60 → 15 → 45 → 5.
 *
 *   Если бы телефон просто «не тянул», страдали бы и звук, и картинка
 *   одновременно. Разделение по играм говорит о другом: где-то дороже
 *   графика, где-то — звук, и лечится это разными настройками.
 *
 * ПОЧЕМУ КАДРЫ ПРЫГАЮТ, А НЕ ПРОСТО НИЗКИЕ
 *   Несколько очень разных причин, и все выглядят одинаково на глаз:
 *
 *   • Шейдеры собираются на ходу. Игра пошла в новый угол обзора — новый
 *     шейдер — пауза на кадр (или на десять). Это «просадка на ровном
 *     месте», которая проходит после повторного прохода.
 *   • Упор в вертикальную синхронизацию. Опоздал на один кадр — получил
 *     ровно половину частоты: 60 → 30 → 15. Числа 60/45/30/15 — это именно
 *     деления частоты экрана, а не «сколько успел телефон».
 *   • Тепловой сброс частот. Двадцать минут ровно, потом половина — это
 *     не игра стала сложнее, это SoC сбросил частоты.
 *   • Упор в один поток. Эмулятор держит кадр на одном «главном» потоке;
 *     если он занят, остальные ядра не помогают.
 *   • Мало памяти под кадр: система выгружает текстуры, а потом читает их
 *     обратно с флеша.
 *
 *   Отсюда главное правило: сначала ФАКТЫ, потом советы. «Поставьте
 *   турбо-режим» без данных о том, что частота падает от перегрева, —
 *   это гадание, и оно делает хуже.
 *
 * ЧТО ЗДЕСЬ ЕСТЬ
 *   Чистые функции над числами: ряд частот кадра, средняя и «худший
 *   процент», поиск лестницы синхронизации, совпадение с тепловым
 *   порогом, вывод «что это значит» и что менять. Их можно проверить на
 *   хосте, без телефона и без игры.
 */
object FramePacing {

    /**
     * Причина неровных кадров. Порядок объявления — порядок показа: сначала
     * то, что видно по числам точнее всего.
     */
    enum class Cause(val label: String) {
        /** Частота кадра упала до доли частоты экрана и держится. */
        VSYNC_STEP("упор в синхронизацию экрана"),

        /** Просадки на ровном месте, которые проходят при повторном проходе. */
        SHADER_COMPILE("сборка шейдеров на ходу"),

        /** Падение после N минут и не возвращается: сброс частот SoC. */
        THERMAL("тепловой сброс частот"),

        /** Один кадр из сотни чудовищно долгий, остальные ровные. */
        SINGLE_THREAD("упор в один поток"),

        /** Ровно, но медленно: одинаково долгие кадры без скачков. */
        UNIFORM_SLOW("просто не хватает производительности"),

        /** Прыгает без закономерности. */
        NOISY("неровная загрузка"),

        /** Данных мало: ничего не утверждаем. */
        UNKNOWN("мало данных")
    }

    /** Итог разбора ряда кадровых времён (в миллисекундах). */
    data class Report(
        val samples: Int,
        val averageFps: Double,
        val medianFps: Double,
        /** FPS, который не превышается в 90% кадров: «как ощущается». */
        val p90Fps: Double,
        /** Худшие 1% — это и есть «прыгнуло на 5». */
        val p1Fps: Double,
        val worstMs: Double,
        /** Насколько ряд прыгает: 0 — ровно, 1 — от нуля до упора. */
        val jitter: Double,
        val causes: List<Cause>,
        /** Что сказать человеку: факты, не советы. */
        val facts: List<String>,
        /** Что менять — по одной настройке за раз, с причиной. */
        val advice: List<String>
    )

    /** Частоты экрана, деления которых выглядят как «упор в синхронизацию». */
    // Частоты экранов, доли которых дают знакомые «половину» и «треть».
    // Здесь НЕТ 30: 30 кадров/с на экране 60 Гц — ровно половина, то есть
    // упор в синхронизацию, а на экране 30 Гц — норма. Различить их может
    // только известная частота экрана (refreshHint), поэтому её и слушаем.
    private val refreshRates = listOf(60.0, 90.0, 120.0, 144.0)

    /**
     * Разобрать ряд кадровых времён.
     *
     * [frameMs] — длительности кадров в миллисекундах, последние — свежие.
     * [refreshHint] — частота экрана, если известна (0 — неизвестна).
     * [thermalStart] — с какого образца начался нагрев, если тепловой
     *   монитор уже видел предупреждение (null — не видел).
     */
    fun analyse(
        frameMs: List<Double>,
        refreshHint: Double = 60.0,
        thermalStart: Int? = null,
        shaderHitches: Int = 0
    ): Report {
        val clean = frameMs.filter { it > 0.1 && it < 5000.0 }
        if (clean.size < 20) {
            return Report(
                samples = clean.size,
                averageFps = 0.0,
                medianFps = 0.0,
                p90Fps = 0.0,
                p1Fps = 0.0,
                worstMs = 0.0,
                jitter = 0.0,
                causes = listOf(Cause.UNKNOWN),
                facts = listOf(
                    "Собрано ${clean.size} кадров — этого мало для вывода. " +
                        "Поиграйте минуту и откройте экран снова."
                ),
                advice = emptyList()
            )
        }

        val sorted = clean.sorted()
        val averageFps = 1000.0 / clean.average()
        val medianMs = sorted[sorted.size / 2]
        val medianFps = 1000.0 / medianMs
        val p90Ms = sorted[(sorted.size * 0.9).toInt().coerceIn(0, sorted.lastIndex)]
        val p90Fps = 1000.0 / p90Ms
        val p1Index = (sorted.size * 0.01).toInt().coerceIn(0, sorted.lastIndex)
        val p1Fps = 1000.0 / sorted[p1Index]
        val worstMs = sorted.last()

        // Прыгучесть: относительный разброс. Не дисперсия — она не имеет
        // смысла в глазах человека, а именно доля от среднего.
        val jitter = ((worstMs - medianMs) / medianMs).coerceIn(0.0, 1.0)

        val causes = ArrayList<Cause>()
        val facts = ArrayList<String>()
        val advice = ArrayList<String>()

        // 1. Лестница синхронизации: медиана рядом с половиной или третью
        //    частоты экрана И разброс небольшой.
        val stepFps = snapToRefresh(medianFps, refreshHint)
        if (stepFps > 0 && jitter < 0.9) {
            causes.add(Cause.VSYNC_STEP)
            facts.add(
                "Медиана ${medianFps.round1()} кадров/с — это ровно доля частоты экрана " +
                    "(${refreshHint.round1()} → ${stepFps.round1()}). Так выглядит упор в " +
                    "синхронизацию: опоздал на кадр — получил сразу половину."
            )
        }

        // 2. Тепловой сброс: до какого-то места ровно, после — устойчиво ниже.
        val thermal = detectStep(clean, thermalStart)
        if (thermal != null) {
            causes.add(Cause.THERMAL)
            facts.add(
                "До ${thermal.first} кадра средняя ${thermal.beforeFps.round1()} кадров/с, " +
                    "после — ${thermal.afterFps.round1()}. Ровный обрыв по времени — это сброс " +
                    "частот процессора и видеочипа от нагрева, а не усложнившаяся сцена."
            )
            advice.add(
                "Снимите чехол и дайте телефону остыть 5 минут: если после этого снова ровно — " +
                    "дело в нагреве, а не в настройках."
            )
            advice.add(
                "«Инструменты» → «Подбор FPS»: профиль с меньшим разрешением держит частоты " +
                    "дольше, чем турбо-режим с перегревом."
            )
        }

        // 3. Шейдеры: короткие просадки при стабильной медиане.
        val hitchThreshold = medianMs * 3
        val hitches = clean.count { it > hitchThreshold }
        if (hitches >= 2 && jitter > 0.6 && (thermal == null || hitches > clean.size / 20)) {
            causes.add(Cause.SHADER_COMPILE)
            facts.add(
                "Ровная медиана ${medianFps.round1()} кадров/с, но $hitches кадров длиннее " +
                    "тройного обычного. Это типичная цена сборки шейдера: новый эффект — пауза, " +
                    "при повторном проходе её уже нет."
            )
            advice.add(
                "Включите сборку шейдеров заранее: «Настройки» → «Графика» → " +
                    "«Асинхронные шейдеры» включить, «Точность GPU» — «Средняя». " +
                    "Игра пройдёт те же места второй раз без этих пауз."
            )
        }

        if (shaderHitches > 0 && Cause.SHADER_COMPILE !in causes) {
            facts.add("Сборок шейдеров за это время: $shaderHitches.")
        }

        // 4. Один поток: очень редкие, но чудовищные кадры.
        if (jitter > 0.97 && hitches <= 1 && medianFps > 45) {
            causes.add(Cause.SINGLE_THREAD)
            facts.add(
                "Медиана ${medianFps.round1()} кадров/с хорошая, но худший кадр " +
                    "${worstMs.round0()} мс. Похоже на упор в главный поток: кадр целиком " +
                    "ждёт одну задачу, и ядра ему не помогают."
            )
            advice.add(
                "Проверьте точность процессора: «Точность CPU» → «Небезопасная» даёт больше " +
                    "скорости на главном потоке. Если стало хуже — верните «Авто»."
            )
        }

        // 5. Ровно и медленно — честно назвать это тем, что есть.
        if (jitter < 0.35 && medianFps < refreshHint * 0.9 && Cause.VSYNC_STEP !in causes) {
            causes.add(Cause.UNIFORM_SLOW)
            facts.add(
                "Кадры ровные (разброс ${((jitter) * 100).round0()}%), но медиана " +
                    "${medianFps.round1()} кадров/с. Это не скачки, а нехватка " +
                    "производительности: картинка идёт медленно, но одинаково."
            )
            advice.add(
                "Разрешение — первое, что стоит снизить: «Разрешение» → 0.75x обычно даёт " +
                    "треть кадров и почти не портит картинку на экране телефона."
            )
        }

        // 6. Всё остальное — неровная загрузка без явной закономерности.
        if (causes.isEmpty()) {
            causes.add(Cause.NOISY)
            facts.add(
                "Медиана ${medianFps.round1()} кадров/с, худший кадр ${worstMs.round0()} мс, " +
                    "1% кадров медленнее ${p1Fps.round1()} кадров/с. Явной закономерности нет: " +
                    "нужно больше данных — поиграйте минуту с включённым оверлеем."
            )
            advice.add(
                "Откройте «Инструменты» → «Подбор FPS» и выберите профиль под это устройство: " +
                    "он меняет несколько настроек сразу, а не одну наугад."
            )
        }

        // Общие факты — всегда, потому что это первое, что спросят.
        facts.add(
            "Всего ${clean.size} кадров: средняя ${averageFps.round1()}, медиана " +
                "${medianFps.round1()}, 90% кадров не медленнее ${p90Fps.round1()}, " +
                "худшие 1% — ${p1Fps.round1()} кадров/с."
        )

        return Report(
            samples = clean.size,
            averageFps = averageFps,
            medianFps = medianFps,
            p90Fps = p90Fps,
            p1Fps = p1Fps,
            worstMs = worstMs,
            jitter = jitter,
            causes = causes,
            facts = facts,
            advice = advice
        )
    }

    /**
     * Отдельно — про звук.
     *
     * Звук в эмуляторе идёт по своему буферу и не обязан совпадать с
     * кадрами. Если кадры ровные, а звук «догоняет», то буфер звука мал для
     * этого телефона; если наоборот, звук тянет кадры за собой.
     */
    fun audioVerdict(frameStable: Boolean, underruns: Int, bufferMs: Int): String = when {
        frameStable && underruns > 0 ->
            "Кадры ровные, а звук рвался $underruns раз: буфер звука ($bufferMs мс) слишком " +
                "мал для этого телефона. Увеличение до 60–80 мс убирает треск и не мешает " +
                "играть; на задержку в аркадах это влияет меньше, чем кажется — при 60 мс " +
                "разница с 40 мс на глаз не ловится."
        !frameStable && underruns == 0 ->
            "Звук целый, а кадры неровные: значит тормозит отрисовка, и чинить нужно её, а не звук."
        frameStable && underruns == 0 ->
            "И кадры, и звук ровные — жаловаться не на что."
        else ->
            "И кадры, и звук неровные: телефону не хватает производительности целиком, " +
                "начинайте с разрешения и точности GPU."
    }

    /** Число кадров в секунду для крупной подписи на экране. */
    fun formatFps(fps: Double): String = when {
        fps <= 0.0 -> "—"
        fps >= 100.0 -> String.format(java.util.Locale.ROOT, "%.0f", fps)
        else -> String.format(java.util.Locale.ROOT, "%.1f", fps)
    }

    // ── внутреннее ──────────────────────────────────────────────────────

    private data class Step(val first: Int, val beforeFps: Double, val afterFps: Double)

    /**
     * Найти ровный обрыв производительности по времени.
     *
     * Сравниваем первую треть со последней: усложнение сцены даёт шум, а
     * нагрев — устойчивую разницу. Порог намеренно грубый (25%), иначе шум
     * сцены выдавался бы за перегрев.
     */
    private fun detectStep(samples: List<Double>, thermalStart: Int?): Step? {
        if (samples.size < 60) return null
        val third = samples.size / 3
        val head = samples.take(third)
        val tail = samples.takeLast(third)
        val headFps = 1000.0 / head.average()
        val tailFps = 1000.0 / tail.average()
        val fell = tailFps < headFps * 0.75
        if (!fell && thermalStart == null) return null
        // Когда именно — ищем первое место, после которого средняя за треть
        // падает ниже 80% исходной.
        var first = samples.size - 1
        for (i in third until samples.size) {
            val window = samples.subList(i, minOf(samples.size, i + third))
            if (window.size < 5) break
            if (1000.0 / window.average() < headFps * 0.8) {
                first = i
                break
            }
        }
        return Step(first, headFps, tailFps)
    }

    /**
     * Ближайшая известная частота экрана, если медиана — её заметная доля.
     *
     * Возвращает 0, когда совпадения нет И когда медиана равна самой частоте
     * экрана: 60 при экране 60 — это норма, а не «упор». Раньше проверка
     * шла долями вперёд, 60 подходило под «удвоенную половину», и ровные
     * 60 кадров объявлялись упором в синхронизацию.
     */
    private fun snapToRefresh(medianFps: Double, refreshHint: Double): Double {
        val rates = (refreshRates + refreshHint).filter { it > 20 }.distinct().sorted()
        fun close(value: Double, target: Double) = abs(value - target) <= maxOf(0.8, target * 0.05)
        // Точное совпадение с частотой экрана — норма.
        if (close(medianFps, refreshHint)) return 0.0
        if (rates.any { close(medianFps, it) }) return 0.0
        for (rate in rates) {
            for (fraction in listOf(2.0 / 3.0, 0.5, 1.0 / 3.0, 0.25)) {
                val target = rate * fraction
                if (close(medianFps, target)) return target
            }
        }
        return 0.0
    }

    private fun Double.round0(): String = Math.round(this).toString()
    private fun Double.round1(): String = String.format(java.util.Locale.ROOT, "%.1f", this)
}

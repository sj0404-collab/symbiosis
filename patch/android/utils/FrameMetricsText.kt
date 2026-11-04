// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

/**
 * Разбор снимка, который отдаёт нативная часть (`FrameMetrics::Snapshot`).
 *
 * ФОРМАТ
 *     total=12345
 *     count=600
 *     underruns=3
 *     shaders=12
 *     samples=16.6,16.7,…
 *
 * ПОЧЕМУ ТЕКСТ, А НЕ JSON
 *   Снимок собирается в горячем пути и разбирается на экране. Текст
 *   «ключ=значение» нельзя разобрать «почти правильно»: либо ключ есть, либо
 *   его нет, и никакой парсер не упадёт на пропущенной запятой. Плюс он
 *   читается глазами, если придётся смотреть журнал.
 *
 *   Всё, что может испортиться, обрабатывается здесь: неизвестные ключи
 *   игнорируются, мусор в числах пропускается, отсутствующий снимок даёт
 *   пустой результат, а не исключение. Экран не должен падать из-за того,
 *   что нативная часть ответила не тем.
 */
object FrameMetricsText {

    /** Что пришло из нативной части. */
    data class Snapshot(
        /** Сколько кадров записано всего за сеанс. */
        val totalFrames: Long = 0,
        /** Провалы звука: буфер кончался, в динамик ушёл повтор кадра. */
        val audioUnderruns: Long = 0,
        /** Сколько раз собраны новые шейдеры. */
        val shaderCompiles: Long = 0,
        /** Длительности кадров, мс, от старых к новым. */
        val frameMs: List<Double> = emptyList()
    ) {
        val isEmpty: Boolean get() = frameMs.isEmpty()
    }

    /**
     * Разобрать снимок.
     *
     * Одна строка — один ключ. Повторяющийся ключ перезаписывается: так
     * «samples» не склеится в двойной список, если строк окажется две.
     */
    fun parse(raw: String): Snapshot {
        if (raw.isBlank()) return Snapshot()
        var total = 0L
        var underruns = 0L
        var shaders = 0L
        var samples = emptyList<Double>()

        raw.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEach
            // Ключ и значение обрезаем по отдельности: «total = 42» —
            // такой же обычный ответ, как «total=42», и пробелы не должны
            // делать из известного ключа неизвестный.
            val key = trimmed.substringBefore('=', "").trim()
            val value = trimmed.substringAfter('=', "").trim()
            when (key) {
                "total" -> total = value.toLongOrNull() ?: total
                "underruns" -> underruns = value.toLongOrNull() ?: underruns
                "shaders" -> shaders = value.toLongOrNull() ?: shaders
                "samples" -> samples = value.split(',')
                    .mapNotNull { it.trim().toDoubleOrNull() }
                    // Нативный снимок обрезает паузы пятью секундами, но
                    // отрицательные и нулевые сюда всё равно не пройдут.
                    .filter { it > 0.0 && it < 5000.0 }
                // count, и всё незнакомое — просто не нужно экрану.
                else -> Unit
            }
        }
        return Snapshot(total, underruns, shaders, samples)
    }

    /**
     * Человеческая строка для шапки экрана.
     *
     * «600 кадров · провалов звука: 3 · сборок шейдеров: 12» — это первое,
     * что человек должен прочитать, ещё до советов.
     */
    fun headline(snapshot: Snapshot): String {
        if (snapshot.isEmpty) {
            return "Данных пока нет: запустите игру, поиграйте минуту и вернитесь сюда."
        }
        val parts = mutableListOf<String>()
        parts.add("${snapshot.frameMs.size} кадров в снимке")
        parts.add("провалов звука: ${snapshot.audioUnderruns}")
        if (snapshot.shaderCompiles > 0) {
            parts.add("сборок шейдеров: ${snapshot.shaderCompiles}")
        }
        return parts.joinToString(" · ")
    }
}

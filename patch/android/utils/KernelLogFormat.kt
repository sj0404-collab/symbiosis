// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import java.io.File

/**
 * Чистая часть журнала ядра: чтение хвоста и подсчёт проблем.
 *
 * Отдельным объектом без единого импорта из Android — чтобы это можно было
 * проверить на хосте (tests/KernelLogTest.kt). Логика чтения хвоста — не
 * «мелочь»: журнал за долгий сеанс бывает в сотни мегабайт, и наивное
 * `readText()` роняет приложение по памяти ровно тогда, когда пользователь
 * пришёл искать причину падения.
 */
object KernelLogFormat {

    /** Сколько последних байт показывать по умолчанию. */
    const val TAIL_BYTES = 256 * 1024

    /**
     * Хвост файла, без чтения его целиком.
     *
     * Первая строка обрезана (мы начали не с её начала) — её выбрасываем:
     * половина строки в журнале выглядит как повреждение.
     */
    fun tailOf(file: File, maxBytes: Int): String {
        val length = file.length()
        val from = if (length > maxBytes) length - maxBytes else 0L
        val bytes = java.io.RandomAccessFile(file, "r").use { raf ->
            raf.seek(from)
            val buffer = ByteArray((length - from).toInt())
            raf.readFully(buffer)
            buffer
        }
        val text = String(bytes, Charsets.UTF_8)
        return if (from > 0) text.substringAfter('\n', text) else text
    }

    /**
     * Сколько строк уровня Error и Critical.
     *
     * Ядро печатает уровень прямо в строке (`[ 12.345678] Loader <Error>`),
     * поэтому достаточно поиска подстрок. Это ответ на вопрос «в журнале
     * есть что-то плохое?» до чтения тысячи строк.
     */
    fun countProblems(text: String): Int = text.lineSequence()
        .count { it.contains("<Error>") || it.contains("<Critical>") }
}

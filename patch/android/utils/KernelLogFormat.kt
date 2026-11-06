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
     * Разбор куска журнала на ЦЕЛЫЕ строки.
     *
     * ЗАЧЕМ ЭТО ВООБЩЕ. Журнал дописывается ядром во время игры, поэтому
     * читать его целиком каждые три секунды нельзя. Мы читаем только новый
     * кусок — от прошлого смещения до конца файла. Но кусок почти всегда
     * обрывается посреди последней строки: её пишут прямо сейчас.
     *
     * На телефоне 06.11 это стоило целого раунда: строка
     * `SvcBreak reason=0x0 pid=109 …` попала в два чтения — `SvcBr` в одно,
     * `eak reason=0x0 …` в другое. Поиск подстроки «SvcBreak» не нашёл её ни
     * разу, хотя на экране она была видна: экран читал файл целиком. Отсюда и
     * «второе ядро не отдало ни одного кадра за 30 с» вместо приговора.
     *
     * Теперь смещение двигается только до последнего перевода строки, а
     * хвостовой огрызок остаётся на следующее чтение — тогда строка
     * складывается целиком и находится.
     *
     * @return текст целых строк и сколько байт из куска израсходовано
     *         (0 — в куске нет ни одного перевода строки, читать нечего)
     */
    fun completeLines(bytes: ByteArray, count: Int): Pair<String, Int> {
        var end = minOf(count, bytes.size) - 1
        while (end >= 0 && bytes[end] != '\n'.code.toByte()) end--
        if (end < 0) return "" to 0
        return String(bytes, 0, end + 1, Charsets.UTF_8) to (end + 1)
    }

    /**
     * Последние строки, где встречается одно из слов.
     *
     * Нужно, чтобы показать, что ядро само говорит про отрисовку: без этого
     * экран повторяет только наши слова («кадров нет»), а причина остаётся в
     * файле, которого человек не видит.
     */
    fun pickLines(text: String, needles: List<String>, limit: Int): List<String> =
        text.lineSequence()
            .filter { line -> needles.any { line.contains(it, ignoreCase = true) } }
            .map { it.trim().take(160) }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
            .takeLast(limit)

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

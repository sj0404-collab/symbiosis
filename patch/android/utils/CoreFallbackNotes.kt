// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

/**
 * Записки «эту игру ядро уже роняло» — чистая логика, без Android.
 *
 * ЗАЧЕМ ОТДЕЛЬНЫЙ ФАЙЛ. Решение принимает главный процесс, а записку
 * оставляет процесс `:kenji`; между ними файл. Разбор этого файла должен
 * быть проверяемым без телефона, иначе ошибка в разборе стоит ещё одной
 * сборки — один такой круг уже был, когда обещание «переведена на Eden»
 * печаталось, даже если ключ игры не сохранился.
 *
 * Формат строки: `<путь>\t<причина>`. Табуляция, потому что в пути и в
 * причине её не бывает, а переводы строк в причине ломают разбор.
 */
object CoreFallbackNotes {

    /** Сколько записок держим: больше не нужно, а файл не должен расти. */
    const val KEEP = 8

    /** Имя файла в записке: по нему ищем совпадение, если путь записан иначе. */
    fun fileNameOf(path: String): String =
        path.substringAfterLast('/').substringAfterLast(':').trim()

    /** Одна и та же игра: тот же путь или тот же файл в другой папке. */
    fun sameGame(a: String, b: String): Boolean {
        if (a.isBlank() || b.isBlank()) return false
        if (a == b) return true
        val na = fileNameOf(a).lowercase()
        val nb = fileNameOf(b).lowercase()
        return na.isNotEmpty() && na == nb && (a.contains('/') || b.contains('/'))
    }

    /** Разобрать файл в пары «путь — причина». Мусорные строки отбрасываются. */
    fun entries(text: String): List<Pair<String, String>> =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                val tab = line.indexOf('\t')
                if (tab <= 0) null else line.substring(0, tab).trim() to line.substring(tab + 1).trim()
            }
            .toList()

    /** Есть ли записка про эту игру. */
    fun has(text: String, path: String): Boolean =
        entries(text).any { sameGame(it.first, path) }

    /** Причина из записки про эту игру, если она есть. */
    fun reasonFor(text: String, path: String): String =
        entries(text).firstOrNull { sameGame(it.first, path) }?.second.orEmpty()

    /** Добавить записку, не дублируя игру, и оставить только последние [KEEP]. */
    fun add(text: String, path: String, reason: String): String {
        if (path.isBlank()) return text
        val kept = entries(text).filterNot { sameGame(it.first, path) }
        val merged = kept + (path to reason.replace('\t', ' ').replace('\n', ' '))
        return merged.takeLast(KEEP).joinToString("\n") { "${it.first}\t${it.second}" }
    }

    /** Убрать записку про эту игру — когда человек сам выбрал ядро. */
    fun remove(text: String, path: String): String =
        entries(text).filterNot { sameGame(it.first, path) }
            .joinToString("\n") { "${it.first}\t${it.second}" }
}

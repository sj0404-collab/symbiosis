// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import java.io.File

/**
 * Есть ли у нас ключ конкретной игры — по файлам на диске, без ядра.
 *
 * ЗАЧЕМ
 *   На телефоне `prod.keys` был на 204 ключа, `title.keys` — на 6, и экран
 *   честно писал `core reports keys present: true`. Библиотека после этого
 *   говорила «ключи на месте, значит дело не в них» — и это было новой
 *   неправдой: 204 ключа консоли не содержат ключа ни одной игры. Ключ игры
 *   лежит либо в билете внутри образа, либо строкой `0100… = 32hex` в
 *   `title.keys`. Если билета с правом нет, а строки нет — ядро не
 *   расшифрует NCA и скажет MissingTitlekey.
 *
 * ЧТО СЧИТАЕТСЯ КЛЮЧОМ ИГРЫ
 *   Строка, у которой до `=` ровно 16 шестнадцатеричных знаков. Это правило
 *   уже есть в [ExtraKeys] и здесь переиспользуется, чтобы не завести второе,
 *   чуть другое.
 */
object TitleKeys {

    /** Идентификаторы игр, для которых в этих строках есть ключ. */
    fun ids(lines: List<String>): Set<String> = lines
        .asSequence()
        .map { it.trim() }
        .filter { ExtraKeys.isTitleLine(it) }
        .map { ExtraKeys.keyOf(it).lowercase() }
        .filter { isPlausible(it) }
        .toSortedSet()

    /**
     * Идентификатор похож на настоящий.
     *
     * Мусор вида `ffffffffffffffff` в билетах встречается постоянно — это
     * «права нет». Показывать его человеку как идентификатор игры нельзя:
     * на телефоне в диалоге так и вышло, «игра ffffffffffffffff».
     */
    fun isPlausible(titleId: String): Boolean {
        if (titleId.length != 16) return false
        if (!titleId.all { it.isDigit() || it in 'a'..'f' }) return false
        if (titleId.toSet().size == 1) return false
        return titleId.startsWith("0100")
    }

    /** Есть ли ключ для игры. [titleId] — 16 hex-знаков. */
    fun has(lines: List<String>, titleId: String): Boolean {
        if (!isPlausible(titleId.lowercase())) return false
        return ids(lines).contains(titleId.lowercase())
    }

    /**
     * Все строки ключей игры из папки ключей: `title.keys` и то, что ядро
     * подхватывает само (`*.keys`, `*.txt` в корне и в `extra`).
     *
     * Читается только текст: ядро всё равно перечитает эти файлы само, а нам
     * нужен ответ «есть или нет» до запуска.
     */
    fun readAll(keysDir: File?): List<String> {
        if (keysDir == null || !keysDir.isDirectory) return emptyList()
        val out = mutableListOf<String>()
        val title = File(keysDir, "title.keys")
        if (title.isFile) out += runCatching { title.readLines() }.getOrDefault(emptyList())
        // Корень и вложенная extra — как их видит сам ядро при загрузке
        // (ExtraKeys собирает ровно этот же список).
        val names = (keysDir.list() ?: emptyArray()).toList().toMutableList()
        val extraDir = File(keysDir, "extra")
        if (extraDir.isDirectory) {
            (extraDir.list() ?: emptyArray()).forEach { names.add("extra/$it") }
        }
        for (name in ExtraKeys.extraFileNames(names)) {
            val file = File(keysDir, name)
            if (file.isFile) out += runCatching { file.readLines() }.getOrDefault(emptyList())
        }
        return out
    }

    /**
     * Строка ключа игры из того, что человек вписал руками.
     *
     * @return «0100… = 32hex» или null, если ввод не годится: путать
     *   «не тот формат» и «ключ не подошёл» нельзя — это разные починки.
     */
    fun manualLine(id: String, key: String): String? {
        val cleanId = id.filterNot { it.isWhitespace() }.lowercase()
        val cleanKey = key.filterNot { it.isWhitespace() }.lowercase()
        if (!isPlausible(cleanId)) return null
        if (cleanKey.length != 32) return null
        if (!cleanKey.all { it.isDigit() || it in 'a'..'f' }) return null
        return "$cleanId = $cleanKey"
    }

    /**
     * Вставить строку ключа в `title.keys`, заменив прежнюю запись той же игры.
     *
     * Ключей игр много, по одному на игру, поэтому добавление — это всегда
     * правка списка, а не замена файла: иначе одна новая игра стёрла бы все
     * остальные.
     */
    fun upsert(lines: List<String>, line: String): List<String> {
        val id = ExtraKeys.keyOf(line).lowercase()
        if (id.isEmpty()) return lines
        val out = lines.toMutableList()
        val index = out.indexOfFirst { ExtraKeys.isTitleLine(it) && ExtraKeys.keyOf(it).lowercase() == id }
        if (index >= 0) out[index] = line else out.add(line)
        return out
    }

    /**
     * Что сказать на экране ключей.
     *
     * @param wanted идентификатор игры, на которой человек застрял; пусто —
     *               просто список
     */
    fun report(lines: List<String>, wanted: String = ""): String {
        val present = ids(lines)
        if (present.isEmpty()) {
            return "ключей игр нет: ни одной строки «0100… = 32hex» в title.keys " +
                "и в дополнительных файлах"
        }
        val builder = StringBuilder()
        builder.append("ключей игр: ").append(present.size).append('\n')
        present.take(12).forEach { builder.append("  ").append(it).append('\n') }
        if (present.size > 12) {
            builder.append("  … ещё ").append(present.size - 12).append('\n')
        }
        if (wanted.isNotEmpty() && isPlausible(wanted.lowercase())) {
            builder.append("ключ для ").append(wanted.lowercase()).append(": ")
            builder.append(if (present.contains(wanted.lowercase())) "есть" else "НЕТ")
            builder.append('\n')
        }
        return builder.toString().trimEnd()
    }
}

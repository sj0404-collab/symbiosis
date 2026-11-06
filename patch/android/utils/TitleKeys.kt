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

    /**
     * Левая и правая части строки ключа — терпимо к тому, как её записали.
     *
     * РАЗДЕЛИТЕЛЬ. `title.keys` в природе встречается и с `=`, и с `:`, и с
     * табуляцией вместо пробелов вокруг. Требовать ровно `=` значило
     * отвергнуть настоящий файл: на телефоне 06.11 в `title.keys` было
     * 6 записей, а приложение сказало «ни одной строки» — то есть соврало
     * про файл, который лежит и работает.
     *
     * BOM и невидимые знаки убираются: редакторы на Windows и часть
     * конвертеров ставят их молча, и первый идентификатор перестаёт быть
     * шестнадцатеричным.
     */
    private fun parts(line: String): Pair<String, String>? {
        val clean = line.trim().trimStart('\uFEFF').replace("\u200B", "").trim()
        if (clean.isEmpty() || clean.startsWith("#") || clean.startsWith("//")) return null
        val index = clean.indexOfFirst { it == '=' || it == ':' }
        if (index < 0) return null
        val left = clean.substring(0, index).filterNot { it.isWhitespace() }
        val right = clean.substring(index + 1).filterNot { it.isWhitespace() }
        return left to right
    }

    /** Строка вида `0100… = 32hex` — ключ игры. */
    private fun isKeyLine(line: String): Boolean {
        val (left, right) = parts(line) ?: return false
        return isHex(left, 16) && isHex(right, 32)
    }

    private fun isHex(text: String, length: Int): Boolean =
        text.length == length && text.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }

    /** Идентификаторы игр, для которых в этих строках есть ключ. */
    fun ids(lines: List<String>): Set<String> = lines
        .asSequence()
        .filter { isKeyLine(it) }
        .mapNotNull { parts(it)?.first?.lowercase() }
        .filter { isPlausible(it) }
        .toSortedSet()

    /**
     * Привести чужие строки к каноническому виду `0100… = 32hex`.
     *
     * Нужно при установке файла: человек приносит `title.keys` в том виде, в
     * каком его сделал его инструмент, а ядро ждёт ровно `=`. Заодно
     * отбрасываются мусорные и «права нет» (FF) — их нельзя путать с ключом.
     */
    fun keyLines(text: String): List<String> = text.lineSequence()
        .mapNotNull { raw ->
            val (left, right) = parts(raw) ?: return@mapNotNull null
            if (!isHex(left, 16) || !isHex(right, 32)) return@mapNotNull null
            if (!isPlausible(left.lowercase())) return@mapNotNull null
            left.lowercase() + " = " + right.lowercase()
        }
        .toList()

    /** Что нашлось в наборе строк: принятые ключи и отвергнутые строки. */
    data class Survey(
        val ids: List<String>,
        /** Строки с разделителем, которые ключом игры не оказались. */
        val rejected: List<String>,
        /** Сколько строк вообще содержит `=` или `:` (для честного счёта). */
        val withSeparator: Int
    )

    /**
     * Разобрать строки и объяснить, что с ними не так.
     *
     * Нужно не для логики, а для ответа человеку: «в файле нет строк вида» при
     * шести настоящих записях — это не диагноз, а отписка. Здесь у каждой
     * отвергнутой строки есть причина, а значение ключа в отчёт не попадает:
     * показывается только левая часть и длина правой.
     */
    fun survey(lines: List<String>): Survey {
        val accepted = mutableListOf<String>()
        val rejected = mutableListOf<String>()
        var withSeparator = 0
        for (raw in lines) {
            val (left, right) = parts(raw) ?: continue
            withSeparator++
            when {
                isKeyLine(raw) && isPlausible(left.lowercase()) -> accepted.add(left.lowercase())
                !isHex(left, 16) ->
                    rejected.add("«" + left.take(20) + "» — слева не 16 hex-знаков (" + left.length + ")")
                !isHex(right, 32) ->
                    rejected.add(left.lowercase() + " — справа не 32 hex-знака (" + right.length + ")")
                else -> rejected.add(left.lowercase() + " — идентификатор не похож на игру (0100…)")
            }
        }
        return Survey(accepted.distinct().sorted(), rejected, withSeparator)
    }

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
     * Прочитать текстовый файл ключей так, как он записан на самом деле.
     *
     * `title.keys` встречается в UTF-16 (блокнот Windows сохраняет так по
     * умолчанию для некоторых версий) и с BOM. Если прочитать его как UTF-8,
     * каждая буква отделяется нулевым байтом, и «0100…» перестаёт быть
     * шестнадцатеричным — файл выглядит пустым, хотя он полный. Именно это и
     * вышло на телефоне: 6 записей на экране и «ключей игр нет» в отчёте.
     */
    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 2) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            if (b0 == 0xFF && b1 == 0xFE) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            if (b0 == 0xFE && b1 == 0xFF) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        // Без BOM: если каждый второй байт нулевой в начале файла — это UTF-16.
        val head = bytes.take(64)
        val zerosEven = head.filterIndexed { i, b -> i % 2 == 0 && b == 0.toByte() }.size
        val zerosOdd = head.filterIndexed { i, b -> i % 2 == 1 && b == 0.toByte() }.size
        if (head.size >= 8 && zerosOdd > head.size / 4 && zerosEven == 0) {
            return String(bytes, Charsets.UTF_16LE)
        }
        if (head.size >= 8 && zerosEven > head.size / 4 && zerosOdd == 0) {
            return String(bytes, Charsets.UTF_16BE)
        }
        return String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
    }

    private fun readTextFile(file: File): List<String> =
        runCatching { decode(file.readBytes()).lineSequence().toList() }.getOrDefault(emptyList())

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
        if (title.isFile) out += readTextFile(title)
        // Корень и вложенная extra — как их видит сам ядро при загрузке
        // (ExtraKeys собирает ровно этот же список).
        val names = (keysDir.list() ?: emptyArray()).toList().toMutableList()
        val extraDir = File(keysDir, "extra")
        if (extraDir.isDirectory) {
            (extraDir.list() ?: emptyArray()).forEach { names.add("extra/$it") }
        }
        for (name in ExtraKeys.extraFileNames(names)) {
            val file = File(keysDir, name)
            if (file.isFile) out += readTextFile(file)
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
        val found = survey(lines)
        val builder = StringBuilder()

        if (found.ids.isEmpty()) {
            builder.append("ключей игр нет")
            if (found.withSeparator > 0) {
                // Раньше здесь стояло «ни одной строки вида 0100… = 32hex»
                // даже когда строки были: разбор не понимал разделитель.
                builder.append(": строк с «=» или «:» — ").append(found.withSeparator)
                found.rejected.take(4).forEach { builder.append("\n    ").append(it) }
                if (found.rejected.size > 4) {
                    builder.append("\n    … и ещё ").append(found.rejected.size - 4)
                }
            } else {
                builder.append(": ни одного файла с ключами игр")
            }
            return builder.toString()
        }

        // Один файл title.keys — это ключи сразу для многих игр, а не для
        // одной. Так устроена консоль, и об этом надо сказать, иначе
        // «вписать ключ игры» читается как «по одному на каждую».
        builder.append("ключей игр: ").append(found.ids.size)
        builder.append(" (один файл покрывает все эти игры сразу)\n")
        found.ids.take(12).forEach { id ->
            builder.append("  ").append(id).append("  ").append(roleOf(id)).append('\n')
        }
        if (found.ids.size > 12) {
            builder.append("  … ещё ").append(found.ids.size - 12).append('\n')
        }
        if (found.rejected.isNotEmpty()) {
            builder.append("не разобрано: ").append(found.rejected.size).append('\n')
            found.rejected.take(3).forEach { builder.append("    ").append(it).append('\n') }
        }
        if (wanted.isNotEmpty() && isPlausible(wanted.lowercase())) {
            builder.append("ключ для ").append(wanted.lowercase()).append(": ")
            builder.append(if (found.ids.contains(wanted.lowercase())) "есть" else "НЕТ")
            builder.append('\n')
        }
        return builder.toString().trimEnd()
    }

    /** Игра, обновление или дополнение — по хвосту идентификатора. */
    private fun roleOf(titleId: String): String = when {
        titleId.length != 16 -> ""
        titleId.takeLast(3) == "000" -> "игра"
        titleId.takeLast(3) == "800" -> "обновление"
        else -> "дополнение"
    }
}

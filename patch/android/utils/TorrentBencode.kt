// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

/**
 * Bencode — формат, на котором держится весь BitTorrent.
 *
 * Четыре вида значений: число, строка (она же байтовый массив), список и
 * словарь с ключами в произвольном порядке, но отсортированными по байтам.
 *
 * ПОЧЕМУ НЕ БИБЛИОТЕКА
 *   Нужно ровно это: разобрать `.torrent`, собрать пару словарей для
 *   трекера и обмена метаданными. Зависимость в 300 КБ ради 200 строк, при
 *   том что каждая зависимость — это ещё одна причина, по которой сборка
 *   может не пройти, здесь не оправдана.
 *
 * ПОЧЕМУ СМЕЩЕНИЯ
 *   Info hash — SHA-1 от БАЙТОВ словаря `info` в исходном файле, а не от
 *   его повторного кодирования: у людей в `.torrent` встречаются
 *   неотсортированные ключи и числа с `e`, и повторная сборка даст другой
 *   хеш. Поэтому разбор помнит, где именно в исходнике лежит каждое
 *   значение ([Node.start], [Node.end]).
 */
sealed class BValue {
    data class Num(val value: Long) : BValue()

    /** Строка — байты. Сравнение по содержимому, а не по ссылке. */
    class Str(val bytes: ByteArray) : BValue() {
        override fun equals(other: Any?): Boolean =
            other is Str && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = bytes.contentHashCode()

        fun text(): String = String(bytes, Charsets.UTF_8)
    }

    data class Lst(val items: List<BValue>) : BValue()
    data class Dict(val entries: List<Pair<String, BValue>>) : BValue() {
        operator fun get(key: String): BValue? =
            entries.firstOrNull { it.first == key }?.second
    }

    fun asLongOrNull(): Long? = (this as? Num)?.value
    fun asBytesOrNull(): ByteArray? = (this as? Str)?.bytes
    fun asTextOrNull(): String? = (this as? Str)?.text()
    fun asListOrNull(): List<BValue>? = (this as? Lst)?.items
    fun asDictOrNull(): Dict? = this as? Dict
}

/** Значение вместе с его местом в исходных байтах. */
data class BNode(val value: BValue, val start: Int, val end: Int)

/**
 * Разбор и сборка bencode.
 *
 * Разбор намеренно строгий: битый файл должен вернуть null, а не «сколько
 * получилось». Торрент с угаданной половиной полей — это торрент, который
 * скачает половину файла и объявит себя исправным.
 */
object TorrentBencode {

    /** Сколько вложенности допускаем. Защита от `[[[[[…` на мегабайт. */
    private const val MAX_DEPTH = 64

    fun decode(bytes: ByteArray): BValue? = decodeNode(bytes, 0)?.first?.value

    /** Разбор с позиции [start]. Возвращает значение и позицию за ним. */
    fun decodeNode(bytes: ByteArray, start: Int): Pair<BNode, Int>? {
        val parser = Parser(bytes)
        return try {
            val node = parser.parse(start, 0)
            node to node.end
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /**
     * Где в `.torrent` лежит словарь `info`.
     *
     * Именно эти байты хешируются в info hash. Верхний уровень обязан быть
     * словарём: у метаинформации торрента другой структуры не бывает.
     */
    fun infoSpan(bytes: ByteArray): IntRange? {
        val parser = Parser(bytes)
        return try {
            val top = parser.parse(0, 0).value as? BValue.Dict ?: return null
            var at = 1 // за 'd'
            for ((key, value) in top.entries) {
                if (!value.let { true } && key.isEmpty()) return null
                // Длину ключа и значения считаем по факту: парсер уже прошёл
                // этот словарь, поэтому позиции восстанавливаем повторно.
                val keyLen = key.toByteArray(Charsets.UTF_8).size
                val keyStart = at
                at = keyStart + keyLen.toString().length + 1 + keyLen
                val node = parser.parse(at, 1)
                if (key == "info") return node.start until node.end
                at = node.end
            }
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    fun encode(value: BValue): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        write(value, out)
        return out.toByteArray()
    }

    private fun write(value: BValue, out: java.io.ByteArrayOutputStream) {
        when (value) {
            is BValue.Num -> {
                out.write('i'.code)
                out.write(value.value.toString().toByteArray(Charsets.US_ASCII))
                out.write('e'.code)
            }
            is BValue.Str -> {
                out.write(value.bytes.size.toString().toByteArray(Charsets.US_ASCII))
                out.write(':'.code)
                out.write(value.bytes)
            }
            is BValue.Lst -> {
                out.write('l'.code)
                value.items.forEach { write(it, out) }
                out.write('e'.code)
            }
            is BValue.Dict -> {
                out.write('d'.code)
                // Ключи обязаны идти по возрастанию байтов: этого ждёт и
                // трекер, и обмен метаданными с пирами.
                value.entries.sortedBy { it.first }.forEach { (key, v) ->
                    write(BValue.Str(key.toByteArray(Charsets.UTF_8)), out)
                    write(v, out)
                }
                out.write('e'.code)
            }
        }
    }

    private class Parser(private val bytes: ByteArray) {

        fun parse(start: Int, depth: Int): BNode {
            require(depth <= MAX_DEPTH) { "слишком глубокая вложенность" }
            require(start < bytes.size) { "конец данных" }
            return when (val c = bytes[start].toInt().toChar()) {
                'i' -> parseNumber(start)
                'l' -> parseList(start, depth)
                'd' -> parseDict(start, depth)
                in '0'..'9' -> parseString(start)
                else -> throw IllegalArgumentException("неизвестный тип '$c'")
            }
        }

        private fun parseNumber(start: Int): BNode {
            var at = start + 1
            val from = at
            while (at < bytes.size && bytes[at].toInt().toChar() != 'e') at++
            require(at < bytes.size) { "число без конца" }
            val text = String(bytes, from, at - from, Charsets.US_ASCII)
            require(text.isNotEmpty() && text != "-") { "пустое число" }
            val value = text.toLongOrNull() ?: throw IllegalArgumentException("не число: $text")
            return BNode(BValue.Num(value), start, at + 1)
        }

        private fun parseString(start: Int): BNode {
            var at = start
            var length = 0L
            while (at < bytes.size && bytes[at].toInt().toChar() in '0'..'9') {
                length = length * 10 + (bytes[at] - '0'.code)
                // Строка длиннее файла — не строка. Без этой проверки
                // «99999999999:x» превращалось в попытку выделить гигабайты.
                require(length <= bytes.size) { "длина строки больше файла" }
                at++
            }
            require(at < bytes.size && bytes[at].toInt().toChar() == ':') { "нет ':'" }
            val from = at + 1
            val to = from + length.toInt()
            require(to <= bytes.size) { "строка обрезана" }
            return BNode(BValue.Str(bytes.copyOfRange(from, to)), start, to)
        }

        private fun parseList(start: Int, depth: Int): BNode {
            val items = ArrayList<BValue>()
            var at = start + 1
            while (true) {
                require(at < bytes.size) { "список без конца" }
                if (bytes[at].toInt().toChar() == 'e') return BNode(BValue.Lst(items), start, at + 1)
                val node = parse(at, depth + 1)
                items.add(node.value)
                at = node.end
            }
        }

        private fun parseDict(start: Int, depth: Int): BNode {
            val entries = ArrayList<Pair<String, BValue>>()
            var at = start + 1
            while (true) {
                require(at < bytes.size) { "словарь без конца" }
                if (bytes[at].toInt().toChar() == 'e') return BNode(BValue.Dict(entries), start, at + 1)
                val keyNode = parse(at, depth + 1)
                val key = keyNode.value.asTextOrNull()
                    ?: throw IllegalArgumentException("ключ не строка")
                val value = parse(keyNode.end, depth + 1)
                entries.add(key to value.value)
                at = value.end
            }
        }
    }
}

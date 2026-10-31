// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

/**
 * Разбор и запись JSON — свой, маленький.
 *
 * ПОЧЕМУ НЕ БИБЛИОТЕКА
 *   Плагины читаются на старте и в экране настроек, то есть на пути, где
 *   любая зависимость — ещё один способ уронить приложение из-за файла,
 *   который написал человек. Здесь нет ни рефлексии, ни аннотаций: разбор и
 *   разметка — это ~150 строк, которые проверяются на хосте целиком, включая
 *   поведение на мусоре.
 *
 * ЧТО ВАЖНЕЕ УДОБСТВА
 *   Ошибка обязана называть место. «Не удалось прочитать плагин» без
 *   координаты — это сообщение, после которого человек бросает это дело;
 *   «строка 7, позиция 12: ожидалось „,‎“» — сообщение, после которого он
 *   правит запятую.
 *
 * ОГРАНИЧЕНИЯ — НАМЕРЕННЫЕ
 *   Вход не больше [MAX_INPUT] символов и вложенность не глубже [MAX_DEPTH]:
 *   иначе файл на 50 МБ из тысячи скобок съест память ещё до того, как
 *   станет понятно, что он не плагин.
 */
object PluginJson {

    /** Потолок размера входного текста. Плагин больше — это уже не плагин. */
    const val MAX_INPUT = 262_144

    /** Потолок вложенности массивов и объектов. */
    const val MAX_DEPTH = 24

    class Error(message: String) : Exception(message)

    fun parse(text: String): Any? {
        if (text.length > MAX_INPUT) {
            throw Error("файл слишком большой: ${text.length} символов, предел $MAX_INPUT")
        }
        val p = Cursor(text)
        p.skipSpace()
        val value = p.value(0)
        p.skipSpace()
        if (!p.done()) throw p.fail("лишние данные после конца документа")
        return value
    }

    /** Разбор с превращением любой проблемы в понятное сообщение. */
    fun parseObject(text: String): Map<String, Any?> {
        val value = try {
            parse(text)
        } catch (e: Error) {
            throw e
        } catch (e: Exception) {
            throw Error("не удалось разобрать JSON: ${e.message ?: e.javaClass.simpleName}")
        }
        return value as? Map<String, Any?>
            ?: throw Error("ожидался объект { … }, а пришло ${kindOf(value)}")
    }

    fun kindOf(value: Any?): String = when (value) {
        null -> "ничего (null)"
        is Map<*, *> -> "объект { … }"
        is List<*> -> "массив [ … ]"
        is String -> "строка"
        is Boolean -> "да/нет"
        is Number -> "число"
        else -> value.javaClass.simpleName
    }

    private class Cursor(val s: String) {
        var i = 0

        fun done() = i >= s.length

        fun skipSpace() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun fail(what: String): Error {
            var line = 1
            var col = 1
            for (k in 0 until minOf(i, s.length)) {
                if (s[k] == '\n') {
                    line++
                    col = 1
                } else {
                    col++
                }
            }
            return Error("строка $line, позиция $col: $what")
        }

        fun value(depth: Int): Any? {
            if (depth > MAX_DEPTH) throw fail("слишком глубокая вложенность (предел $MAX_DEPTH)")
            skipSpace()
            if (done()) throw fail("неожиданный конец файла")
            return when (val c = s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) num() else throw fail("неожиданный символ «$c»")
            }
        }

        fun literal(word: String, result: Any?): Any? {
            if (!s.startsWith(word, i)) throw fail("ожидалось «$word»")
            i += word.length
            return result
        }

        fun num(): Double {
            val start = i
            if (i < s.length && s[i] == '-') i++
            while (i < s.length && s[i].isDigit()) i++
            if (i < s.length && s[i] == '.') {
                i++
                while (i < s.length && s[i].isDigit()) i++
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                while (i < s.length && s[i].isDigit()) i++
            }
            val raw = s.substring(start, i)
            return raw.toDoubleOrNull() ?: throw Error("не число: «$raw»")
        }

        fun str(): String {
            i++ // открывающая кавычка
            val sb = StringBuilder()
            while (true) {
                if (done()) throw fail("строка не закрыта")
                when (val c = s[i]) {
                    '"' -> {
                        i++
                        return sb.toString()
                    }
                    '\\' -> {
                        i++
                        if (done()) throw fail("строка обрывается на обратной косой черте")
                        when (val e = s[i]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 >= s.length) throw fail("\\u обрывается")
                                val hex = s.substring(i + 1, i + 5)
                                val code = hex.toIntOrNull(16) ?: throw fail("\\u$hex — не шестнадцатеричное")
                                sb.append(code.toChar())
                                i += 4
                            }
                            else -> throw fail("неизвестная escape-последовательность «\\$e»")
                        }
                        i++
                    }
                    else -> {
                        sb.append(c)
                        i++
                    }
                }
            }
        }

        fun obj(depth: Int): Map<String, Any?> {
            i++
            val out = LinkedHashMap<String, Any?>()
            skipSpace()
            if (!done() && s[i] == '}') {
                i++
                return out
            }
            while (true) {
                skipSpace()
                if (done()) throw fail("объект не закрыт")
                if (s[i] != '"') throw fail("ожидалось имя поля в кавычках")
                val key = str()
                skipSpace()
                if (done() || s[i] != ':') throw fail("после имени «$key» ожидалось «:»")
                i++
                if (out.containsKey(key)) throw fail("поле «$key» повторяется")
                out[key] = value(depth + 1)
                skipSpace()
                if (done()) throw fail("объект не закрыт")
                when (s[i]) {
                    ',' -> {
                        i++
                        skipSpace()
                        // Запятая перед закрывающей скобкой — самая частая
                        // опечатка в рукописном JSON. Говорим прямо.
                        if (!done() && s[i] == '}') throw fail("лишняя запятая перед «}»")
                    }
                    '}' -> {
                        i++
                        return out
                    }
                    else -> throw fail("ожидалось «,» или «}»")
                }
            }
        }

        fun arr(depth: Int): List<Any?> {
            i++
            val out = ArrayList<Any?>()
            skipSpace()
            if (!done() && s[i] == ']') {
                i++
                return out
            }
            while (true) {
                out.add(value(depth + 1))
                skipSpace()
                if (done()) throw fail("массив не закрыт")
                when (s[i]) {
                    ',' -> {
                        i++
                        skipSpace()
                        if (!done() && s[i] == ']') throw fail("лишняя запятая перед «]»")
                    }
                    ']' -> {
                        i++
                        return out
                    }
                    else -> throw fail("ожидалось «,» или «]»")
                }
            }
        }
    }

    /** Запись: нужна экрану плагинов, чтобы показать разобранный набор обратно. */
    fun write(value: Any?, pretty: Boolean = true, indent: Int = 0): String {
        val pad = "  ".repeat(indent)
        val padInner = "  ".repeat(indent + 1)
        return when (value) {
            null -> "null"
            is Boolean -> value.toString()
            is Number -> if (value.toDouble() == value.toLong().toDouble()) value.toLong().toString() else value.toString()
            is String -> quote(value)
            is Map<*, *> -> {
                if (value.isEmpty()) return "{}"
                val body = value.entries.joinToString(if (pretty) ",\n" else ",") { (k, v) ->
                    (if (pretty) padInner else "") + quote(k.toString()) + ": " +
                        write(v, pretty, indent + 1)
                }
                if (pretty) "{\n$body\n$pad}" else "{$body}"
            }
            is List<*> -> {
                if (value.isEmpty()) return "[]"
                val body = value.joinToString(if (pretty) ",\n" else ",") { v ->
                    (if (pretty) padInner else "") + write(v, pretty, indent + 1)
                }
                if (pretty) "[\n$body\n$pad]" else "[$body]"
            }
            else -> quote(value.toString())
        }
    }

    fun quote(s: String): String = buildString {
        append('"')
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04X".format(c.code)) else append(c)
            }
        }
        append('"')
    }
}

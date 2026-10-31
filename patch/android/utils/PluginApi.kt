// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

/**
 * Формат плагина: что вообще можно «пропатчить» без пересборки приложения.
 *
 * ГЛАВНОЕ РЕШЕНИЕ — ПЛАГИН НЕ ИСПОЛНЯЕТ КОД
 *   Плагин здесь — это набор декларативных правил: «если у игры такой-то
 *   признак, то такие-то настройки / такое-то ядро / такая-то пометка».
 *   Он не может читать файлы, ходить в сеть или звать нативные методы, и
 *   именно поэтому его можно принять от кого угодно и не бояться: худшее,
 *   что он сделает, — выставит неверную настройку, и это видно в журнале
 *   решений и отключается одним переключателем.
 *
 *   Альтернатива (загружаемый dex) дала бы больше свободы и ровно столько же
 *   способов тихо сломать приложение. Для задачи «починить то, что плохо
 *   работает» хватает правил: плохо работающая игра — это почти всегда
 *   конкретная настройка или конкретное ядро.
 *
 * ЧТО ПРОВЕРЯЕТСЯ ПРИ ЗАГРУЗКЕ
 *   Всё, что можно проверить до запуска, проверяется здесь: имена полей,
 *   допустимые значения (сверяются с [EngineHybrid.FIELDS]), длины строк,
 *   число правил, версия схемы. Опечатка в имени поля — ошибка загрузки, а не
 *   «плагин молча ничего не делает». Это две разные беды, и вторая хуже.
 */
object PluginApi {

    /** Версия схемы. Файл с большим номером приложение не поймёт — и скажет об этом. */
    const val SCHEMA = 1

    const val MAX_RULES = 128
    const val MAX_TEXT = 200
    const val MAX_NAME = 80
    const val MAX_DESCRIPTION = 400

    /** Поля, по которым можно сравнивать. Список закрытый: опечатка = ошибка. */
    val FIELDS = listOf("title", "path", "ext", "titleId", "gpu", "engine", "ramGb", "core")

    /** Числовые поля: для них разрешены сравнения lt/lte/gt/gte. */
    val NUMERIC_FIELDS = setOf("ramGb")

    enum class Severity { INFO, WARN, ERROR;
        companion object {
            fun of(raw: String): Severity = when (raw.lowercase()) {
                "info" -> INFO
                "warn", "warning" -> WARN
                "error" -> ERROR
                else -> throw LoadError("severity «$raw»: допустимо info, warn или error")
            }
        }
    }

    enum class Verdict { OK, COMPRESSED, UNREADABLE;
        companion object {
            fun of(raw: String): Verdict = when (raw.lowercase()) {
                "ok" -> OK
                "compressed" -> COMPRESSED
                "unreadable" -> UNREADABLE
                else -> throw LoadError("verdict «$raw»: допустимо ok, compressed или unreadable")
            }
        }
    }

    /** Ошибка загрузки плагина: текст всегда говорит, что именно не так. */
    class LoadError(message: String) : Exception(message)

    /** Как сравнивать значение поля. */
    enum class Op { MATCH, EQ, NE, LT, LTE, GT, GTE }

    /**
     * Один терм условия.
     *
     * [patterns] — список, а не одна строка, потому что массив в JSON значит
     * «любое из»: `"ext": ["nsz", "xci"]` — это одно условие с двумя
     * вариантами, а не два условия, которые не могут выполниться вместе.
     * Сложить их через «и» — самая частая ловушка в таких форматах.
     */
    data class Term(
        val field: String,
        val op: Op,
        val patterns: List<String>,
        val number: Double
    ) {
        val text: String get() = patterns.firstOrNull() ?: ""

        fun matches(ctx: GameContext): Boolean {
            if (field in NUMERIC_FIELDS) {
                val value = ctx.ramGb.toDouble()
                return when (op) {
                    Op.LT -> value < number
                    Op.LTE -> value <= number
                    Op.GT -> value > number
                    Op.GTE -> value >= number
                    Op.NE -> value != number
                    else -> value == number
                }
            }
            val value = ctx.valueOf(field)
            val any = patterns.any { glob(it, value) }
            return when (op) {
                Op.NE -> !any
                else -> any
            }
        }

        /** Для экрана плагинов: «gpu = mali», «ramGb < 8», «ext = nsz или xci». */
        fun describe(): String = when (op) {
            Op.MATCH -> if (patterns.size == 1) "$field ≈ ${patterns[0]}"
            else "$field ≈ " + patterns.joinToString(" или ")
            Op.EQ -> "$field = $text"
            Op.NE -> "$field ≠ $text"
            Op.LT -> "$field < $text"
            Op.LTE -> "$field ≤ $text"
            Op.GT -> "$field > $text"
            Op.GTE -> "$field ≥ $text"
        }
    }

    /**
     * Условие: список термов, соединённых «И».
     *
     * Пустое условие истинно всегда — это удобно для правил вроде «на всех
     * играх с расширением nsz поставь пометку».
     */
    data class Condition(val terms: List<Term>) {
        fun matches(ctx: GameContext): Boolean = terms.all { it.matches(ctx) }
        fun describe(): String =
            if (terms.isEmpty()) "всегда" else terms.joinToString(" и ") { it.describe() }

        companion object {
            val ALWAYS = Condition(emptyList())
        }
    }

    sealed class Rule {
        data class Setting(val whenIt: Condition, val values: Map<String, String>) : Rule()
        data class Core(val whenIt: Condition, val core: EngineHybrid.Core, val reason: String) : Rule()
        data class File(
            val whenIt: Condition,
            val badge: String,
            val note: String,
            val verdict: Verdict
        ) : Rule()
        data class Banner(val whenIt: Condition, val text: String, val severity: Severity) : Rule()

        fun condition(): Condition = when (this) {
            is Setting -> whenIt
            is Core -> whenIt
            is File -> whenIt
            is Banner -> whenIt
        }

        /** Человеческая строка для экрана плагинов. */
        fun describe(): String {
            val whenText = condition().describe()
            return when (this) {
                is Setting -> "$whenText → " +
                    values.entries.joinToString(", ") { (k, v) -> "$k=$v" }
                is Core -> "$whenText → ядро ${core.label}" +
                    if (reason.isEmpty()) "" else " ($reason)"
                is File -> "$whenText → пометка «${note}»"
                is Banner -> "$whenText → сообщение: $text"
            }
        }
    }

    data class Plugin(
        val id: String,
        val name: String,
        val version: String,
        val author: String,
        val priority: Int,
        val description: String,
        val rules: List<Rule>
    ) {
        /** Порядок применения: сначала меньший приоритет, потом по id. */
        fun order(): String = "%06d/%s".format(priority, id)
    }

    /** Что известно о запуске в момент решения. Заполняется вызывающим. */
    data class GameContext(
        val title: String = "",
        val path: String = "",
        val ext: String = "",
        val titleId: String = "",
        val gpu: String = "",
        val engine: String = "",
        val ramGb: Int = 0,
        val core: String = ""
    ) {
        fun valueOf(field: String): String = when (field) {
            "title" -> title
            "path" -> path
            "ext" -> ext
            "titleId" -> titleId
            "gpu" -> gpu
            "engine" -> engine
            "ramGb" -> ramGb.toString()
            "core" -> core
            else -> ""
        }
    }

    /**
     * Сравнение с образцом, где `*` — любое количество любых символов.
     *
     * Своя реализация вместо регулярного выражения намеренно: шаблон пишет
     * человек, и `*a*a*a*a*a*a*a*b` в регулярке — это секунды процессорного
     * времени на одной строке. Здесь время всегда O(длина × длина).
     */
    fun glob(pattern: String, value: String): Boolean {
        val p = pattern.lowercase()
        val v = value.lowercase()
        if (p.isEmpty()) return v.isEmpty()
        if (p == "*") return true
        var pi = 0
        var vi = 0
        var star = -1
        var mark = 0
        while (vi < v.length) {
            when {
                pi < p.length && (p[pi] == v[vi]) -> {
                    pi++
                    vi++
                }
                pi < p.length && p[pi] == '*' -> {
                    star = pi
                    mark = vi
                    pi++
                }
                star >= 0 -> {
                    pi = star + 1
                    vi = ++mark
                }
                else -> return false
            }
        }
        while (pi < p.length && p[pi] == '*') pi++
        return pi == p.length
    }

    // ── загрузка ────────────────────────────────────────────────────────

    /** Разобрать плагин из текста. Любая проблема — [LoadError] с местом. */
    fun load(text: String, sourceName: String = ""): Plugin {
        // «Не JSON» и «JSON с ошибкой в 15 строке» — разные проблемы, и
        // лечатся они по-разному. Различаем по первому значимому символу.
        if (!text.trimStart().startsWith("{")) {
            throw LoadError(
                prefix(sourceName) +
                    "это не JSON: файл плагина должен начинаться с фигурной скобки { … }"
            )
        }
        val root = try {
            PluginJson.parseObject(text)
        } catch (e: PluginJson.Error) {
            throw LoadError(prefix(sourceName) + "это не JSON: " + e.message)
        }

        val schema = number(root, "schema")?.toInt()
            ?: throw LoadError(prefix(sourceName) + "нет поля «schema» — не понять, какой это формат")
        if (schema > SCHEMA) {
            throw LoadError(
                prefix(sourceName) +
                    "плагин новее приложения (схема $schema, приложение знает $SCHEMA). " +
                    "Обновите приложение или возьмите версию плагина для схемы $SCHEMA."
            )
        }
        if (schema < 1) throw LoadError(prefix(sourceName) + "схема $schema не поддерживается")

        val id = string(root, "id", MAX_NAME)
            ?: throw LoadError(prefix(sourceName) + "нет поля «id»")
        if (!ID_PATTERN.matches(id)) {
            throw LoadError(
                prefix(sourceName) +
                    "id «$id»: допустимы строчные латинские буквы, цифры, точка, дефис и подчёркивание"
            )
        }

        val rulesRaw = root["rules"] as? List<*>
            ?: throw LoadError(prefix(sourceName) + "нет массива «rules»")
        if (rulesRaw.size > MAX_RULES) {
            throw LoadError(prefix(sourceName) + "правил ${rulesRaw.size}, предел $MAX_RULES")
        }

        val rules = rulesRaw.mapIndexed { index, raw ->
            val where = prefix(sourceName) + "правило ${index + 1}: "
            val obj = raw as? Map<*, *>
                ?: throw LoadError(where + "ожидался объект, а пришло ${PluginJson.kindOf(raw)}")
            @Suppress("UNCHECKED_CAST")
            loadRule(obj as Map<String, Any?>, where)
        }

        if (rules.isEmpty()) throw LoadError(prefix(sourceName) + "плагин без правил ничего не делает")

        return Plugin(
            id = id,
            name = string(root, "name", MAX_NAME) ?: id,
            version = string(root, "version", 16) ?: "1",
            author = string(root, "author", 60) ?: "",
            priority = (number(root, "priority") ?: 0.0).toInt().coerceIn(0, 10_000),
            description = string(root, "description", MAX_DESCRIPTION) ?: "",
            rules = rules
        )
    }

    private val ID_PATTERN = Regex("^[a-z0-9][a-z0-9._-]{0,63}$")

    private fun prefix(source: String) = if (source.isEmpty()) "" else "$source: "

    private fun loadRule(obj: Map<String, Any?>, where: String): Rule {
        val type = string(obj, "type", 16)
            ?: throw LoadError(where + "нет поля «type» (setting, core, file или banner)")
        val whenIt = loadCondition(obj["when"], where)
        return when (type.lowercase()) {
            "setting" -> {
                unknownKeys(obj, setOf("type", "when", "set"), where)
                val set = obj["set"] as? Map<*, *>
                    ?: throw LoadError(where + "у правила setting нужен объект «set»")
                val values = LinkedHashMap<String, String>()
                set.forEach { (k, v) ->
                    val key = k.toString()
                    val field = EngineHybrid.FIELDS.firstOrNull { it.id == key }
                        ?: throw LoadError(
                            where + "нет настройки «$key». Есть: " +
                                EngineHybrid.FIELDS.joinToString(", ") { it.id }
                        )
                    val value = when (v) {
                        is String -> v.trim()
                        is Boolean -> v.toString()
                        is Number -> if (v.toDouble() == v.toLong().toDouble()) v.toLong().toString() else v.toString()
                        else -> throw LoadError(where + "значение «$key» должно быть строкой, числом или да/нет")
                    }
                    val allowed = field.choices.map { it.value }
                    if (allowed.isNotEmpty() && value !in allowed) {
                        throw LoadError(
                            where + "«$key» = «$value», допустимо: " + allowed.joinToString(", ")
                        )
                    }
                    // Проверка «применится ли»: мусор в значении, который
                    // прошёл по списку, но не понят движком, лучше поймать тут.
                    if (EngineHybrid.apply(EngineHybrid.KenjiConfig(), key, value) == null) {
                        throw LoadError(where + "«$key» = «$value» не применяется к конфигу ядра")
                    }
                    values[key] = value
                }
                if (values.isEmpty()) throw LoadError(where + "у setting пустой «set»")
                Rule.Setting(whenIt, values)
            }

            "core" -> {
                unknownKeys(obj, setOf("type", "when", "core", "reason"), where)
                val raw = string(obj, "core", 16)
                    ?: throw LoadError(where + "у правила core нужен «core»: eden или kenji")
                // Не EngineHybrid.Core.of: он отдаёт EDEN на любой мусор, то
                // есть опечатка «kenij» тихо поехала бы на Eden.
                val core = EngineHybrid.Core.values().firstOrNull { it.id == raw.lowercase() }
                    ?: throw LoadError(where + "ядро «$raw»: допустимо eden или kenji")
                Rule.Core(whenIt, core, string(obj, "reason", MAX_TEXT) ?: "")
            }

            "file" -> {
                unknownKeys(obj, setOf("type", "when", "badge", "note", "verdict"), where)
                val note = string(obj, "note", MAX_TEXT)
                    ?: throw LoadError(where + "у правила file нужен «note» — текст пометки")
                Rule.File(
                    whenIt = whenIt,
                    badge = string(obj, "badge", 8) ?: "",
                    note = note,
                    verdict = (string(obj, "verdict", 16)?.let { Verdict.of(it) } ?: Verdict.OK)
                )
            }

            "banner" -> {
                unknownKeys(obj, setOf("type", "when", "text", "severity"), where)
                val text = string(obj, "text", MAX_TEXT)
                    ?: throw LoadError(where + "у правила banner нужен «text»")
                Rule.Banner(
                    whenIt = whenIt,
                    text = text,
                    severity = (string(obj, "severity", 16)?.let { Severity.of(it) } ?: Severity.INFO)
                )
            }

            else -> throw LoadError(where + "неизвестный тип «$type»: допустимо setting, core, file или banner")
        }
    }

    private fun loadCondition(raw: Any?, where: String): Condition {
        if (raw == null) return Condition.ALWAYS
        val map = raw as? Map<*, *>
            ?: throw LoadError(where + "«when» должен быть объектом { поле: условие }")
        val terms = ArrayList<Term>()
        map.forEach { (k, v) ->
            val field = k.toString()
            if (field !in FIELDS) {
                throw LoadError(
                    where + "нет поля «$field» для условия. Есть: " + FIELDS.joinToString(", ")
                )
            }
            when (v) {
                is String -> terms.add(Term(field, Op.MATCH, listOf(v), 0.0))
                is Number -> {
                    if (field !in NUMERIC_FIELDS) {
                        throw LoadError(where + "поле «$field» текстовое: сравнивайте строкой или шаблоном со *")
                    }
                    terms.add(Term(field, Op.EQ, listOf("%.0f".format(v.toDouble())), v.toDouble()))
                }
                is Boolean -> throw LoadError(
                    where + "поле «$field» нельзя сравнивать с да/нет: используйте строку"
                )
                is List<*> -> {
                    // Массив строк — «любое из». Это чаще всего и нужно:
                    // «nsz или nsp», «mali или adreno».
                    if (v.isEmpty()) throw LoadError(where + "пустой список значений у «$field»")
                    val patterns = v.map { item ->
                        item as? String
                            ?: throw LoadError(where + "в списке «$field» только строки")
                    }
                    // Один терм с несколькими образцами — «любое из».
                    terms.add(Term(field, Op.MATCH, patterns, 0.0))
                }
                is Map<*, *> -> {
                    if (field !in NUMERIC_FIELDS) {
                        throw LoadError(where + "у поля «$field» нет числовых сравнений: только шаблон строки")
                    }
                    v.forEach { (opRaw, valueRaw) ->
                        val op = when (opRaw.toString().lowercase()) {
                            "lt" -> Op.LT
                            "lte" -> Op.LTE
                            "gt" -> Op.GT
                            "gte" -> Op.GTE
                            "eq" -> Op.EQ
                            "ne" -> Op.NE
                            else -> throw LoadError(
                                where + "сравнение «$opRaw»: допустимо lt, lte, gt, gte, eq, ne"
                            )
                        }
                        val number = (valueRaw as? Number)?.toDouble()
                            ?: throw LoadError(where + "сравнение «$opRaw» требует числа")
                        terms.add(Term(field, op, listOf("%.0f".format(number)), number))
                    }
                }
                else -> throw LoadError(where + "непонятное условие для «$field»")
            }
        }
        return Condition(terms)
    }

    private fun unknownKeys(obj: Map<String, Any?>, known: Set<String>, where: String) {
        obj.keys.forEach { key ->
            if (key !in known) {
                throw LoadError(
                    where + "лишнее поле «$key». Допустимо: " + known.joinToString(", ")
                )
            }
        }
    }

    private fun string(obj: Map<String, Any?>, key: String, max: Int): String? {
        val raw = obj[key] ?: return null
        val text = raw as? String ?: throw LoadError("«$key» должно быть строкой")
        if (text.length > max) throw LoadError("«$key»: ${text.length} символов, предел $max")
        return text
    }

    private fun number(obj: Map<String, Any?>, key: String): Double? {
        val raw = obj[key] ?: return null
        return (raw as? Number)?.toDouble() ?: throw LoadError("«$key» должно быть числом")
    }
}

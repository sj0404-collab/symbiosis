// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Плагины: правила, разбор и решения.
//
// Проверяется [PluginApi], [PluginJson] и [PluginEngine] — весь путь от
// текста файла до «какие настройки уедут в ядро и какая пометка появится на
// плитке». Именно этот путь и есть обещание плагинов: «поправить, не
// пересобирая». Если он не проверен, обещание держится на вере.
//
// Run: kotlinc tests/PluginEngineTest.kt patch/android/utils/{PluginJson,
//        PluginApi,PluginEngine,BuiltinPlugins,EngineHybrid,
//        NativeLibraryPresenter,LibraryIndex}.kt -include-runtime -d /tmp/pe.jar
//        && java -jar /tmp/pe.jar

import org.yuzu.yuzu_emu.utils.BuiltinPlugins
import org.yuzu.yuzu_emu.utils.EngineHybrid
import org.yuzu.yuzu_emu.utils.NativeLibraryPresenter
import org.yuzu.yuzu_emu.utils.PluginApi
import org.yuzu.yuzu_emu.utils.PluginApi.GameContext
import org.yuzu.yuzu_emu.utils.PluginEngine
import org.yuzu.yuzu_emu.utils.PluginJson

private var passed = 0
private val failures = mutableListOf<String>()

private fun check(what: String, ok: Boolean, detail: String = "") {
    if (ok) passed++ else failures.add("$what${if (detail.isEmpty()) "" else " → $detail"}")
}

private fun <T> eq(what: String, got: T, want: T) =
    check(what, got == want, "получено [$got], ожидалось [$want]")

private fun throwsWith(what: String, needle: String, block: () -> Unit) {
    val message = try {
        block()
        null
    } catch (e: Exception) {
        e.message ?: ""
    }
    check(
        what,
        message != null && message.contains(needle),
        if (message == null) "ошибки не было" else "сообщение: $message"
    )
}

private fun plugin(
    id: String = "test",
    priority: Int = 0,
    rules: String
) = """
{
  "schema": 1,
  "id": "$id",
  "name": "Тест $id",
  "priority": $priority,
  "rules": [ $rules ]
}
"""

fun main() {

    // ── 1. JSON ────────────────────────────────────────────────────────
    eq("разбор объекта", (PluginJson.parse("""{"a":1}""") as Map<*, *>)["a"], 1.0)
    eq("разбор массива", (PluginJson.parse("""[1,2,3]""") as List<*>).size, 3)
    eq("вложенность разобрана", ((PluginJson.parse("""{"a":{"b":[true]}}""") as Map<*, *>)["a"] ?: "").toString().contains("b"), true)
    eq("кавычка внутри строки", PluginJson.parse("[\"a\\\"b\"]").let { (it as List<*>)[0] }, "a\"b")
    eq("обратная косая черта", PluginJson.parse("[\"a\\\\b\"]").let { (it as List<*>)[0] }, "a\\b")
    eq("перевод строки", PluginJson.parse("[\"a\\nb\"]").let { (it as List<*>)[0] }, "a\nb")
    eq("юникод", PluginJson.parse("""["\u0414"]""").let { (it as List<*>)[0] }, "Д")
    eq("числа с минусом и точкой", PluginJson.parse("[-1.5e2]").let { (it as List<*>)[0] }, -150.0)
    eq("пустой объект", (PluginJson.parse("{}") as Map<*, *>).size, 0)
    eq("пробелы не мешают", (PluginJson.parse(" { \"a\" : 1 } ") as Map<*, *>)["a"], 1.0)

    throwsWith("незакрытая строка говорит о строке и позиции", "строка") {
        PluginJson.parse("{\"a\": \"abc}")
    }
    throwsWith("лишняя запятая названа словами", "лишняя запятая") {
        PluginJson.parse("""{"a":1,}""")
    }
    throwsWith("повтор поля — ошибка", "повторяется") {
        PluginJson.parse("""{"a":1,"a":2}""")
    }
    throwsWith("мусор после конца", "лишние данные") {
        PluginJson.parse("""{"a":1} x""")
    }
    throwsWith("слишком большая вложенность", "вложенность") {
        PluginJson.parse("[".repeat(40) + "]".repeat(40))
    }
    throwsWith("слишком большой файл", "слишком большой") {
        PluginJson.parse("[" + "1,".repeat(PluginJson.MAX_INPUT) + "1]")
    }
    // Проверяем не байты, а смысл: пробел после двоеточия — вопрос
    // оформления, а вот потерянное значение — уже ошибка.
    check("запись и повторное чтение дают то же дерево", run {
        val text = """{"a":[1,"два",true,null],"b":{"c":"д"},"n":-2.5}"""
        val once = PluginJson.parse(text)
        val twice = PluginJson.parse(PluginJson.write(once, pretty = false))
        once == twice
    })
    eq("кавычки экранируются при записи", PluginJson.quote("a\"b"), "\"a\\\"b\"")

    // ── 2. загрузка плагина ────────────────────────────────────────────
    val simple = PluginApi.load(
        plugin(rules = """{"type":"setting","when":{"ext":"nsz"},"set":{"memory":"1"}}"""),
        "simple.json"
    )
    eq("плагин разобран", simple.id, "test")
    eq("правило одно", simple.rules.size, 1)
    check("условие описано словами", simple.rules[0].describe().contains("memory=1"), simple.rules[0].describe())

    throwsWith("нет схемы — понятная ошибка", "schema") {
        PluginApi.load("""{"id":"x","rules":[]}""")
    }
    throwsWith("новая схема не принимается", "новее приложения") {
        PluginApi.load("""{"schema":99,"id":"x","rules":[{"type":"banner","text":"a"}]}""")
    }
    throwsWith("плохой id", "латинские") {
        PluginApi.load("""{"schema":1,"id":"Мой плагин","rules":[{"type":"banner","text":"a"}]}""")
    }
    throwsWith("пустой набор правил бесполезен", "без правил") {
        PluginApi.load("""{"schema":1,"id":"x","rules":[]}""")
    }
    throwsWith("опечатка в настройке названа с подсказкой", "нет настройки") {
        PluginApi.load(plugin(rules = """{"type":"setting","set":{"memy":"3"}}"""))
    }
    throwsWith("недопустимое значение настройки", "допустимо") {
        PluginApi.load(plugin(rules = """{"type":"setting","set":{"memory":"999"}}"""))
    }
    throwsWith("опечатка в поле условия", "нет поля") {
        PluginApi.load(plugin(rules = """{"type":"banner","when":{"gpuu":"mali"},"text":"a"}"""))
    }
    throwsWith("лишнее поле в правиле", "лишнее поле") {
        PluginApi.load(plugin(rules = """{"type":"banner","text":"a","svereity":"warn"}"""))
    }
    throwsWith("неизвестный тип правила", "неизвестный тип") {
        PluginApi.load(plugin(rules = """{"type":"magic","text":"a"}"""))
    }
    throwsWith("ядро проверяется по имени", "допустимо eden или kenji") {
        PluginApi.load(plugin(rules = """{"type":"core","core":"kenij"}"""))
    }
    throwsWith("текстовое поле не сравнивают числом", "текстовое") {
        PluginApi.load(plugin(rules = """{"type":"banner","when":{"title":5},"text":"a"}"""))
    }
    throwsWith("неизвестное сравнение", "допустимо lt") {
        PluginApi.load(plugin(rules = """{"type":"banner","when":{"ramGb":{"less":6}},"text":"a"}"""))
    }
    throwsWith("слишком много правил", "предел") {
        val rule = """{"type":"banner","text":"a"}"""
        PluginApi.load(plugin(rules = (1..(PluginApi.MAX_RULES + 1)).joinToString(",") { rule }))
    }
    throwsWith("слишком длинный текст", "предел") {
        PluginApi.load(plugin(rules = """{"type":"banner","text":"${"а".repeat(PluginApi.MAX_TEXT + 1)}"}"""))
    }
    throwsWith("файл не JSON — сказано, что не JSON", "JSON") {
        PluginApi.load("это не json")
    }
    check("имя файла попадает в сообщение об ошибке", run {
        try {
            PluginApi.load("{}", "my.json")
            false
        } catch (e: PluginApi.LoadError) {
            (e.message ?: "").contains("my.json")
        }
    })

    // ── 3. условия ─────────────────────────────────────────────────────
    check("шаблон со звёздочкой", PluginApi.glob("*mali*", "Mali-G57 MC2"))
    check("шаблон в начале и конце", PluginApi.glob("spl*3", "Splatoon 3"))
    check("шаблон не совпал", !PluginApi.glob("*adreno*", "Mali-G57"))
    check("регистр не важен", PluginApi.glob("*MALI*", "mali-g57"))
    check("без звёздочки — точное совпадение", !PluginApi.glob("nsp", "nsz"))
    check("звёздочка на всё", PluginApi.glob("*", "что угодно"))

    val ctxMali8 = GameContext(gpu = "Mali-G57 MC2", ramGb = 8, ext = "nsp", title = "Metroid Dread")
    val ctxSmall = GameContext(gpu = "Mali-G57 MC2", ramGb = 4, ext = "nsz", title = "Splatoon 3")

    fun rule(json: String) = PluginApi.load(plugin(rules = json), "t.json").rules[0]

    check(
        "условие «и»",
        rule("""{"type":"banner","when":{"ext":"nsp","title":"*Dread*"},"text":"a"}""")
            .condition().matches(ctxMali8)
    )
    check(
        "условие «и» ломается на втором",
        !rule("""{"type":"banner","when":{"ext":"nsp","title":"*Splatoon*"},"text":"a"}""")
            .condition().matches(ctxMali8)
    )
    check(
        "массив — любое из",
        rule("""{"type":"banner","when":{"ext":["nsz","xci"]},"text":"a"}""")
            .condition().matches(ctxSmall)
    )
    check(
        "число меньше",
        rule("""{"type":"banner","when":{"ramGb":{"lt":6}},"text":"a"}""")
            .condition().matches(ctxSmall)
    )
    check(
        "число меньше — не срабатывает на 8",
        !rule("""{"type":"banner","when":{"ramGb":{"lt":6}},"text":"a"}""")
            .condition().matches(ctxMali8)
    )
    check(
        "пустое условие истинно всегда",
        rule("""{"type":"banner","text":"a"}""").condition().matches(GameContext())
    )

    // ── 4. решения ─────────────────────────────────────────────────────
    val maliSafe = PluginApi.load(BuiltinPlugins.all[0], "mali-safe.json")
    val lowram = PluginApi.load(BuiltinPlugins.all[1], "lowram-eden.json")

    val onMali = PluginEngine.decide(listOf(maliSafe), ctxMali8)
    eq("mali-safe правит fastGpuTime", onMali.settings["fastGpuTime"], "false")
    eq("mali-safe правит анизотропию", onMali.settings["aniso"], "0")
    eq("mali-safe не трогает память", onMali.settings.containsKey("memory"), false)
    eq("журнал называет плагин", onMali.trace.first().startsWith("mali-safe"), true)

    val onSmall = PluginEngine.decide(listOf(maliSafe), ctxSmall)
    eq("на малой памяти появляется сообщение", onSmall.banners.size, 1)
    eq("сообщение важное", onSmall.banners[0].severity, PluginApi.Severity.WARN)

    val withLowram = PluginEngine.decide(listOf(maliSafe, lowram), ctxSmall)
    eq("lowram оставляет на Eden", withLowram.core, EngineHybrid.Core.EDEN)
    check("причина названа", withLowram.coreReason.contains("6 ГБ"), withLowram.coreReason)

    // приоритет: больший выигрывает, порядок не зависит от порядка в списке
    val first = PluginApi.load(
        plugin(id = "aaa-first", priority = 10, rules = """{"type":"setting","set":{"memory":"2"}}"""),
        "a.json"
    )
    val second = PluginApi.load(
        plugin(id = "zzz-second", priority = 90, rules = """{"type":"setting","set":{"memory":"1"}}"""),
        "b.json"
    )
    eq("выигрывает больший приоритет", PluginEngine.decide(listOf(first, second), ctxMali8).settings["memory"], "1")
    eq(
        "порядок списка не влияет",
        PluginEngine.decide(listOf(second, first), ctxMali8).settings["memory"],
        "1"
    )

    val a1 = PluginApi.load(
        plugin(id = "aaa", priority = 50, rules = """{"type":"setting","set":{"aniso":"4"}}"""), "1.json"
    )
    val z1 = PluginApi.load(
        plugin(id = "zzz", priority = 50, rules = """{"type":"setting","set":{"aniso":"8"}}"""), "2.json"
    )
    eq("при равном приоритете решает id", PluginEngine.decide(listOf(z1, a1), ctxMali8).settings["aniso"], "8")
    eq(
        "и это воспроизводится",
        PluginEngine.decide(listOf(a1, z1), ctxMali8).settings["aniso"],
        PluginEngine.decide(listOf(z1, a1), ctxMali8).settings["aniso"]
    )

    // плашки: без дублей, важные выше
    val b1 = PluginApi.load(
        plugin(id = "b1", rules = """{"type":"banner","text":"Одно и то же","severity":"info"}"""), "b1.json"
    )
    val b2 = PluginApi.load(
        plugin(id = "b2", rules = """{"type":"banner","text":"Одно и то же","severity":"info"}"""), "b2.json"
    )
    val b3 = PluginApi.load(
        plugin(id = "b3", rules = """{"type":"banner","text":"Важное","severity":"error"}"""), "b3.json"
    )
    val banners = PluginEngine.decide(listOf(b1, b2, b3), ctxMali8).banners
    eq("одинаковые сообщения не дублируются", banners.size, 2)
    eq("важное наверху", banners[0].text, "Важное")

    // конфликты видны, но не запрещены
    val k1 = PluginApi.load(plugin(id = "k1", priority = 10, rules = """{"type":"core","core":"eden"}"""), "k1.json")
    val k2 = PluginApi.load(plugin(id = "k2", priority = 20, rules = """{"type":"core","core":"kenji"}"""), "k2.json")
    val conflicts = PluginEngine.conflicts(listOf(k1, k2), ctxMali8)
    eq("конфликт ядер замечен", conflicts.size, 1)
    check("в конфликте назван победитель", conflicts[0].contains("Победит k2"), conflicts[0])
    eq("решение по конфликту — больший приоритет", PluginEngine.decide(listOf(k1, k2), ctxMali8).core, EngineHybrid.Core.KENJI)

    // Invalid manager mode must be rejected when a plugin is loaded.
    val rejectedMode = runCatching {
        PluginApi.load(plugin(rules = """{"type":"setting","set":{"memory":"3"}}"""))
    }.exceptionOrNull()
    check("плагин с недопустимым режимом 3 отклонён", rejectedMode is PluginApi.LoadError)

    // ── 5. патч конфига ────────────────────────────────────────────────
    val base = EngineHybrid.KenjiConfig(memoryManagerMode = 2, resScale = 1f, maxAnisotropy = 8f)
    val patched = PluginEngine.patch(base, mapOf("memory" to "1", "aniso" to "0"))
    eq("режим памяти пропатчен", patched.memoryManagerMode, 1)
    eq("недопустимый режим 3 не применяется", PluginEngine.patch(base, mapOf("memory" to "3")).memoryManagerMode, 2)
    eq("анизотропия пропатчена", patched.maxAnisotropy, 0f)
    eq("остальное не тронуто", patched.resScale, 1f)
    eq("мусор в патче игнорируется", PluginEngine.patch(base, mapOf("memory" to "нет")).memoryManagerMode, 2)
    eq("неизвестный ключ игнорируется", PluginEngine.patch(base, mapOf("nope" to "1")).resScale, 1f)
    eq(
        "порядок патча не меняет результат",
        PluginEngine.patch(base, mapOf("memory" to "1", "aniso" to "0")),
        PluginEngine.patch(base, mapOf("aniso" to "0", "memory" to "1"))
    )

    // ── 6. пометки на плитках ──────────────────────────────────────────
    val item = NativeLibraryPresenter.Item(
        title = "Splatoon 3",
        subtitle = "Nintendo",
        badge = "nsz",
        note = "сжатый",
        path = "/games/Splatoon 3.nsz",
        action = NativeLibraryPresenter.Action.CONVERT,
        runnable = false
    )
    val fileRule = PluginEngine.decide(
        listOf(
            PluginApi.load(
                plugin(
                    id = "pack",
                    rules = """{"type":"file","when":{"ext":"nsz"},"badge":"nsz","note":"нужна распаковка","verdict":"compressed"}"""
                ),
                "pack.json"
            )
        ),
        GameContext(path = "/games/Splatoon 3.nsz", ext = "nsz")
    )
    val decorated = PluginEngine.decorate(item, fileRule)
    check("пометка добавлена", decorated.note.contains("нужна распаковка"), decorated.note)
    eq("старая пометка не потеряна", decorated.note.startsWith("сжатый"), true)
    eq("действие не подменено", decorated.action, NativeLibraryPresenter.Action.CONVERT)
    eq("без решений плитка не меняется", PluginEngine.decorate(item, PluginEngine.Decisions()), item)
    eq(
        "пометка не повторяется дважды",
        PluginEngine.decorate(decorated, fileRule),
        decorated
    )

    // ── 7. сводка и пустота ────────────────────────────────────────────
    eq("без решений сводка пустая", PluginEngine.summary(PluginEngine.Decisions()), "")
    check(
        "сводка называет ядро",
        PluginEngine.summary(withLowram).contains("Eden"),
        PluginEngine.summary(withLowram)
    )
    check(
        "сводка называет настройки",
        PluginEngine.summary(onMali).contains("fastGpuTime=false"),
        PluginEngine.summary(onMali)
    )
    check("решения не пустые, когда есть правило", !onMali.isEmpty())
    check("решения пустые без плагинов", PluginEngine.decide(emptyList(), ctxMali8).isEmpty())

    // ── 8. встроенный набор ────────────────────────────────────────────
    eq("встроенных плагинов три", BuiltinPlugins.all.size, 3)
    BuiltinPlugins.all.forEachIndexed { index, json ->
        val loaded = runCatching { PluginApi.load(json, "builtin-${index + 1}.json") }
        check(
            "встроенный ${index + 1} разбирается",
            loaded.isSuccess,
            loaded.exceptionOrNull()?.message ?: ""
        )
    }
    val ids = BuiltinPlugins.all.map { PluginApi.load(it, "b.json").id }
    eq("id встроенных уникальны", ids.toSet().size, ids.size)
    check("mali-safe включён по умолчанию", BuiltinPlugins.enabledByDefault.contains(BuiltinPlugins.MALI_SAFE))
    check("lowram выключен по умолчанию", !BuiltinPlugins.enabledByDefault.contains(BuiltinPlugins.LOWRAM_EDEN))
    eq("шаблон не срабатывает никогда", run {
        val tpl = PluginApi.load(BuiltinPlugins.all[2], "t.json")
        PluginEngine.decide(listOf(tpl), GameContext(title = "Metroid Dread", path = "/g/a.nsp", ext = "nsp")).banners.size
    }, 0)

    // ── 9. правила не могут выйти за пределы настроек ядра ────────────
    val fieldIds = EngineHybrid.FIELDS.map { it.id }.toSet()
    BuiltinPlugins.all.forEach { json ->
        PluginApi.load(json, "b.json").rules.filterIsInstance<PluginApi.Rule.Setting>()
            .flatMap { it.values.keys }
            .forEach { key -> check("настройка «$key» существует", key in fieldIds) }
    }
    check(
        "все значения встроенных правил применимы",
        run {
            BuiltinPlugins.all.all { json ->
                PluginApi.load(json, "b.json").rules.filterIsInstance<PluginApi.Rule.Setting>()
                    .all { rule -> rule.values.all { (k, v) -> EngineHybrid.apply(EngineHybrid.KenjiConfig(), k, v) != null } }
            }
        }
    )

    // ── итог ───────────────────────────────────────────────────────────
    println()
    failures.forEach { println("FAIL  $it") }
    println("passed=$passed failed=${failures.size}")
    if (failures.isNotEmpty()) kotlin.system.exitProcess(1)
}

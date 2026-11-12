// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Гибрид: два ядра, одни настройки на двоих.
//
// Проверяется [EngineHybrid] — там решается, что из настроек Eden поедет во
// второе ядро, что останется своим и каким ядром запустится конкретная игра.
// Экран только рисует результат, а `KenjiBridge` подставляет его в нативные
// вызовы, поэтому тест читает и тот, и другой файл: логика без проводки до
// ядра бесполезна, и именно это было сломано (настройки стояли числами в
// коде).
//
// Run: kotlinc tests/EngineHybridTest.kt patch/android/utils/EngineHybrid.kt \
//        -include-runtime -d /tmp/eh.jar && java -jar /tmp/eh.jar

import java.io.File
import org.yuzu.yuzu_emu.utils.EngineHybrid
import org.yuzu.yuzu_emu.utils.EngineHybrid.Core
import org.yuzu.yuzu_emu.utils.EngineHybrid.Source

private var passed = 0
private var failed = 0
private val failures = mutableListOf<String>()

private fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) {
        passed++
        println("  ok    $name${if (detail.isEmpty()) "" else "   [$detail]"}")
    } else {
        failed++
        failures += name
        println("  FAIL  $name${if (detail.isEmpty()) "" else "   [$detail]"}")
    }
}

private fun eq(name: String, actual: Any?, expected: Any?) =
    check(name, actual == expected, "получено $actual, ожидалось $expected")

fun main() {
    println("EngineHybrid — два ядра в одном")

    val eden = EngineHybrid.EdenSnapshot(
        resolutionScale = 0.75f,
        docked = false,
        shaderCache = true,
        nce = false,
        ramGb = 8,
        language = 2,
        region = 2
    )

    // ── 1. общие настройки берутся у Eden ───────────────────────────────
    val seeded = EngineHybrid.seed(eden)
    eq("масштаб пришёл из Eden", EngineHybrid.trimFloat(seeded.resScale), "0.75")
    eq("док пришёл из Eden", seeded.enableDockedMode, false)
    eq("кэш шейдеров пришёл из Eden", seeded.enableShaderCache, true)
    eq("NCE пришёл из Eden", seeded.useNce, false)
    eq("язык пришёл из Eden", seeded.systemLanguage, 2)
    eq("режим памяти на 8 ГБ", seeded.memoryManagerMode, 2)
    check("PPTC остаётся своим (включён)", seeded.enablePtc)
    check("macro HLE остаётся своим (включён)", seeded.enableMacroHLE)
    eq(
        "объём RAM не подменяет режим управления памятью",
        EngineHybrid.seed(eden.copy(ramGb = 12)).memoryManagerMode,
        2
    )
    eq("масштаб ниже 0.25 зажимается", EngineHybrid.trimFloat(EngineHybrid.seed(eden.copy(resolutionScale = 0.1f)).resScale), "0.25")
    eq("масштаб выше 4 зажимается", EngineHybrid.trimFloat(EngineHybrid.seed(eden.copy(resolutionScale = 9f)).resScale), "4")

    // ── 2. своё значение перекрывает только своё поле ────────────────────
    val withOwn = EngineHybrid.merge(eden, mapOf("kenji.memory" to "1", "kenji.macroHle" to "false"))
    eq("своя память победила", withOwn.memoryManagerMode, 1)
    check("свой macro HLE победил", !withOwn.enableMacroHLE)
    eq("масштаб всё ещё из Eden", EngineHybrid.trimFloat(withOwn.resScale), "0.75")
    check("PPTC не тронут", withOwn.enablePtc)

    val scaleOwn = EngineHybrid.merge(eden, mapOf("kenji.scale" to "1.5"))
    eq("свой масштаб у второго ядра", EngineHybrid.trimFloat(scaleOwn.resScale), "1.5")
    eq(
        "и Eden при этом не изменился",
        EngineHybrid.trimFloat(EngineHybrid.seed(eden).resScale),
        "0.75"
    )

    // ── 3. мусор в хранилище не ломает запуск ───────────────────────────
    val junk = EngineHybrid.merge(
        eden,
        mapOf(
            "kenji.memory" to "восемь",
            "kenji.scale" to "2147483000",
            "kenji.nce" to "может быть",
            "kenji.такого нет" to "1"
        )
    )
    eq("мусор в памяти → значение Eden", junk.memoryManagerMode, 2)
    eq("мусор в масштабе → значение Eden", EngineHybrid.trimFloat(junk.resScale), "0.75")
    check("мусор в NCE → значение Eden", !junk.useNce)
    check("неизвестный ключ молча пропущен", EngineHybrid.apply(seeded, "kenji.foo", "1") == null)

    // ── 4. каждое значение из списка полей применимо ────────────────────
    var choicesChecked = 0
    EngineHybrid.FIELDS.forEach { field ->
        check("у поля «${field.label}» есть варианты", field.choices.size >= 2)
        field.choices.forEach { choice ->
            val applied = EngineHybrid.apply(seeded, field.id, choice.value)
            check(
                "вариант «${choice.label}» применяется",
                applied != null && EngineHybrid.raw(applied, field.id) == choice.value,
                "${field.id}=${choice.value}"
            )
            choicesChecked++
        }
    }
    check("проверено вариантов", choicesChecked >= 20, "$choicesChecked")
    val ids = EngineHybrid.FIELDS.map { it.id }
    check("id полей уникальны", ids.size == ids.toSet().size)
    check("подпись текущего значения находится", EngineHybrid.label(seeded, "scale") == "0.75x")

    // ── 5. переключение по кругу не застревает ──────────────────────────
    var cfg = seeded
    val seen = mutableSetOf<String>()
    repeat(EngineHybrid.FIELDS.first { it.id == "memory" }.choices.size) {
        val next = EngineHybrid.next(cfg, "memory")
        seen += next
        cfg = EngineHybrid.apply(cfg, "memory", next) ?: cfg
    }
    eq(
        "круг по памяти обошёл все варианты",
        seen.size,
        EngineHybrid.FIELDS.first { it.id == "memory" }.choices.size
    )

    // ── 6. строки «что куда поедет» ─────────────────────────────────────
    val rows = EngineHybrid.rows(eden, mapOf("kenji.memory" to "1"))
    val memory = rows.first { it.id == "memory" }
    eq("своя память помечена как своё", memory.source, Source.OWN)
    eq("режим управления памятью", memory.value, "режим 1")
    val oldMemory = EngineHybrid.rows(eden, mapOf("kenji.memory" to "3")).first { it.id == "memory" }
    check("старый недопустимый режим виден", oldMemory.value.contains("3 отклонено"), oldMemory.value)
    eq("старый режим не отправляется в ядро", EngineHybrid.merge(eden, mapOf("kenji.memory" to "3")).memoryManagerMode, 2)
    check("режим 3 нельзя установить", EngineHybrid.apply(seeded, "memory", "3") == null)
    val scale = rows.first { it.id == "scale" }
    eq("масштаб помечен как из Eden", scale.source, Source.EDEN)
    check("в списке есть и общие, и свои строки", rows.any { it.source == Source.EDEN } && rows.any { it.source == Source.OWN })
    eq("строк столько же, сколько полей", rows.size, EngineHybrid.FIELDS.size)

    // ── 7. ядро на игру ────────────────────────────────────────────────
    eq("по умолчанию Eden", EngineHybrid.coreFor("eden", null), Core.EDEN)
    eq("игра переопределила на Kenji", EngineHybrid.coreFor("eden", "kenji"), Core.KENJI)
    eq("игра вернулась на Eden", EngineHybrid.coreFor("kenji", "eden"), Core.EDEN)
    eq("без своего значения — общее", EngineHybrid.coreFor("kenji", null), Core.KENJI)
    eq("мусор в значении — Eden", EngineHybrid.coreFor("eden", "quake"), Core.EDEN)
    check("ключи игр различаются", EngineHybrid.gameKey("content://a/Game 1.nsp") != EngineHybrid.gameKey("content://a/Game 2.nsp"))
    eq("ключ игры стабилен", EngineHybrid.gameKey("content://a/x.nsp"), EngineHybrid.gameKey("content://a/x.nsp"))
    eq("длина ключа игры", EngineHybrid.gameKey("content://a/x.nsp").length, 16)
    eq("пустой путь — пустой ключ", EngineHybrid.gameKey(""), "")

    // ── 8. сводка для человека ──────────────────────────────────────────
    val summary = EngineHybrid.summary(withOwn)
    check("сводка называет масштаб", summary.contains("масштаб"), summary)
    check("сводка называет память", summary.contains("режим 1"), summary)
    check("сводка называет NCE", summary.contains("NCE"), summary)

    // ── 8b. передача настроек в процесс :kenji ──────────────────────────
    val eff = EngineHybrid.effective(withOwn)
    eq("в наборе все поля", eff.size, EngineHybrid.FIELDS.size)
    val encoded = EngineHybrid.encode(eff)
    check("строка настроек компактна", encoded.length < 400, "${encoded.length} символов")
    val decoded = EngineHybrid.decode(encoded)
    eq("разбор вернул столько же полей", decoded.size, eff.size)
    eq("память пережила передачу", decoded["memory"], "1")
    eq(
        "и конфиг из переданного набора совпадает",
        EngineHybrid.merge(eden, decoded.mapKeys { "kenji." + it.key }).memoryManagerMode,
        withOwn.memoryManagerMode
    )
    eq("мусор в строке пропускается", EngineHybrid.decode("мусор;=;a=;b=1").size, 1)
    eq("пустая строка — пустой набор", EngineHybrid.decode("").size, 0)

    // ── 9. проводка до ядра, а не только логика ─────────────────────────
    val root = File(".").absoluteFile
    if (File(root, "patch/android").isDirectory) {
        val bridge = File(root, "patch/android/utils/KenjiBridge.kt")
        if (bridge.isFile) {
            val src = bridge.readText()
            check("мост берёт настройки из хранилища", src.contains("EngineStore.config(context)"))
            val body = src.substringAfter("fun preparePlay", "").substringBefore("fun attachSurface")
            check(
                "мост больше не вписывает значения числами",
                !body.contains("memoryManagerMode = 2") && !body.contains("resScale = 1f"),
                body.take(120)
            )
            check(
                "каждое поле конфига доезжает до nativeDeviceInit",
                body.contains("cfg.memoryManagerMode") && body.contains("cfg.enablePtc") &&
                    body.contains("cfg.resScale") && body.contains("cfg.enableMacroHLE")
            )
        } else {
            check("мост второго ядра существует", false)
        }

        val launcher = File(root, "patch/android/fragments/NativeLauncherFragment.kt")
        if (launcher.isFile) {
            val src = launcher.readText()
            check("лаунчер спрашивает ядро у хранилища", src.contains("EngineStore.coreFor"))
            check("лаунчер умеет открыть второе ядро", src.contains("KenjiPlayerActivity.intent"))
            check("выбор ядра запоминается за игрой", src.contains("EngineStore.setCoreFor"))
        }

        val engines = File(root, "patch/android/fragments/EnginesFragment.kt")
        if (engines.isFile) {
            val src = engines.readText()
            check("экран ядер показывает связь настроек", src.contains("EngineStore.rows"))
            check("настройку можно переключить", src.contains("EngineStore.cycleField"))
            check("значения Eden возвращаются кнопкой", src.contains("EngineStore.resetFields"))
            check("экран называет оба ядра сразу", src.contains("EngineLoader.Engine.values()"))
        }

        val manual = File(root, "patch/android/utils/Manual.kt")
        if (manual.isFile) {
            check(
                "в руководстве есть раздел про два ядра",
                manual.readText().contains("id = \"cores\"")
            )
        }

        val store = File(root, "patch/android/utils/EngineStore.kt")
        if (store.isFile) {
            check(
                "схема хранения проверяется",
                store.readText().contains("EngineHybrid.VERSION")
            )
        }
    } else {
        println("  note  дерево patch/ рядом не найдено — проверка дрейфа пропущена")
    }

    // ── ключ игры по имени файла ───────────────────────────────────────
    // Второй ключ нужен, когда путь одной игры приходит записанным иначе.
    // Ошибка здесь = «переведена на Eden», а запуск снова на Kenji (05.11).
    val k1 = EngineHybrid.gameKeyByName("/storage/emulated/0/Games/TMNT.nsp")
    val k2 = EngineHybrid.gameKeyByName(
        "content://com.android.externalstorage.documents/tree/x/document/TMNT.nsp")
    eq("то же имя файла по другому пути даёт тот же ключ", k1, k2)
    check("точный ключ пути при этом отличается",
        EngineHybrid.gameKey("/storage/emulated/0/Games/TMNT.nsp") !=
            EngineHybrid.gameKey("content://x/TMNT.nsp"))
    check("разные файлы дают разные ключи",
        k1 != EngineHybrid.gameKeyByName("/games/Mario.nsp"))
    eq("пустой путь — пустой ключ", EngineHybrid.gameKeyByName(""), "")
    check("ключ короткий и устойчивый", k1.length == 16 && k1.all { it in "0123456789abcdef" })

    val configReport = EngineHybrid.configurationReport(EngineHybrid.encode(EngineHybrid.effective(EngineHybrid.KenjiConfig())))
    check("отчёт называет подготовленные настройки", configReport.contains("подготовленные"), configReport)
    check("отчёт не утверждает применение", configReport.contains("не подтверждение"), configReport)
    check("отчёт содержит масштаб", configReport.contains("Масштаб"), configReport)
    check("произвольная строка не попадает в отчёт", !EngineHybrid.configurationReport("scale=secret;unknown=secret").contains("secret"))
    check("пустые настройки честно недоступны", EngineHybrid.configurationReport("").contains("не удалось"))

    println()
    println("passed=$passed failed=$failed")
    if (failed > 0) {
        println("failing: ${failures.joinToString(", ")}")
        kotlin.system.exitProcess(1)
    }
}

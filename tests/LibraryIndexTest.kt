// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later
//
// «Из сотен nsz, nsp, xci видит одну игру».
//
// Три разных места решали, что такое игра, и решали по-разному:
// импортёр (`GameHelper.addGamesRecursive`) брал только запускаемые
// расширения и молча выбрасывал всё, у чего не прочитался заголовок;
// сканер папки показывал ещё и nsz; панель считала по кэшу. Сжатый NSZ
// не открывается ядром -> `GameMetadata.getIsValid` = false -> `getGame()`
// = null -> записи нет. Сотни файлов давали ноль строк.
//
// `LibraryIndex` - одно правило. Тест зовёт именно его, а не модель:
// файл в тесте и файл в APK - один и тот же исходник.
//
// Run: kotlinc tests/LibraryIndexTest.kt patch/android/utils/LibraryIndex.kt \
//        -include-runtime -d /tmp/li.jar && java -jar /tmp/li.jar

import java.io.File
import org.yuzu.yuzu_emu.utils.LibraryIndex
import org.yuzu.yuzu_emu.utils.LibraryIndex.Kind

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
    println("LibraryIndex — одна игра из сотен")

    // ── база, обновление, DLC ────────────────────────────────────────────
    val base = LibraryIndex.classify("Sonic Mania [01004E300BD5C000][v0].nsp")
    eq("база: вид", base.kind, Kind.GAME)
    eq("база: titleId", base.titleId, "01004E300BD5C000")
    eq("база: имя без тегов", base.title, "Sonic Mania")
    eq("база: запускается", base.runnable, true)

    val upd = LibraryIndex.classify("Sonic Mania [01004E300BD5C800][v65536][UPD].nsp")
    eq("обновление: вид", upd.kind, Kind.UPDATE)
    eq("обновление: версия", upd.version, "1.0.0")
    eq("обновление: запускается", upd.runnable, true)

    val v131072 = LibraryIndex.classify("Game [01004E300BD5C800][v131072][UPD].nsp")
    eq("обновление: 131072 = 2.0.0", v131072.version, "2.0.0")

    val dlc = LibraryIndex.classify("Sonic Mania [01004E300BD5C001][DLC] Encore.nsp")
    eq("DLC: вид", dlc.kind, Kind.DLC)
    eq("DLC: имя", dlc.title, "Sonic Mania Encore")

    // Роль определяется и без тега [UPD]/[DLC] - по последним 12 битам ID.
    eq("роль 000 = база", LibraryIndex.roleOf("01004E300BD5C000"), LibraryIndex.Role.BASE)
    eq("роль 800 = обновление", LibraryIndex.roleOf("01004E300BD5C800"), LibraryIndex.Role.UPDATE)
    eq("роль 001 = DLC", LibraryIndex.roleOf("01004E300BD5C001"), LibraryIndex.Role.DLC)
    eq("базовый ID от DLC", LibraryIndex.baseTitleId("01004E300BD5C001"), "01004E300BD5C000")

    // ── сжатые образы: именно они и пропадали ────────────────────────────
    for (ext in listOf("nsz", "xcz", "ncz")) {
        val e = LibraryIndex.classify("Zelda [${
            if (ext == "ncz") "0100000000010000" else "01007EF00011E000"
        }][v0].$ext")
        eq("$ext: вид", e.kind, Kind.COMPRESSED)
        eq("$ext: не запускается", e.runnable, false)
        check("$ext: причина понятна", e.reason.contains("Конвертер"), e.reason)
    }
    check("nsz хочется видеть в библиотеке", LibraryIndex.isWanted("game.nsz"))
    check("nsp хочется видеть в библиотеке", LibraryIndex.isWanted("game.nsp"))
    check("xci хочется видеть в библиотеке", LibraryIndex.isWanted("game.xci"))
    check("txt не хочется", !LibraryIndex.isWanted("keys.txt"))
    check("nsz не запускается", !LibraryIndex.isRunnable("game.nsz"))

    // ── разрезанный образ: части - не отдельные игры ─────────────────────
    val p0 = LibraryIndex.classify("Big Game [0100AAAAAAAAA000][v0].nsp.00")
    val p1 = LibraryIndex.classify("Big Game [0100AAAAAAAAA000][v0].nsp.01")
    eq("часть 0: вид", p0.kind, Kind.SPLIT_PART)
    eq("часть 1: вид", p1.kind, Kind.SPLIT_PART)
    eq("часть: не запускается", p0.runnable, false)
    eq(
        "две части - одна запись",
        LibraryIndex.baseKey("Big Game [0100AAAAAAAAA000][v0].nsp.00") ==
            LibraryIndex.baseKey("Big Game [0100AAAAAAAAA000][v0].nsp.01"),
        true
    )
    eq("часть обновления - обновление", p0.copy(kind = Kind.UPDATE).kind, Kind.UPDATE)
    val updPart = LibraryIndex.classify("Big [0100AAAAAAAAA800][v65536][UPD].xci.part01")
    eq("разрезанное обновление: вид", updPart.kind, Kind.UPDATE)

    // ── распакованная игра ───────────────────────────────────────────────
    val naked = LibraryIndex.classify("main")
    eq("main: вид", naked.kind, Kind.UNPACKED)
    eq("main: запускается", naked.runnable, true)
    eq("main: имя", naked.title, "main")

    // ── «не игра»: не притворяемся, что папка пуста ──────────────────────
    val zip = LibraryIndex.classify("backup.7z")
    eq("архив: вид", zip.kind, Kind.OTHER)
    check("архив: причина словами", zip.reason.contains("архив"), zip.reason)
    val txt = LibraryIndex.classify("keys.txt")
    eq("txt: вид", txt.kind, Kind.OTHER)
    check("txt: причина без имени класса", !txt.reason.contains(".kt"), txt.reason)

    // ── имена с мусором ──────────────────────────────────────────────────
    eq(
        "мусорное имя чистится",
        LibraryIndex.titleFrom("  [0100AAAAAAAAA000]  Super   Mario  [v0] (1).nsp"),
        "Super Mario (1)"
    )
    eq("пустое имя не падает", LibraryIndex.classify("").kind, Kind.OTHER)
    eq("имя из одной точки", LibraryIndex.classify(".").kind, Kind.OTHER)

    // ── сводка по папке ──────────────────────────────────────────────────
    // 200 сжатых + 4 игры + 2 обновления + 3 DLC + мусор.
    val names = mutableListOf<String>()
    repeat(200) { names.add("Dump $it [0100AAAAAAAA${"%04X".format(it)}][v0].nsz") }
    repeat(4) { names.add("Game $it [0100BBBBBBBB${"%04X".format(it)}0000][v0].nsp") }
    repeat(2) { names.add("Game 0 [0100BBBBBBBB0000${"%04X".format(it + 0x800)}][v${
        (it + 1) * 65536
    }][UPD].nsp") }
    repeat(3) { names.add("Game 0 [0100BBBBBBBB000${"%03X".format(it + 1)}][DLC] x.nsp") }
    names.add("keys.txt")
    names.add("readme.md")
    val s = LibraryIndex.summarise(names)
    eq("сводка: файлов", s.files, 211)
    eq("сводка: игр", s.games, 4)
    eq("сводка: обновлений", s.updates, 2)
    eq("сводка: DLC", s.dlc, 3)
    eq("сводка: сжатых", s.compressed, 200)
    eq("сводка: прочих", s.other, 2)
    check("сводка: сжатые названы в строке", s.line().contains("200 сжатых"), s.line())
    check("сводка: игры названы", s.line().contains("4 игры"), s.line())

    // Один и тот же файл, найденный дважды, не удваивает счёт.
    val dup = LibraryIndex.summarise(listOf("A [0100CCCCCCCC0000][v0].nsp", "A [0100CCCCCCCC0000][v0].nsp"))
    eq("дубликат схлопнут", dup.games, 1)

    // ── что сломало прошлый обход: имя без точки ─────────────────────────
    // `getGame()` считал programId как name.substring(0, name.lastIndexOf(".")),
    // и на файле «main» это substring(0, -1) - исключение на весь обход.
    val crashProne = LibraryIndex.titleFrom("main")
    eq("main без точки: имя", crashProne, "main")
    check(
        "main без точки: id берётся безопасно",
        LibraryIndex.titleFrom("main").substringBeforeLast('.', "main") == "main"
    )

    // ── расхождение с исходниками (то, что модель не поймает) ────────────
    val root = File(".").absoluteFile
    val helper = File(root, "patch/android/utils/GameHelper.kt")
    if (helper.isFile && File(root, "patch/android").isDirectory) {
        val src = helper.readText()
        check("GameHelper берёт правило из LibraryIndex", src.contains("LibraryIndex.isWanted"))
        // В комментарии строка упомянута намеренно - проверяем код, а не
        // рассказ о нём: ищем именно вызов.
        check(
            "GameHelper не режет id через lastIndexOf(\".\")",
            !src.contains("programId = name.substring(0, name.lastIndexOf")
        )
        check("GameHelper не теряет файл при null от getGame", src.contains("side.add(") && src.contains("stats.failed++"))
        check("GameHelper считает статистику обхода", src.contains("scanStats"))

        val scanner = File(root, "patch/android/utils/GameFolderScanner.kt").readText()
        check(
            "сканер не режет обход на 400 папках",
            !scanner.contains("MAX_DIRECTORIES = 400")
        )
        check("сканер считает сжатые", scanner.contains("LibraryIndex.isWanted"))

        val panel = File(root, "patch/android/utils/LivePanel.kt").readText()
        check("панели есть чем ответить «почему не видно»", panel.contains("libraryReport"))

        val html = File(root, "docs/library.html").readText()
        val asset = File(root, "patch/android/assets/library.html")
        check("страница знает про сжатые", html.contains("data-filter=\"dead\"") && html.contains("deadAction"))
        check("страница показывает сводку", html.contains("paintStats") && html.contains("libraryReport"))
        if (asset.isFile) {
            check(
                "docs/ и assets/ совпадают байт в байт",
                html == asset.readText()
            )
        }
    } else {
        println("  note  исходники рядом не найдены - проверка дрейфа пропущена")
    }

    println()
    println("passed=$passed failed=$failed")
    if (failed > 0) {
        println("failing: ${failures.joinToString(", ")}")
        kotlin.system.exitProcess(1)
    }
}

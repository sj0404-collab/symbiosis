// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later
//
// «Видит только одну игру и всё».
//
// The library importer walked ONE level deep unless the folder dialog's
// "recursive search" switch was ticked, and the switch is off by default. A
// console dump is normally kept one folder per game, so a folder picked at
// the top of one yielded exactly the files lying loose in its root. On a
// device with a single ROM at the root that is precisely the report: one
// game, and the rest are "not seen" - and with the rail showing a single
// tile, "not seen" and "cannot be scrolled to" look like the same thing.
//
// Two rules drifted apart and let it through, and both are pinned here:
//
//   * the depth was written twice - `if (deepScan) 3 else 1` in GameHelper
//     and the same expression in GameFolderScanner. The counter walked
//     deeper than the importer, so the folder screen could promise a game
//     the library then refused to import;
//   * nothing stopped the deeper walk at the data root's own layout
//     (nand, load, cache), which is what the previous fix had just made
//     safe at one level.
//
// Neither GameHelper nor GameFolderScanner compiles without Android's
// ContentResolver, so the two walks are modelled over an in-memory tree -
// the property that matters is that they agree, and that neither enters a
// layout directory. The last block then reads the shipped sources, because
// a model cannot notice the two copies of the depth rule drifting apart
// again.
//
// Run: kotlinc tests/DeepLibraryTest.kt -include-runtime -d /tmp/t.jar && java -jar /tmp/t.jar

import java.io.File
import java.util.Locale

// ── the rules under test, mirrored from the sources ────────────────────

private val LAUNCHABLE = setOf("xci", "nsp", "nca", "nro", "nso", "kip")

/** GameFolderScanner: enough for Games/Title/game.nsp, more with the switch. */
const val SHALLOW_SCAN_DEPTH = 3
const val DEEP_SCAN_DEPTH = 6

fun depthFor(deepScan: Boolean): Int = if (deepScan) DEEP_SCAN_DEPTH else SHALLOW_SCAN_DEPTH

val LAYOUT = setOf(
    "nand", "load", "cache", "sdmc", "keys", "config",
    "dump", "screenshots", "amiibo", "tas", "icons", "log",
    "play_time", "crash_dumps", "shader", "system", "contents",
    "registered", "user", "save"
)

fun isLayoutName(name: String) = name.lowercase(Locale.ROOT) in LAYOUT

/** loader.cpp: a file called "main" is an unpacked game, "00" a split NCA. */
private val ROM_NAMES = setOf("main", "00")

/** GameFormats.isLaunchable: the name, or the extension behind it. */
private fun isRom(name: String): Boolean {
    val lower = name.lowercase(Locale.ROOT)
    return lower in ROM_NAMES ||
        lower.substringAfterLast('.', "").lowercase(Locale.ROOT) in LAUNCHABLE
}

private fun isFileSegment(name: String) = '.' in name || name.lowercase(Locale.ROOT) in ROM_NAMES

/** Stand-in for a SAF document tree: a segment with a dot is a file. */
sealed class Node {
    data class Dir(val name: String, val children: MutableList<Node> = mutableListOf()) : Node()
    data class Doc(val name: String) : Node()
}

/**
 * A tree written as paths: "Games/Blade Chimera/game.nsp" for one file,
 * several of them separated by ";", all sharing the first segment as the
 * folder the user picked. A segment with a dot is a file, and so are
 * "main" and "00" - an unpacked game has no extension at all.
 */
fun tree(spec: String): Node.Dir {
    val paths = spec.split(';').map { it.trim('/') }.filter { it.isNotEmpty() }
    val first = paths[0].split('/')
    val root = Node.Dir(if (isFileSegment(first[0])) "root" else first[0])
    for (path in paths) {
        val parts = path.split('/')
        var current = root
        for (part in parts.drop(1)) {
            val node: Node = if (isFileSegment(part)) Node.Doc(part) else Node.Dir(part)
            current.children.add(node)
            if (node is Node.Dir) current = node
        }
    }
    return root
}

/**
 * GameHelper.addGamesRecursive: the same depth rule as the counter, and a
 * refusal to enter the emulator's own layout directories. Returns paths
 * below the root, so an assertion reads as the folder that holds the file.
 */
fun importGames(spec: String, deepScan: Boolean): List<String> {
    val root = tree(spec)
    val out = mutableListOf<String>()
    fun walk(files: List<Node>, prefix: String, depth: Int) {
        if (depth <= 0) return
        for (child in files) {
            when (child) {
                is Node.Dir ->
                    if (!isLayoutName(child.name)) {
                        walk(child.children, "$prefix${child.name}/", depth - 1)
                    }
                is Node.Doc ->
                    if (isRom(child.name)) out.add(prefix + child.name)
            }
        }
    }
    walk(root.children, "", depthFor(deepScan))
    return out
}

/** GameFolderScanner.scanOne: counts what the importer will import. */
fun countGames(spec: String, deepScan: Boolean): Int {
    var count = 0
    val q = ArrayDeque<Pair<Node.Dir, Int>>()
    q.add(tree(spec) to depthFor(deepScan))
    while (q.isNotEmpty()) {
        val (d, depth) = q.removeFirst()
        for (child in d.children) {
            when (child) {
                is Node.Dir ->
                    if (depth > 1 && !isLayoutName(child.name)) q.add(child to depth - 1)
                is Node.Doc -> if (isRom(child.name)) count++
            }
        }
    }
    return count
}

/**
 * GameHelper.getGames() with no keys: the previous list comes back, and
 * what was persisted is left alone.
 */
fun getGamesWithoutKeys(cached: List<String>): List<String> = cached

var failed = 0

fun check(name: String, cond: Boolean, detail: String = "") {
    if (cond) println("  ok   $name")
    else {
        failed++
        println("  FAIL $name" + if (detail.isNotEmpty()) "\n       $detail" else "")
    }
}

fun main() {
    println("\nглубина обхода библиотеки")

    check("мелкий обход смотрит на три уровня", depthFor(false) == SHALLOW_SCAN_DEPTH)
    check("глубокий обход глубже мелкого", depthFor(true) > depthFor(false))

    // ── the report: a dump with one folder per game ────────────────────
    val perGame = "Games/Loose.nsp;" +
        "Games/Blade Chimera/game.nsp;" +
        "Games/Mario Kart/game.xci;" +
        "Games/Zelda/main"
    check("папка на каждую игру - четыре игры без переключателя",
        importGames(perGame, deepScan = false).size == 4,
        "найдено ${importGames(perGame, false)}")
    check("счётчик папки видит те же четыре",
        countGames(perGame, deepScan = false) == 4)
    check("вложенный nsz игрой не считается",
        importGames("Games/Sega/Sonic.nsz", deepScan = false).isEmpty())

    // Switch dumps: Switch/Title [id]/Exefs/main is level three.
    val switchDump = "Switch/Blade Chimera [0100B7B00F2E800]/Exefs/main;" +
        "Switch/Mario Kart [0100152000022000]/game.nsp"
    check("дамп Switch виден целиком", importGames(switchDump, deepScan = false).size == 2)
    check("счётчик и импортёр видят один дамп одинаково",
        countGames(switchDump, deepScan = false) == importGames(switchDump, false).size)

    // Flat libraries must keep working.
    val flat = "Games/a.nsp;Games/b.xci;Games/readme.txt;Games/c.kip"
    check("плоская папка без перемен", importGames(flat, false).size == 3)
    check("счётчик и импортёр видят плоскую папку одинаково",
        countGames(flat, false) == importGames(flat, false).size)

    // ── deeper than the shallow walk, and the switch that reaches it ────
    val buried = "sd/a/b/c/d/deep.nsp"
    check("мелкий обход не достаёт до пятого уровня",
        importGames(buried, deepScan = false).isEmpty())
    check("глубокий обход достаёт", importGames(buried, deepScan = true).size == 1)
    check("счётчик и импортёр видят глубокий обход одинаково",
        countGames(buried, true) == importGames(buried, true).size)

    // ── the data root's own layout stays out of it, at any depth ───────
    val dataRoot = "files/blade.nsp;" +
        "files/nand/user/save/0000000000000000/abcdef/slot.bin;" +
        "files/load/[0100B7B00F2E800]/cheat.nsp;" +
        "files/cache/shader/cache.bin;" +
        "files/shader/precompiled.bin"
    check("обход не заходит в nand/load/cache даже вглубь",
        importGames(dataRoot, deepScan = true) == listOf("blade.nsp"),
        "импортировано ${importGames(dataRoot, true)}")
    check("счётчик папки тоже не заходит", countGames(dataRoot, deepScan = true) == 1)

    // ── no keys must not mean "no games" ───────────────────────────────
    val remembered = listOf("games/blade.nsp", "games/mario.nsp")
    check("без ключей прошлый список не стирается",
        getGamesWithoutKeys(remembered) == remembered)
    check("без ключей и без прошлого обхода - пусто, и это честно",
        getGamesWithoutKeys(emptyList()).isEmpty())

    // ── the shipped sources ────────────────────────────────────────────
    println("\nисходники в репозитории")
    val scanner = File("patch/android/utils/GameFolderScanner.kt")
    val helper = File("patch/android/utils/GameHelper.kt")
    val panel = File("patch/android/utils/LivePanel.kt")
    for (f in listOf(scanner, helper, panel)) {
        check("${f.path} на месте", f.exists())
    }
    if (scanner.exists()) {
        val src = scanner.readText()
        check("сканер задаёт мелкую глубину 3",
            Regex("const val SHALLOW_SCAN_DEPTH = 3").containsMatchIn(src),
            "мелкий обход снова смотрит один уровень")
        check("глубокий обход глубже мелкого",
            Regex("const val DEEP_SCAN_DEPTH = (\\d+)").find(src)
                ?.groupValues?.get(1)?.toIntOrNull()?.let { it > 3 } == true)
        check("depthFor() отдаёт именно эти числа",
            Regex(
                "fun depthFor\\(deepScan: Boolean\\): Int = if \\(deepScan\\) " +
                    "DEEP_SCAN_DEPTH else SHALLOW_SCAN_DEPTH"
            ).containsMatchIn(src))
        check("счётчик и список идут на одной глубине",
            Regex("scanOne\\(context, dir\\.uriString, depthFor\\(dir\\.deepScan\\)")
                .containsMatchIn(src))
        check("список файлов папки берёт ту же глубину",
            Regex("maxDepth: Int = SHALLOW_SCAN_DEPTH").containsMatchIn(src),
            "listGames() по умолчанию смотрит один уровень")
    }
    if (helper.exists()) {
        val src = helper.readText()
        check("импортёр берёт глубину у сканера",
            Regex("GameFolderScanner\\.depthFor\\(gameDir\\.deepScan\\)").containsMatchIn(src))
        check("в импортёре не осталось своей копии правила",
            !Regex("if \\(gameDir\\.deepScan\\)").containsMatchIn(src),
            "две копии одного числа разъедутся снова")
        check("импортёр не заходит в папки раскладки",
            Regex("isLayoutName\\(GameFolderScanner\\.displayNameOf").containsMatchIn(src),
            "обход в три уровня уходит в nand/load/cache")
        // Утверждение про присваивание, а не про текст: та же строка
        // стоит в комментарии, где объясняется, что её убрали.
        check("отсутствие ключей не стирает прошлый список",
            !Regex("(?m)^\\s*cachedGameList = mutableListOf\\(\\)\\s*$").containsMatchIn(src) &&
                Regex("return rememberedGames\\(\\)").containsMatchIn(src),
            "библиотека гаснет, пока не подложат ключи")
        check("есть откуда взять прошлый список",
            Regex("fun rememberedGames\\(\\): List<Game>").containsMatchIn(src))
    }
    if (panel.exists()) {
        val src = panel.readText()
        check("панель берёт список у GameHelper",
            Regex("fun rememberedGames\\(\\): List<Game> =\\s*\\n?\\s*" +
                "runCatching \\{ GameHelper.rememberedGames\\(\\) \\}")
                .containsMatchIn(src))
        check("панель не гасит список без ключей",
            !src.contains("if (!keysPresent()) return emptyList()"),
            "rememberedGames() возвращает пустоту, если ключей нет")
        check("панель честно сообщает про ключи",
            Regex("put\\(\"keys\", keysPresent\\(\\)\\)").containsMatchIn(src))
    }

    println()
    if (failed > 0) {
        println("$failed check(s) failed")
        kotlin.system.exitProcess(1)
    }
    println("all checks passed")
}

// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

import java.io.File
import org.yuzu.yuzu_emu.utils.KenjiData

/**
 * Папка данных второго ядра: раскладка, проверка и поиск готовых папок.
 *
 * Почему это проверяется на хосте: ошибка здесь стоит человеку трёхсот
 * мегабайт копирования на каждом запуске или чёрного экрана без причины.
 * Логика — обычная работа с файлами, поэтому и проверяется обычными файлами.
 */
fun main() {
    var failures = 0
    fun check(name: String, ok: Boolean, detail: String = "") {
        if (ok) println("ok   $name")
        else {
            failures++
            println("FAIL $name ${if (detail.isEmpty()) "" else "— $detail"}")
        }
    }

    val base = File(System.getProperty("java.io.tmpdir"), "kenji-data-test").apply {
        deleteRecursively()
        mkdirs()
    }

    // ── 1. раскладка ядра ───────────────────────────────────────────────
    val root = File(base, "core")
    check(
        "прошивка ищется в bis/system/Contents/registered",
        KenjiData.firmwareDir(root).invariantSeparatorsPath
            .endsWith("core/bis/system/Contents/registered"),
        KenjiData.firmwareDir(root).path
    )
    check(
        "ключи ищутся в system",
        KenjiData.keysDir(root).invariantSeparatorsPath.endsWith("core/system"),
        KenjiData.keysDir(root).path
    )

    // ── 2. пустая папка — не данные ядра ────────────────────────────────
    check("пустая папка: прошивки нет", !KenjiData.hasFirmware(root))
    check("пустая папка: ключей нет", !KenjiData.hasKeys(root))
    check("пустая папка не считается данными ядра", !KenjiData.looksLikeCoreData(root))
    check(
        "в описании пустой папки сказано, что прошивки нет",
        KenjiData.describe(root).contains("прошивки нет"),
        KenjiData.describe(root)
    )

    // ── 3. папка с прошивкой — готова к запуску без переноса ────────────
    val ready = File(base, "ready")
    val registered = KenjiData.firmwareDir(ready).apply { mkdirs() }
    File(registered, "0000000000000010.nca").writeText("x")
    File(registered, "0000000000000011.nca").writeText("x")
    File(KenjiData.keysDir(ready), "prod.keys").apply { parentFile?.mkdirs(); writeText("k") }
    check("прошивка найдена: 2 файла", KenjiData.firmwareCount(ready) == 2)
    check("ключи найдены", KenjiData.hasKeys(ready))
    check("папка считается данными ядра", KenjiData.looksLikeCoreData(ready))
    val described = KenjiData.describe(ready)
    check("описание называет число файлов прошивки", described.contains("прошивка: 2 файлов"), described)
    check("описание говорит про ключи", described.contains("ключи: есть"), described)

    // ── 4. поиск готовых папок ──────────────────────────────────────────
    val scanRoot = File(base, "sdcard").apply { mkdirs() }
    // готовый кандидат — с прошивкой
    val kenjiSpace = File(scanRoot, "Kenji").apply { mkdirs() }
    File(KenjiData.firmwareDir(kenjiSpace), "a.nca").apply { parentFile?.mkdirs(); writeText("x") }
    // пустая папка рядом — кандидатом быть не должна
    File(scanRoot, "Пустая").mkdirs()
    // папка с играми, но без прошивки — тоже не кандидат
    File(File(scanRoot, "Игры"), "Games").mkdirs()

    val found = KenjiData.candidates(listOf(scanRoot))
    check("найдена папка с прошивкой", found.any { it.name == "Kenji" }, found.joinToString { it.name })
    check("пустая папка не предложена", found.none { it.name == "Пустая" })
    check("папка без прошивки не предложена", found.none { it.name == "Игры" })

    // ── 5. запись ──────────────────────────────────────────────────────
    check("в свою папку писать можно", KenjiData.writable(File(base, "w")))
    check(
        "проверка записи не оставляет мусора",
        File(base, "w").listFiles()?.isEmpty() != false
    )

    // ── 6. глубина обхода ограничена ────────────────────────────────────
    val deep = File(scanRoot, "a/b/c/d/e").apply { mkdirs() }
    File(KenjiData.firmwareDir(deep), "far.nca").apply { parentFile?.mkdirs(); writeText("x") }
    val shallow = KenjiData.candidates(listOf(scanRoot), maxDepth = 2)
    check("слишком глубокая папка не предлагается", shallow.none { it.name == "e" })

    base.deleteRecursively()

    if (failures == 0) println("\nвсе проверки KenjiData пройдены")
    else {
        println("\nпровалено: $failures")
        kotlin.system.exitProcess(1)
    }
}

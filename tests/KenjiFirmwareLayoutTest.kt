// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

// Где именно второе ядро ищет прошивку — проверка по исходникам Kenji-NX.
//
// `ContentManager.LoadEntries` смотрит двумя способами:
//   1) подпапки внутри `registered` (файлы в `registered` не читаются вообще);
//   2) файлы `.nca` прямо в `bis/system/Contents`.
// Eden кладёт прошивку плоским списком внутрь `registered`, поэтому ссылка на
// папку Eden давала второму ядру ноль файлов — и в журнале появлялось
// `TimeZoneBinary system title not found`.
//
// Здесь проверяется разбор раскладки, на синтетических файлах: устройство не
// нужно, реальных 300 МБ не создаётся.

import org.yuzu.yuzu_emu.utils.KenjiData
import java.io.File
import java.nio.file.Files

private var passed = 0
private val failures = mutableListOf<String>()

private fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) {
        passed++
        println("  ok    $name")
    } else {
        failures += if (detail.isEmpty()) name else "$name → $detail"
        println("  FAIL  $name${if (detail.isEmpty()) "" else " → $detail"}")
    }
}

private fun eq(name: String, actual: Any?, expected: Any?) =
    check(name, actual == expected, "получено $actual, ожидалось $expected")

private fun touch(dir: File, name: String): File {
    dir.mkdirs()
    val file = File(dir, name)
    file.writeBytes(ByteArray(16))
    return file
}

fun main() {
    val root = Files.createTempDirectory("kenji-layout").toFile()
    try {
        // 1. Путь, который читает Kenji: bis/system/Contents (не registered).
        eq("папка содержимого — <ядро>/bis/system/Contents",
            KenjiData.firmwareContentsDir(root).absolutePath,
            File(File(File(root, "bis"), "system"), "Contents").absolutePath)

        // 2. Пусто — ноль, ни одного исключения.
        eq("пустая папка — 0 прошивочных файлов", KenjiData.firmwareCount(root), 0)
        check("пустая папка — прошивки нет", !KenjiData.hasFirmware(root))

        // 3. Раскладка Eden, подставленная ссылкой на папку: файлы в registered
        //    плоско. Для Kenji это по-прежнему ноль в Contents.
        val registered = KenjiData.firmwareDir(root)
        touch(registered, "0100000000000809.nca")
        touch(registered, "0100000000000810.nca")
        eq("файлы внутри registered не считаются плоскими",
            KenjiData.flatFirmwareCount(root), 0)
        eq("но nested-счётчик их видит", KenjiData.firmwareCountOf(registered), 2)

        // 4. Настоящая правка: плоские .nca в Contents — то, что Kenji читает.
        val contents = KenjiData.firmwareContentsDir(root)
        touch(contents, "0100000000000809.nca")
        touch(contents, "0100000000000810.nca")
        eq("плоские .nca в Contents считаются", KenjiData.flatFirmwareCount(root), 2)
        eq("общий счёт = nested + плоские", KenjiData.firmwareCount(root), 4)
        check("прошивка видна", KenjiData.hasFirmware(root))

        // 5. Посторонние файлы не выдаются за прошивку.
        touch(contents, "prod.keys")
        touch(contents, "readme.txt")
        eq("не .nca не считается прошивкой", KenjiData.flatFirmwareCount(root), 2)

        // 6. Подпапка внутри Contents (вариант установки) не превращается в
        //    плоский файл — счётчики не путаются.
        val sub = File(contents, "0100000000001000")
        touch(sub, "0100000000001000.nca")
        eq("подпапка не попадает в плоский счётчик", KenjiData.flatFirmwareCount(root), 2)
        eq("плоский счёт остаётся прежним по числу", KenjiData.firmwareCount(root), 4)

        // 7. Верхний путь: registered как ссылка на папку Eden всё ещё виден
        //    nested-счётчиком — это нужно для отчёта «копия, N файлов».
        check("registered остаётся отдельной папкой в отчёте",
            KenjiData.firmwareDir(root).absolutePath != contents.absolutePath)
    } finally {
        root.deleteRecursively()
    }

    println()
    println("passed=$passed failed=${failures.size}")
    if (failures.isNotEmpty()) {
        failures.forEach { println("  × $it") }
        kotlin.system.exitProcess(1)
    }
}

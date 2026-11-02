// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * Дополнительные файлы ключей: что считать ключом и что дописывать.
 *
 * ПОЧЕМУ ПРОВЕРЯЕТСЯ НА ХОСТЕ
 *   Ошибка здесь не видна ни на экране, ни в сборке: ключ просто «не
 *   подхватился», и человек снова читает «нет ключа для этой игры», пока
 *   остальные запускаются. Правила простые (какая строка — ключ игры, какую
 *   не перезаписывать), поэтому проверяются целиком, без телефона.
 */

import org.yuzu.yuzu_emu.utils.ExtraKeys
import java.io.File
import java.nio.file.Files

private var passed = 0
private val failures = mutableListOf<String>()

private fun check(what: String, ok: Boolean, detail: String = "") {
    if (ok) {
        ++passed
    } else {
        failures.add(what + if (detail.isEmpty()) "" else " -> $detail")
    }
}

private const val HEX32 = "00112233445566778899AABBCCDDEEFF00112233445566778899AABBCCDDEEFF"

fun main() {
    // ── какая строка — ключ игры ────────────────────────────────────────
    check("идентификатор игры — ключ игры",
        ExtraKeys.isTitleLine("01004F302847E000 = $HEX32"))
    check("имя ключа — ключ консоли", !ExtraKeys.isTitleLine("header_key = $HEX32"))
    check("15 знаков — не идентификатор", !ExtraKeys.isTitleLine("01004F302847E00 = $HEX32"))
    check("буквы в нижнем регистре — тоже идентификатор",
        ExtraKeys.isTitleLine("01004f302847e000 = $HEX32"))
    check("строка без «=» — не ключ", !ExtraKeys.isTitleLine("это не ключ"))
    check("пробелы вокруг «=» не мешают",
        ExtraKeys.keyOf("  header_key   =   $HEX32") == "header_key")

    // ── какие файлы читать ──────────────────────────────────────────────
    val names = listOf(
        "prod.keys", "title.keys", "console.keys", "dev.keys",
        "my_game.keys", "старые.txt", "readme.md", "Картинка.PNG"
    )
    val picked = ExtraKeys.extraFileNames(names)
    check("ядровые файлы не трогаем", !picked.contains("prod.keys") && !picked.contains("title.keys"),
        picked.toString())
    check("свой файл ключей взят", picked.contains("my_game.keys"), picked.toString())
    check("txt взят", picked.contains("старые.txt"), picked.toString())
    check("не ключи не взят", !picked.contains("readme.md") && !picked.contains("Картинка.PNG"),
        picked.toString())

    // ── сведение ────────────────────────────────────────────────────────
    val existingProd = listOf("# свои ключи", "header_key = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
    val existingTitle = listOf("0100000000000000 = BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB")
    val plan = ExtraKeys.plan(
        existingProd,
        existingTitle,
        mapOf(
            "my_game.keys" to """
                # ключ, которого не было у остальных игр
                01004F302847E000 = $HEX32
            """.trimIndent(),
            "console_extra.txt" to """
                header_key = $HEX32
                new_key = $HEX32
            """.trimIndent()
        )
    )
    check("ключ игры добавлен", plan.addedTitle == 1, plan.addedTitle.toString())
    check("ключ консоли добавлен только новый", plan.addedProd == 1, plan.addedProd.toString())
    check("свой header_key не перезаписан",
        plan.prod.any { it == "header_key = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" },
        plan.prod.toString())
    check("новый ключ консоли в списке", plan.prod.any { it.startsWith("new_key") }, plan.prod.toString())
    check("комментарий сохранён", plan.prod.first() == "# свои ключи", plan.prod.toString())
    check("ключ игры в title.keys", plan.title.any { it.startsWith("01004F302847E000") },
        plan.title.toString())

    // Повтор в двух файлах — один ключ, а не два.
    val twice = ExtraKeys.plan(
        emptyList(), emptyList(),
        mapOf(
            "a.keys" to "01004F302847E000 = $HEX32",
            "b.keys" to "01004f302847e000 = $HEX32"
        )
    )
    check("повтор не задваивается", twice.addedTitle == 1, twice.addedTitle.toString())

    // Мусорные строки считаются, а не молча проглатываются.
    val messy = ExtraKeys.plan(
        emptyList(), emptyList(),
        mapOf("bad.keys" to "просто текст\nключ без значения =\nключ = $HEX32")
    )
    check("неразобранные строки посчитаны", messy.unrecognised == 2, messy.unrecognised.toString())
    check("годная строка всё равно взята", messy.addedProd == 1, messy.addedProd.toString())

    // CRLF: файл, сохранённый на Windows, не должен давать ключ с «\r».
    val crlf = ExtraKeys.plan(
        emptyList(), emptyList(),
        mapOf("win.keys" to "header_key = $HEX32\r\nnew_key = $HEX32\r\n")
    )
    check("CRLF разобран", crlf.addedProd == 2, crlf.addedProd.toString())
    check("без \\r в значении", crlf.prod.none { it.contains('\r') }, crlf.prod.toString())

    // ── отчёт ───────────────────────────────────────────────────────────
    check("нет файлов — нет отчёта", ExtraKeys.plan(emptyList(), emptyList(), emptyMap()).report().isEmpty())
    check("отчёт называет файл", plan.report().contains("my_game.keys"), plan.report())
    check("отчёт считает добавленное", plan.report().contains("ключей игр +1"), plan.report())

    val nothingNew = ExtraKeys.plan(
        listOf("header_key = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"), emptyList(),
        mapOf("dup.keys" to "header_key = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
    )
    check("про повтор сказано прямо", nothingNew.report().contains("Новых ключей нет"),
        nothingNew.report())
    check("изменений нет — писать нечего", !nothingNew.changed)

    // ── работа с папкой ─────────────────────────────────────────────────
    val dir = Files.createTempDirectory("keys").toFile()
    File(dir, "prod.keys").writeText("header_key = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\n")
    File(dir, "title.keys").writeText("")
    File(dir, "vault.keys").writeText("01004F302847E000 = $HEX32\n")

    var reloaded = 0
    val report = ExtraKeys.apply(dir) { ++reloaded; true }
    check("отчёт про папку не пуст", report.isNotBlank(), report)
    check("ядро попросили перечитать", reloaded == 1, reloaded.toString())
    check("ключ дописан в title.keys",
        File(dir, "title.keys").readText().contains("01004F302847E000"),
        File(dir, "title.keys").readText())
    check("prod.keys не испорчен",
        File(dir, "prod.keys").readText().contains("header_key = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"),
        File(dir, "prod.keys").readText())

    // Второй проход: добавлять нечего — писать и перечитывать не нужно.
    reloaded = 0
    val second = ExtraKeys.apply(dir) { ++reloaded; true }
    check("второй проход ничего не добавил", second.contains("Новых ключей нет"), second)
    check("второй проход не перечитывает", reloaded == 0, reloaded.toString())

    // Ядро не подтвердило перечитывание — это сказано, а не скрыто.
    File(dir, "more.keys").writeText("new_key = $HEX32\n")
    val failedReload = ExtraKeys.apply(dir) { false }
    check("отказ перечитывания назван", failedReload.contains("не подтвердило"), failedReload)

    // Пустая папка и отсутствующая папка — пустой отчёт, без исключений.
    val empty = Files.createTempDirectory("keys_empty").toFile()
    check("пустая папка — пусто", ExtraKeys.apply(empty) { true }.isEmpty())
    check("нет папки — пусто", ExtraKeys.apply(File(empty, "нет-такой")) { true }.isEmpty())

    dir.deleteRecursively()
    empty.deleteRecursively()

    println()
    println("passed=$passed failed=${failures.size}")
    failures.forEach { println("FAIL  $it") }
    if (failures.isNotEmpty()) kotlin.system.exitProcess(1)
}

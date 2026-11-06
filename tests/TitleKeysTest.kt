// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

// «Ключи стоят, а игра не открывается» — проверка того, что ключ игры вообще
// есть. На телефоне 06.11 было 204 ключа консоли и 6 ключей игр, и вопрос
// «есть ли ключ у этой игры» никто не задавал: экран отвечал про prod.keys,
// библиотека — «дело не в ключах», ядро — MissingTitlekey.
//
// Считать руками нечего: строки «0100… = 32hex» в title.keys и в
// дополнительных файлах.

import java.io.File
import java.nio.file.Files
import org.yuzu.yuzu_emu.utils.ExtraKeys
import org.yuzu.yuzu_emu.utils.TitleKeys

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

private const val TNMT = "0100fe701475a000 = 11111111111111111111111111111111"
private const val VAULT = "01004f302847e000 = 22222222222222222222222222222222"

private fun tmpDir(): File = Files.createTempDirectory("keys").toFile()

fun main() {
    // ── разбор title.keys ───────────────────────────────────────────────
    val title = listOf(
        "# Nintendo Switch title keys",
        VAULT,
        "0100FE701475A000 = 33333333333333333333333333333333",
        "",
        "  01007fc01cf4e000=44444444444444444444444444444444  ",
    )
    val ids = TitleKeys.ids(title)
    eq("ключей игр найдено", ids.size, 3)
    check("идентификатор в нижнем регистре", ids.contains("01004f302847e000"), ids.toString())
    check("пробелы вокруг «=» не мешают", ids.contains("01007fc01cf4e000"), ids.toString())
    check("регистр ключа не важен", TitleKeys.has(title, "0100fe701475A000"))

    // ── что ключом игры не является ─────────────────────────────────────
    val systemOnly = listOf(
        "header_key = aabbccddeeff00112233445566778899",
        "titlekek_source = 0102030405060708090a0b0c0d0e0f10",
    )
    eq("ключи консоли за ключи игр не считаются", TitleKeys.ids(systemOnly).size, 0)
    check("и запроса про игру не закрывают",
        !TitleKeys.has(systemOnly, "01004f302847e000"))

    // ── мусорный идентификатор ──────────────────────────────────────────
    // Права в билете нет — Rights ID забит FF. Такой «идентификатор» нельзя
    // ни показать, ни применить: на телефоне он вылез как ffffffffffffffff.
    check("FF-идентификатор не настоящий", !TitleKeys.isPlausible("ffffffffffffffff"))
    check("нулевой не настоящий", !TitleKeys.isPlausible("0000000000000000"))
    check("короткий не настоящий", !TitleKeys.isPlausible("01004f302847e00"))
    check("не про игру — не настоящий", !TitleKeys.isPlausible("aabbccddeeff0011"))
    check("настоящий опознан", TitleKeys.isPlausible("01004f302847e000"))

    // ── файлы на диске ──────────────────────────────────────────────────
    val dir = tmpDir()
    File(dir, "title.keys").writeText("$VAULT\n")
    File(dir, "мой-ключ.txt").writeText("$TNMT\n")
    File(dir, "extra").mkdirs()
    File(dir, "extra/ещё.keys").writeText("01007fc01cf4e000 = 55555555555555555555555555555555\n")
    val all = TitleKeys.readAll(dir)
    eq("собраны ключи из title.keys, *.txt и extra", TitleKeys.ids(all).size, 3)
    check("ключ из txt виден", TitleKeys.has(all, "0100fe701475a000"))
    check("ключ из extra виден", TitleKeys.has(all, "01007fc01cf4e000"))
    check("чужой игры нет", !TitleKeys.has(all, "0100000000001000"))

    // ── отчёт для экрана ключей ─────────────────────────────────────────
    val empty = TitleKeys.report(emptyList())
    check("пустой отчёт говорит про ключи игр", empty.contains("ключей игр нет"), empty)
    val withOne = TitleKeys.report(all, "01004f302847e000")
    check("отчёт считает ключи", withOne.contains("ключей игр: 3"), withOne)
    check("отчёт отвечает про конкретную игру", withOne.contains("есть"), withOne)
    val missing = TitleKeys.report(all, "0100000000001000")
    check("отчёт честно говорит «НЕТ»", missing.contains("0100000000001000: НЕТ"), missing)

    // ── ручной ввод ключа ───────────────────────────────────────────────
    // Путать «не тот формат» и «ключ не подошёл» нельзя: починка разная.
    val manual = TitleKeys.manualLine("01007FC01CF4E000", "00112233445566778899AABBCCDDEEFF")
    eq("ввод приведён к строке ключа", manual, "01007fc01cf4e000 = 00112233445566778899aabbccddeeff")
    check("пробелы во вводе не мешают",
        TitleKeys.manualLine(" 0100 FE70 1475 A000 ", "00112233445566778899aabbccddeeff") != null)
    check("короткий ключ не принимается",
        TitleKeys.manualLine("01004f302847e000", "00112233") == null)
    check("мусорный идентификатор не принимается",
        TitleKeys.manualLine("ffffffffffffffff", "00112233445566778899aabbccddeeff") == null)
    check("не hex не принимается",
        TitleKeys.manualLine("01004f302847e000", "zz112233445566778899aabbccddeeff") == null)

    // ── добавление не стирает чужие ключи ───────────────────────────────
    // У каждой игры свой ключ: новый файл или строка не должны затирать
    // остальные — на телефоне в title.keys всего 6 записей, и каждая на счёт.
    val before = listOf("# keys", VAULT)
    val added = TitleKeys.upsert(before, manual!!)
    eq("ключей стало два", TitleKeys.ids(added).size, 2)
    check("прежний ключ на месте",
        added.any { it.contains("01004f302847e000", true) })
    eq("повторный ввод не плодит строки",
        TitleKeys.upsert(added, manual).count { it.contains("01007fc01cf4e000", true) }, 1)
    val replaced = TitleKeys.upsert(added, "01007fc01cf4e000 = 99999999999999999999999999999999")
    eq("новый ключ той же игры заменяет старый",
        replaced.first { it.contains("01007fc01cf4e000", true) },
        "01007fc01cf4e000 = 99999999999999999999999999999999")
    eq("и не добавляет вторую строку", TitleKeys.ids(replaced).size, 2)

    dir.deleteRecursively()

    println()
    println("passed=$passed failed=${failures.size}")
    failures.forEach { println("FAIL  $it") }
    if (failures.isNotEmpty()) kotlin.system.exitProcess(1)
}

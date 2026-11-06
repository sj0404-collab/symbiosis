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

    // ── настоящий title.keys: другой разделитель, BOM, UTF-16 ───────────
    // На телефоне 06.11 в title.keys было 6 записей, а приложение сказало
    // «ключей игр нет: ни одной строки вида «0100… = 32hex»». Файл был не
    // виноват — разбор понимал только «=» и только UTF-8.
    val colon = listOf("01004f302847e000 : 22222222222222222222222222222222")
    eq("двоеточие как разделитель", TitleKeys.ids(colon).size, 1)
    val spaces = listOf("0100FE701475A000\t=   33333333333333333333333333333333")
    check("табуляция и регистр не мешают", TitleKeys.ids(spaces).contains("0100fe701475a000"))
    eq("BOM в начале файла не мешает",
        TitleKeys.ids(listOf("\uFEFF01004f302847e000 = 22222222222222222222222222222222")).size, 1)

    val utf16 = TitleKeys.decode(
        "\uFEFF01004f302847e000 = 22222222222222222222222222222222\r\n"
            .toByteArray(Charsets.UTF_16LE)
    )
    eq("UTF-16 прочитан", TitleKeys.ids(utf16.lines()).size, 1)
    val utf16NoBom = TitleKeys.decode(
        "0100fe701475a000 = 33333333333333333333333333333333\r\n".toByteArray(Charsets.UTF_16LE)
    )
    eq("UTF-16 без BOM прочитан", TitleKeys.ids(utf16NoBom.lines()).size, 1)

    // Отчёт обязан объяснить, что не так, а не отписаться «нет строк».
    val bad = listOf(
        "01004f302847e000 = 00112233",                     // ключ короткий
        "заметка = значение",                               // слева не идентификатор
        "0011223344556677 = 22222222222222222222222222222222", // идентификатор не игры
    )
    val survey = TitleKeys.survey(bad)
    eq("принятых нет", survey.ids.size, 0)
    eq("строк с разделителем три", survey.withSeparator, 3)
    eq("все три объяснены", survey.rejected.size, 3)
    check("короткий ключ назван", survey.rejected.any { it.contains("32") }, survey.rejected.toString())
    check("причина названа по правой части",
        survey.rejected.any { it.contains("справа не 32") }, survey.rejected.toString())
    // Разбор пробует обе стороны: сначала смотрит, годится ли левая как
    // идентификатор, и только потом сообщает, что именно не так.
    check("негодная левая часть тоже названа",
        survey.rejected.any { it.contains("не похоже на идентификатор") },
        survey.rejected.toString())

    val text = TitleKeys.report(bad)
    check("отчёт не врёт про «нет строк»", !text.contains("ни одного файла"), text)
    check("отчёт показывает счёт строк", text.contains("3"), text)

    // ── установка файла: приводим к «id = key», мусор не тащим ──────────
    val file = TitleKeys.keyLines(
        "# комментарий\r\n" +
            "01004F302847E000:22222222222222222222222222222222\r\n" +
            "0100fe701475a000 = 00112233445566778899AABBCCDDEEFF\r\n" +
            "ffffffffffffffff = 11111111111111111111111111111111\r\n"
    )
    eq("из файла взяты две настоящие строки", file.size, 2)
    check("вид приведён к «id = key»",
        file.contains("01004f302847e000 = 22222222222222222222222222222222"), file.toString())
    check("FF-строка отброшена", file.none { it.startsWith("ffff") }, file.toString())

    // ── файл с консоли: слева права билета, 32 знака ────────────────────
    // Ровно это лежит у человека: 6 строк вида
    //   0100c2500fc20800000000000000000e = <32 hex>
    // Раньше приложение отвечало «слева не 16 hex-знаков (32)» и считало
    // файл негодным, хотя все шесть ключей настоящие.
    val rightsForm = listOf(
        "0100c2500fc20800000000000000000e = 11111111111111111111111111111111",
        "0100c2500fc20000000000000000000e = 22222222222222222222222222222222",
        "010092501eb2c0000000000000000011 = 33333333333333333333333333333333",
        "ffffffffffffffffffffffffffffffff = 44444444444444444444444444444444",
    )
    val rs = TitleKeys.survey(rightsForm)
    eq("права билета поняты как игры", rs.ids.size, 3)
    eq("и это отмечено в отчёте", rs.rightsForm, 3)
    eq("FF-права отброшены", rs.rejected.size, 1)
    check("идентификатор — первые 16 знаков прав",
        rs.ids.contains("0100c2500fc20800"), rs.ids.toString())
    check("обновление не путается с игрой",
        TitleKeys.report(rightsForm).contains("обновление"), TitleKeys.report(rightsForm))

    // Канонический вид — тот, который читает ядро: «игра = ключ».
    eq("права приведены к идентификатору",
        TitleKeys.canonicalKeyLine(rightsForm[0]),
        "0100c2500fc20800 = 11111111111111111111111111111111")
    eq("16-значная строка не меняется",
        TitleKeys.canonicalKeyLine(VAULT), VAULT)

    // ── нормализация файла целиком (как при установке) ──────────────────
    val keysDir2 = tmpDir()
    File(keysDir2, "title.keys").writeText(
        "# ключи\n" + rightsForm.take(3).joinToString("\n") + "\n"
    )
    val note = ExtraKeys.apply(keysDir2) { true }
    check("нормализация отчиталась", note.contains("приведён"), note)
    val after = File(keysDir2, "title.keys").readText()
    check("в файле теперь канонический вид",
        after.contains("0100c2500fc20800 = 11111111111111111111111111111111"), after)
    check("комментарий сохранён", after.contains("# ключи"), after)
    eq("ключей видно три", TitleKeys.ids(after.lines()).size, 3)
    keysDir2.deleteRecursively()

    dir.deleteRecursively()

    println()
    println("passed=$passed failed=${failures.size}")
    failures.forEach { println("FAIL  $it") }
    if (failures.isNotEmpty()) kotlin.system.exitProcess(1)
}

// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * Текст в шторке после распаковки.
 *
 * ПОЧЕМУ ОТДЕЛЬНЫЙ ТЕСТ
 *   С устройства пришло уведомление «Распаковка закончилась без результата.
 *   Файлы здесь: /storage/emulated/0/Download/Symbiosis» — и ни слова о
 *   причине. Причина была, но её заменил путь. Проверять такое глазами
 *   дорого: чтобы увидеть отказ, нужен битый файл на телефоне.
 */

import org.yuzu.yuzu_emu.utils.ConverterReasons

private var passed = 0
private val failures = mutableListOf<String>()

private fun check(what: String, ok: Boolean, detail: String = "") {
    if (ok) passed++ else failures.add("$what${if (detail.isEmpty()) "" else " → $detail"}")
}

private fun eq(what: String, got: Any?, want: Any?) =
    check(what, got == want, "получено [$got], ожидалось [$want]")

fun main() {
    // Успех: путь и есть ответ, причина не нужна.
    eq("успех показывает путь", ConverterReasons.outcomeText(true, 0, "мелочь", "/storage/emulated/0/Download/Symbiosis"),
        "/storage/emulated/0/Download/Symbiosis")

    // Отказ: причина ПЕРВОЙ строкой, путь второй.
    val refused = ConverterReasons.outcomeText(
        false,
        "«Game.zip» — это архив ZIP, а не образ. Распакуйте его в проводнике и выберите оттуда файл .nsp, .xci, .nsz или .xcz.",
        "Файлы здесь: /storage/emulated/0/Download/Symbiosis"
    )
    val lines = refused.lines()
    check("причина в первой строке", lines[0].contains("архив ZIP"), refused)
    check("путь остался второй строкой", lines.size == 2 && lines[1].startsWith("Файлы здесь"), refused)
    check("слово «без результата» больше не единственный ответ", lines[0].contains("а не образ"), refused)

    // Причина без пути - только причина, без пустой строки.
    eq("без пути одна строка", ConverterReasons.outcomeText(false, 0, "файл обрезан", ""), "файл обрезан")

    // Пустая причина при отказе не должна превращаться в пустое уведомление.
    eq("пустая причина заменена", ConverterReasons.outcomeText(false, 0, "   ", ""), "распаковка не удалась")

    // Путь, но без причины: причина важнее, но и путь терять нельзя.
    eq("пустая причина + путь", ConverterReasons.outcomeText(false, 0, "", "Файлы здесь: /x"),
        "распаковка не удалась\nФайлы здесь: /x")

    // Многострочная причина: путь не должен склеиваться с её последней строкой.
    val multi = ConverterReasons.outcomeText(false, 0, "первая\nвторая", "путь")
    eq("многострочная причина", multi, "первая\nвторая\nпуть")

    // Имя файла в сообщении: «сжатые данные повреждены» без имени не говорит,
    // какой из выбранных файлов чинить. С устройства пришло именно сообщение
    // без файла.
    val fromQueue = "«Zelda.nsz»: сжатые данные внутри файла повреждены"
    val shown = ConverterReasons.outcomeText(false, 0, fromQueue, "Файлы здесь: /x")
    check("имя файла видно в первой строке", shown.lines()[0].contains("Zelda.nsz"), shown)

    // ── распаковано, но не открывается ─────────────────────────────────
    // На снимке 06.11: в журнале «Конвертация завершена за 2 с, размер NSP:
    // 307.2 МБ», а в шторке «Распаковка закончилась без результата». Оба
    // сообщения про одно дело, и человек решал, что распаковка провалилась.
    val unpackedOne = ConverterReasons.outcomeText(
        false, 1,
        "«Vault of Power.nsp»: нет ключа игры 01004f302847e000 в title.keys",
        "Файлы здесь: /storage/emulated/0/Download/Symbiosis"
    )
    check("сказано, что распаковано", unpackedOne.contains("Распаковано"), unpackedOne)
    check("и что дело не в распаковке", !unpackedOne.contains("без результата"), unpackedOne)
    check("причина названа", unpackedOne.contains("title.keys"), unpackedOne)
    check("путь остался", unpackedOne.contains("Файлы здесь"), unpackedOne)

    val unpackedMany = ConverterReasons.outcomeText(false, 3, "нет ключа игры", "")
    check("для нескольких файлов число названо", unpackedMany.contains("3"), unpackedMany)

    val nothing = ConverterReasons.outcomeText(false, 0, "файл обрезан", "")
    eq("ничего не распаковано — только причина", nothing, "файл обрезан")

    // Успех не должен обрастать подробностями.
    eq("успех без прикрас", ConverterReasons.outcomeText(true, 5, "", "/x"), "/x")

    println()
    println("passed=$passed failed=${failures.size}")
    failures.forEach { println("FAIL  $it") }
    if (failures.isNotEmpty()) kotlin.system.exitProcess(1)
}

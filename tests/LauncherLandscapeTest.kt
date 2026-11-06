// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Альбомная ориентация лаунчера: те же имена размеров в двух папках и ужатые
// значения в values-land.
//
// Зачем отдельный тест. 06.11 человек прислал снимок: в альбоме из списка игр
// видна одна плитка, листать неудобно. Причина — шапка в портретных отступах.
// Правка сделана ресурсами (`values` + `values-land`), и ошибка здесь тихая:
// забыли имя в values-land — сборка пройдёт, а в альбоме просто останутся
// старые размеры. Поэтому проверяем состав имён и сами значения.

import java.io.File

var passed = 0
var failed = 0

fun check(name: String, ok: Boolean, extra: String = "") {
    if (ok) {
        passed++
    } else {
        failed++
        println("FAIL  $name" + if (extra.isNotEmpty()) " → $extra" else "")
    }
}

fun eq(name: String, got: Any?, want: Any?) = check(name, got == want, "получено $got, ожидалось $want")

/** Прочитать `<dimen name="…">значение</dimen>` и `<integer name="…">…</integer>`. */
fun resources(file: File): Map<String, String> {
    if (!file.isFile) return emptyMap()
    val text = file.readText()
    val regex = Regex("<(dimen|integer|bool)\\s+name=\"([^\"]+)\"\\s*>([^<]+)</")
    return regex.findAll(text).associate { it.groupValues[2] to it.groupValues[3].trim() }
}

/** dp как число. */
fun dp(value: String?): Int? = value?.removeSuffix("dp")?.trim()?.toIntOrNull()

fun main() {
    val base = File(System.getProperty("sym.patch") ?: "patch/android")
    val portrait = resources(File(base, "values/symbiosis_dimens.xml"))
    val landscape = resources(File(base, "values-land/symbiosis_dimens.xml"))

    check("портретные размеры на месте", portrait.isNotEmpty(), portrait.toString())
    check("альбомные размеры на месте", landscape.isNotEmpty(), landscape.toString())

    // Набор имён обязан совпадать: имя, которого нет в values-land, молча
    // возьмёт портретное значение — и правка перестанет работать.
    eq("одни и те же имена в обеих папках", portrait.keys.sorted(), landscape.keys.sorted())

    // Плитка в альбоме должна быть заметно ниже: иначе смысла в правке нет.
    check("отступ плитки в альбоме меньше",
        (dp(landscape["sym_tile_margin"]) ?: 99) < (dp(portrait["sym_tile_margin"]) ?: 0),
        "${landscape["sym_tile_margin"]} против ${portrait["sym_tile_margin"]}")
    check("внутренний отступ плитки меньше",
        (dp(landscape["sym_tile_pad"]) ?: 99) < (dp(portrait["sym_tile_pad"]) ?: 0))
    check("кружок с буквой меньше",
        (dp(landscape["sym_tile_monogram"]) ?: 99) < (dp(portrait["sym_tile_monogram"]) ?: 0))
    eq("подпись плитки — одна строка в альбоме", landscape["sym_tile_lines"], "1")
    eq("и две строки в портрете", portrait["sym_tile_lines"], "2")

    // Шапка: именно она съедала экран.
    check("панель заголовка ниже",
        (dp(landscape["sym_toolbar_height"]) ?: 999) < (dp(portrait["sym_toolbar_height"]) ?: 0))
    check("промежутки шапки меньше",
        (dp(landscape["sym_header_gap"]) ?: 99) < (dp(portrait["sym_header_gap"]) ?: 0))
    check("отступ сводки меньше",
        (dp(landscape["sym_summary_pad_top"]) ?: 99) < (dp(portrait["sym_summary_pad_top"]) ?: 0))

    // Плитка и шапка обязаны ссылаться на эти ресурсы, иначе файлы размеров
    // никто не читает.
    val tile = File(base, "layout/item_launcher_tile.xml").readText()
    check("плитка берёт отступ из ресурсов", tile.contains("@dimen/sym_tile_margin"))
    check("плитка берёт строки из ресурсов", tile.contains("@integer/sym_tile_lines"))
    check("плитка берёт кружок из ресурсов", tile.contains("@dimen/sym_tile_monogram"))

    val launcher = File(base, "layout/fragment_native_launcher.xml").readText()
    check("шапка берёт высоту панели из ресурсов",
        launcher.contains("@dimen/sym_toolbar_height"))
    check("шапка берёт промежуток из ресурсов", launcher.contains("@dimen/sym_header_gap"))

    println("passed=$passed failed=$failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}

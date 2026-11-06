// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

// Прошивка у двух ядер одна. 06.11 человек сказал прямо: не дублировать
// триста с лишним мегабайт, а пользоваться прошивкой Eden через ярлык.
//
// Здесь проверяется решение, а не системный вызов: правила простые, но
// ошибиться в них легко — можно снести чужую прошивку или оставить копию
// там, где ссылка работала бы.

import org.yuzu.yuzu_emu.utils.KenjiData

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

fun main() {
    eq("ссылка на ту же папку — делать нечего",
        KenjiData.sharePlan(linked = true, sameTarget = true, plainCopy = false, linkPossible = true),
        KenjiData.Share.ALREADY_LINKED)

    eq("копия прошивки Eden заменяется ссылкой",
        KenjiData.sharePlan(linked = false, sameTarget = false, plainCopy = true, linkPossible = true),
        KenjiData.Share.REPLACE_COPY)

    eq("своя прошивка не трогается",
        KenjiData.sharePlan(linked = false, sameTarget = false, plainCopy = false, linkPossible = true),
        KenjiData.Share.KEEP_OWN)

    eq("на внешнем томе — только копия",
        KenjiData.sharePlan(linked = false, sameTarget = false, plainCopy = true, linkPossible = false),
        KenjiData.Share.COPY_ONLY)

    eq("ссылка на чужую папку — переставить",
        KenjiData.sharePlan(linked = true, sameTarget = false, plainCopy = true, linkPossible = true),
        KenjiData.Share.REPLACE_COPY)

    eq("битая ссылка без копии — поставить заново",
        KenjiData.sharePlan(linked = true, sameTarget = false, plainCopy = false, linkPossible = true),
        KenjiData.Share.KEEP_OWN)

    // Раскладка прошивки у обоих ядер одна и та же внутри разных корней:
    // Eden — <данные>/nand/system/Contents/registered,
    // ядро — <корень>/bis/system/Contents/registered.
    val root = java.io.File("/tmp/kenji-share-test")
    eq("каталог прошивки ядра",
        KenjiData.firmwareDir(root).path, "/tmp/kenji-share-test/bis/system/Contents/registered")

    println()
    println("passed=$passed failed=${failures.size}")
    failures.forEach { println("FAIL  $it") }
    if (failures.isNotEmpty()) kotlin.system.exitProcess(1)
}

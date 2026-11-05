// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * Записки «эту игру ядро уже роняло».
 *
 * ПОЧЕМУ ЭТОТ ФАЙЛ СУЩЕСТВУЕТ
 *   05.11 игра была «переведена на Eden», а следующий запуск опять ушёл на
 *   Kenji и снова упал. Разбор показал две дыры: запись выбора делалась
 *   асинхронно и не проверялась, а сама записка удалялась сразу после
 *   попытки. Формат записки и её разбор — единственное, что можно проверить
 *   без телефона, поэтому он проверяется здесь.
 */

import org.yuzu.yuzu_emu.utils.CoreFallbackNotes

private var passed = 0
private val failures = mutableListOf<String>()

private fun check(what: String, ok: Boolean, detail: String = "") {
    if (ok) passed++ else failures.add("$what${if (detail.isEmpty()) "" else " → $detail"}")
}

private fun eq(what: String, got: Any?, want: Any?) =
    check(what, got == want, "получено [$got], ожидалось [$want]")

fun main() {
    val a = "/storage/emulated/0/Games/TMNT.nsp"
    val b = "content://com.android.externalstorage.documents/tree/x/document/y"
    val other = "/storage/emulated/0/Games/Mario.nsp"

    // ── добавление и поиск ─────────────────────────────────────────────
    var text = CoreFallbackNotes.add("", a, "ядро упало на сервисе")
    check("записка добавлена", CoreFallbackNotes.has(text, a), text)
    check("чужая игра не помечена", !CoreFallbackNotes.has(text, other), text)
    eq("причина читается обратно", CoreFallbackNotes.reasonFor(text, a), "ядро упало на сервисе")

    // Повтор не плодит строки — иначе файл растёт с каждым запуском.
    text = CoreFallbackNotes.add(text, a, "второй раз")
    eq("повтор заменяет, а не добавляет", CoreFallbackNotes.entries(text).size, 1)
    eq("причина обновилась", CoreFallbackNotes.reasonFor(text, a), "второй раз")

    // ── совпадение по имени файла ──────────────────────────────────────
    check("тот же файл по другому пути считается той же игрой",
        CoreFallbackNotes.sameGame(a, "content://x/TMNT.nsp"))
    check("разные игры не совпадают", !CoreFallbackNotes.sameGame(a, other))
    check("пустой путь ни с чем не совпадает", !CoreFallbackNotes.sameGame("", a))

    // ── удаление ───────────────────────────────────────────────────────
    text = CoreFallbackNotes.add(text, other, "другая причина")
    eq("две игры — две записки", CoreFallbackNotes.entries(text).size, 2)
    text = CoreFallbackNotes.remove(text, a)
    check("после явного выбора записка снята", !CoreFallbackNotes.has(text, a), text)
    check("записка другой игры осталась", CoreFallbackNotes.has(text, other), text)

    // ── мусор в файле не ломает разбор ─────────────────────────────────
    val junk = "не строка без таба\n\n" + CoreFallbackNotes.add("", b, "ок")
    eq("мусорные строки отброшены", CoreFallbackNotes.entries(junk).size, 1)
    check("годная строка прочитана", CoreFallbackNotes.has(junk, b), junk)

    // ── ограничение размера ────────────────────────────────────────────
    var many = ""
    for (i in 1..12) many = CoreFallbackNotes.add(many, "/games/g$i.nsp", "причина $i")
    eq("файл не растёт бесконечно", CoreFallbackNotes.entries(many).size, CoreFallbackNotes.KEEP)
    check("последняя записка на месте", CoreFallbackNotes.has(many, "/games/g12.nsp"))
    check("самая старая вытеснена", !CoreFallbackNotes.has(many, "/games/g1.nsp"))

    println()
    println("passed=$passed failed=${failures.size}")
    failures.forEach { println("FAIL  $it") }
    if (failures.isNotEmpty()) kotlin.system.exitProcess(1)
}

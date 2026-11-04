// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * Проверки списка папок с играми. Запускаются на хосте, без Android.
 *
 * Почему именно этот список заслужил отдельные проверки: после сбоя с
 * телефона пользователь остался с одной папкой из четырёх, и игры из
 * остальных трёх исчезли из библиотеки. Такое не должно зависеть от того,
 * удачно ли прошёл обход диска, — значит правила работы со списком обязаны
 * быть проверяемыми отдельно от Android.
 *
 * Что здесь важно поймать:
 *   * восстановление недостающих папок не теряет порядок и не добавляет дублей;
 *   * вложенные папки больше не «схлопываются» насмерть;
 *   * зеркало переживает испорченную строку, а не выбрасывает весь список.
 */

import org.yuzu.yuzu_emu.utils.GameFolderList
import org.yuzu.yuzu_emu.utils.GameFolderList.Entry

private var passed = 0
private val failures = mutableListOf<String>()

private fun check(what: String, ok: Boolean, detail: String = "") {
    if (ok) {
        passed++
    } else {
        failures.add("$what${if (detail.isEmpty()) "" else " → $detail"}")
    }
}

private fun <T> eq(what: String, got: T, want: T) =
    check(what, got == want, "получено [$got], ожидалось [$want]")

private fun uris(list: List<Entry>) = list.map { it.uri }

fun main() {
    val a = "content://com.android.externalstorage.documents/tree/primary%3AGames"
    val b = "content://com.android.externalstorage.documents/tree/primary%3ASwitch"
    val c = "content://com.android.externalstorage.documents/tree/primary%3Aroms"

    // --- нормализация ---------------------------------------------------
    eq("хвостовой слэш не создаёт вторую папку", GameFolderList.normalise("$a/"), a)
    eq("пробелы по краям снимаются", GameFolderList.normalise("  $a  "), a)
    check("папка без схемы не считается папкой", !GameFolderList.isUsable("Games"))
    check("пустая строка не считается папкой", !GameFolderList.isUsable("   "))

    // --- добавление -----------------------------------------------------
    var list = listOf<Entry>()
    list = GameFolderList.add(list, a, deepScan = true)
    list = GameFolderList.add(list, b, deepScan = false)
    list = GameFolderList.add(list, c, deepScan = true)
    eq("порядок добавления сохраняется", uris(list), listOf(a, b, c))
    eq("размер после трёх добавлений", list.size, 3)

    list = GameFolderList.add(list, "$b/", deepScan = true)
    eq("повторное добавление не создаёт дубль", list.size, 3)
    eq("повторное добавление обновляет глубину", list.first { it.uri == b }.deepScan, true)
    eq("повторное добавление не двигает папку", uris(list).indexOf(b), 1)

    eq(
        "негодный URI не добавляется",
        GameFolderList.add(list, "мусор", true).size,
        3
    )

    // --- вложенные папки ------------------------------------------------
    // Ровно тот случай, который раньше «схлопывался» и уносил родителя:
    // в /Games лежат игры в корне, в /Games/Switch — подпапка.
    val parent = "content://com.android.externalstorage.documents/tree/primary%3AGames"
    val child = "$parent/document/primary%3AGames%2FSwitch"
    val nested = GameFolderList.add(
        GameFolderList.add(listOf<Entry>(), parent, true),
        child,
        true
    )
    eq("родитель и вложенная папка остаются оба", nested.size, 2)
    check(
        "родитель не удаляется из-за вложенной папки",
        uris(nested).contains(parent)
    )
    check(
        "вложенная папка считается покрытой при глубоком обходе родителя",
        GameFolderList.coveredByParent(nested[1], nested)
    )
    val shallowParent = listOf(Entry(parent, deepScan = false), Entry(child, deepScan = true))
    check(
        "мелкий обход родителя не отменяет обход вложенной папки",
        !GameFolderList.coveredByParent(shallowParent[1], shallowParent)
    )

    // --- удаление -------------------------------------------------------
    eq("удаление убирает одну папку", GameFolderList.remove(list, b).size, 2)
    eq("удаление не трогает вложенные", GameFolderList.remove(nested, parent).size, 1)
    check(
        "удаление по другой папке ничего не меняет",
        GameFolderList.remove(list, "content://…/tree/primary%3AИнкубатор").size == 3
    )

    // --- восстановление из зеркала --------------------------------------
    // Сбой: в конфиге осталась одна папка из трёх, зеркало помнит все.
    val mirror = list
    val broken = listOf(list.first())
    val lost = GameFolderList.missingFrom(broken, mirror)
    eq("найдены две пропавшие папки", lost.size, 2)
    eq("первая пропавшая — та, что была второй", lost[0].uri, b)
    eq("вторая пропавшая — третья по порядку", lost[1].uri, c)
    eq(
        "ничего не находится, когда конфиг полный",
        GameFolderList.missingFrom(mirror, mirror).size,
        0
    )
    eq(
        "пустое зеркало не добавляет ничего",
        GameFolderList.missingFrom(broken, emptyList()).size,
        0
    )
    eq(
        "объединение не раздувает список",
        GameFolderList.union(mirror, mirror).size,
        3
    )
    eq(
        "объединение дополняет конфиг",
        GameFolderList.union(broken, mirror).size,
        3
    )

    // --- зеркало на диске ------------------------------------------------
    val text = GameFolderList.toText(mirror)
    eq("зеркало читается обратно", GameFolderList.fromText(text), mirror)
    eq(
        "одна битая строка не убивает остальные",
        GameFolderList.fromText("мусор\n$text").size,
        3
    )
    eq(
        "пустое зеркало читается как пустой список",
        GameFolderList.fromText("").size,
        0
    )
    eq(
        "зеркало без флага сохраняет папку",
        GameFolderList.fromText(a).size,
        1
    )
    eq(
        "дубль в зеркале схлопывается в одну запись",
        GameFolderList.fromText("$a\n$a").size,
        1
    )
    eq(
        "глубокий обход переживает запись и чтение",
        GameFolderList.fromText(GameFolderList.toText(listOf(Entry(a, true))))[0].deepScan,
        true
    )

    // --- имя для показа --------------------------------------------------
    eq(
        "имя папки раскодировано",
        GameFolderList.displayName("content://…/tree/primary%3ASD%20Card"),
        "SD Card"
    )
    eq(
        "имя папки без префикса тома",
        GameFolderList.displayName("content://…/tree/primary%3AGames"),
        "Games"
    )
    eq(
        "имя вложенной папки — последний сегмент",
        GameFolderList.displayName("content://…/tree/primary%3AGames%2FSwitch"),
        "Switch"
    )
    eq(
        "имя без кодирования остаётся собой",
        GameFolderList.displayName("content://…/tree/Plain"),
        "Plain"
    )

    // --- дубликаты одного файла из разных папок --------------------------
    //
    // Пользователь добавил `Games` и `Games/Switch`. Один и тот же файл
    // приходит с двумя URI, отличающимися частью `/tree/…`, и раньше это
    // давало две плитки одной игры.
    val viaParent =
        "content://com.android.externalstorage.documents/tree/primary%3AGames" +
            "/document/primary%3AGames%2FSwitch%2Fgame.nsp"
    val viaChild =
        "content://com.android.externalstorage.documents/tree/primary%3AGames%2FSwitch" +
            "/document/primary%3AGames%2FSwitch%2Fgame.nsp"
    eq(
        "один файл через разные папки даёт один ключ",
        GameFolderList.fileKey(viaParent),
        GameFolderList.fileKey(viaChild)
    )
    check(
        "ключ файла — это путь внутри хранилища, без части /tree/",
        GameFolderList.fileKey(viaParent) == "primary:Games/Switch/game.nsp",
        GameFolderList.fileKey(viaParent)
    )
    check(
        "разные файлы дают разные ключи",
        GameFolderList.fileKey(viaParent) !=
            GameFolderList.fileKey(viaParent.replace("game.nsp", "other.nsp"))
    )
    check(
        "регистр не склеивает две настоящие игры",
        GameFolderList.fileKey(viaParent) !=
            GameFolderList.fileKey(viaParent.replace("game.nsp", "Game.nsp"))
    )
    eq(
        "повторы убираются, порядок сохраняется",
        GameFolderList.uniqueFiles(listOf(viaParent, viaChild, viaParent)),
        listOf(viaParent)
    )
    eq(
        "разные файлы не схлопываются",
        GameFolderList.uniqueFiles(listOf(viaParent, viaChild.replace("game.nsp", "b.nsp"))).size,
        2
    )

    println()
    println("passed=$passed failed=${failures.size}")
    failures.forEach { println("FAIL  $it") }
    if (failures.isNotEmpty()) kotlin.system.exitProcess(1)
}

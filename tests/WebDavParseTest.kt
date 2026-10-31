// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * Проверки разбора ответов WebDAV и сборки путей.
 *
 * Почему это тест, а не «проверим на сервере»: сервер отвечает XML-ом, в
 * котором пространства имён пишут по-разному (Nextcloud — `d:`, Яндекс —
 * `D:`, часть серверов — без префикса). Ошибка тут не падает, а тихо
 * возвращает пустой список, и следующий шаг — «залить всё заново поверх».
 *
 * Ответы в тестах синтетические — это разрешено и правильно: проверяется
 * разбор, а не чужой сервер.
 */

import org.yuzu.yuzu_emu.utils.WebDavParse

private var passed = 0
private val failures = mutableListOf<String>()

private fun check(what: String, ok: Boolean, detail: String = "") {
    if (ok) passed++ else failures.add("$what${if (detail.isEmpty()) "" else " → $detail"}")
}

private fun <T> eq(what: String, got: T, want: T) =
    check(what, got == want, "получено [$got], ожидалось [$want]")

private fun sample(prefix: String) = """<?xml version="1.0" encoding="utf-8"?>
<$prefix:multistatus xmlns:$prefix="DAV:">
  <$prefix:response>
    <$prefix:href>/remote.php/dav/files/me/symbiosis/saves/0100ABCD/</$prefix:href>
    <$prefix:propstat><$prefix:prop>
      <$prefix:resourcetype><$prefix:collection/></$prefix:resourcetype>
    </$prefix:prop></$prefix:propstat>
  </$prefix:response>
  <$prefix:response>
    <$prefix:href>/remote.php/dav/files/me/symbiosis/saves/0100ABCD/0000000000000001/</$prefix:href>
    <$prefix:propstat><$prefix:prop>
      <$prefix:resourcetype><$prefix:collection/></$prefix:resourcetype>
      <$prefix:getlastmodified>Wed, 21 Oct 2026 07:28:00 GMT</$prefix:getlastmodified>
    </$prefix:prop></$prefix:propstat>
  </$prefix:response>
  <$prefix:response>
    <$prefix:href>/remote.php/dav/files/me/symbiosis/saves/0100ABCD/0000000000000001/Save%20Data.bin</$prefix:href>
    <$prefix:propstat><$prefix:prop>
      <$prefix:getcontentlength>4096</$prefix:getcontentlength>
      <$prefix:getlastmodified>Fri, 30 Oct 2026 18:10:00 GMT</$prefix:getlastmodified>
    </$prefix:prop></$prefix:propstat>
  </$prefix:response>
</$prefix:multistatus>"""

fun main() {
    // ── разбор листинга ─────────────────────────────────────────────────
    for (prefix in listOf("d", "D")) {
        val entries = WebDavParse.parseMultiStatus(sample(prefix))
        eq("[$prefix:] разобраны все три ресурса", entries.size, 3)
        if (entries.size == 3) {
            check("[$prefix:] папка распознана", entries[0].isCollection)
            eq("[$prefix:] имя папки", entries[0].name, "0100ABCD")
            eq("[$prefix:] имя файла раскодировано", entries[2].name, "Save Data.bin")
            eq("[$prefix:] размер файла", entries[2].length, 4096L)
            check("[$prefix:] дата файла разобрана", entries[2].modified > 0)
        }
    }

    // Без префикса — тоже валидный ответ.
    val noPrefix = """<?xml version="1.0"?>
<multistatus><response><href>/dav/a.bin</href>
<propstat><prop><getcontentlength>12</getcontentlength></prop></propstat>
</response></multistatus>"""
    val plain = WebDavParse.parseMultiStatus(noPrefix)
    eq("ответ без префикса разбирается", plain.size, 1)
    eq("и размер из него тоже", plain.firstOrNull()?.length, 12L)

    // Мусор не должен выдаваться за пустую папку молча — но и падать не должен.
    eq("пустой ответ — пустой список", WebDavParse.parseMultiStatus("").size, 0)
    eq("html вместо xml — пустой список", WebDavParse.parseMultiStatus("<html>403</html>").size, 0)

    // ── экранирование ───────────────────────────────────────────────────
    val escaped = """<multistatus><response><href>/dav/a%26b.bin</href>
        <propstat><prop><getcontentlength>1</getcontentlength></prop></propstat></response></multistatus>"""
    eq(
        "процентное кодирование раскодировано",
        WebDavParse.parseMultiStatus(escaped).firstOrNull()?.name,
        "a&b.bin"
    )

    // ── даты ────────────────────────────────────────────────────────────
    check(
        "дата RFC 1123 разбирается",
        (WebDavParse.parseHttpDate("Wed, 21 Oct 2026 07:28:00 GMT") ?: 0) > 0
    )
    eq("пустая дата — null", WebDavParse.parseHttpDate(""), null)
    eq("ерунда вместо даты — null", WebDavParse.parseHttpDate("вчера"), null)

    // ── адрес ───────────────────────────────────────────────────────────
    eq(
        "схема добавляется, если её нет",
        WebDavParse.normaliseBase("webdav.yandex.ru"),
        "https://webdav.yandex.ru"
    )
    eq(
        "хвостовой слэш убирается",
        WebDavParse.normaliseBase("https://webdav.yandex.ru/"),
        "https://webdav.yandex.ru"
    )
    eq(
        "двойной слэш в пути схлопывается",
        WebDavParse.normaliseBase("https://x.y//dav//files/"),
        "https://x.y/dav/files"
    )
    eq("пробелы по краям не мешают", WebDavParse.normaliseBase("  https://x.y  "), "https://x.y")
    eq("пустой адрес остаётся пустым", WebDavParse.normaliseBase("   "), "")

    // ── пути ────────────────────────────────────────────────────────────
    eq(
        "путь сейва собирается по шагам",
        WebDavParse.savePath("https://x.y/dav", "0100abcd", "0000000000000001", "a.bin"),
        "https://x.y/dav/saves/0100ABCD/0000000000000001/a.bin"
    )
    eq(
        "пробел в имени кодируется",
        WebDavParse.savePath("https://x.y/dav", "0100ABCD", "p", "Save Data.bin"),
        "https://x.y/dav/saves/0100ABCD/p/Save%20Data.bin"
    )
    eq(
        "кириллица кодируется, а не ломает запрос",
        WebDavParse.encodeSegment("Сейвы 2026"),
        "%D0%A1%D0%B5%D0%B9%D0%B2%D1%8B%202026"
    )
    eq(
        "разделители путей не кодируются",
        WebDavParse.join("https://x.y/dav", "saves", "a", "b.bin"),
        "https://x.y/dav/saves/a/b.bin"
    )
    eq(
        "пустые куски пропускаются",
        WebDavParse.join("https://x.y/dav", "", "saves"),
        "https://x.y/dav/saves"
    )
    eq(
        "образ игры кладётся в games/TitleID",
        WebDavParse.gamePath("https://x.y/dav", "0100abcd", "Game.nsp"),
        "https://x.y/dav/games/0100ABCD/Game.nsp"
    )
    eq(
        "без TitleID — в папку unknown",
        WebDavParse.gamePath("https://x.y/dav", "", "Game.nsp"),
        "https://x.y/dav/games/unknown/Game.nsp"
    )

    // ── папки для создания ──────────────────────────────────────────────
    val ancestors = WebDavParse.ancestorsOf("https://x.y/dav/saves/0100ABCD/p/a.bin")
    // Корень сервера не создаётся: MKCOL на «https://x.y/» — это запрос
    // создать сам хост. Начинаем от первого сегмента пути; существующие
    // папки сервер отвергнет кодом 405, и это не считается ошибкой.
    eq("все недостающие папки перечислены", ancestors.size, 4)
    eq("первая — первая папка пути", ancestors.first(), "https://x.y/dav")
    eq("последняя перед файлом — папка профиля", ancestors.last(), "https://x.y/dav/saves/0100ABCD/p")

    // ── абсолютные ссылки ───────────────────────────────────────────────
    eq(
        "относительная ссылка становится абсолютной",
        WebDavParse.absoluteHref("https://x.y/dav/", "/dav/saves/"),
        "https://x.y/dav/saves"
    )
    eq(
        "абсолютная остаётся собой",
        WebDavParse.absoluteHref("https://x.y/dav", "https://x.y/dav/a/"),
        "https://x.y/dav/a"
    )

    println()
    println("passed=$passed failed=${failures.size}")
    failures.forEach { println("FAIL  $it") }
    if (failures.isNotEmpty()) kotlin.system.exitProcess(1)
}

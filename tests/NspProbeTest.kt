// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * Разбор контейнера без ключей: что внутри файла и почему ядро могло отказать.
 *
 * ПОЧЕМУ ЭТОТ ФАЙЛ СУЩЕСТВУЕТ
 *   На телефоне библиотека сказала «ядро не прочитало заголовок · проверьте
 *   ключи», а экран ключей в это же время показал prod.keys на 204 ключа,
 *   title.keys, прошивку 22.0.0 и ответ ядра «keys present: true». Одно из
 *   двух было неправдой. Проверяем разбор на синтетических контейнерах:
 *   настоящий NSZ, XCI с расширением .nsp, обрезанный файл, NSP с билетом.
 */

import org.yuzu.yuzu_emu.utils.NspProbe

private var passed = 0
private val failures = mutableListOf<String>()

private fun check(what: String, ok: Boolean, detail: String = "") {
    if (ok) passed++ else failures.add("$what${if (detail.isEmpty()) "" else " → $detail"}")
}

private fun eq(what: String, got: Any?, want: Any?) =
    check(what, got == want, "получено [$got], ожидалось [$want]")

// ── сборка синтетического PFS0 в памяти ────────────────────────────────

private class File(var name: String, var data: ByteArray)

private fun le32(v: Int): ByteArray =
    byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte())

private fun le64(v: Long): ByteArray {
    val out = ByteArray(8)
    var x = v
    for (i in 0 until 8) {
        out[i] = (x and 0xFF).toByte()
        x = x ushr 8
    }
    return out
}

/** Собрать PFS0 из файлов. */
private fun buildPfs0(files: List<File>): ByteArray {
    val names = files.joinToString("") { it.name + "\u0000" }.toByteArray(Charsets.US_ASCII)
    var dataSize = 0
    files.forEach { dataSize += it.data.size }
    val headerSize = 0x10 + files.size * 0x18 + names.size

    val out = ByteArray(headerSize + dataSize)
    System.arraycopy("PFS0".toByteArray(), 0, out, 0, 4)
    System.arraycopy(le32(files.size), 0, out, 4, 4)
    System.arraycopy(le32(names.size), 0, out, 8, 4)

    var nameOffset = 0
    var dataOffset = 0
    files.forEachIndexed { i, f ->
        val base = 0x10 + i * 0x18
        // Смещение относительное — от начала области данных, как в живом
        // PFS0. Абсолютное здесь дало бы файл, которого не бывает: именно на
        // этом тест и не поймал ошибку §49, где «не NCA» ставилось всем.
        System.arraycopy(le64(dataOffset.toLong()), 0, out, base, 8)
        System.arraycopy(le64(f.data.size.toLong()), 0, out, base + 8, 8)
        System.arraycopy(le32(nameOffset), 0, out, base + 0x10, 4)
        System.arraycopy(f.data, 0, out, headerSize + dataOffset, f.data.size)
        nameOffset += f.name.length + 1
        dataOffset += f.data.size
    }
    System.arraycopy(names, 0, out, 0x10 + files.size * 0x18, names.size)
    return out
}

/** Источник байтов поверх массива: как файл, только в памяти. */
private fun bytesOf(data: ByteArray) = NspProbe.Bytes { offset, size ->
    val start = offset.toInt()
    if (start < 0 || start >= data.size) ByteArray(0)
    else data.copyOfRange(start, minOf(data.size, start + size))
}

/** Билет с идентификатором игры на своём месте (0x2A0). */
private fun ticket(titleIdHex: String): ByteArray {
    val t = ByteArray(0x2B0)
    // Идентификатор в билете — 16 байт: восемь пар по два hex-символа.
    val id = ByteArray(16)
    for (i in 0 until 8) {
        id[i] = titleIdHex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
    System.arraycopy(id, 0, t, 0x2A0, 16)
    return t
}

/** Билет без права: Rights ID забит FF — так бывает у дампов из картриджа. */
private fun ticketWithoutRights(): ByteArray {
    val t = ByteArray(0x2B0)
    for (i in 0 until 16) t[0x2A0 + i] = 0xFF.toByte()
    return t
}

private fun xciHeader(): ByteArray {
    val head = ByteArray(0x200)
    System.arraycopy("HEAD".toByteArray(), 0, head, 0x100, 4)
    return head
}

fun main() {
    // ── нормальный NSP с билетом ────────────────────────────────────────
    val tnmt = "0100fe701475a000"
    val good = buildPfs0(
        listOf(
            File("$tnmt.nca", ByteArray(64)),
            File(tnmt + "00000000000000000000000000000000.tik", ticket(tnmt)),
            File("abc.cert", ByteArray(16)),
        )
    )
    val rGood = NspProbe.probe(bytesOf(good), "game.nsp")
    // Порядок записей как в живом дампе: сертификат, билет, потом части.
    // Проверка «первая запись — NCA» на таком файле врала.
    val liveOrder = buildPfs0(
        listOf(
            File("cert.cert", ByteArray(0x300)),
            File("t.tik", ticket(tnmt)),
            File("abc.nca", ByteArray(0x400).also {
                System.arraycopy("NCA3".toByteArray(), 0, it, 0x200, 4)
            }),
            File("abc.cnmt.nca", ByteArray(0x300)),
        )
    )
    val rLive = NspProbe.probe(bytesOf(liveOrder), "live.nsp")
    check("живой порядок записей не считается ошибкой", rLive.problem.isEmpty(), rLive.problem)
    check("и вердикт не отправляет за ключами наугад",
        !rLive.problem.contains("не NCA"), rLive.problem)
    eq("обычный NSP распознан", rGood.container, NspProbe.Container.NSP)
    eq("идентификатор взят из билета", rGood.titleId, tnmt)
    eq("это игра, а не обновление", rGood.part, NspProbe.Part.GAME)
    check("билет найден", rGood.hasTicket)
    check("с контейнером всё в порядке", rGood.problem.isEmpty(), rGood.problem)
    // Ключ игры — не то же самое, что 204 ключа консоли. 06.11 библиотека
    // написала «дело не в ключах, это наш дефект», а во вкладке «Папки» про
    // тот же файл — «нужен title key». Оба раза человеку было нечем
    // проверить.
    check("без записи в title.keys вердикт называет именно её",
        NspProbe.verdict(rGood, true, false).contains("title.keys"),
        NspProbe.verdict(rGood, true, false))
    check("и называет идентификатор игры",
        NspProbe.verdict(rGood, true, false).contains(tnmt),
        NspProbe.verdict(rGood, true, false))
    check("с записью — говорит, что ключ есть",
        NspProbe.verdict(rGood, true, true).contains("есть"),
        NspProbe.verdict(rGood, true, true))

    // Обновление: хвост идентификатора 800.
    // Обновление: билет на 800, плюс NCA базовой игры рядом — билет должен
    // победить, иначе обновление назовётся игрой.
    val upd = buildPfs0(listOf(File("$tnmt.nca", ByteArray(8)),
        File("t.tik", ticket("0100fe701475a800"))))
    eq("обновление распознано", NspProbe.probe(bytesOf(upd), "u.nsp").part, NspProbe.Part.UPDATE)

    // ── NSZ, переименованный в NSP ──────────────────────────────────────
    val nsz = buildPfs0(listOf(File("a.ncz", ByteArray(32)), File("b.ncz", ByteArray(32))))
    val rNsz = NspProbe.probe(bytesOf(nsz), "Vault_of_Power.nsp")
    eq("NSZ узнан по содержимому", rNsz.container, NspProbe.Container.NSZ)
    check("сжатость отмечена", rNsz.compressed)
    check("причина названа: сначала распаковать",
        rNsz.problem.contains("NSZ"), rNsz.problem)
    check("вердикт предлагает распаковку",
        NspProbe.verdict(rNsz, true).contains("Распаковать"), NspProbe.verdict(rNsz, true))

    // ── XCI с расширением .nsp ──────────────────────────────────────────
    val rXci = NspProbe.probe(bytesOf(xciHeader()), "Crafty_Survivors.nsp")
    eq("образ картриджа узнан по «HEAD»", rXci.container, NspProbe.Container.XCI)
    check("расхождение расширения названо",
        rXci.problem.contains("XCI"), rXci.problem)
    check("вердикт объясняет выбор загрузчика по расширению",
        NspProbe.verdict(rXci, true).contains("расширению"), NspProbe.verdict(rXci, true))
    eq("xci с правильным расширением без придирок",
        NspProbe.probe(bytesOf(xciHeader()), "game.xci").problem, "")

    // ── обрезанный файл ─────────────────────────────────────────────────
    val cut = good.copyOfRange(0, 0x20)
    val rCut = NspProbe.probe(bytesOf(cut), "cut.nsp")
    check("обрезанный файл назван обрезанным",
        rCut.problem.contains("обрезан"), rCut.problem)

    // ── не образ вовсе ──────────────────────────────────────────────────
    val zip = byteArrayOf(0x50, 0x4B, 0x03, 0x04) + ByteArray(64)
    val rZip = NspProbe.probe(bytesOf(zip), "game.nsp")
    eq("архив не принят за образ", rZip.container, NspProbe.Container.UNKNOWN)
    check("причина названа по первым байтам", rZip.problem.contains("PK"), rZip.problem)

    eq("пустой файл", NspProbe.probe(bytesOf(ByteArray(0)), "x.nsp").container,
        NspProbe.Container.UNKNOWN)

    // ── битый заголовок не должен тянуть память ─────────────────────────
    // В заголовке PFS0 размер таблицы имён — 32-битное число. Файл с
    // объявленной таблицей в гигабайты раньше заставлял выделять её целиком
    // прямо во время обхода библиотеки, то есть ронял приложение при запуске.
    val huge = buildPfs0(listOf(File("a.nca", ByteArray(64)))).copyOf()
    System.arraycopy(le32(0x3FFFFFFF), 0, huge, 8, 4)
    val rHuge = NspProbe.probe(bytesOf(huge), "huge.nsp")
    check("огромная таблица имён названа порчей заголовка",
        rHuge.problem.contains("таблица имён"), rHuge.problem)
    eq("и помечена как битый заголовок", NspProbe.label(rHuge, true), "заголовок битый")

    // ── билет без права ─────────────────────────────────────────────────
    // Vault of Power на телефоне: билет внутри есть, а Rights ID — FF.
    // Показывать ffffffffffffffff как идентификатор игры нельзя, поэтому он
    // берётся из имени файла, где он и лежит.
    val noRights = buildPfs0(
        listOf(
            File("hashed.nca", ByteArray(64)),
            File("t.tik", ticketWithoutRights()),
        )
    )
    val rNoRights = NspProbe.probe(
        bytesOf(noRights),
        "Vault of Power [01004F302847E000][v0] (0.31 GB).nsp"
    )
    eq("мусорный Rights ID не выдаётся за игру", rNoRights.titleId, "01004f302847e000")
    check("и это базовая игра, а не обновление", rNoRights.part == NspProbe.Part.GAME)

    // ── части остались сжатыми ──────────────────────────────────────────
    // Если конвертер записал NCZ под именем .nca, снаружи это выглядит как
    // обычный NSP, а ядро не прочитает ни одной части.
    val stillPacked = buildPfs0(listOf(File("a.nca", ByteArray(0x210).also {
        System.arraycopy("NCZS".toByteArray(), 0, it, 0x200, 4)
    })))
    val rStill = NspProbe.probe(bytesOf(stillPacked), "still-packed.nsp")
    check("недоделанная распаковка названа", rStill.problem.contains("NCZS"), rStill.problem)
    eq("пометка — сжатый", NspProbe.label(rStill, true), "сжатый")

    // Непонятная метка части — НЕ приговор файлу.
    //
    // Дважды за день «собран неверно» стояло на живых файлах, и оба раза это
    // была ошибка чтения смещения. Поэтому теперь приговор выносится только
    // тогда, когда точно видно сжатие (NCZS); всё непонятное молчит.
    val garbageEntry = buildPfs0(
        listOf(
            File("a.nca", ByteArray(0x210).also {
                System.arraycopy("XXXX".toByteArray(), 0, it, 0x200, 4)
            }),
            File("t.tik", ticket(tnmt)),
        )
    )
    val rGarbage = NspProbe.probe(bytesOf(garbageEntry), "garbage.nsp")
    check("непонятная метка не обвиняет файл", rGarbage.problem.isEmpty(), rGarbage.problem)

    // Конвертеры иногда пишут абсолютные смещения — такие файлы ядро
    // открывает, и обвинять их нельзя.
    val absolute = buildPfs0(
        listOf(
            File("a.nca", ByteArray(0x210).also {
                System.arraycopy("NCA3".toByteArray(), 0, it, 0x200, 4)
            }),
            File("t.tik", ticket(tnmt)),
        )
    )
    val headerSize = 0x10 + 2 * 0x18 + "a.nca".length + 1 + "t.tik".length + 1
    System.arraycopy(le64(headerSize.toLong()), 0, absolute, 0x10, 8)
    val rAbsolute = NspProbe.probe(bytesOf(absolute), "absolute.nsp")
    check("абсолютные смещения тоже читаются", rAbsolute.problem.isEmpty(), rAbsolute.problem)

    // ── NSP без билета ──────────────────────────────────────────────────
    val noTicket = buildPfs0(listOf(File("aaa.nca", ByteArray(16))))
    val rNo = NspProbe.probe(bytesOf(noTicket), "no-ticket.nsp")
    check("отсутствие билета названо",
        rNo.problem.contains("title.keys"), rNo.problem)
    check("без билета вердикт называет title.keys",
        NspProbe.verdict(rNo, false).contains("title.keys"), NspProbe.verdict(rNo, false))

    // ── части по хвосту идентификатора ──────────────────────────────────
    eq("000 — игра", NspProbe.partOf("0100fe701475a000"), NspProbe.Part.GAME)
    eq("800 — обновление", NspProbe.partOf("0100fe701475a800"), NspProbe.Part.UPDATE)
    eq("0100…b001 — дополнение", NspProbe.partOf("0100fe701475b001"), NspProbe.Part.DLC)
    eq("мусор — неизвестно", NspProbe.partOf("короткий"), NspProbe.Part.UNKNOWN)

    // ── короткая пометка на плитке ──────────────────────────────────────
    // Пометка — то, что человек видит до нажатия. Она не имеет права
    // говорить «нет ключей», когда ключи на месте.
    eq("пометка: сжатый", NspProbe.label(rNsz, true), "сжатый")
    // «Файл в порядке» на плитке больше нет: целый контейнер — не рабочая
    // игра, и эта пометка была неправдой на живом устройстве.
    eq("пометка: ключ игры не найден", NspProbe.label(rGood, true, false), "нет ключа игры")
    eq("пометка: ключ игры есть", NspProbe.label(rGood, true, true), "ключи на месте")
    eq("пометка: нет ключей консоли", NspProbe.label(rGood, false, null), "нет ключей")
    eq("пометка: нет билета", NspProbe.label(rNo, true, false), "нет ключа игры")
    eq("пометка: не образ", NspProbe.label(rZip, true), "не образ")
    eq("пометка: обрезан", NspProbe.label(rCut, true), "обрезан")

    println()
    println("passed=$passed failed=${failures.size}")
    failures.forEach { println("FAIL  $it") }
    if (failures.isNotEmpty()) kotlin.system.exitProcess(1)
}

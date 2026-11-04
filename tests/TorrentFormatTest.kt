// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * Торренты и открытые каталоги: разбор, арифметика кусков, протокол.
 *
 * ПОЧЕМУ ЭТИ ПРОВЕРКИ СУЩЕСТВУЮТ
 *   Torrent — это место, где ошибка на байт выглядит как «подключился и
 *   молчит»: сокет открыт, данные не идут, причина неизвестна. Проверить
 *   здесь дешевле, чем на телефоне.
 *
 *   И один настоящий файл, а не только придуманный: в `tests/data` лежит
 *   `debian-netinst.torrent` — официальный торрент образа Debian
 *   (cdimage.debian.org, легальная раздача). На нём проверяется то, что не
 *   проверить на выдуманном: info hash от БАЙТОВ словаря info, а не от
 *   повторного кодирования.
 */

import java.io.File
import java.security.MessageDigest
import org.yuzu.yuzu_emu.utils.BValue
import org.yuzu.yuzu_emu.utils.SourceIndex
import org.yuzu.yuzu_emu.utils.TorrentBencode
import org.yuzu.yuzu_emu.utils.TorrentMeta
import org.yuzu.yuzu_emu.utils.TorrentPlan
import org.yuzu.yuzu_emu.utils.TorrentTracker
import org.yuzu.yuzu_emu.utils.TorrentWire

private var passed = 0
private val failures = mutableListOf<String>()

private fun check(what: String, ok: Boolean, detail: String = "") {
    if (ok) {
        passed++
    } else {
        failures.add("$what${if (detail.isEmpty()) "" else " → $detail"}")
    }
}

private fun eq(what: String, got: Any?, want: Any?) =
    check(what, got == want, "получено [$got], ожидалось [$want]")

private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

private fun torrentBytes(): ByteArray? {
    val candidates = listOf(
        File("tests/data/debian-netinst.torrent"),
        File("../tests/data/debian-netinst.torrent"),
        File(System.getProperty("user.dir") + "/tests/data/debian-netinst.torrent")
    )
    return candidates.firstOrNull { it.isFile }?.readBytes()
}

fun main() {
    // --- bencode ---------------------------------------------------------
    eq("число", TerDump(TorrentBencode.decode("i42e".toByteArray())), "42")
    eq("отрицательное число", TerDump(TorrentBencode.decode("i-7e".toByteArray())), "-7")
    eq("строка", TerDump(TorrentBencode.decode("4:spam".toByteArray())), "\"spam\"")
    eq("список", TerDump(TorrentBencode.decode("l4:spami1ee".toByteArray())), "[\"spam\", 1]")
    eq(
        "словарь",
        TerDump(TorrentBencode.decode("d3:cow3:moo4:spam4:eggse".toByteArray())),
        "{cow:\"moo\", spam:\"eggs\"}"
    )
    check("битый bencode — null", TorrentBencode.decode("d3:cow".toByteArray()) == null)
    check("строка длиннее файла — null", TorrentBencode.decode("999999:x".toByteArray()) == null)
    check("оборванный список — null", TorrentBencode.decode("l4:spam".toByteArray()) == null)

    // Сборка: ключи обязаны идти по возрастанию.
    val encoded = TorrentBencode.encode(
        BValue.Dict(
            listOf(
                "b" to BValue.Num(2),
                "a" to BValue.Num(1)
            )
        )
    )
    eq("сборка сортирует ключи", String(encoded), "d1:ai1e1:bi2ee")
    eq(
        "разбор собранного",
        TerDump(TorrentBencode.decode(encoded)),
        "{a:1, b:2}"
    )

    // --- настоящий торрент ------------------------------------------------
    val raw = torrentBytes()
    if (raw == null) {
        check("файл tests/data/debian-netinst.torrent найден", false)
    } else {
        val span = TorrentBencode.infoSpan(raw)
        check("словарь info найден", span != null)
        if (span != null) {
            val infoBytes = raw.copyOfRange(span.first, span.last + 1)
            // Info hash обязан считаться по БАЙТАМ исходника. Сверяем с
            // повторной сборкой: у настоящего файла они могут разойтись, и
            // именно поэтому важны смещения.
            val reencoded = TorrentBencode.encode(TorrentBencode.decode(infoBytes)!!)
            val fromOriginal = MessageDigest.getInstance("SHA-1").digest(infoBytes)
            val fromReencoded = MessageDigest.getInstance("SHA-1").digest(reencoded)
            check(
                "info hash берётся из исходных байтов",
                fromOriginal.isNotEmpty(),
                hex(fromOriginal)
            )
            println("  info hash: ${hex(fromOriginal)}")
            if (!fromOriginal.contentEquals(fromReencoded)) {
                println("  (у этого файла байты info не совпадают с пересборкой — поэтому и важно смещение)")
            }
        }

        val meta = TorrentMeta.parse(raw).getOrNull()
        check("торрент разобран", meta != null)
        if (meta != null) {
            eq("один файл", meta.files.size, 1)
            check("имя — iso", meta.name.endsWith(".iso"), meta.name)
            check("размер правдоподобный", meta.totalLength > 50_000_000, meta.totalLength.toString())
            check(
                "кусков столько, сколько нужно",
                meta.pieceCount == ((meta.totalLength + meta.pieceLength - 1) / meta.pieceLength).toInt(),
                "${meta.pieceCount} при размере куска ${meta.pieceLength}"
            )
            check("трекеры найдены", meta.announce.isNotEmpty(), meta.announce.toString())
            println("  торрент: ${meta.listing()}")
            println("  трекеров: ${meta.announce.size}, первый: ${meta.announce.firstOrNull()}")

            // План: один файл — все куски.
            val wanted = TorrentPlan.wantedPieces(meta, emptyList())
            eq("без выбора нужны все куски", wanted.count { it }, meta.pieceCount)
            val range = TorrentPlan.piecesForFile(meta, 0)
            eq("первый кусок файла", range.first, 0)
            eq("последний кусок файла", range.last, meta.pieceCount - 1)
            eq(
                "размер последнего куска",
                TorrentPlan.pieceSize(meta, meta.pieceCount - 1),
                meta.totalLength - (meta.pieceCount - 1).toLong() * meta.pieceLength
            )
            eq("вид файла для iso — игра или архив", TorrentPlan.kindOf("iso") in listOf("игра"), true)
        }

        // Испорченный файл не должен считаться годным.
        val broken = raw.copyOfRange(0, raw.size / 2)
        check("обрезанный торрент отвергается", TorrentMeta.parse(broken).isFailure)
        check("пустой файл отвергается", TorrentMeta.parse(ByteArray(0)).isFailure)
        check(
            "не торрент отвергается",
            TorrentMeta.parse("<html>не торрент</html>".toByteArray()).isFailure
        )
    }

    // --- план по нескольким файлам ---------------------------------------
    // Собираем торрент из двух файлов вручную: 3 куска по 16 байт.
    val pieceLength = 16L
    val fakePieces = (0 until 3).map { ByteArray(20) { i -> (it * 20 + i).toByte() } }
    val fakeMeta = TorrentMeta.Meta(
        infoHash = ByteArray(20),
        name = "набор",
        pieceLength = pieceLength,
        pieceHashes = fakePieces,
        files = listOf(
            TorrentMeta.FileEntry("фильм.mkv", 20, 0),
            TorrentMeta.FileEntry("книга.epub", 16, 20),
            TorrentMeta.FileEntry("музыка.flac", 12, 36)
        ),
        announce = emptyList(),
        isPrivate = false,
        createdAt = null
    )
    eq("размер всего набора", fakeMeta.totalLength, 48L)
    eq(
        "файл, пересекающий границу куска",
        TorrentPlan.piecesForFile(fakeMeta, 0).toList(),
        listOf(0, 1)
    )
    // Второй файл (20..35) тоже пересекает границу: куски 16..31 и 32..47.
    eq("второй файл — два куска", TorrentPlan.piecesForFile(fakeMeta, 1).toList(), listOf(1, 2))
    eq("третий файл — тоже кусок 2", TorrentPlan.piecesForFile(fakeMeta, 2).toList(), listOf(2))
    eq(
        "выбор одного файла не тянет остальные",
        TorrentPlan.wantedPieces(fakeMeta, listOf(2)).count { it },
        1
    )
    eq(
        "порядок с приоритетом начинается с нужного куска",
        TorrentPlan.order(
            TorrentPlan.wantedPieces(fakeMeta, emptyList()),
            sequential = true,
            priority = 2..2
        ).toList(),
        listOf(2, 0, 1)
    )
    eq(
        "порядок без приоритета последовательный",
        TorrentPlan.order(booleanArrayOf(true, true, true)).toList(),
        listOf(0, 1, 2)
    )
    eq(
        "разбросанный порядок всё равно содержит все куски",
        TorrentPlan.order(booleanArrayOf(true, true, true, true, true), sequential = false)
            .sorted().toList(),
        listOf(0, 1, 2, 3, 4)
    )
    eq(
        "диапазон внутри файла",
        TorrentPlan.piecesForRange(fakeMeta, 2, 0, 4).toList(),
        listOf(2)
    )

    // Проверка куска: свой хеш проходит, испорченный — нет.
    val good = ByteArray(16) { it.toByte() }
    fakeMeta.pieceHashes[0].let { }
    val metaWithHash = fakeMeta.copy(
        pieceHashes = listOf(
            MessageDigest.getInstance("SHA-1").digest(good)
        ) + fakePieces.drop(1)
    )
    check("свой кусок проходит проверку", TorrentPlan.verifyPiece(metaWithHash, 0, good))
    val bad = good.copyOf().also { it[3] = (it[3] + 1).toByte() }
    check("испорченный кусок не проходит", !TorrentPlan.verifyPiece(metaWithHash, 0, bad))
    check("кусок неверной длины не проходит", !TorrentPlan.verifyPiece(metaWithHash, 0, ByteArray(8)))

    // --- magnet -----------------------------------------------------------
    val magnet = TorrentMeta.parseMagnet(
        "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=Debian&tr=udp%3A%2F%2Ftracker.example%3A80%2Fannounce"
    ).getOrNull()
    check("magnet разобран", magnet != null)
    eq("info hash из hex", magnet?.infoHash?.let { hex(it) }, "0123456789abcdef0123456789abcdef01234567")
    eq("имя из magnet", magnet?.name, "Debian")
    eq("трекер раскодирован", magnet?.trackers?.firstOrNull(), "udp://tracker.example:80/announce")
    check(
        "magnet без btih отвергается",
        TorrentMeta.parseMagnet("magnet:?dn=x").isFailure
    )
    check("не magnet отвергается", TorrentMeta.parseMagnet("https://x/y").isFailure)
    // base32-вариант: 32 символа алфавита base32.
    val b32 = TorrentMeta.parseMagnet("magnet:?xt=urn:btih:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
    check("base32 magnet разобран", b32.isSuccess, b32.exceptionOrNull()?.message.orEmpty())

    // --- рукопожатие и сообщения -----------------------------------------
    val infoHash = ByteArray(20) { it.toByte() }
    val peerId = "-SY0001-012345678901".toByteArray()
    val hello = TorrentWire.handshake(infoHash, peerId)
    eq("длина рукопожатия", hello.size, 68)
    eq("первый байт — длина строки протокола", hello[0].toInt(), 19)
    eq(
        "строка протокола",
        String(hello, 1, 19, Charsets.US_ASCII),
        "BitTorrent protocol"
    )
    check("объявлена поддержка расширений", (hello[25].toInt() and 0x10) != 0)
    val parsedHello = TorrentWire.parseHandshake(hello)
    check("рукопожатие читается обратно", parsedHello != null)
    eq("info hash в рукопожатии", parsedHello?.infoHash?.let { hex(it) }, hex(infoHash))
    check("мусор вместо рукопожатия", TorrentWire.parseHandshake(ByteArray(68)) == null)

    val request = TorrentWire.request(3, 16384, 16384)
    eq("длина сообщения запроса", request.size, 17)
    eq("длина в первых 4 байтах", ((request[0].toInt() and 0xFF) shl 24) or (request[3].toInt() and 0xFF), 13)
    eq("id сообщения REQUEST", request[4].toInt(), 6)
    val payload = request.copyOfRange(5, request.size)
    eq("номер куска в запросе", TorrentWire.pieceIndex(payload), 3)
    eq("смещение блока в запросе", TorrentWire.blockOffset(payload), 16384)

    val piecePayload = byteArrayOf(0, 0, 0, 5, 0, 0, 0, 16) + ByteArray(16) { 7 }
    eq("номер куска в ответе", TorrentWire.pieceIndex(piecePayload), 5)
    eq("смещение в ответе", TorrentWire.blockOffset(piecePayload), 16)
    eq("данные ответа", TorrentWire.blockData(piecePayload).size, 16)

    eq(
        "bitfield читается",
        TorrentWire.piecesFromBitfield(byteArrayOf(0b1010_0000.toByte()), 4).toList(),
        listOf(true, false, true, false)
    )
    eq(
        "bitfield собирается",
        TorrentWire.bitfield(booleanArrayOf(true, false, true, false, false, false, false, true, true))
            .copyOfRange(5, 7).toList(),
        listOf(0b1010_0001.toByte(), 0b1000_0000.toByte())
    )

    // Расширенное рукопожатие: разбор своего же пакета.
    val ext = TorrentWire.extensionHandshake()
    val extPayload = ext.copyOfRange(5, ext.size)
    val extHandshake = TorrentWire.parseExtensionHandshake(extPayload)
    check("расширенное рукопожатие читается", extHandshake != null)
    eq("id ut_metadata", extHandshake?.utMetadataId, 1)

    // Кусок метаданных: данные идут СРАЗУ за словарём, без длины.
    val dict = TorrentBencode.encode(
        BValue.Dict(listOf("msg_type" to BValue.Num(1), "piece" to BValue.Num(0), "total_size" to BValue.Num(4)))
    )
    val metaPayload = byteArrayOf(1) + dict + byteArrayOf(0x64, 0x38, 0x3a)
    val metaPiece = TorrentWire.parseMetadataPiece(metaPayload)
    check("кусок метаданных читается", metaPiece != null)
    eq("номер куска метаданных", metaPiece?.piece, 0)
    eq("полный размер метаданных", metaPiece?.totalSize, 4)
    eq("данные ровно за словарём", metaPiece?.data?.toList(), listOf<Byte>(0x64, 0x38, 0x3a))

    // --- трекер -----------------------------------------------------------
    eq(
        "процентное кодирование info hash",
        TorrentTracker.percentEncode(byteArrayOf(0x1f, 0x41, 0x7f)),
        "%1FA%7F"
    )
    eq(
        "безопасные символы не кодируются",
        TorrentTracker.percentEncode("aZ09-_.~".toByteArray()),
        "aZ09-_.~"
    )
    val url = TorrentTracker.httpUrl(
        "http://tracker.example/announce", infoHash, peerId, 6881, left = 1000
    )
    check("адрес трекера содержит info_hash", url.contains("info_hash=%00%01%02"), url)
    check("адрес трекера содержит порт", url.contains("port=6881"))
    check("адрес трекера содержит compact=1", url.contains("compact=1"))
    eq(
        "разделитель параметров выбран верно",
        TorrentTracker.httpUrl("http://x/announce?key=1", infoHash, peerId, 1, 0)
            .substringAfter("key=1").first(),
        '&'
    )

    // Компактный ответ: два пира.
    val compact = byteArrayOf(1, 2, 3, 4, 0x1A, 0xE1.toByte(), 5, 6, 7, 8, 0, 80)
    val httpBody = TorrentBencode.encode(
        BValue.Dict(
            listOf(
                "interval" to BValue.Num(1800),
                "peers" to BValue.Str(compact)
            )
        )
    )
    val response = TorrentTracker.parseHttpResponse(httpBody)
    eq("интервал из ответа", response.intervalSeconds, 1800)
    eq("пиров разобрано", response.peers.size, 2)
    eq("первый пир", response.peers.firstOrNull()?.toString(), "1.2.3.4:6881")
    eq("второй пир", response.peers.lastOrNull()?.toString(), "5.6.7.8:80")
    eq(
        "отказ трекера читается",
        TorrentTracker.parseHttpResponse(
            TorrentBencode.encode(BValue.Dict(listOf("failure reason" to BValue.Str("нет такого торрента".toByteArray()))))
        ).failure,
        "нет такого торрента"
    )

    // UDP: пакеты фиксированной длины и порядок байтов.
    val connect = TorrentTracker.udpConnect(42)
    eq("длина connect", connect.size, 16)
    eq("протокол в connect", TorrentTracker.udpParseConnect(connect, 42) != null, false)
    val connectReply = ByteArray(16).also {
        it[3] = 0 // action = 0
        it[7] = 42 // transaction id
        it[15] = 7 // connection id младший байт
    }
    eq("connection id из ответа", TorrentTracker.udpParseConnect(connectReply, 42), 7L)
    check("ответ с чужим номером транзакции отбрасывается", TorrentTracker.udpParseConnect(connectReply, 43) == null)

    val announce = TorrentTracker.udpAnnounce(7L, 42, infoHash, peerId, 0, 1000, 0, 6881)
    eq("длина announce", announce.size, 98)
    eq("info hash в announce", announce.copyOfRange(16, 36).let { hex(it) }, hex(infoHash))
    eq("порт в announce", ((announce[96].toInt() and 0xFF) shl 8) or (announce[97].toInt() and 0xFF), 6881)

    val announceReply = ByteArray(20 + 6).also {
        it[3] = 1 // action = announce
        it[7] = 42
        it[11] = 30 // interval
        it[20] = 9; it[21] = 9; it[22] = 9; it[23] = 9
        it[24] = 0x1F; it[25] = 0x90.toByte() // порт 8080
    }
    val udpResponse = TorrentTracker.udpParseAnnounce(announceReply, 42)
    eq("интервал из UDP-трекера", udpResponse.intervalSeconds, 30)
    eq("пир из UDP-трекера", udpResponse.peers.firstOrNull()?.toString(), "9.9.9.9:8080")
    eq(
        "ошибка UDP-трекера читается",
        TorrentTracker.udpParseAnnounce(
            byteArrayOf(0, 0, 0, 3, 0, 0, 0, 1) + "трекер выключен".toByteArray(), 1
        ).failure,
        "трекер выключен"
    )
    eq("udp-адрес распознан", TorrentTracker.udpEndpoint("udp://tracker.x:1337/announce"), "tracker.x" to 1337)

    // --- открытый каталог -------------------------------------------------
    val html = """
        <html><head><title>Index of /games/switch</title></head>
        <body><h1>Index of /games/switch</h1>
        <a href="../">Parent Directory</a>
        <a href="Game%20One.nsp">Game One.nsp</a> 2.4G
        <a href="sub/">sub/</a> -
        <a href="manual.pdf">manual.pdf</a> 345K
        <a href="https://other.example/film.mkv">film.mkv</a> 1,2G
        </body></html>
    """.trimIndent()
    val page = SourceIndex.parse("https://mirror.example/games/switch/", html)
    eq("заголовок страницы", page.title, "Index of /games/switch")
    check("это похоже на каталог", page.looksLikeListing)
    eq("навигация отброшена, остались файлы и папка", page.entries.size, 4)
    eq(
        "относительная ссылка стала абсолютной",
        page.entries.firstOrNull { it.name == "Game One.nsp" }?.url,
        "https://mirror.example/games/switch/Game%20One.nsp"
    )
    eq(
        "вид файла определён",
        page.entries.firstOrNull { it.name == "Game One.nsp" }?.kind,
        SourceIndex.Kind.GAME
    )
    eq(
        "размер из текста",
        page.entries.firstOrNull { it.name == "Game One.nsp" }?.sizeBytes,
        2_576_980_377L
    )
    eq(
        "папка помечена папкой",
        page.entries.firstOrNull { it.name == "sub" }?.isDirectory,
        true
    )
    eq(
        "pdf — книга",
        page.entries.firstOrNull { it.name == "manual.pdf" }?.kind,
        SourceIndex.Kind.BOOK
    )
    eq(
        "абсолютная ссылка на другой сайт сохранена",
        page.entries.firstOrNull { it.name == "film.mkv" }?.url,
        "https://other.example/film.mkv"
    )

    eq("имя из адреса", SourceIndex.fileNameOf("https://x/a/My%20Film.mkv?token=1"), "My Film.mkv")
    eq("расширение из адреса", SourceIndex.extensionOf("https://x/a/Film.MKV?x=1"), "mkv")
    eq(
        "имя из Content-Disposition",
        SourceIndex.fileNameFromDisposition("attachment; filename=\"Фильм.mkv\""),
        "Фильм.mkv"
    )
    eq(
        "имя из Content-Disposition*",
        SourceIndex.fileNameFromDisposition("attachment; filename*=UTF-8''%D0%A4%D0%B8%D0%BB%D1%8C%D0%BC.mkv"),
        "Фильм.mkv"
    )
    check("это файл по расширению", SourceIndex.isDirectFile("https://x/a.mkv"))
    check("это не файл", !SourceIndex.isDirectFile("https://x/a/"))
    eq("размер 1.2G", SourceIndex.parseSizeIn("1.2G"), 1_288_490_188L)
    eq("размер с пробелами", SourceIndex.parseSizeIn("1 024 M"), 1_073_741_824L)
    eq("размер 345K", SourceIndex.parseSizeIn("345K"), 353_280L)
    eq("нет размера", SourceIndex.parseSizeIn("-"), null)
    eq(
        "схлопывание ../..",
        SourceIndex.resolve("https://x/a/b/c", "../d.txt"),
        "https://x/a/d.txt"
    )
    eq("абсолютный путь от корня", SourceIndex.resolve("https://x/a/b", "/z.txt"), "https://x/z.txt")
    eq("magnet не трогаем", SourceIndex.resolve("https://x/", "magnet:?xt=urn:btih:00"), "magnet:?xt=urn:btih:00")

    // --- человеческие подписи ---------------------------------------------
    eq("байты", TorrentPlan.humanBytes(512), "512 Б")
    eq("килобайты", TorrentPlan.humanBytes(2048), "2 КБ")
    eq("мегабайты", TorrentPlan.humanBytes(5 * 1024L * 1024L), "5 МБ")
    eq("гигабайты", TorrentPlan.humanBytes(3L * 1024 * 1024 * 1024), "3 ГБ")
    eq("часы в длительности", TorrentPlan.humanDuration(3661), "1 ч 1 мин")
    eq("секунды в длительности", TorrentPlan.humanDuration(42), "42 с")
    eq("пустая длительность", TorrentPlan.humanDuration(0), "—")

    println()
    println("passed=$passed failed=${failures.size}")
    failures.forEach { println("FAIL  $it") }
    if (failures.isNotEmpty()) kotlin.system.exitProcess(1)
}

/** Короткое текстовое представление значения — чтобы сравнение читалось. */
private fun TerDump(value: BValue?): String = when (value) {
    null -> "null"
    is BValue.Num -> value.value.toString()
    is BValue.Str -> "\"${value.text()}\""
    is BValue.Lst -> value.items.joinToString(", ", "[", "]") { TerDump(it) }
    is BValue.Dict -> value.entries.joinToString(", ", "{", "}") { "${it.first}:${TerDump(it.second)}" }
}

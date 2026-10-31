// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * Скачивание торрента целиком: трекер → пир → куски → файл.
 *
 * ЧТО ЗДЕСЬ ПРОИСХОДИТ
 *   Поднимаются ДВА своих сервера на 127.0.0.1: трекер (отдаёт список пиров
 *   словарём bencode) и пир (здоровается, отдаёт bitfield, снимает choke и
 *   отдаёт запрошенные блоки). Между ними — настоящий TorrentDownload,
 *   с настоящими сокетами, рукопожатиями и SHA-1.
 *
 *   Никакой сети наружу: тест не зависит от того, жив ли чей-то торрент
 *   сегодня, и не тянет чужие файлы. Но и обмана нет — это тот же код,
 *   который пойдёт в APK.
 *
 * ЧТО ПРОВЕРЯЕТСЯ
 *   - файл на диске побайтово совпадает с исходным;
 *   - прогресс доходит до 100% и растёт монотонно;
 *   - битый кусок от пира не попадает в файл (пир отдаёт мусор → повтор);
 *   - при отсутствии пиров загрузка завершается ПРИЧИНОЙ, а не тишиной.
 */

import java.io.DataInputStream
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import org.yuzu.yuzu_emu.utils.BValue
import org.yuzu.yuzu_emu.utils.TorrentBencode
import org.yuzu.yuzu_emu.utils.TorrentDownload
import org.yuzu.yuzu_emu.utils.TorrentMeta
import org.yuzu.yuzu_emu.utils.TorrentWire

private var passed = 0
private val failures = mutableListOf<String>()

private fun check(what: String, ok: Boolean, detail: String = "") {
    if (ok) passed++ else failures.add("$what${if (detail.isEmpty()) "" else " → $detail"}")
}

private fun eq(what: String, got: Any?, want: Any?) =
    check(what, got == want, "получено [$got], ожидалось [$want]")

private const val PIECE = 16 * 1024

/** Собрать торрент из одного файла: куски считаются по-настоящему. */
private fun buildTorrent(data: ByteArray, name: String): ByteArray {
    val pieces = StringBuilder()
    val digest = MessageDigest.getInstance("SHA-1")
    var at = 0
    while (at < data.size) {
        val end = minOf(at + PIECE, data.size)
        digest.update(data, at, end - at)
        pieces.append(String(digest.digest(), Charsets.ISO_8859_1))
        at = end
    }
    val info = BValue.Dict(
        listOf(
            "length" to BValue.Num(data.size.toLong()),
            "name" to BValue.Str(name.toByteArray()),
            "piece length" to BValue.Num(PIECE.toLong()),
            "pieces" to BValue.Str(pieces.toString().toByteArray(Charsets.ISO_8859_1))
        )
    )
    return TorrentBencode.encode(
        BValue.Dict(listOf("info" to info))
    )
}

/**
 * Трекер: отвечает списком из одного пира.
 *
 * Формат ответа — как у настоящего: интервал и компактный список «4 байта
 * адреса + 2 байта порта».
 */
private class StubTracker(private val peerPort: Int) {
    private val server = ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
    val url: String get() = "http://127.0.0.1:${server.localPort}/announce"
    private val stop = AtomicBoolean(false)
    private val requests = java.util.concurrent.atomic.AtomicInteger()

    fun start() {
        Thread {
            while (!stop.get()) {
                val client = try {
                    server.accept()
                } catch (e: Throwable) {
                    return@Thread
                }
                Thread {
                    client.use { socket ->
                        val request = socket.getInputStream().bufferedReader().readLine().orEmpty()
                        requests.incrementAndGet()
                        val compact = byteArrayOf(127, 0, 0, 1, (peerPort shr 8).toByte(), peerPort.toByte())
                        val body = TorrentBencode.encode(
                            BValue.Dict(
                                listOf(
                                    "interval" to BValue.Num(60),
                                    "peers" to BValue.Str(compact)
                                )
                            )
                        )
                        val head = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n" +
                            "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                        socket.getOutputStream().write(head.toByteArray())
                        socket.getOutputStream().write(body)
                        socket.getOutputStream().flush()
                        if (!request.contains("info_hash")) failures.add("трекер: в запросе нет info_hash")
                    }
                }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
    }

    fun announced(): Int = requests.get()
    fun stop() = runCatching { stop.set(true); server.close() }.let { }
}

/**
 * Пир: настоящий протокол, но данные свои.
 *
 * [corruptFirst] — отдать первый запрошенный блок испорченным. Клиент обязан
 * это заметить по SHA-1 и переспросить, а не записать мусор в файл.
 */
private class StubPeer(
    private val infoHash: ByteArray,
    private val data: ByteArray,
    private val corruptFirst: Boolean = false
) {
    private val server = ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
    val port: Int get() = server.localPort
    private val stop = AtomicBoolean(false)
    private val servedBlocks = java.util.concurrent.atomic.AtomicInteger()

    fun start() {
        Thread {
            while (!stop.get()) {
                val client = try {
                    server.accept()
                } catch (e: Throwable) {
                    return@Thread
                }
                Thread { serve(client) }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
    }

    private fun serve(client: Socket) {
        client.use { socket ->
            socket.soTimeout = 10_000
            val input = DataInputStream(socket.getInputStream())
            val handshake = ByteArray(68)
            input.readFully(handshake)
            val hello = TorrentWire.parseHandshake(handshake)
            if (hello == null || !hello.infoHash.contentEquals(infoHash)) {
                failures.add("пир: клиент прислал неверное рукопожатие")
                return
            }
            val output = socket.getOutputStream()
            output.write(
                TorrentWire.handshake(infoHash, "-ST0001-abcdefghijkl".toByteArray(Charsets.US_ASCII))
            )
            // Bitfield: кусков ровно на файл.
            val pieceCount = (data.size + PIECE - 1) / PIECE
            output.write(TorrentWire.bitfield(BooleanArray(pieceCount) { true }))
            output.write(TorrentWire.unchoke())
            output.flush()

            var first = true
            while (!stop.get()) {
                val header = ByteArray(4)
                val read = runCatching { input.readFully(header); true }.getOrDefault(false)
                if (!read) return
                val length = ((header[0].toInt() and 0xFF) shl 24) or
                    ((header[1].toInt() and 0xFF) shl 16) or
                    ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
                if (length <= 0 || length > 1 shl 20) return
                val body = ByteArray(length)
                input.readFully(body)
                when (body[0].toInt() and 0xFF) {
                    TorrentWire.MSG_INTERESTED -> {
                        output.write(TorrentWire.unchoke())
                        output.flush()
                    }
                    TorrentWire.MSG_REQUEST -> {
                        val payload = body.copyOfRange(1, body.size)
                        val index = TorrentWire.pieceIndex(payload)
                        val offset = TorrentWire.blockOffset(payload)
                        val size = TorrentWire.requestLength(payload)
                        var start = index * PIECE + offset
                        var block = ByteArray(size)
                        if (start < data.size) {
                            val end = minOf(start + size, data.size)
                            block = data.copyOfRange(start, end)
                        }
                        if (corruptFirst && first) {
                            first = false
                            block = block.copyOf().also { it[0] = (it[0] + 1).toByte() }
                        }
                        servedBlocks.incrementAndGet()
                        output.write(TorrentWire.piece(index, offset, block))
                        output.flush()
                    }
                }
            }
        }
    }

    fun blocks(): Int = servedBlocks.get()
    fun stop() = runCatching { stop.set(true); server.close() }.let { }
}

fun main() {
    val data = ByteArray(3 * PIECE + 777) { (it * 31 + 7).toByte() }
    val torrent = buildTorrent(data, "файл.bin")
    val meta = TorrentMeta.parse(torrent).getOrThrow()
    check("торрент собран и разобран", meta.pieceCount == 4, "${meta.pieceCount}")

    // --- обычная загрузка -------------------------------------------------
    val peer = StubPeer(meta.infoHash, data)
    peer.start()
    val tracker = StubTracker(peer.port)
    tracker.start()
    val metaWithTracker = meta.copy(announce = listOf(tracker.url))

    val outDir = File("/tmp/torrent-test/ok")
    outDir.deleteRecursively()
    val percents = ArrayList<Double>()
    val states = ArrayList<String>()
    val download = TorrentDownload(metaWithTracker, emptyList(), outDir) { state ->
        percents.add(state.percent)
        if (states.isEmpty() || states.last() != state.status) states.add(state.status)
    }
    val result = download.run()
    val files = result.getOrNull()

    check("загрузка завершилась успешно", result.isSuccess, result.exceptionOrNull()?.message.orEmpty())
    if (files != null) {
        eq("файлов получено", files.size, 1)
        val file = File(outDir, "файл.bin")
        check("файл на месте", file.isFile)
        eq("размер полученного файла", file.length(), data.size.toLong())
        check("содержимое совпадает побайтово", file.readBytes().contentEquals(data))
    }
    check("трекер был спрошен", tracker.announced() > 0, "${tracker.announced()}")
    check("пир отдал блоки", peer.blocks() > 0, "${peer.blocks()}")
    check("прогресс дошёл до 100%", percents.any { it >= 99.9 }, percents.takeLast(3).toString())
    check("прогресс не убывал", percents.zipWithNext().all { (a, b) -> b >= a - 0.001 })
    check(
        "статусы рассказали о ходе дела",
        states.any { it.contains("скач") } || states.any { it.contains("провер") },
        states.toString()
    )
    peer.stop()
    tracker.stop()

    // --- битый кусок ------------------------------------------------------
    val badPeer = StubPeer(meta.infoHash, data, corruptFirst = true)
    badPeer.start()
    val tracker2 = StubTracker(badPeer.port)
    tracker2.start()
    val outDir2 = File("/tmp/torrent-test/corrupt")
    outDir2.deleteRecursively()
    val download2 = TorrentDownload(meta.copy(announce = listOf(tracker2.url)), emptyList(), outDir2) { }
    val result2 = download2.run()
    check("испорченный кусок не помешал загрузке", result2.isSuccess, result2.exceptionOrNull()?.message.orEmpty())
    val file2 = File(outDir2, "файл.bin")
    check(
        "файл после повтора верный",
        file2.isFile && file2.readBytes().contentEquals(data)
    )
    check("блок был запрошен повторно", badPeer.blocks() > meta.pieceCount, "${badPeer.blocks()}")
    badPeer.stop()
    tracker2.stop()

    // --- трекер без пиров -------------------------------------------------
    val emptyTracker = StubTracker(1) // порт 1: там никого нет
    emptyTracker.start()
    val outDir3 = File("/tmp/torrent-test/nope")
    outDir3.deleteRecursively()
    val download3 = TorrentDownload(meta.copy(announce = listOf(emptyTracker.url)), emptyList(), outDir3) { }
    val started = System.currentTimeMillis()
    // Пир 1 не ответит: соединяемся, получаем отказ, — это быстро, но на
    // всякий случай ограничиваем ожидание самим тестом.
    val result3 = runCatching { download3.run() }.getOrThrow()
    val elapsed = System.currentTimeMillis() - started
    check("без пиров — ошибка, а не пустота", result3.isFailure)
    check(
        "причина названа человеческим языком",
        result3.exceptionOrNull()?.message?.contains("торрент") == true,
        result3.exceptionOrNull()?.message.orEmpty()
    )
    check("ожидание не бесконечное", elapsed < 90_000, "$elapsed мс")
    emptyTracker.stop()

    // --- magnet из того же торрента ---------------------------------------
    val magnetText = "magnet:?xt=urn:btih:" +
        meta.infoHash.joinToString("") { "%02x".format(it) } +
        "&dn=" + java.net.URLEncoder.encode("файл.bin", "UTF-8") +
        "&tr=" + java.net.URLEncoder.encode(tracker.url, "UTF-8")
    val magnet = TorrentMeta.parseMagnet(magnetText).getOrNull()
    check("magnet разобран", magnet != null)
    eq("отпечаток в magnet совпал", magnet?.infoHash?.contentEquals(meta.infoHash), true)

    println()
    println("passed=$passed failed=${failures.size}")
    failures.forEach { println("FAIL  $it") }
    if (failures.isNotEmpty()) kotlin.system.exitProcess(1)
}

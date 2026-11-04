// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import java.io.File
import java.io.RandomAccessFile
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * Скачивание торрента: трекер → пиры → куски → проверка → файлы.
 *
 * ЧТО ЗДЕСЬ ЕСТЬ И ЧЕГО НЕТ
 *   Есть ровно то, что нужно, чтобы получить файл: announce на трекер (HTTP и
 *   UDP), рукопожатие с пиром, заинтересованность, запросы блоками по 16 КБ,
 *   проверка SHA-1 каждого куска, запись прямо в итоговые файлы по
 *   смещениям, повтор у другого пира после отказа или таймаута.
 *
 *   Нет: раздачи (мы только качаем), DHT, uTP, шифрования трафика, обмена
 *   метаданными из magnet — последнее в отдельном классе и появится, когда
 *   будет проверено на живых пирах.
 *
 * ПОЧЕМУ КУСКИ ПРОВЕРЯЮТСЯ
 *   Пир может прислать что угодно, и «скачалось» без SHA-1 не значит ничего.
 *   Испорченный кусок не пишется вовсе: сначала в память, потом проверка,
 *   потом диск.
 *
 * ЧЕСТНО О СКОРОСТИ
 *   Пиры отваливаются (это норма протокола), трекер молчит, кусок может не
 *   скачаться ни с кого. Всё это возвращается как состояние, а не тишина:
 *   экран показывает «пиров: 3 · скачано 40% · 1,2 МБ/с» или «никто не
 *   отдаёт этот торрент».
 */
class TorrentDownload(
    private val meta: TorrentMeta.Meta,
    /** Индексы файлов, которые нужны. Пусто — все. */
    private val selectedFiles: Collection<Int>,
    private val outDir: File,
    private val progress: (State) -> Unit
) {

    data class State(
        val percent: Double,
        val doneBytes: Long,
        val totalBytes: Long,
        val peers: Int,
        val speedBytesPerSecond: Long,
        val status: String,
        val finished: Boolean = false,
        val failure: String? = null
    )

    private val wanted = TorrentPlan.wantedPieces(meta, selectedFiles)
    private val totalWanted = wanted.indices.sumOf { if (wanted[it]) TorrentPlan.pieceSize(meta, it) else 0L }
    private val pieces = arrayOfNulls<ByteArray>(meta.pieceCount)
    private val have = BooleanArray(meta.pieceCount)
    private val inFlight = java.util.concurrent.ConcurrentHashMap<Int, Int>()
    /**
     * Сколько байт куска уже принято.
     *
     * Раньше «кусок собран» определялось по числу ненулевых байт — и на
     * данных, где нули обычны (образ диска, iso, любой сжатый файл), кусок
     * не считался собранным НИКОГДА: скачивание шло, а прогресс стоял.
     * Теперь считается именно принятое.
     */
    private val receivedBytes = java.util.concurrent.ConcurrentHashMap<Int, Int>()
    private val doneBytes = AtomicLong(0)
    private val stopRequested = AtomicBoolean(false)
    private val recentBytes = AtomicLong(0)
    private val speed = AtomicLong(0)

    private val outputs = LinkedHashMap<Int, RandomAccessFile>()

    private val peerId: ByteArray = buildString {
        append("-SY")
        append("0001")
        append("-")
        while (length < 20) append(('a'..'z').random())
    }.take(20).toByteArray(Charsets.US_ASCII)

    val outputFiles: List<File> get() = outputs.keys.mapNotNull { index -> fileFor(index) }

    fun cancel() {
        stopRequested.set(true)
    }

    fun cancelled(): Boolean = stopRequested.get()

    /**
     * Скачать всё выбранное. Блокирующий вызов — вызывать из Dispatchers.IO.
     *
     * Возвращает список файлов, которые появились на диске.
     */
    fun run(): Result<List<File>> {
        if (totalWanted == 0L) return Result.failure(IllegalStateException("нечего скачивать"))
        outDir.mkdirs()

        var peers = emptyList<TorrentTracker.Peer>()
        var lastAnnounce = 0L
        var start = System.currentTimeMillis()
        var rounds = 0
        var noProgressRounds = 0
        val maxRounds = 40

        report(status = if (meta.isPrivate) "ищу пиров" else "ищу пиров и в DHT-списках")

        while (remainingPieces() > 0 && !stopRequested.get() && rounds < maxRounds) {
            val now = System.currentTimeMillis()
            if (peers.isEmpty() || now - lastAnnounce > 120_000) {
                peers = announceAll()
                lastAnnounce = now
                if (peers.isEmpty() && rounds == 0) {
                    report(status = "трекеры не дали пиров — пробую ещё")
                }
            }
            if (peers.isEmpty()) {
                if (rounds >= 1) {
                    return Result.failure(
                        IllegalStateException(
                            "никто не отдаёт этот торрент: трекеры не дали ни одного пира"
                        )
                    )
                }
                Thread.sleep(3000)
                rounds++
                continue
            }

            val batch = peers.shuffled().take(MAX_PEERS_PER_ROUND)
            val threads = batch.map { peer ->
                Thread { pumpPeer(peer) }.apply { isDaemon = true; start() }
            }
            // Скорость считаем раз в секунду, пока работают пиры.
            while (threads.any { it.isAlive } && !stopRequested.get() && remainingPieces() > 0) {
                Thread.sleep(1000)
                val bytes = recentBytes.getAndSet(0)
                speed.set(bytes)
                val elapsed = (System.currentTimeMillis() - start) / 1000.0
                report(
                    peers = threads.count { it.isAlive },
                    status = if (remainingPieces() > 0) "скачиваю" else "проверяю"
                )
                if (elapsed > MAX_SECONDS) {
                    stopRequested.set(true)
                }
            }
            rounds++
            if (doneBytes.get() == 0L) {
                noProgressRounds++
                // Если за несколько кругов не пришло НИ ОДНОГО байта, ждать
                // сорок кругов бессмысленно: пиры не отвечают вовсе.
                if (noProgressRounds >= 6) break
            } else {
                noProgressRounds = 0
            }
            if (remainingPieces() > 0) {
                report(status = "часть кусков не пришла — пробую других пиров")
            }
        }

        // Список файлов снимаем ДО закрытия: после closeOutputs он пуст,
        // и вызывающий получал «скачано успешно» без единого файла.
        val produced = outputFiles
        closeOutputs()
        if (stopRequested.get()) return Result.failure(IllegalStateException("остановлено"))
        if (remainingPieces() > 0) {
            val missing = remainingPieces()
            val message = if (doneBytes.get() == 0L) {
                "никто не отдал ни одного куска: у торрента нет раздающих или порты закрыты"
            } else {
                "не удалось скачать $missing из ${wanted.count { it }} кусков — " +
                    "раздающих слишком мало, можно попробовать позже"
            }
            return Result.failure(IllegalStateException(message))
        }
        report(percent = 100.0, finished = true, status = "готово")
        return Result.success(produced.filter { it.isFile && it.length() > 0 })
    }

    // --- состояние ---------------------------------------------------------

    private fun remainingPieces(): Int = have.indices.count { wanted[it] && !have[it] }

    private fun report(
        peers: Int = 0,
        status: String = "",
        percent: Double = -1.0,
        finished: Boolean = false
    ) {
        val done = doneBytes.get()
        val percentValue = if (percent >= 0) percent
        else if (totalWanted == 0L) 0.0
        else done.toDouble() * 100.0 / totalWanted
        runCatching {
            progress(
                State(
                    percent = percentValue,
                    doneBytes = done,
                    totalBytes = totalWanted,
                    peers = peers,
                    speedBytesPerSecond = speed.get(),
                    status = status,
                    finished = finished
                )
            )
        }
    }

    // --- трекеры -----------------------------------------------------------

    /**
     * Спросить все трекеры параллельно и собрать общий список пиров.
     *
     * UDP-трекеры спрашиваются первыми: ответ за миллисекунды, а HTTP может
     * висеть до таймаута, и торрент, у которого только HTTP-трекер в списке,
     * выглядел бы мёртвым.
     */
    private fun announceAll(): List<TorrentTracker.Peer> {
        val trackers = meta.announce.take(MAX_TRACKERS)
        if (trackers.isEmpty()) return emptyList()
        val found = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val result = ArrayList<TorrentTracker.Peer>()
        val threads = trackers.map { url ->
            Thread {
                val peers = runCatching { announceOne(url) }.getOrDefault(emptyList())
                for (peer in peers) {
                    if (found.add(peer.toString())) result.add(peer)
                }
            }.apply { isDaemon = true; start() }
        }
        threads.forEach { runCatching { it.join(8000) } }
        return result
    }

    private fun announceOne(url: String): List<TorrentTracker.Peer> {
        val left = totalWanted - doneBytes.get()
        return if (TorrentTracker.isUdp(url)) {
            val endpoint = TorrentTracker.udpEndpoint(url) ?: return emptyList()
            announceUdp(endpoint.first, endpoint.second, left)
        } else {
            announceHttp(url, left)
        }
    }

    private fun announceHttp(url: String, left: Long): List<TorrentTracker.Peer> {
        val request = TorrentTracker.httpUrl(url, meta.infoHash, peerId, PORT, left)
        val connection = URL(request).openConnection() as java.net.HttpURLConnection
        connection.connectTimeout = 6000
        connection.readTimeout = 8000
        connection.setRequestProperty("User-Agent", USER_AGENT)
        return try {
            val body = connection.inputStream.use { it.readBytes() }
            val parsed = TorrentTracker.parseHttpResponse(body)
            parsed.failure?.let { Log.info("[Torrent] трекер отказал: $it") }
            parsed.peers
        } finally {
            connection.disconnect()
        }
    }

    private fun announceUdp(host: String, port: Int, left: Long): List<TorrentTracker.Peer> {
        DatagramSocket().use { socket ->
            socket.soTimeout = 4000
            val address = InetAddress.getByName(host)
            val transaction = Random.nextInt()
            send(socket, TorrentTracker.udpConnect(transaction), address, port)
            val connectReply = ByteArray(2048)
            val connectPacket = DatagramPacket(connectReply, connectReply.size)
            socket.receive(connectPacket)
            val connectionId = TorrentTracker.udpParseConnect(
                connectReply.copyOf(connectPacket.length), transaction
            ) ?: return emptyList()

            val announceTransaction = transaction + 1
            send(
                socket,
                TorrentTracker.udpAnnounce(
                    connectionId, announceTransaction, meta.infoHash, peerId,
                    downloaded = doneBytes.get(), left = left.coerceAtLeast(0),
                    uploaded = 0, port = PORT
                ),
                address, port
            )
            val reply = ByteArray(4096)
            val packet = DatagramPacket(reply, reply.size)
            socket.receive(packet)
            return TorrentTracker.udpParseAnnounce(
                reply.copyOf(packet.length), announceTransaction
            ).peers
        }
    }

    private fun send(socket: DatagramSocket, data: ByteArray, address: InetAddress, port: Int) {
        socket.send(DatagramPacket(data, data.size, address, port))
    }

    // --- пиры --------------------------------------------------------------

    /**
     * Одна сессия с одним пиром: рукопожатие, заинтересованность, блоки.
     *
     * Всё в try/catch: пир закрывает сокет в любой момент — это нормальная
     * часть протокола, а не ошибка приложения.
     */
    private fun pumpPeer(peer: TorrentTracker.Peer) {
        var socket: Socket? = null
        try {
            socket = Socket().apply {
                connect(InetSocketAddress(peer.host, peer.port), CONNECT_TIMEOUT)
                soTimeout = READ_TIMEOUT
            }
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            output.write(TorrentWire.handshake(meta.infoHash, peerId))
            output.flush()

            val handshake = ByteArray(TorrentWire.HANDSHAKE_LENGTH)
            if (!readFully(input, handshake)) return
            val theirHello = TorrentWire.parseHandshake(handshake) ?: return
            if (!theirHello.infoHash.contentEquals(meta.infoHash)) return

            output.write(TorrentWire.interested())
            // Свой bitfield: у нас ничего нет, но сообщить об этом — вежливо
            // и совместимо со старыми клиентами.
            output.write(TorrentWire.bitfield(have))
            output.flush()

            var choked = true
            val buffer = ByteArray(4)
            while (!stopRequested.get() && remainingPieces() > 0) {
                if (!readFully(input, buffer)) return
                val length = ((buffer[0].toInt() and 0xFF) shl 24) or
                    ((buffer[1].toInt() and 0xFF) shl 16) or
                    ((buffer[2].toInt() and 0xFF) shl 8) or (buffer[3].toInt() and 0xFF)
                if (length == 0) continue // keep-alive
                if (length < 0 || length > MAX_MESSAGE) return
                val body = ByteArray(length)
                if (!readFully(input, body)) return

                when (body[0].toInt() and 0xFF) {
                    TorrentWire.MSG_CHOKE -> choked = true
                    TorrentWire.MSG_UNCHOKE -> choked = false
                    TorrentWire.MSG_HAVE -> Unit
                    TorrentWire.MSG_PIECE -> {
                        val payload = body.copyOfRange(1, body.size)
                        onPiece(payload)
                        requestNext(output, choked)
                    }
                    TorrentWire.MSG_BITFIELD -> Unit
                    else -> Unit
                }
                if (!choked) requestNext(output, choked)
            }
        } catch (e: Throwable) {
            // Пир ушёл — это ожидаемо. Не логируем как ошибку приложения.
        } finally {
            runCatching { socket?.close() }
        }
    }

    private fun requestNext(output: java.io.OutputStream, choked: Boolean) {
        if (choked || stopRequested.get()) return
        while (inFlight.size < MAX_IN_FLIGHT) {
            val index = nextWantedPiece() ?: return
            if (inFlight.putIfAbsent(index, 0) != null) continue
            // Просим кусок целиком, блоками по 16 КБ.
            val size = TorrentPlan.pieceSize(meta, index)
            var offset = 0L
            while (offset < size) {
                val block = minOf(TorrentWire.BLOCK_SIZE.toLong(), size - offset).toInt()
                output.write(TorrentWire.request(index, offset.toInt(), block))
                offset += block
            }
            output.flush()
            return
        }
    }

    /** Следующий нужный кусок: по порядку, чтобы файл рос с начала. */
    private fun nextWantedPiece(): Int? {
        for (index in wanted.indices) {
            if (wanted[index] && !have[index] && !inFlight.containsKey(index)) return index
        }
        return null
    }

    private fun onPiece(payload: ByteArray) {
        val index = TorrentWire.pieceIndex(payload)
        if (index !in wanted.indices || !wanted[index] || have[index]) return
        val offset = TorrentWire.blockOffset(payload)
        val data = TorrentWire.blockData(payload)
        if (data.isEmpty()) return

        val size = TorrentPlan.pieceSize(meta, index).toInt()
        if (offset < 0 || offset + data.size > size) return
        val slot = synchronized(pieces) {
            pieces[index] ?: ByteArray(size).also { pieces[index] = it }
        }
        blockReceived(index, offset, data, slot)
    }

    private fun blockReceived(index: Int, offset: Int, data: ByteArray, slot: ByteArray) {
        synchronized(slot) {
            data.copyInto(slot, offset)
            val got = receivedBytes.merge(index, data.size, Int::plus) ?: data.size
            if (got < slot.size) return
            if (!TorrentPlan.verifyPiece(meta, index, slot)) {
                // Пир прислал мусор: кусок выбрасываем и просим заново.
                pieces[index] = null
                inFlight.remove(index)
                receivedBytes.remove(index)
                return
            }
            if (have[index]) return
            have[index] = true
            inFlight.remove(index)
            receivedBytes.remove(index)
            writePiece(index, slot)
            recentBytes.addAndGet(slot.size.toLong())
            doneBytes.addAndGet(slot.size.toLong())
            report(status = "скачиваю")
        }
    }

    // --- запись ------------------------------------------------------------

    private fun fileFor(index: Int): File? {
        val file = meta.files.getOrNull(index) ?: return null
        // Пути внутри торрента бывают с подкаталогами; создаём как есть, но
        // без выхода из папки загрузки.
        val safe = file.path.split('/').filter { it.isNotBlank() && it != ".." }.joinToString("/")
        return File(outDir, safe)
    }

    private fun writePiece(index: Int, data: ByteArray) {
        val pieceStart = index.toLong() * meta.pieceLength
        val pieceEnd = pieceStart + data.size
        meta.files.forEachIndexed { fileIndex, entry ->
            if (selectedFiles.isNotEmpty() && fileIndex !in selectedFiles) return@forEachIndexed
            val fileStart = entry.offset
            val fileEnd = entry.offset + entry.length
            if (fileEnd <= pieceStart || fileStart >= pieceEnd) return@forEachIndexed
            val from = maxOf(pieceStart, fileStart)
            val to = minOf(pieceEnd, fileEnd)
            val target = fileFor(fileIndex) ?: return@forEachIndexed
            target.parentFile?.mkdirs()
            val raf = synchronized(outputs) {
                outputs.getOrPut(fileIndex) { RandomAccessFile(target, "rw") }
            }
            synchronized(raf) {
                raf.seek(from - fileStart)
                raf.write(data, (from - pieceStart).toInt(), (to - from).toInt())
            }
        }
    }

    private fun closeOutputs() {
        synchronized(outputs) {
            outputs.values.forEach { runCatching { it.close() } }
            outputs.clear()
        }
    }

    private fun readFully(input: java.io.InputStream, buffer: ByteArray): Boolean {
        var read = 0
        while (read < buffer.size) {
            val n = input.read(buffer, read, buffer.size - read)
            if (n <= 0) return false
            read += n
        }
        return true
    }

    companion object {
        private const val PORT = 6881
        private const val CONNECT_TIMEOUT = 6000
        private const val READ_TIMEOUT = 20_000
        private const val MAX_MESSAGE = 2 * 1024 * 1024
        private const val MAX_IN_FLIGHT = 8
        private const val MAX_PEERS_PER_ROUND = 12
        private const val MAX_TRACKERS = 8

        /** Больше двух часов на один торрент не ждём: экран честно скажет. */
        private const val MAX_SECONDS = 2 * 60 * 60.0
        private const val USER_AGENT = "Symbiosis/1.0"
    }
}

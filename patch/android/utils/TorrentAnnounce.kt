// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URL
import kotlin.random.Random

/**
 * «Эй, трекер, кто отдаёт этот торрент?»
 *
 * Один запрос — один список адресов пиров. Логика общая для скачивания по
 * `.torrent` и для получения списка файлов из magnet-ссылки, поэтому она
 * живёт здесь, а не внутри загрузчика.
 *
 * UDP-трекеры спрашиваются через сокет (быстро, но ответ может потеряться),
 * HTTP — обычным запросом. Оба варианта отвечают словарём bencode; пиры в
 * нём упакованы по шесть байт на адрес (4 — адрес, 2 — порт).
 */
object TorrentAnnounce {

    data class Answer(
        val peers: List<TorrentTracker.Peer>,
        val intervalSeconds: Int = 0,
        val failure: String? = null
    )

    /**
     * Спросить все трекеры и вернуть объединённый список пиров без повторов.
     *
     * Трекеры опрашиваются ПАРАЛЛЕЛЬНО: один мёртвый трекер не должен
     * добавлять к ожиданию свой таймаут целиком.
     */
    fun peers(
        trackers: Collection<String>,
        infoHash: ByteArray,
        peerId: ByteArray,
        port: Int,
        downloaded: Long = 0,
        left: Long = 0,
        timeoutMillis: Int = 8000
    ): Answer {
        val list = trackers.filter { it.startsWith("http://", true) || it.startsWith("https://", true) || TorrentTracker.isUdp(it) }
            .distinct()
            .take(MAX_TRACKERS)
        if (list.isEmpty()) {
            return Answer(emptyList(), failure = "в торренте нет ни одного трекера")
        }
        val seen = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val peers = java.util.Collections.synchronizedList(ArrayList<TorrentTracker.Peer>())
        val failures = java.util.Collections.synchronizedList(ArrayList<String>())
        val threads = list.map { url ->
            Thread {
                val one = runCatching {
                    ask(url, infoHash, peerId, port, downloaded, left)
                }.getOrElse { Answer(emptyList(), failure = it.message ?: "не ответил") }
                one.failure?.let { failures.add("$url: $it") }
                one.peers.forEach { if (seen.add(it.toString())) peers.add(it) }
            }.apply { isDaemon = true; start() }
        }
        threads.forEach { runCatching { it.join(timeoutMillis.toLong()) } }
        return Answer(
            peers = peers.toList(),
            failure = if (peers.isEmpty() && failures.isNotEmpty()) failures.first() else null
        )
    }

    private fun ask(
        tracker: String,
        infoHash: ByteArray,
        peerId: ByteArray,
        port: Int,
        downloaded: Long,
        left: Long
    ): Answer {
        // Тело-блок, а не выражение: внутри есть ранний выход, когда адрес
        // UDP-трекера не разбирается.
        if (!TorrentTracker.isUdp(tracker)) {
            return http(tracker, infoHash, peerId, port, downloaded, left)
        }
        val endpoint = TorrentTracker.udpEndpoint(tracker)
            ?: return Answer(emptyList(), failure = "адрес трекера не разобран")
        return udp(endpoint.first, endpoint.second, infoHash, peerId, port, downloaded, left)
    }

    private fun http(
        tracker: String,
        infoHash: ByteArray,
        peerId: ByteArray,
        port: Int,
        downloaded: Long,
        left: Long
    ): Answer {
        val url = TorrentTracker.httpUrl(
            tracker, infoHash, peerId, port, left = left, downloaded = downloaded
        )
        val connection = URL(url).openConnection() as java.net.HttpURLConnection
        connection.connectTimeout = 6000
        connection.readTimeout = 8000
        connection.setRequestProperty("User-Agent", USER_AGENT)
        return try {
            if (connection.responseCode !in 200..299) {
                Answer(emptyList(), failure = "код ${connection.responseCode}")
            } else {
                val body = connection.inputStream.use { it.readBytes() }
                val parsed = TorrentTracker.parseHttpResponse(body)
                Answer(parsed.peers, parsed.intervalSeconds, parsed.failure)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun udp(
        host: String,
        port: Int,
        infoHash: ByteArray,
        peerId: ByteArray,
        clientPort: Int,
        downloaded: Long,
        left: Long
    ): Answer {
        DatagramSocket().use { socket ->
            socket.soTimeout = 4000
            val address = InetAddress.getByName(host)
            val transaction = Random.nextInt()
            socket.send(
                DatagramPacket(
                    TorrentTracker.udpConnect(transaction), 16, address, port
                )
            )
            val reply = ByteArray(2048)
            val packet = DatagramPacket(reply, reply.size)
            socket.receive(packet)
            val connectionId = TorrentTracker.udpParseConnect(
                reply.copyOf(packet.length), transaction
            ) ?: return Answer(emptyList(), failure = "трекер не подтвердил соединение")

            val announceTransaction = transaction + 1
            val data = TorrentTracker.udpAnnounce(
                connectionId, announceTransaction, infoHash, peerId,
                downloaded, left, 0, clientPort
            )
            socket.send(DatagramPacket(data, data.size, address, port))
            val answer = ByteArray(8192)
            val answerPacket = DatagramPacket(answer, answer.size)
            socket.receive(answerPacket)
            val parsed = TorrentTracker.udpParseAnnounce(
                answer.copyOf(answerPacket.length), announceTransaction
            )
            return Answer(parsed.peers, parsed.intervalSeconds, parsed.failure)
        }
    }

    private const val MAX_TRACKERS = 8
    private const val USER_AGENT = "Symbiosis/1.0"
}

// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

/**
 * Трекер: спросить «кто раздаёт этот торрент» и получить список адресов.
 *
 * Два протокола, оба обязательны на практике:
 *
 *   * HTTP(S) — обычный GET с параметрами; в ответ приходит bencode со
 *     списком пиров (BEP 3);
 *   * UDP (BEP 15) — три пакета: connect → announce → ответ. Быстрее и
 *     живёт дольше; на многих трекерах HTTP уже отключён.
 *
 * Здесь только сборка и разбор пакетов. Сеть — в TorrentSession: так
 * «правильно ли закодирован info_hash» проверяется тестом, а не догадкой.
 *
 * ВАЖНО ПРО info_hash: он кодируется как СЫРЫЕ 20 байт, а не как текст. Это
 * самая частая ошибка в самодельных клиентах: вместо хеша уходит строка
 * «%1f%8b…» от hex-представления, трекер отвечает «torrent not registered»,
 * и выглядит это как несуществующий торрент.
 */
object TorrentTracker {

    data class Peer(val host: String, val port: Int) {
        override fun toString(): String = "$host:$port"
    }

    data class Response(
        val peers: List<Peer>,
        val intervalSeconds: Int,
        val failure: String? = null
    )

    // --- HTTP -------------------------------------------------------------

    /**
     * Адрес запроса к HTTP-трекеру.
     *
     * [peerId] и [infoHash] — байты; всё, что не ASCII, кодируется
     * процентами. Пробел кодируется как %20, а не как +: в строке запроса
     * торрента плюс — это плюс.
     */
    fun httpUrl(
        announce: String,
        infoHash: ByteArray,
        peerId: ByteArray,
        port: Int,
        left: Long,
        uploaded: Long = 0,
        downloaded: Long = 0,
        event: String? = "started"
    ): String {
        val separator = if (announce.contains('?')) '&' else '?'
        val params = StringBuilder()
        params.append("info_hash=").append(percentEncode(infoHash))
        params.append("&peer_id=").append(percentEncode(peerId))
        params.append("&port=").append(port)
        params.append("&uploaded=").append(uploaded)
        params.append("&downloaded=").append(downloaded)
        params.append("&left=").append(left.coerceAtLeast(0))
        params.append("&compact=1")
        params.append("&numwant=80")
        if (!event.isNullOrEmpty()) params.append("&event=").append(event)
        return announce + separator + params
    }

    /**
     * Кодирование байтов для строки запроса.
     *
     * Незарезервированные символы остаются собой — трекеры это ожидают;
     * всё остальное, включая байты >127, уходит процентами. Именно это и
     * делает info_hash правильным.
     */
    fun percentEncode(bytes: ByteArray): String {
        val safe = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_.~"
        val out = StringBuilder(bytes.size * 3)
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            if (ch in safe) {
                out.append(ch)
            } else {
                out.append('%')
                out.append("0123456789ABCDEF"[c shr 4])
                out.append("0123456789ABCDEF"[c and 0x0F])
            }
        }
        return out.toString()
    }

    /**
     * Разбор ответа HTTP-трекера.
     *
     * Пиры бывают в двух видах: компактный (6 байт на пира: 4 IP + 2 порт,
     * big-endian) и словарями. Первый — норма, второй ещё встречается у
     * старых трекеров, и поддерживать оба дешевле, чем объяснять, почему
     * «на трекере A работает, на B нет».
     */
    fun parseHttpResponse(body: ByteArray): Response {
        val dict = TorrentBencode.decode(body)?.asDictOrNull()
            ?: return Response(emptyList(), 0, "ответ трекера не читается")
        dict["failure reason"]?.asTextOrNull()?.let { reason ->
            return Response(emptyList(), 0, reason)
        }
        val interval = dict["interval"]?.asLongOrNull()?.toInt() ?: 0
        val peers = ArrayList<Peer>()
        dict["peers"]?.asBytesOrNull()?.let { compact ->
            var at = 0
            while (at + 6 <= compact.size) {
                val ip = "${compact[at].toInt() and 0xFF}.${compact[at + 1].toInt() and 0xFF}." +
                    "${compact[at + 2].toInt() and 0xFF}.${compact[at + 3].toInt() and 0xFF}"
                val port = ((compact[at + 4].toInt() and 0xFF) shl 8) or
                    (compact[at + 5].toInt() and 0xFF)
                if (port in 1..65535) peers.add(Peer(ip, port))
                at += 6
            }
        }
        dict["peers"]?.asListOrNull()?.forEach { item ->
            val d = item.asDictOrNull() ?: return@forEach
            val ip = d["ip"]?.asTextOrNull() ?: return@forEach
            val port = d["port"]?.asLongOrNull()?.toInt() ?: return@forEach
            if (port in 1..65535) peers.add(Peer(ip, port))
        }
        return Response(peers, interval)
    }

    // --- UDP (BEP 15) -----------------------------------------------------

    const val UDP_PROTOCOL_ID = 0x41727101980L
    const val ACTION_CONNECT = 0
    const val ACTION_ANNOUNCE = 1
    const val ACTION_ERROR = 3

    /** Пакет connect: 16 байт. */
    fun udpConnect(transactionId: Int): ByteArray = ByteArray(16).also { out ->
        putLong(out, 0, UDP_PROTOCOL_ID)
        putInt(out, 8, ACTION_CONNECT)
        putInt(out, 12, transactionId)
    }

    /** Разбор connect-ответа: connection id и тот же номер транзакции. */
    fun udpParseConnect(bytes: ByteArray, transactionId: Int): Long? {
        if (bytes.size < 16) return null
        if (getInt(bytes, 0) != ACTION_CONNECT) return null
        if (getInt(bytes, 4) != transactionId) return null
        return getLong(bytes, 8)
    }

    /** Пакет announce: 98 байт. */
    fun udpAnnounce(
        connectionId: Long,
        transactionId: Int,
        infoHash: ByteArray,
        peerId: ByteArray,
        downloaded: Long,
        left: Long,
        uploaded: Long,
        port: Int,
        event: Int = 2 // 2 = started
    ): ByteArray = ByteArray(98).also { out ->
        putLong(out, 0, connectionId)
        putInt(out, 8, ACTION_ANNOUNCE)
        putInt(out, 12, transactionId)
        infoHash.copyInto(out, 16, 0, 20)
        peerId.copyInto(out, 36, 0, 20)
        putLong(out, 56, downloaded)
        putLong(out, 64, left)
        putLong(out, 72, uploaded)
        putInt(out, 80, event)
        putInt(out, 84, 0) // IP: «возьми мой»
        putInt(out, 88, 0) // ключ
        putInt(out, 92, -1) // numwant = по умолчанию
        // Порт — ДВА байта, а не четыре: последнее поле пакета (96..97).
        out[96] = (port ushr 8).toByte()
        out[97] = port.toByte()
    }

    /** Разбор announce-ответа: интервал и компактный список пиров. */
    fun udpParseAnnounce(bytes: ByteArray, transactionId: Int): Response {
        if (bytes.size < 8) return Response(emptyList(), 0, "короткий ответ трекера")
        val action = getInt(bytes, 0)
        if (action == ACTION_ERROR) {
            val text = String(bytes, 8, bytes.size - 8, Charsets.UTF_8)
            return Response(emptyList(), 0, text)
        }
        if (action != ACTION_ANNOUNCE) return Response(emptyList(), 0, "неожиданный ответ трекера")
        if (getInt(bytes, 4) != transactionId) return Response(emptyList(), 0, "ответ не на наш запрос")
        val interval = getInt(bytes, 8)
        val peers = ArrayList<Peer>()
        var at = 20
        while (at + 6 <= bytes.size) {
            val ip = "${bytes[at].toInt() and 0xFF}.${bytes[at + 1].toInt() and 0xFF}." +
                "${bytes[at + 2].toInt() and 0xFF}.${bytes[at + 3].toInt() and 0xFF}"
            val port = ((bytes[at + 4].toInt() and 0xFF) shl 8) or (bytes[at + 5].toInt() and 0xFF)
            if (port in 1..65535) peers.add(Peer(ip, port))
            at += 6
        }
        return Response(peers, interval)
    }

    /** Похож ли адрес на UDP-трекер. */
    fun isUdp(url: String): Boolean = url.startsWith("udp://", ignoreCase = true)

    /** Разобрать `udp://host:port/announce` в хост и порт. */
    fun udpEndpoint(url: String): Pair<String, Int>? {
        val rest = url.substringAfter("://", "")
        val hostPart = rest.substringBefore('/')
        if (hostPart.isBlank()) return null
        val host = hostPart.substringBefore(':')
        val port = hostPart.substringAfter(':', "80").toIntOrNull() ?: return null
        return host to port
    }

    private fun putInt(out: ByteArray, at: Int, value: Int) {
        out[at] = (value ushr 24).toByte()
        out[at + 1] = (value ushr 16).toByte()
        out[at + 2] = (value ushr 8).toByte()
        out[at + 3] = value.toByte()
    }

    private fun putLong(out: ByteArray, at: Int, value: Long) {
        for (i in 0 until 8) {
            out[at + i] = (value ushr (56 - i * 8)).toByte()
        }
    }

    private fun getInt(bytes: ByteArray, at: Int): Int =
        ((bytes[at].toInt() and 0xFF) shl 24) or ((bytes[at + 1].toInt() and 0xFF) shl 16) or
            ((bytes[at + 2].toInt() and 0xFF) shl 8) or (bytes[at + 3].toInt() and 0xFF)

    private fun getLong(bytes: ByteArray, at: Int): Long {
        var value = 0L
        for (i in 0 until 8) {
            value = (value shl 8) or (bytes[at + i].toLong() and 0xFF)
        }
        return value
    }
}

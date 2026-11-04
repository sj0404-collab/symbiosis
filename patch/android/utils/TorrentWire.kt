// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

/**
 * Протокол обмена с пиром: рукопожатие и сообщения.
 *
 * Отделено от сокетов намеренно. Всё, что здесь есть, — чистые функции над
 * байтами, поэтому проверяется на хосте: длина рукопожатия, порядок байтов,
 * разметка сообщений. Ошибка в этих четырёх структурах выглядит на телефоне
 * как «подключается и молчит», и искать её в живом сокете — самое
 * неблагодарное занятие.
 *
 * Ссылки: BEP 3 (протокол), BEP 10 (расширения), BEP 9 (метаданные).
 */
object TorrentWire {

    const val PROTOCOL = "BitTorrent protocol"
    const val HANDSHAKE_LENGTH = 68
    const val BLOCK_SIZE = 16 * 1024

    /** Кусок обмена метаданными (BEP 9) — фиксированные 16 КБ. */
    const val METADATA_PIECE_SIZE = 16 * 1024

    const val MSG_CHOKE = 0
    const val MSG_UNCHOKE = 1
    const val MSG_INTERESTED = 2
    const val MSG_NOT_INTERESTED = 3
    const val MSG_HAVE = 4
    const val MSG_BITFIELD = 5
    const val MSG_REQUEST = 6
    const val MSG_PIECE = 7
    const val MSG_CANCEL = 8
    const val MSG_PORT = 9
    const val MSG_EXTENDED = 20

    /**
     * Рукопожатие.
     *
     * Байт 5 в резерве (0x10) объявляет поддержку расширений BEP 10 — от
     * этого зависит, сможем ли мы вытащить список файлов из magnet-ссылки.
     */
    fun handshake(infoHash: ByteArray, peerId: ByteArray): ByteArray {
        require(infoHash.size == 20) { "info hash обязан быть 20 байт" }
        require(peerId.size == 20) { "peer id обязан быть 20 байт" }
        val out = ByteArray(HANDSHAKE_LENGTH)
        out[0] = PROTOCOL.length.toByte()
        PROTOCOL.toByteArray(Charsets.US_ASCII).copyInto(out, 1)
        val at = 1 + PROTOCOL.length
        out.fill(0, at, at + 8)
        out[at + 5] = 0x10.toByte() // BEP 10
        infoHash.copyInto(out, at + 8)
        peerId.copyInto(out, at + 28)
        return out
    }

    data class Handshake(val infoHash: ByteArray, val peerId: ByteArray, val supportsExtensions: Boolean)

    /** Разбор ответного рукопожатия. null — пришло что-то не то. */
    fun parseHandshake(bytes: ByteArray): Handshake? {
        if (bytes.size < HANDSHAKE_LENGTH) return null
        if (bytes[0].toInt() != PROTOCOL.length) return null
        val protocol = String(bytes, 1, PROTOCOL.length, Charsets.US_ASCII)
        if (protocol != PROTOCOL) return null
        val at = 1 + PROTOCOL.length
        return Handshake(
            infoHash = bytes.copyOfRange(at + 8, at + 28),
            peerId = bytes.copyOfRange(at + 28, at + 48),
            supportsExtensions = (bytes[at + 5].toInt() and 0x10) != 0
        )
    }

    /** Сообщение без длины впереди: [id] + [payload]. */
    fun message(id: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val out = ByteArray(4 + 1 + payload.size)
        val length = 1 + payload.size
        out[0] = (length ushr 24).toByte()
        out[1] = (length ushr 16).toByte()
        out[2] = (length ushr 8).toByte()
        out[3] = length.toByte()
        out[4] = id.toByte()
        payload.copyInto(out, 5)
        return out
    }

    fun keepAlive(): ByteArray = ByteArray(4)

    fun interested(): ByteArray = message(MSG_INTERESTED)

    fun have(piece: Int): ByteArray = message(
        MSG_HAVE,
        byteArrayOf(
            (piece ushr 24).toByte(), (piece ushr 16).toByte(),
            (piece ushr 8).toByte(), piece.toByte()
        )
    )

    fun request(piece: Int, offset: Int, length: Int): ByteArray = message(
        MSG_REQUEST,
        byteArrayOf(
            (piece ushr 24).toByte(), (piece ushr 16).toByte(),
            (piece ushr 8).toByte(), piece.toByte(),
            (offset ushr 24).toByte(), (offset ushr 16).toByte(),
            (offset ushr 8).toByte(), offset.toByte(),
            (length ushr 24).toByte(), (length ushr 16).toByte(),
            (length ushr 8).toByte(), length.toByte()
        )
    )

    fun cancel(piece: Int, offset: Int, length: Int): ByteArray = message(
        MSG_CANCEL, request(piece, offset, length).copyOfRange(5, 17)
    )

    data class Message(val id: Int, val payload: ByteArray)

    /**
     * Разбор сообщения без длины: первым идёт [id].
     *
     * Пустое сообщение (только длина) — keep-alive, у него id = -1.
     */
    fun parseMessage(id: Int, payload: ByteArray): Message = Message(id, payload)

    /** «Не блокирую» — без него пир не начнёт отдавать куски. */
    fun unchoke(): ByteArray = message(MSG_UNCHOKE)

    /** Чанк с данными: номер куска, смещение, сами байты. */
    fun piece(index: Int, offset: Int, data: ByteArray): ByteArray {
        val payload = ByteArray(8 + data.size)
        payload[0] = (index ushr 24).toByte()
        payload[1] = (index ushr 16).toByte()
        payload[2] = (index ushr 8).toByte()
        payload[3] = index.toByte()
        payload[4] = (offset ushr 24).toByte()
        payload[5] = (offset ushr 16).toByte()
        payload[6] = (offset ushr 8).toByte()
        payload[7] = offset.toByte()
        data.copyInto(payload, 8)
        return message(MSG_PIECE, payload)
    }

    /** Длина блока из запроса — третье поле после номера куска и смещения. */
    fun requestLength(payload: ByteArray): Int {
        if (payload.size < 12) return 0
        return ((payload[8].toInt() and 0xFF) shl 24) or
            ((payload[9].toInt() and 0xFF) shl 16) or
            ((payload[10].toInt() and 0xFF) shl 8) or (payload[11].toInt() and 0xFF)
    }

    fun pieceIndex(payload: ByteArray): Int =
        if (payload.size < 4) -1
        else ((payload[0].toInt() and 0xFF) shl 24) or ((payload[1].toInt() and 0xFF) shl 16) or
            ((payload[2].toInt() and 0xFF) shl 8) or (payload[3].toInt() and 0xFF)

    fun blockOffset(payload: ByteArray): Int =
        if (payload.size < 8) -1
        else ((payload[4].toInt() and 0xFF) shl 24) or ((payload[5].toInt() and 0xFF) shl 16) or
            ((payload[6].toInt() and 0xFF) shl 8) or (payload[7].toInt() and 0xFF)

    /** Блок данных из сообщения PIECE. */
    fun blockData(payload: ByteArray): ByteArray =
        if (payload.size <= 8) ByteArray(0) else payload.copyOfRange(8, payload.size)

    /** Какие куски есть у пира, по его bitfield. */
    fun piecesFromBitfield(payload: ByteArray, count: Int): BooleanArray {
        val have = BooleanArray(count)
        for (i in 0 until count) {
            val byte = payload.getOrNull(i / 8) ?: break
            val bit = 7 - (i % 8)
            have[i] = ((byte.toInt() shr bit) and 1) == 1
        }
        return have
    }

    /** Наш bitfield: какие куски есть у нас. */
    fun bitfield(have: BooleanArray): ByteArray {
        val payload = ByteArray((have.size + 7) / 8)
        have.forEachIndexed { index, present ->
            if (present) {
                val byte = index / 8
                payload[byte] = (payload[byte].toInt() or (1 shl (7 - index % 8))).toByte()
            }
        }
        return message(MSG_BITFIELD, payload)
    }

    // --- BEP 10: расширения ------------------------------------------------

    /** Наше расширенное рукопожатие: объявляем поддержку метаданных (BEP 9). */
    fun extensionHandshake(): ByteArray {
        val dict = BValue.Dict(
            listOf(
                "m" to BValue.Dict(listOf("ut_metadata" to BValue.Num(1)))
            )
        )
        val body = TorrentBencode.encode(dict)
        val payload = ByteArray(1 + body.size)
        payload[0] = 0 // extended message id 0 = handshake
        body.copyInto(payload, 1)
        return message(MSG_EXTENDED, payload)
    }

    data class ExtensionHandshake(
        val utMetadataId: Int,
        val metadataSize: Int
    )

    fun parseExtensionHandshake(payload: ByteArray): ExtensionHandshake? {
        if (payload.size < 2 || payload[0].toInt() != 0) return null
        val dict = TorrentBencode.decode(payload.copyOfRange(1, payload.size))?.asDictOrNull()
            ?: return null
        val id = dict["m"]?.asDictOrNull()?.get("ut_metadata")?.asLongOrNull()?.toInt() ?: 0
        val size = dict["metadata_size"]?.asLongOrNull()?.toInt() ?: 0
        return ExtensionHandshake(id, size)
    }

    /** Запрос куска метаданных (0 — пожалуйста, пришли). */
    fun metadataRequest(theirId: Int, piece: Int): ByteArray {
        val body = TorrentBencode.encode(
            BValue.Dict(
                listOf(
                    "msg_type" to BValue.Num(0),
                    "piece" to BValue.Num(piece.toLong())
                )
            )
        )
        val payload = ByteArray(1 + body.size)
        payload[0] = theirId.toByte()
        body.copyInto(payload, 1)
        return message(MSG_EXTENDED, payload)
    }

    data class MetadataPiece(val piece: Int, val totalSize: Int, val data: ByteArray)

    /**
     * Ответ с куском метаданных.
     *
     * Данные лежат СРАЗУ ЗА bencode-словарём, без длины: поэтому здесь
     * важно знать, где словарь кончился. Обрезать «до конца сообщения»
     * значило бы приклеить к куску чужие байты и получить неверный хеш.
     */
    fun parseMetadataPiece(payload: ByteArray): MetadataPiece? {
        if (payload.size < 2) return null
        val (node, _) = TorrentBencode.decodeNode(payload, 1) ?: return null
        val dict = node.value.asDictOrNull() ?: return null
        val type = dict["msg_type"]?.asLongOrNull() ?: return null
        if (type != 1L) return null
        val piece = dict["piece"]?.asLongOrNull()?.toInt() ?: return null
        val total = dict["total_size"]?.asLongOrNull()?.toInt() ?: 0
        val data = payload.copyOfRange(node.end, payload.size)
        return MetadataPiece(piece, total, data)
    }
}

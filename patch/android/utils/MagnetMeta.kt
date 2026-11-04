// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest

/**
 * Magnet-ссылка: в ней нет списка файлов, только отпечаток.
 *
 * ЧТО ПРОИСХОДИТ ПРИ ОТКРЫТИИ
 *   1. Спрашиваем трекеры из самой ссылки — кто отдаёт этот торрент.
 *   2. Стучимся пиру, здороваемся, объявляем расширения (BEP 10).
 *   3. Если пир умеет `ut_metadata` (BEP 9), просим у него словарь `info` —
 *      тот самый, из которого и посчитан отпечаток.
 *   4. Склеиваем присланные куски и СВЕРЯЕМ SHA-1 с отпечатком из ссылки.
 *      Не сошлось — данные выбрасываем: чужой или испорченный словарь
 *      показывать как «список файлов» нельзя.
 *
 * ЧЕГО НЕТ
 *   DHT: если в magnet нет ни одного трекера, пиров взять неоткуда. Тогда
 *   честный ответ «в ссылке нет трекеров, добавьте трекер», а не вечная
 *   загрузка.
 */
object MagnetMeta {

    /**
     * Достать список файлов из magnet-ссылки.
     *
     * [progress] — шаги текстом: экран показывает их по мере дела.
     */
    fun fetch(
        magnet: TorrentMeta.Magnet,
        progress: (String) -> Unit = {}
    ): Result<TorrentMeta.Meta> {
        val trackers = magnet.trackers
        if (trackers.isEmpty()) {
            return Result.failure(
                IllegalArgumentException(
                    "в ссылке нет ни одного трекера — без него не найти, у кого просить список файлов"
                )
            )
        }
        val peerId = newPeerId()
        progress("спрашиваю трекеры")
        val answer = TorrentAnnounce.peers(
            trackers = trackers,
            infoHash = magnet.infoHash,
            peerId = peerId,
            port = 6881,
            timeoutMillis = 9000
        )
        if (answer.peers.isEmpty()) {
            return Result.failure(
                IllegalArgumentException(
                    if (answer.failure != null) {
                        "трекеры не ответили: ${answer.failure}"
                    } else {
                        "трекеры ответили, но раздающих нет — включён ли торрент у кого-то"
                    }
                )
            )
        }

        val peers = answer.peers.shuffled().take(8)
        progress("пиров: ${peers.size}, прошу список файлов")
        var lastReason = ""
        for (peer in peers) {
            val info = try {
                infoFromPeer(peer, magnet, peerId, progress)
            } catch (e: Throwable) {
                // Пир закрыл соединение — пробуем следующего, а причину
                // запоминаем: если не выйдет ни с кем, её и покажем.
                lastReason = e.message ?: e.javaClass.simpleName
                null
            } ?: continue
            val parsed = TorrentMeta.fromInfoDict(info, trackers)
            if (parsed.isFailure) {
                lastReason = parsed.exceptionOrNull()?.message ?: "список файлов не разобран"
                continue
            }
            return Result.success(parsed.getOrThrow())
        }
        return Result.failure(
            IllegalArgumentException(
                if (lastReason.isBlank()) {
                    "ни один пир не отдал список файлов — часто это пир без поддержки обмена метаданными"
                } else {
                    "не удалось получить список файлов: $lastReason"
                }
            )
        )
    }

    /**
     * Одна попытка: рукопожатие, расширения, куски метаданных.
     *
     * Куски просим по одному и по порядку: у большинства пиров их немного
     * (2–40), зато параллельные запросы к одному пиру часть клиентов
     * игнорирует целиком.
     */
    private fun infoFromPeer(
        peer: TorrentTracker.Peer,
        magnet: TorrentMeta.Magnet,
        peerId: ByteArray,
        progress: (String) -> Unit
    ): ByteArray? {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(peer.host, peer.port), 6000)
            socket.soTimeout = 15_000
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            output.write(TorrentWire.handshake(magnet.infoHash, peerId))
            output.flush()

            val hello = ByteArray(TorrentWire.HANDSHAKE_LENGTH)
            if (!readFully(input, hello)) return null
            val their = TorrentWire.parseHandshake(hello) ?: return null
            if (!their.infoHash.contentEquals(magnet.infoHash)) return null

            // Объявляем свои расширения и bitfield (пустой — у нас ничего).
            output.write(TorrentWire.extensionHandshake())
            output.write(TorrentWire.bitfield(BooleanArray(0)))
            output.flush()

            var theirMetadataId = 0
            var assembled: ByteArray? = null
            var totalSize = 0
            var requested = 0
            val deadline = System.currentTimeMillis() + 25_000

            while (System.currentTimeMillis() < deadline) {
                val header = ByteArray(4)
                if (!readFully(input, header)) return null
                val length = ((header[0].toInt() and 0xFF) shl 24) or
                    ((header[1].toInt() and 0xFF) shl 16) or
                    ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
                if (length == 0) continue
                if (length < 0 || length > 4 * 1024 * 1024) return null
                val body = ByteArray(length)
                if (!readFully(input, body)) return null

                when (body[0].toInt() and 0xFF) {
                    TorrentWire.MSG_EXTENDED -> {
                        val extendedId = body[1].toInt() and 0xFF
                        val payload = body.copyOfRange(2, body.size)
                        if (extendedId == 0) {
                            val handshake = TorrentWire.parseExtensionHandshake(payload) ?: continue
                            theirMetadataId = handshake.utMetadataId
                            if (theirMetadataId == 0) {
                                return null // пир не умеет отдавать метаданные
                            }
                            output.write(TorrentWire.metadataRequest(theirMetadataId, 0))
                            output.flush()
                            requested = 1
                        } else {
                            val piece = TorrentWire.parseMetadataPiece(payload) ?: continue
                            if (piece.piece == 0) {
                                totalSize = piece.totalSize
                                if (totalSize <= 0 || totalSize > 8 * 1024 * 1024) return null
                                assembled = ByteArray(totalSize)
                                progress("список файлов: ${piece.totalSize} байт")
                            }
                            val target = assembled ?: continue
                            val offset = piece.piece * TorrentWire.METADATA_PIECE_SIZE
                            if (offset + piece.data.size > target.size) continue
                            piece.data.copyInto(target, offset)
                            requested++
                            if (requested * TorrentWire.METADATA_PIECE_SIZE < totalSize) {
                                output.write(TorrentWire.metadataRequest(theirMetadataId, requested))
                                output.flush()
                            } else {
                                val info = assembled ?: return null
                                val hash = MessageDigest.getInstance("SHA-1").digest(info)
                                if (!hash.contentEquals(magnet.infoHash)) {
                                    progress("пир прислал данные, не совпадающие с отпечатком — отброшено")
                                    return null
                                }
                                return info
                            }
                        }
                    }
                    TorrentWire.MSG_CHOKE, TorrentWire.MSG_UNCHOKE,
                    TorrentWire.MSG_HAVE, TorrentWire.MSG_BITFIELD -> Unit
                    else -> Unit
                }
            }
            return null
        }
    }

    fun newPeerId(): ByteArray = buildString {
        append("-SY")
        append("1000")
        append('-')
        while (length < 20) append(('a'..'z').random())
    }.take(20).toByteArray(Charsets.US_ASCII)

    private fun readFully(input: InputStream, buffer: ByteArray): Boolean {
        var read = 0
        while (read < buffer.size) {
            val n = input.read(buffer, read, buffer.size - read)
            if (n <= 0) return false
            read += n
        }
        return true
    }
}

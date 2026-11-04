// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import java.security.MessageDigest

/**
 * Что лежит внутри торрента.
 *
 * Один `.torrent` — это словарь `info` (имя, размеры кусков, хеши кусков,
 * список файлов), хеш от которого и есть тот самый info hash, плюс список
 * трекеров и служебные поля. Magnet-ссылка содержит только info hash и
 * трекеры: сам список файлов придётся добыть у пиров (см. TorrentSession).
 *
 * Здесь описано, как превратить эти байты в список файлов с размерами и
 * понять, какие куски нужны для какого файла. Это делается до сети, поэтому
 * проверяется тестом на хосте — в том числе на настоящем `.torrent` из
 * Debian, а не на придуманном.
 */
object TorrentMeta {

    /** Файл внутри торрента. [offset] — его место в общем потоке кусков. */
    data class FileEntry(
        val path: String,
        val length: Long,
        val offset: Long
    ) {
        val extension: String
            get() = path.substringAfterLast('.', "").lowercase()
    }

    data class Meta(
        val infoHash: ByteArray,
        val name: String,
        val pieceLength: Long,
        val pieceHashes: List<ByteArray>,
        val files: List<FileEntry>,
        val announce: List<String>,
        val isPrivate: Boolean,
        val createdAt: Long?
    ) {
        val totalLength: Long get() = files.sumOf { it.length }
        val pieceCount: Int get() = pieceHashes.size

        /**
         * Сколько будет весить выбор только некоторых файлов.
         *
         * Нужно, чтобы «скачать один эпизод» показывало его размер, а не
         * размер всего сезона.
         */
        fun sizeOf(indexes: Collection<Int>): Long =
            indexes.filter { it in files.indices }.sumOf { files[it].length }

        fun kindOf(file: FileEntry): String = TorrentPlan.kindOf(file.extension)

        /** Строка для списка: «файлов: 12 · 4,3 ГБ». */
        fun listing(): String =
            "$name · файлов: ${files.size} · ${TorrentPlan.humanBytes(totalLength)}"
    }

    data class Magnet(
        val infoHash: ByteArray,
        val name: String?,
        val trackers: List<String>
    )

    /**
     * Разобрать `.torrent`.
     *
     * Возвращает ошибку текстом, а не исключение: вызывающий — экран, и ему
     * нужно показать причину. Все проверки на месте: файл из интернета
     * бывает обрезанным наполовину, и «половина торрента» опаснее отказа.
     */
    fun parse(bytes: ByteArray): Result<Meta> {
        if (bytes.isEmpty()) return Result.failure(IllegalArgumentException("файл пустой"))
        val span = TorrentBencode.infoSpan(bytes)
            ?: return Result.failure(IllegalArgumentException("в файле нет словаря info — это не .torrent"))
        val infoBytes = bytes.copyOfRange(span.first, span.last + 1)
        val info = TorrentBencode.decode(infoBytes)?.asDictOrNull()
            ?: return Result.failure(IllegalArgumentException("словарь info не читается"))
        val top = TorrentBencode.decode(bytes)?.asDictOrNull()
            ?: return Result.failure(IllegalArgumentException("файл не читается как bencode"))

        val name = info["name.utf-8"]?.asTextOrNull()
            ?: info["name"]?.asTextOrNull()
            ?: return Result.failure(IllegalArgumentException("в торренте нет имени"))

        val pieceLength = info["piece length"]?.asLongOrNull() ?: 0L
        if (pieceLength <= 0 || pieceLength > 64L * 1024 * 1024) {
            return Result.failure(IllegalArgumentException("неправдоподобный размер куска: $pieceLength"))
        }
        val piecesRaw = info["pieces"]?.asBytesOrNull()
            ?: return Result.failure(IllegalArgumentException("в торренте нет хешей кусков"))
        if (piecesRaw.size % 20 != 0 || piecesRaw.isEmpty()) {
            return Result.failure(IllegalArgumentException("таблица хешей кусков повреждена"))
        }
        val hashes = ArrayList<ByteArray>(piecesRaw.size / 20)
        var at = 0
        while (at + 20 <= piecesRaw.size) {
            hashes.add(piecesRaw.copyOfRange(at, at + 20))
            at += 20
        }

        val files = ArrayList<FileEntry>()
        val fileList = info["files"]?.asListOrNull()
        if (fileList == null) {
            // Однофайловый торрент: имя файла — это name торрента.
            val length = info["length"]?.asLongOrNull() ?: 0L
            if (length <= 0) return Result.failure(IllegalArgumentException("в торренте нет размера"))
            files.add(FileEntry(name, length, 0))
        } else {
            var offset = 0L
            for (item in fileList) {
                val dict = item.asDictOrNull() ?: continue
                val length = dict["length"]?.asLongOrNull() ?: continue
                if (length < 0) return Result.failure(IllegalArgumentException("отрицательный размер файла"))
                val parts = (dict["path.utf-8"] ?: dict["path"])?.asListOrNull()
                    ?.mapNotNull { it.asTextOrNull() }
                    ?: emptyList()
                if (parts.isEmpty()) continue
                files.add(FileEntry(parts.joinToString("/"), length, offset))
                offset += length
            }
            if (files.isEmpty()) {
                return Result.failure(IllegalArgumentException("в торренте нет файлов"))
            }
        }

        val expected = (files.sumOf { it.length } + pieceLength - 1) / pieceLength
        if (expected != hashes.size.toLong()) {
            return Result.failure(
                IllegalArgumentException(
                    "размеры файлов ($expected кусков) не сходятся с таблицей хешей (${hashes.size})"
                )
            )
        }

        val announce = ArrayList<String>()
        (top["announce-list"] ?: BValue.Lst(emptyList())).asListOrNull()
            ?.forEach { tier ->
                tier.asListOrNull()?.forEach { url ->
                    url.asTextOrNull()?.let { if (it !in announce) announce.add(it) }
                }
            }
        top["announce"]?.asTextOrNull()?.let { if (it !in announce) announce.add(0, it) }

        val sha1 = MessageDigest.getInstance("SHA-1").digest(infoBytes)
        return Result.success(
            Meta(
                infoHash = sha1,
                name = name,
                pieceLength = pieceLength,
                pieceHashes = hashes,
                files = files,
                announce = announce,
                isPrivate = (info["private"]?.asLongOrNull() ?: 0L) == 1L,
                createdAt = top["creation date"]?.asLongOrNull()
            )
        )
    }

    /**
     * Собрать [Meta] из ГОЛОГО словаря `info` — так его присылает пир по
     * BEP 9, без обёртки `.torrent`.
     *
     * Обёртка восстанавливается здесь же (`d4:info…e`), чтобы проверки
     * размеров, кусков и имён остались ровно теми же, что и для файла: два
     * разных разбора одного формата — два разных бага.
     */
    fun fromInfoDict(infoBytes: ByteArray, announce: List<String> = emptyList()): Result<Meta> {
        if (infoBytes.isEmpty()) return Result.failure(IllegalArgumentException("пустой словарь info"))
        val wrapped = "d4:info".toByteArray() + infoBytes + "e".toByteArray()
        return parse(wrapped).map { it.copy(announce = announce) }
    }

    /**
     * Разобрать `magnet:?xt=urn:btih:…`.
     *
     * btih бывает двумя видами: 40 шестнадцатеричных символов и 32 символа
     * base32. Второй встречается редко, но встречается, и «неизвестная
     * ссылка» на нём — обидный отказ.
     */
    fun parseMagnet(uri: String): Result<Magnet> {
        val trimmed = uri.trim()
        if (!trimmed.startsWith("magnet:", ignoreCase = true)) {
            return Result.failure(IllegalArgumentException("это не magnet-ссылка"))
        }
        val query = trimmed.substringAfter('?', "")
        val params = query.split('&').mapNotNull { part ->
            val eq = part.indexOf('=')
            if (eq <= 0) null else part.substring(0, eq).lowercase() to decode(part.substring(eq + 1))
        }
        val xt = params.filter { it.first == "xt" }.map { it.second }
        val hashText = xt.firstOrNull { it.startsWith("urn:btih:", ignoreCase = true) }
            ?.substring("urn:btih:".length)
            ?: return Result.failure(IllegalArgumentException("в ссылке нет info hash (urn:btih)"))
        val hash = when (hashText.length) {
            40 -> hexToBytes(hashText)
            32 -> base32ToBytes(hashText)
            else -> null
        } ?: return Result.failure(IllegalArgumentException("info hash в ссылке не читается"))
        val trackers = params.filter { it.first == "tr" }.map { it.second }.filter { it.isNotBlank() }
        val name = params.firstOrNull { it.first == "dn" }?.second
        return Result.success(Magnet(hash, name, trackers.distinct()))
    }

    private fun decode(value: String): String =
        runCatching { java.net.URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    private fun hexToBytes(text: String): ByteArray? {
        if (text.length % 2 != 0) return null
        val out = ByteArray(text.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(text[i * 2], 16)
            val lo = Character.digit(text[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    /** Base32 без выравнивающих символов, как в magnet-ссылках. */
    private fun base32ToBytes(text: String): ByteArray? {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        var buffer = 0
        var bits = 0
        val out = java.io.ByteArrayOutputStream()
        for (ch in text.uppercase()) {
            val value = alphabet.indexOf(ch)
            if (value < 0) return null
            buffer = (buffer shl 5) or value
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
            }
        }
        val bytes = out.toByteArray()
        return if (bytes.size == 20) bytes else null
    }

    fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }
}

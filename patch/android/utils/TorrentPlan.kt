// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import java.security.MessageDigest

/**
 * План скачивания: какие куски нужны, в каком порядке, и как проверить, что
 * кусок не испорчен.
 *
 * Зачем отдельным модулем:
 *
 *   * «показать файлы в торренте» и «скачать только этот файл» — одна и та
 *     же арифметика (кусок 4 МБ редко совпадает с границей файла), и
 *     ошибиться в ней легко: файл попросит кусок, лежащий наполовину в
 *     соседнем файле. Здесь это проверяется тестом на настоящем торренте;
 *   * «смотреть по частям» — это порядок кусков. Для видео нужен не первый
 *     кусок целиком, а куски под текущей позицией плеера, иначе фильм
 *     начинается через час.
 */
object TorrentPlan {

    /** Какие куски нужно скачать, чтобы получить файл целиком. */
    fun piecesForFile(meta: TorrentMeta.Meta, fileIndex: Int): IntRange {
        val file = meta.files.getOrNull(fileIndex) ?: return IntRange.EMPTY
        if (file.length == 0L) return IntRange.EMPTY
        val first = file.offset / meta.pieceLength
        val last = (file.offset + file.length - 1) / meta.pieceLength
        return first.toInt()..last.toInt()
    }

    /** Куски, покрывающие байтовый диапазон внутри файла. */
    fun piecesForRange(meta: TorrentMeta.Meta, fileIndex: Int, fromByte: Long, toByte: Long): IntRange {
        val file = meta.files.getOrNull(fileIndex) ?: return IntRange.EMPTY
        val from = (file.offset + fromByte).coerceIn(file.offset, file.offset + file.length)
        val to = (file.offset + toByte).coerceIn(0, file.offset + file.length)
        if (to <= from) return IntRange.EMPTY
        val first = from / meta.pieceLength
        val last = (to - 1) / meta.pieceLength
        return first.toInt()..last.toInt()
    }

    /**
     * Биты «нужно скачать» для выбранных файлов.
     *
     * Пустой выбор означает «всё»: кнопка «скачать» без галочек не должна
     * молча скачивать ничего.
     */
    fun wantedPieces(meta: TorrentMeta.Meta, selectedFiles: Collection<Int>): BooleanArray {
        val wanted = BooleanArray(meta.pieceCount)
        val indexes = if (selectedFiles.isEmpty()) meta.files.indices.toList() else selectedFiles
        indexes.forEach { index ->
            val range = piecesForFile(meta, index)
            for (piece in range) {
                if (piece in wanted.indices) wanted[piece] = true
            }
        }
        return wanted
    }

    /**
     * Порядок, в котором просить куски.
     *
     * [sequential] — качать по порядку (видео «по частям», архивы, образы).
     * [priority] — то, что нужно прямо сейчас (позиция плеера), идёт первым.
     * Остальное — по порядку в любом из режимов: это куда лучше случайного
     * порядка, если файл потом откроют до конца.
     */
    fun order(
        wanted: BooleanArray,
        sequential: Boolean = true,
        priority: IntRange = IntRange.EMPTY
    ): IntArray {
        val out = ArrayList<Int>(wanted.count { it })
        if (!priority.isEmpty()) {
            for (i in priority) if (i in wanted.indices && wanted[i]) out.add(i)
        }
        val already = out.toHashSet()
        if (sequential) {
            for (i in wanted.indices) {
                if (wanted[i] && i !in already) out.add(i)
            }
        } else {
            // «Вперемешку» полезно, когда пиров много и трекер отдаёт их
            // пачками: редкие куски находятся быстрее. Порядок при этом
            // всё равно детерминированный, чтобы тест был воспроизводим.
            val rest = wanted.indices.filter { wanted[it] && it !in already }
            var lo = 0
            var hi = rest.size - 1
            while (lo <= hi) {
                out.add(rest[lo])
                if (lo != hi) out.add(rest[hi])
                lo++
                hi--
            }
        }
        return out.toIntArray()
    }

    /** Длина куска: последний бывает короче. */
    fun pieceSize(meta: TorrentMeta.Meta, index: Int): Long {
        val remaining = meta.totalLength - index.toLong() * meta.pieceLength
        return if (remaining < meta.pieceLength) remaining else meta.pieceLength
    }

    /**
     * Совпадает ли скачанный кусок со своим хешем.
     *
     * Это и есть вся защита от порчи: торрент не доверяет ни пиру, ни диску,
     * он сверяет SHA-1. Без этой проверки «скачалось» ничего не значит.
     */
    fun verifyPiece(meta: TorrentMeta.Meta, index: Int, data: ByteArray): Boolean {
        val expected = meta.pieceHashes.getOrNull(index) ?: return false
        if (data.size.toLong() != pieceSize(meta, index)) return false
        val actual = MessageDigest.getInstance("SHA-1").digest(data)
        return actual.contentEquals(expected)
    }

    /** К какому виду относится файл — по расширению. */
    fun kindOf(extension: String): String = when (extension.lowercase()) {
        "mp4", "mkv", "avi", "mov", "webm", "m4v", "ts", "mpg", "mpeg", "flv", "wmv", "m2ts" -> "видео"
        "mp3", "flac", "ogg", "opus", "wav", "m4a", "aac", "wma", "ape" -> "аудио"
        "epub", "fb2", "pdf", "mobi", "azw3", "djvu", "cbz", "cbr", "txt", "rtf", "doc", "docx" -> "книга"
        "nsp", "xci", "nsz", "xcz", "nca", "cia", "3ds", "gba", "nds", "iso", "zip", "7z", "rar",
        "tar", "gz", "xz", "bin", "cue", "chd", "rvz", "wbfs" -> "игра"
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg" -> "картинка"
        "torrent" -> "торрент"
        else -> "файл"
    }

    /** «4,3 ГБ» — для списка файлов. Локаль не нужна: это число, а не текст. */
    fun humanBytes(bytes: Long): String {
        if (bytes < 0) return "?"
        if (bytes < 1024) return "$bytes Б"
        val units = listOf("КБ", "МБ", "ГБ", "ТБ")
        var value = bytes.toDouble() / 1024
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        val whole = value >= 100 || value == value.toLong().toDouble()
        return (if (whole) "%.0f".format(value) else "%.1f".format(value)) + " " + units[unit]
    }

    /** «12 мин 30 с» — остаток времени. */
    fun humanDuration(seconds: Long): String {
        if (seconds <= 0) return "—"
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return when {
            h > 0 -> "$h ч $m мин"
            m > 0 -> "$m мин $s с"
            else -> "$s с"
        }
    }
}

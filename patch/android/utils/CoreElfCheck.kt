// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later
package org.yuzu.yuzu_emu.utils

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Validate all loadable ELF64 ARM segments against the current page size. */
object CoreElfCheck {
    fun error(file: File, pageSize: Long): String? {
        if (pageSize <= 0 || pageSize > 65536 || pageSize and (pageSize - 1) != 0L)
            return "неизвестный размер страниц устройства"
        return try {
            RandomAccessFile(file, "r").use { f ->
                val header = ByteArray(64)
                f.readFully(header)
                val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                if (header[0] != 0x7f.toByte() || header[1] != 69.toByte() || header[2] != 76.toByte() ||
                    header[3] != 70.toByte() || header[4] != 2.toByte() || header[5] != 1.toByte() ||
                    header[6] != 1.toByte() || (b.getShort(16).toInt() and 0xffff) != 3 ||
                    (b.getShort(18).toInt() and 0xffff) != 183) return "ядро не является ARM64 ELF-библиотекой"
                val phoff = b.getLong(32)
                val entsize = b.getShort(54).toInt() and 0xffff
                val count = b.getShort(56).toInt() and 0xffff
                if (phoff < 64 || entsize !in 56..512 || count !in 1..256 ||
                    phoff > f.length() || entsize.toLong() * count > f.length() - phoff)
                    return "не удалось прочитать таблицу сегментов ядра"
                var loadCount = 0
                val bytes = ByteArray(56)
                for (i in 0 until count) {
                    f.seek(phoff + i.toLong() * entsize); f.readFully(bytes)
                    val p = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                    if (p.getInt(0) != 1) continue
                    loadCount++
                    val offset = p.getLong(8); val address = p.getLong(16)
                    val fileSize = p.getLong(32); val memSize = p.getLong(40); val align = p.getLong(48)
                    if (offset < 0 || address < 0 || fileSize < 0 || memSize < fileSize ||
                        offset > f.length() || fileSize > f.length() - offset || align < pageSize ||
                        align and (align - 1) != 0L || offset % pageSize != address % pageSize ||
                        offset % align != address % align)
                        return "сегмент ядра несовместим со страницами ${pageSize / 1024} КБ"
                }
                if (loadCount == 0) "в ядре нет загружаемых сегментов" else null
            }
        } catch (_: Exception) { "не удалось проверить ELF-файл ядра" }
    }
}

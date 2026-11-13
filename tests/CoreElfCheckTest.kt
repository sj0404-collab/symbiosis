// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later
import org.yuzu.yuzu_emu.utils.CoreElfCheck
import java.nio.ByteBuffer
import java.nio.ByteOrder

fun main() {
    val file = java.nio.file.Files.createTempFile("core-elf-", ".so").toFile()
    try {
        val a = ByteArray(65536)
        val b = ByteBuffer.wrap(a).order(ByteOrder.LITTLE_ENDIAN)
        a[0] = 127; a[1] = 69; a[2] = 76; a[3] = 70; a[4] = 2; a[5] = 1; a[6] = 1
        b.putShort(16, 3); b.putShort(18, 183); b.putLong(32, 64); b.putShort(54, 56); b.putShort(56, 2)
        fun segment(at: Int, offset: Long, address: Long, align: Long) {
            b.putInt(at, 1); b.putLong(at+8, offset); b.putLong(at+16, address)
            b.putLong(at+32, 1024); b.putLong(at+40, 1024); b.putLong(at+48, align)
        }
        segment(64, 0, 0, 16384); segment(120, 16384, 16384, 16384)
        file.writeBytes(a)
        check(CoreElfCheck.error(file, 4096) == null && CoreElfCheck.error(file, 16384) == null)
        segment(120, 16384, 16384, 4096); file.writeBytes(a)
        check(CoreElfCheck.error(file, 4096) == null && CoreElfCheck.error(file, 16384) != null)
        segment(120, 16384, 16385, 16384); file.writeBytes(a)
        check(CoreElfCheck.error(file, 16384) != null)
        b.putLong(32, Long.MAX_VALUE); file.writeBytes(a)
        check(CoreElfCheck.error(file, 4096) != null)
        check(CoreElfCheck.error(file, 0) != null)
        println("CoreElfCheck: all segments, architecture, bounds and 4K/16K pages verified")
    } finally { file.delete() }
}

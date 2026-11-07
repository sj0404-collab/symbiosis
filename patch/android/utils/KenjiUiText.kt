// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later
package org.yuzu.yuzu_emu.utils

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Exact standard UTF-8 for the planned native UI response path (not JNI's
 * modified UTF-8). NUL cannot be represented in the core's C string: reject it
 * rather than silently truncating the player's answer. */
object KenjiUiText {
    fun encode(text: String): ByteArray? {
        if ('\u0000' in text) return null
        return try {
            val buffer = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(text))
            ByteArray(buffer.remaining()).also { buffer.get(it) }
        } catch (_: CharacterCodingException) {
            null
        }
    }

    fun decode(bytes: ByteArray): String? {
        if (bytes.any { it.toInt() == 0 }) return null
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }

    // The reference Android UiHandler uses String.length (UTF-16 code units),
    // not byte count or Unicode scalar count. Invalid bounds are not guessed.
    fun withinBounds(text: String, min: Int, max: Int): Boolean =
        min >= 0 && max >= min && text.length in min..max
}

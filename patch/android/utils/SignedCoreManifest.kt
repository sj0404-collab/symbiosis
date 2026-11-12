// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later
package org.yuzu.yuzu_emu.utils

import java.io.File
import java.security.MessageDigest
import java.security.KeyFactory
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** Signature verification only: this does NOT install, fetch, or trust a core. */
object SignedCoreManifest {
    const val MAX_BYTES = 256L * 1024 * 1024
    const val MIN_BYTES = 1_000_000L
    private val digestPattern = Regex("[0-9a-f]{64}")
    private val integerPattern = Regex("[1-9][0-9]{0,18}")
    private val signaturePattern = Regex("[A-Za-z0-9+/]+={0,2}")

    data class Core(val sequence: Long, val bytes: Long, val sha256: String) {
        // The manifest never supplies an arbitrary URL or redirect target.
        val url: String get() = "https://github.com/sj0404-collab/symbiosis/releases/download/" +
            "engine-test-$sequence/libkenji-$sha256.so"
    }

    /** Checks bytes again before use; metadata alone never authenticates a file. */
    fun matches(core: Core, file: File): Boolean {
        if (!file.isFile || file.length() != core.bytes) return false
        return try {
            val hash = MessageDigest.getInstance("SHA-256")
            var total = 0L
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (n == 0) continue
                    total += n
                    if (total > core.bytes) return false
                    hash.update(buffer, 0, n)
                }
            }
            total == core.bytes && hash.digest().joinToString("") { "%02x".format(it) } == core.sha256
        } catch (_: Exception) { false }
    }

    /** null means reject; caller must still verify downloaded bytes and install safely. */
    fun verify(data: ByteArray, publicKeyDer: ByteArray, minimumSequence: Long): Core? {
        if (data.isEmpty() || data.size > 2048 || minimumSequence < 0) return null
        if (data.any { (it.toInt() and 0xff) !in 0x20..0x7e && it != 10.toByte() }) return null
        val text = String(data, Charsets.US_ASCII)
        val lines = text.split('\n')
        if (lines.size != 6 || lines[5] != "" || lines[0] != "SYMBIOSIS-KENJI-1") return null
        if (!lines[1].startsWith("sequence=") || !lines[2].startsWith("bytes=") ||
            !lines[3].startsWith("sha256=") || !lines[4].startsWith("signature=")) return null
        val sequenceText = lines[1].removePrefix("sequence=")
        val bytesText = lines[2].removePrefix("bytes=")
        val sha = lines[3].removePrefix("sha256=")
        val encoded = lines[4].removePrefix("signature=")
        if (!integerPattern.matches(sequenceText) || !integerPattern.matches(bytesText) ||
            !digestPattern.matches(sha) || !signaturePattern.matches(encoded)) return null
        val sequence = sequenceText.toLongOrNull() ?: return null
        val bytes = bytesText.toLongOrNull() ?: return null
        if (sequence < minimumSequence || bytes !in MIN_BYTES..MAX_BYTES) return null
        return try {
            val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(publicKeyDer)) as? RSAPublicKey
                ?: return null
            if (key.modulus.bitLength() !in 2048..4096) return null
            val signature = Base64.getDecoder().decode(encoded)
            if (Base64.getEncoder().encodeToString(signature) != encoded ||
                signature.size != (key.modulus.bitLength() + 7) / 8) return null
            val payload = lines.take(4).joinToString("\n", postfix = "\n").toByteArray(Charsets.US_ASCII)
            val verifier = Signature.getInstance("SHA256withRSA")
            verifier.initVerify(key)
            verifier.update(payload)
            if (!verifier.verify(signature)) null else Core(sequence, bytes, sha)
        } catch (_: Exception) { null }
    }
}

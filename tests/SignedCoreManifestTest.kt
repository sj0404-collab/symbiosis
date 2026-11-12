// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later
import org.yuzu.yuzu_emu.utils.SignedCoreManifest
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

fun main() {
    val generator = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
    val key = generator.generateKeyPair()
    val other = generator.generateKeyPair()
    val sha = "ab".repeat(32)
    fun make(k: KeyPair = key, seq: String = "7", bytes: String = "57321040", hash: String = sha): String {
        val payload = "SYMBIOSIS-KENJI-1\nsequence=$seq\nbytes=$bytes\nsha256=$hash\n"
        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(k.private)
        signer.update(payload.toByteArray(Charsets.US_ASCII))
        return payload + "signature=" + Base64.getEncoder().encodeToString(signer.sign()) + "\n"
    }
    fun verify(text: String, minimum: Long = 0) =
        SignedCoreManifest.verify(text.toByteArray(Charsets.UTF_8), key.public.encoded, minimum)
    val signed = make()
    val core = checkNotNull(verify(signed))
    check(core.sequence == 7L && core.bytes == 57321040L && core.sha256 == sha)
    check(core.url == "https://github.com/sj0404-collab/symbiosis/releases/download/engine-test-7/libkenji-$sha.so")
    check(verify(signed, 7) != null)
    check(verify(signed, 8) == null)
    check(verify(make(other)) == null)
    check(verify(signed.replace("sequence=7", "sequence=8")) == null)
    check(verify(signed.replace(sha, "cd".repeat(32))) == null)
    check(verify(signed + "url=https://attacker.invalid\n") == null)
    check(verify(signed.replace("\n", "\r\n")) == null)
    check(verify(signed.dropLast(1)) == null)
    check(verify(signed.replace("signature=", "signature=!!!!")) == null)
    check(verify(make(seq = "01")) == null)
    check(verify(make(seq = "9223372036854775808")) == null)
    check(verify(make(bytes = "999999")) == null)
    check(verify(make(bytes = "268435457")) == null)
    check(verify(make(hash = "AB".repeat(32))) == null)
    check(verify("x".repeat(2049)) == null)
    check(SignedCoreManifest.verify(signed.toByteArray(), byteArrayOf(1,2,3), 0) == null)
    check(SignedCoreManifest.verify(signed.toByteArray(), key.public.encoded, -1) == null)
    // End-to-end interoperability with the actual publisher tool, not just a
    // second in-memory implementation of the wire format.
    val temp = java.nio.file.Files.createTempDirectory("core-manifest-test-").toFile()
    try {
        val privatePem = temp.resolve("key.pem")
        val encodedKey = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(key.private.encoded)
        privatePem.writeText("-----BEGIN PRIVATE KEY-----\n$encodedKey\n-----END PRIVATE KEY-----\n")
        val file = temp.resolve("libkenji.so")
        file.writeBytes(ByteArray(1_000_000) { (it % 251).toByte() })
        val manifestFile = temp.resolve("manifest.txt")
        val process = ProcessBuilder("python3", "tools/sign_core_manifest.py", "--sequence", "13",
            "--core", file.absolutePath, "--private-key", privatePem.absolutePath,
            "--output", manifestFile.absolutePath).redirectErrorStream(true).start()
        val message = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "signing tool failed: $message" }
        val published = checkNotNull(SignedCoreManifest.verify(manifestFile.readBytes(), key.public.encoded, 7))
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }
        check(published.sequence == 13L && published.bytes == file.length() && published.sha256 == digest)
        check(SignedCoreManifest.matches(published, file))
        file.writeBytes(ByteArray(1_000_000))
        check(!SignedCoreManifest.matches(published, file))
        file.writeBytes(ByteArray(10))
        check(!SignedCoreManifest.matches(published, file))
        check(!SignedCoreManifest.matches(published, temp.resolve("missing")))
    } finally { temp.deleteRecursively() }
    println("SignedCoreManifest: signature, tampering, strict schema, limits, rollback and publisher interoperability passed")
}

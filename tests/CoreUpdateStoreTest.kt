// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later
import org.yuzu.yuzu_emu.utils.CoreUpdateStore
import org.yuzu.yuzu_emu.utils.GameSessionLock
import java.io.File
import java.io.InputStream
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64

fun main() {
    val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    val root = java.nio.file.Files.createTempDirectory("core-store-").toFile()
    try {
        fun image(marker: Byte): ByteArray = ByteArray(1_000_000) { marker }.apply {
            this[0] = 0x7f; this[1] = 69; this[2] = 76; this[3] = 70
            this[4] = 2; this[5] = 1; this[6] = 1
            this[16] = 3; this[17] = 0; this[18] = 183.toByte(); this[19] = 0
        }
        fun manifest(version: Int, data: ByteArray): ByteArray {
            val hash = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
            val body = "SYMBIOSIS-KENJI-1\nsequence=$version\nbytes=${data.size}\nsha256=$hash\n"
            val sig = Signature.getInstance("SHA256withRSA").apply { initSign(key.private); update(body.toByteArray()) }.sign()
            return (body + "signature=" + Base64.getEncoder().encodeToString(sig) + "\n").toByteArray()
        }
        val first = image(1); val second = image(2)
        val one = manifest(1, first); val two = manifest(2, second)
        fun install(m: ByteArray, data: ByteArray) = CoreUpdateStore.install(root, m, key.public.encoded, data.inputStream())
        check(install(one, first) is CoreUpdateStore.Outcome.Installed)
        check(CoreUpdateStore.active(root, key.public.encoded)?.core?.sequence == 1L)
        check(install(one, first) is CoreUpdateStore.Outcome.Rejected)
        val badElf = second.copyOf().apply { this[18] = 62 } // x86-64, even though correctly signed
        check(install(manifest(2, badElf), badElf) is CoreUpdateStore.Outcome.Rejected)
        check(install(two, second.copyOf(second.size - 1)) is CoreUpdateStore.Outcome.Rejected)
        check(install(two, second + byteArrayOf(1)) is CoreUpdateStore.Outcome.Rejected)
        check(install(two, first) is CoreUpdateStore.Outcome.Rejected)
        check(CoreUpdateStore.active(root, key.public.encoded)?.core?.sequence == 1L)
        val failing = object : InputStream() { override fun read(): Int = throw java.io.IOException("connection lost") }
        check(CoreUpdateStore.install(root, two, key.public.encoded, failing) is CoreUpdateStore.Outcome.Rejected)
        check(CoreUpdateStore.active(root, key.public.encoded)?.core?.sequence == 1L)
        val held = GameSessionLock.tryAcquire(File(root, GameSessionLock.FILE_NAME)) as GameSessionLock.Result.Acquired
        check(install(two, second) is CoreUpdateStore.Outcome.Rejected)
        held.lease.close()
        check(install(two, second) is CoreUpdateStore.Outcome.Installed)
        val active = checkNotNull(CoreUpdateStore.active(root, key.public.encoded))
        check(active.core.sequence == 2L)
        check(install(one, first) is CoreUpdateStore.Outcome.Rejected)
        active.file.setWritable(true, true)
        active.file.writeBytes(byteArrayOf(0))
        check(CoreUpdateStore.active(root, key.public.encoded) == null) // no silent rollback
        check(install(one, first) is CoreUpdateStore.Outcome.Rejected)
        check(install(two, second) is CoreUpdateStore.Outcome.Installed) // repair with identical signed bytes
        check(CoreUpdateStore.active(root, key.public.encoded)?.core?.sequence == 2L)
        val third = image(3)
        check(CoreUpdateStore.install(root, manifest(3, third), key.public.encoded, third.inputStream()) { "несовместимый ELF" } is CoreUpdateStore.Outcome.Rejected)
        check(CoreUpdateStore.active(root, key.public.encoded)?.core?.sequence == 2L)
        check(CoreUpdateStore.remove(root, key.public.encoded, root.resolve("legacy.so")))
        check(CoreUpdateStore.hasSignedVersion(root, key.public.encoded))
        check(CoreUpdateStore.active(root, key.public.encoded) == null)
        check(install(one, first) is CoreUpdateStore.Outcome.Rejected)
        check(install(two, second) is CoreUpdateStore.Outcome.Installed)
        check(root.resolve("engines/core-test-updates").listFiles()!!.none { it.name.startsWith(".part-") })
        println("CoreUpdateStore: atomic staging, digest, interruption, lock, rollback and corruption checks passed")
    } finally { root.deleteRecursively() }
}

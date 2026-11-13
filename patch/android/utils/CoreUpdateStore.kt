// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later
package org.yuzu.yuzu_emu.utils

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID

/**
 * Test-channel storage primitive, not wired into EngineLoader yet.
 * Both installation and a game session acquire the same stable OS lock. Core
 * directories are immutable after rename; no existing file is overwritten.
 */
object CoreUpdateStore {
    sealed class Outcome {
        data class Installed(val sequence: Long, val file: File) : Outcome()
        data class Rejected(val reason: String) : Outcome()
    }
    data class Active(val core: SignedCoreManifest.Core, val file: File)

    private fun updates(root: File) = File(root, "engines/core-test-updates")

    /** Highest verified metadata, even if its core is damaged (fail closed). */
    private fun highest(root: File, key: ByteArray): Pair<SignedCoreManifest.Core, File>? {
        val directories = updates(root).listFiles() ?: return null
        return directories.asSequence().filter { it.isDirectory && !it.name.startsWith(".") }
            .mapNotNull { dir ->
                val m = File(dir, "manifest.txt")
                if (!m.isFile || m.length() !in 1..2048) return@mapNotNull null
                val parsed = runCatching { SignedCoreManifest.verify(m.readBytes(), key, 0) }.getOrNull()
                    ?: return@mapNotNull null
                if (dir.name != "${parsed.sequence}-${parsed.sha256}") return@mapNotNull null
                parsed to File(dir, "libkenji.so")
            }.maxByOrNull { it.first.sequence }
    }

    fun hasSignedVersion(root: File, key: ByteArray): Boolean = highest(root, key) != null

    /** No silent rollback to an older version if the newest file is damaged. */
    fun active(root: File, key: ByteArray): Active? {
        val (core, file) = highest(root, key) ?: return null
        return if (SignedCoreManifest.matches(core, file)) Active(core, file) else null
    }

    /** Remove binaries but retain signed version records as an anti-rollback watermark. */
    fun remove(root: File, key: ByteArray, legacy: File): Boolean {
        val lock = when (val acquired = GameSessionLock.tryAcquire(File(root, GameSessionLock.FILE_NAME))) {
            is GameSessionLock.Result.Acquired -> acquired.lease
            else -> return false
        }
        lock.use {
            var ok = !legacy.exists() || legacy.delete()
            for (dir in updates(root).listFiles().orEmpty()) {
                val manifest = File(dir, "manifest.txt")
                if (!dir.isDirectory || dir.name.startsWith(".") || !manifest.isFile || manifest.length() !in 1..2048) continue
                val core = runCatching { SignedCoreManifest.verify(manifest.readBytes(), key, 0) }.getOrNull() ?: continue
                if (dir.name != "${core.sequence}-${core.sha256}") continue
                val file = File(dir, "libkenji.so")
                if (file.exists() && !file.delete()) ok = false
            }
            return ok
        }
    }

    /** Caller supplies an already-open byte stream; network policy is separate. */
    fun install(root: File, signedManifest: ByteArray, key: ByteArray, source: InputStream,
                validate: (File) -> String? = { null }): Outcome {
        val candidate = SignedCoreManifest.verify(signedManifest, key, 0)
            ?: return Outcome.Rejected("подпись или описание ядра не прошли проверку")
        val lock = when (val acquired = GameSessionLock.tryAcquire(File(root, GameSessionLock.FILE_NAME))) {
            is GameSessionLock.Result.Acquired -> acquired.lease
            is GameSessionLock.Result.Busy -> return Outcome.Rejected("закройте игру и проверку ядра перед обновлением")
            is GameSessionLock.Result.Failed -> return Outcome.Rejected("не удалось заблокировать запуск игры")
        }
        lock.use {
            val existing = highest(root, key)
            if (existing != null && candidate.sequence < existing.first.sequence) {
                return Outcome.Rejected("версия старее установленной")
            }
            if (existing != null && candidate.sequence == existing.first.sequence && candidate != existing.first) {
                return Outcome.Rejected("номер версии уже принадлежит другому ядру")
            }
            if (existing != null && candidate == existing.first && SignedCoreManifest.matches(candidate, existing.second)) {
                return Outcome.Rejected("эта версия ядра уже установлена")
            }
            val parent = updates(root)
            if (!parent.isDirectory && !parent.mkdirs()) return Outcome.Rejected("не удалось создать папку обновления")
            val stage = File(parent, ".part-${UUID.randomUUID()}")
            if (!stage.mkdir()) return Outcome.Rejected("не удалось подготовить временную папку")
            try {
                val file = File(stage, "libkenji.so")
                FileOutputStream(file).use { out ->
                    // Android requires downloaded executable code to be read-only.
                    // Set mode after opening the descriptor and before writing.
                    if (!file.setReadOnly()) return Outcome.Rejected("не удалось защитить файл ядра от записи")
                    val buffer = ByteArray(64 * 1024)
                    var count = 0L
                    while (true) {
                        val n = source.read(buffer)
                        if (n < 0) break
                        if (n == 0) continue
                        count += n
                        if (count > candidate.bytes) return Outcome.Rejected("размер ядра больше указанного")
                        out.write(buffer, 0, n)
                    }
                    out.fd.sync()
                }
                if (!SignedCoreManifest.matches(candidate, file)) return Outcome.Rejected("размер или SHA-256 ядра не совпали")
                val header = ByteArray(20)
                file.inputStream().use { input ->
                    var n = 0
                    while (n < header.size) {
                        val read = input.read(header, n, header.size - n)
                        if (read <= 0) break
                        n += read
                    }
                    if (n != header.size) return Outcome.Rejected("заголовок ядра обрезан")
                }
                if (header[0] != 0x7f.toByte() || header[1] != 'E'.code.toByte() ||
                    header[2] != 'L'.code.toByte() || header[3] != 'F'.code.toByte() ||
                    header[4] != 2.toByte() || header[5] != 1.toByte() || header[6] != 1.toByte() ||
                    header[16] != 3.toByte() || header[17] != 0.toByte() ||
                    header[18] != 183.toByte() || header[19] != 0.toByte()) {
                    return Outcome.Rejected("файл не является ARM64 ELF-библиотекой")
                }
                val problem = validate(file)
                if (problem != null) return Outcome.Rejected(problem)
                if (existing != null && candidate == existing.first) {
                    // Repair same signed version without dropping its signed high-water mark.
                    if (!file.renameTo(existing.second)) return Outcome.Rejected("не удалось восстановить ядро")
                    return Outcome.Installed(candidate.sequence, existing.second)
                }
                FileOutputStream(File(stage, "manifest.txt")).use { out ->
                    out.write(signedManifest)
                    out.fd.sync()
                }
                val dest = File(parent, "${candidate.sequence}-${candidate.sha256}")
                if (dest.exists() || !stage.renameTo(dest)) return Outcome.Rejected("не удалось сохранить новую версию")
                return Outcome.Installed(candidate.sequence, File(dest, "libkenji.so"))
            } catch (_: Exception) {
                return Outcome.Rejected("загрузка прервана или не удалось записать ядро")
            } finally {
                // On success stage has been renamed and does not exist.
                stage.deleteRecursively()
            }
        }
    }
}

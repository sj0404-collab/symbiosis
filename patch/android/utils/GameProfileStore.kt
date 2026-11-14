// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later
package org.yuzu.yuzu_emu.utils

import java.io.File
import java.io.RandomAccessFile

/** Per-game Kenji overrides, private files shared across Android processes.
 * No filename-only fallback: different games must never share a profile by accident. */
object GameProfileStore {
    private const val HEADER = "KENJI_GAME_PROFILE_1"
    private const val LIMIT = 8192L

    private fun profile(root: File, path: String): File? {
        if (path.isEmpty()) return null
        val key = java.security.MessageDigest.getInstance("SHA-256")
            .digest(path.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return File(File(root, "kenji-game-profiles"), "$key.txt")
    }

    private fun valid(id: String, value: String): Boolean =
        EngineHybrid.FIELDS.firstOrNull { it.id == id }?.choices?.any { it.value == value } == true &&
            EngineHybrid.apply(EngineHybrid.KenjiConfig(), id, value) != null

    /** Null means unreadable/corrupt, not a request to overwrite old settings. */
    private fun load(file: File): Map<String, String>? {
        if (!file.exists()) return emptyMap()
        if (!file.isFile || file.length() !in 1..LIMIT) return null
        return runCatching {
            val rows = file.readLines()
            if (rows.firstOrNull() != HEADER) return@runCatching null
            val result = LinkedHashMap<String, String>()
            for (line in rows.drop(1)) {
                if (line.isEmpty()) continue
                val at = line.indexOf('=')
                if (at <= 0) return@runCatching null
                val id = line.substring(0, at)
                val value = line.substring(at + 1)
                if (!valid(id, value) || result.put(id, value) != null) return@runCatching null
            }
            result
        }.getOrNull()
    }

    fun read(root: File, path: String): Map<String, String>? =
        profile(root, path)?.let(::load)

    fun merge(base: EngineHybrid.KenjiConfig, values: Map<String, String>): EngineHybrid.KenjiConfig {
        var result = base
        values.forEach { (id, value) ->
            if (valid(id, value)) result = EngineHybrid.apply(result, id, value) ?: result
        }
        return result
    }

    /** A null value removes just this override and restores inheritance. */
    @Synchronized
    fun set(root: File, path: String, id: String, value: String?): Boolean {
        if (EngineHybrid.FIELDS.none { it.id == id } || (value != null && !valid(id, value))) return false
        return update(root, path) { old -> if (value == null) old.remove(id) else old[id] = value }
    }

    @Synchronized
    fun reset(root: File, path: String): Boolean = update(root, path) { it.clear() }

    private fun update(root: File, path: String, change: (MutableMap<String, String>) -> Unit): Boolean {
        val file = profile(root, path) ?: return false
        return runCatching {
            val dir = file.parentFile ?: return@runCatching false
            if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) return@runCatching false
            // Keep a stable lock inode. Serialises writers from main and :kenji.
            RandomAccessFile(File(dir, file.name + ".lock"), "rw").use { lockFile ->
                lockFile.channel.lock().use {
                    val old = load(file) ?: return@runCatching false
                    val values = old.toMutableMap()
                    change(values)
                    val data = HEADER + "\n" + values.toSortedMap().entries.joinToString("") { "${it.key}=${it.value}\n" }
                    if (data.toByteArray(Charsets.UTF_8).size > LIMIT) return@runCatching false
                    val temp = File.createTempFile("profile-", ".tmp", dir)
                    try {
                        temp.outputStream().use { stream ->
                            stream.write(data.toByteArray(Charsets.UTF_8))
                            stream.fd.sync()
                        }
                        temp.renameTo(file)
                    } finally { temp.delete() }
                }
            }
        }.getOrDefault(false)
    }
}

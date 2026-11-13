// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later
package org.yuzu.yuzu_emu.utils

import android.content.Context
import java.io.File
import java.security.MessageDigest

object CoreBenchmarkStore {
    /** Invalidate on APK replacement or test-core file/version changes. */
    fun generation(context: Context): String? = runCatching {
        val installed = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        if (installed <= 0) return@runCatching null
        val root = context.filesDir
        val legacy = File(root, "engines/libkenji.so")
        val updates = File(root, "engines/core-test-updates").listFiles().orEmpty()
            .filter { it.isDirectory && !it.name.startsWith(".") }
            .sortedBy { it.name }
            .joinToString("|") { d ->
                val f = File(d, "libkenji.so")
                "${d.name}:${f.length()}:${f.lastModified()}"
            }
        val raw = "$installed:${legacy.length()}:${legacy.lastModified()}:$updates"
        MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
    }.getOrNull()

    data class Comparison(val eden: CoreBenchmark.Sample?, val kenji: CoreBenchmark.Sample?, val choice: EngineHybrid.Core?)
    fun compare(context: Context, path: String, default: EngineHybrid.Core): Comparison {
        val gen = generation(context) ?: return Comparison(null, null, null)
        val now = System.currentTimeMillis()
        val eden = CoreBenchmark.read(context.filesDir, path, EngineHybrid.Core.EDEN, gen, now)
        val kenji = CoreBenchmark.read(context.filesDir, path, EngineHybrid.Core.KENJI, gen, now)
        return Comparison(eden, kenji, CoreBenchmark.choose(eden, kenji, default))
    }
}

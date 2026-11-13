// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later
package org.yuzu.yuzu_emu.utils

import java.io.File
import java.util.Locale

/** Passive FPS estimates from actual user sessions; never launches games itself. */
object CoreBenchmark {
    const val WARMUP_MS = 30_000L
    const val MIN_SAMPLES = 10
    const val MAX_AGE_MS = 14L * 24 * 60 * 60 * 1000
    data class Sample(val fps: Double, val count: Int, val time: Long, val generation: String)

    class Recorder(private val generation: String) {
        private var first = -1L
        private var last = -1L
        private var sum = 0.0
        private var count = 0

        /** A pause, lost focus, loading gap or invalid reading starts a new window. */
        fun reset() {
            first = -1L
            last = -1L
            sum = 0.0
            count = 0
        }

        fun observe(elapsedMs: Long, wallMs: Long, fps: Double): Sample? {
            if (elapsedMs < 0 || wallMs <= 0 || !fps.isFinite() || fps <= 0 || fps > 240) {
                reset()
                return null
            }
            // Sampling normally occurs every 3 seconds. Do not count a long
            // suspension or a non-monotonic clock as active play.
            if (last >= 0 && (elapsedMs < last || elapsedMs - last > 9_000L)) reset()
            if (first < 0) first = elapsedMs
            last = elapsedMs
            if (elapsedMs - first < WARMUP_MS) return null
            sum += fps
            count++
            if (count < MIN_SAMPLES) return null
            val result = Sample(sum / count, count, wallMs, generation)
            sum = 0.0
            count = 0
            return result
        }
    }

    private fun file(root: File, path: String, core: EngineHybrid.Core): File? {
        val key = EngineHybrid.gameKey(path)
        if (key.isEmpty()) return null
        return File(File(root, "core-benchmark"), "$key-${core.id}.txt")
    }

    /** One file per game/core; temp+rename prevents readers observing half a record. */
    fun save(root: File, path: String, core: EngineHybrid.Core, sample: Sample): Boolean = runCatching {
        val dest = file(root, path, core) ?: return@runCatching false
        if (!dest.parentFile.isDirectory && !dest.parentFile.mkdirs() && !dest.parentFile.isDirectory) return@runCatching false
        val temp = File.createTempFile("bench-", ".tmp", dest.parentFile)
        try {
            temp.writeText("1\n${sample.fps}\n${sample.count}\n${sample.time}\n${sample.generation}\n")
            temp.renameTo(dest)
        } finally { temp.delete() }
    }.getOrDefault(false)

    fun read(root: File, path: String, core: EngineHybrid.Core, generation: String, now: Long): Sample? = runCatching {
        val f = file(root, path, core) ?: return@runCatching null
        if (!f.isFile || f.length() !in 1..1024) return@runCatching null
        val rows = f.readLines()
        if (rows.size != 5 || rows[0] != "1" || rows[4] != generation) return@runCatching null
        val fps = rows[1].toDoubleOrNull() ?: return@runCatching null
        val count = rows[2].toIntOrNull() ?: return@runCatching null
        val time = rows[3].toLongOrNull() ?: return@runCatching null
        if (!fps.isFinite() || fps <= 0 || fps > 240 || count < MIN_SAMPLES || count > 10000 ||
            time <= 0 || now < time || now - time > MAX_AGE_MS) return@runCatching null
        Sample(fps, count, time, generation)
    }.getOrNull()

    /** Both real measurements required; close values use the chosen default. */
    fun choose(eden: Sample?, kenji: Sample?, default: EngineHybrid.Core): EngineHybrid.Core? {
        if (eden == null || kenji == null) return null
        return when {
            kenji.fps > eden.fps * 1.05 -> EngineHybrid.Core.KENJI
            eden.fps > kenji.fps * 1.05 -> EngineHybrid.Core.EDEN
            else -> default
        }
    }
    fun label(sample: Sample?): String = sample?.let {
        String.format(Locale.ROOT, "%.1f FPS (%d измерений)", it.fps, it.count)
    } ?: "нет достаточных данных"
}

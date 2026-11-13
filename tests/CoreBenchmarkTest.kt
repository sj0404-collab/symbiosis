// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later
import java.io.File
import org.yuzu.yuzu_emu.utils.CoreBenchmark as B
import org.yuzu.yuzu_emu.utils.EngineHybrid.Core

fun main() {
    val root = createTempDir(prefix = "core-bench-test-")
    try {
        val gen = "generation-1"
        val clock = 2_000_000_000L
        val rec = B.Recorder(gen)
        check(rec.observe(0, clock, 60.0) == null)
        check(rec.observe(10_000, clock, 30.0) == null)
        check(rec.observe(30_000, clock, Double.NaN) == null)
        check(rec.observe(30_000, clock, 0.0) == null)
        for (i in 0 until 9) check(rec.observe(30_000L + i * 3000L, clock + i, 30.0) == null)
        val e = checkNotNull(rec.observe(57_000, clock + 9, 30.0))
        check(e.count == 10 && e.fps == 30.0)
        check(B.save(root, "/game/a.nsp", Core.EDEN, e))
        check(B.read(root, "/game/a.nsp", Core.KENJI, gen, clock + 10) == null)
        check(B.read(root, "/game/b.nsp", Core.EDEN, gen, clock + 10) == null)
        check(B.read(root, "/game/a.nsp", Core.EDEN, "other", clock + 10) == null)
        check(B.read(root, "/game/a.nsp", Core.EDEN, gen, clock - 1) == null)
        check(B.read(root, "/game/a.nsp", Core.EDEN, gen, clock + B.MAX_AGE_MS + 100) == null)
        check(B.choose(e, null, Core.EDEN) == null)
        val k = e.copy(fps = 45.0)
        check(B.save(root, "/game/a.nsp", Core.KENJI, k))
        val readE = B.read(root, "/game/a.nsp", Core.EDEN, gen, clock + 100)
        val readK = B.read(root, "/game/a.nsp", Core.KENJI, gen, clock + 100)
        check(B.choose(readE, readK, Core.EDEN) == Core.KENJI)
        check(B.choose(e.copy(fps = 60.0), k, Core.KENJI) == Core.EDEN)
        check(B.choose(e, e.copy(fps = 31.0), Core.KENJI) == Core.KENJI)
        check(B.choose(e, e.copy(fps = 31.0), Core.EDEN) == Core.EDEN)
        root.walk().first { it.name.endsWith("-eden.txt") }.writeText("1\nNaN\n100\n$clock\n$gen\n")
        check(B.read(root, "/game/a.nsp", Core.EDEN, gen, clock + 100) == null)
        println("CoreBenchmark: samples, isolation, staleness and choice verified")
    } finally { root.deleteRecursively() }
}

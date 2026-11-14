// SPDX-License-Identifier: GPL-3.0-or-later
import java.io.File
import org.yuzu.yuzu_emu.utils.EngineHybrid
import org.yuzu.yuzu_emu.utils.GameProfileStore

fun main() {
    val root = kotlin.io.path.createTempDirectory("kenji-profiles").toFile()
    try {
        val a = "content://source/a/same.nsp"
        val b = "content://source/b/same.nsp"
        check(GameProfileStore.read(root, a) == emptyMap<String, String>())
        check(GameProfileStore.set(root, a, "memory", "1"))
        check(GameProfileStore.set(root, a, "nce", "true"))
        check(GameProfileStore.set(root, b, "memory", "2"))
        check(GameProfileStore.read(root, a)?.get("memory") == "1")
        check(GameProfileStore.read(root, b)?.get("memory") == "2")
        val base = EngineHybrid.KenjiConfig(memoryManagerMode = 0, useNce = false)
        val actual = GameProfileStore.merge(base, GameProfileStore.read(root, a)!!)
        check(actual.memoryManagerMode == 1 && actual.useNce)
        check(GameProfileStore.set(root, a, "memory", null))
        check(GameProfileStore.merge(base, GameProfileStore.read(root, a)!!).memoryManagerMode == 0)
        check(!GameProfileStore.set(root, a, "memory", "3"))
        check(!GameProfileStore.set(root, a, "threads", "4"))
        check(!GameProfileStore.set(root, "", "nce", "true"))
        for (id in listOf("threads", "lowPowerPtc", "jitEviction", "textureRecompression", "fast2DCopy", "macroJit")) {
            val field = EngineHybrid.FIELDS.first { it.id == id }
            for (choice in field.choices) {
                val cfg = EngineHybrid.apply(base, id, choice.value)!!
                check(EngineHybrid.effective(cfg)[id] == choice.value) { "$id ${choice.value}" }
            }
        }
        val profileFile = File(root, "kenji-game-profiles").listFiles()!!.first { it.name.endsWith(".txt") && it.readText().contains("memory=2") }
        profileFile.writeText("bad data")
        check(GameProfileStore.read(root, b) == null)
        check(!GameProfileStore.set(root, b, "nce", "true"))
        check(profileFile.readText() == "bad data")
        println("GameProfileStore: isolation, precedence, validation and corruption passed")
    } finally { root.deleteRecursively() }
}

// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * Проверки палитры. Запускаются на хосте, без Android.
 *
 * Почему это вообще тест: цвет здесь вычисляется из данных, а не лежит в
 * ресурсах. Значит у него есть те же свойства, что у любой другой логики —
 * он может «поплыть» между запусками, слипнуться у соседних игр или дать
 * нечитаемый текст. Всё это видно без телефона.
 */

import org.yuzu.yuzu_emu.utils.SymbiosisTheme

private var passed = 0
private val failures = mutableListOf<String>()

private fun check(what: String, ok: Boolean, detail: String = "") {
    if (ok) {
        passed++
    } else {
        failures.add("$what${if (detail.isEmpty()) "" else " → $detail"}")
    }
}

private fun <T> eq(what: String, got: T, want: T) =
    check(what, got == want, "получено [$got], ожидалось [$want]")

private fun hex(c: Int) = "#%08X".format(c)

private fun contrast(a: Int, b: Int): Float {
    val la = SymbiosisTheme.luminance(a)
    val lb = SymbiosisTheme.luminance(b)
    val hi = maxOf(la, lb)
    val lo = minOf(la, lb)
    return (hi + 0.05f) / (lo + 0.05f)
}

fun main() {
    // ── 1. хеш ─────────────────────────────────────────────────────────
    eq("хеш устойчив", SymbiosisTheme.stableHash("010093801237C000"), 0.let {
        SymbiosisTheme.stableHash("010093801237C000")
    })
    check(
        "хеш неотрицателен",
        listOf("", "a", "010093801237C000", "Ω-тест").all { SymbiosisTheme.stableHash(it) >= 0 }
    )
    check(
        "разные ключи — разные хеши",
        SymbiosisTheme.stableHash("Splatoon 3") != SymbiosisTheme.stableHash("Splatoon 2")
    )

    // ── 2. цвет игры ───────────────────────────────────────────────────
    val titles = listOf(
        "010093801237C000", "0100F2C0115B6000", "0100C2500FC20000",
        "010074F013262000", "0100152000022000", "01006A800016E000",
        "0100E95004038000", "010016A00AEC0000", "010015100B514000"
    )
    val accents = titles.map { SymbiosisTheme.accent(it) }
    check("цвет игры стабилен", accents == titles.map { SymbiosisTheme.accent(it) })
    check(
        "у девяти игр девять разных цветов",
        accents.toSet().size == titles.size,
        "${accents.toSet().size} из ${titles.size}"
    )
    check("цвет игры непрозрачный", accents.all { (it ushr 24) == 0xFF })
    check(
        "соседние Title ID не слипаются",
        run {
            val a = SymbiosisTheme.accent("0100000000010000")
            val b = SymbiosisTheme.accent("0100000000010001")
            a != b
        }
    )

    // ── 3. читаемость ──────────────────────────────────────────────────
    val inkProblems = accents.mapNotNull { bg ->
        val ink = SymbiosisTheme.inkOn(bg)
        val c = contrast(bg, ink)
        if (c < 4.2f) "${hex(bg)} на ${hex(ink)} — ${"%.2f".format(c)}:1" else null
    }
    check("текст на акценте читается (≥4.2:1)", inkProblems.isEmpty(), inkProblems.joinToString("; "))
    check("на белом — тёмный текст", SymbiosisTheme.inkOn(0xFFFFFFFF.toInt()) == SymbiosisTheme.BASE)
    check("на чёрном — светлый текст", SymbiosisTheme.inkOn(0xFF000000.toInt()) == SymbiosisTheme.TEXT)

    // ── 4. разделы ─────────────────────────────────────────────────────
    eq("библиотека — циан", SymbiosisTheme.sectionAccent("library"), SymbiosisTheme.CYAN)
    eq("инструменты — розовый", SymbiosisTheme.sectionAccent("tools"), SymbiosisTheme.PINK)
    eq("второе ядро — розовый", SymbiosisTheme.sectionAccent("Kenji"), SymbiosisTheme.PINK)
    check(
        "разделы различимы по цвету",
        listOf("library", "folders", "converter", "keys", "tools", "guide", "engines")
            .map { SymbiosisTheme.sectionAccent(it) }.toSet().size == 7
    )
    check(
        "неизвестный раздел получает свой цвет, а не серый",
        SymbiosisTheme.sectionAccent("нечто") != SymbiosisTheme.OUTLINE
    )

    // ── 5. форматы файлов ──────────────────────────────────────────────
    eq("nsp — циан", SymbiosisTheme.formatColor("nsp"), SymbiosisTheme.CYAN)
    eq("xci — лайм", SymbiosisTheme.formatColor("XCI"), SymbiosisTheme.LIME)
    eq("nsz — янтарь", SymbiosisTheme.formatColor("nsz"), SymbiosisTheme.AMBER)
    eq("dlc — фиолетовый", SymbiosisTheme.formatColor("DLC"), SymbiosisTheme.VIOLET)
    eq("пустая метка — вторичный текст", SymbiosisTheme.formatColor(""), SymbiosisTheme.TEXT_DIM)
    check(
        "три формата — три разных цвета",
        setOf("nsp", "xci", "nsz").map { SymbiosisTheme.formatColor(it) }.toSet().size == 3
    )

    // ── 6. монограмма ──────────────────────────────────────────────────
    eq("артикль пропускается", SymbiosisTheme.monogram("The Legend of Zelda"), "L")
    eq("обычное название", SymbiosisTheme.monogram("metroid dread"), "M")
    eq("артикль A пропускается", SymbiosisTheme.monogram("A Short Hike"), "S")
    eq("цифра тоже годится", SymbiosisTheme.monogram("007 First Light"), "0")
    eq("пустое название", SymbiosisTheme.monogram("   "), "#")
    eq("название из артикля", SymbiosisTheme.monogram("The"), "T")
    eq("эмодзи не ломает", SymbiosisTheme.monogram("🎮 Mario"), "M")

    // ── 7. арифметика цвета ────────────────────────────────────────────
    eq("hsl красный", SymbiosisTheme.hsl(0f, 1f, 0.5f), 0xFFFF0000.toInt())
    eq("hsl зелёный", SymbiosisTheme.hsl(120f, 1f, 0.5f), 0xFF00FF00.toInt())
    eq("hsl синий", SymbiosisTheme.hsl(240f, 1f, 0.5f), 0xFF0000FF.toInt())
    eq("отрицательный оттенок заворачивается", SymbiosisTheme.hsl(-120f, 1f, 0.5f), 0xFF0000FF.toInt())
    eq("оттенок больше 360 заворачивается", SymbiosisTheme.hsl(480f, 1f, 0.5f), 0xFF00FF00.toInt())
    eq("серый — без насыщенности", SymbiosisTheme.hsl(200f, 0f, 0.5f), 0xFF808080.toInt())

    eq("mix в начале", SymbiosisTheme.mix(SymbiosisTheme.CARD, SymbiosisTheme.CYAN, 0f), SymbiosisTheme.CARD)
    eq("mix в конце", SymbiosisTheme.mix(SymbiosisTheme.CARD, SymbiosisTheme.CYAN, 1f) and 0xFFFFFF, SymbiosisTheme.CYAN and 0xFFFFFF)
    check(
        "mix посередине — между",
        run {
            val mid = SymbiosisTheme.mix(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0.5f) and 0xFF
            mid in 120..135
        }
    )
    eq("прозрачность 20%", (SymbiosisTheme.soft(SymbiosisTheme.CYAN, 0.2f) ushr 24), 51)
    eq("прозрачность не портит цвет", SymbiosisTheme.soft(SymbiosisTheme.CYAN, 0.2f) and 0xFFFFFF, SymbiosisTheme.CYAN and 0xFFFFFF)
    eq("альфа зажата снизу", (SymbiosisTheme.soft(SymbiosisTheme.CYAN, -1f) ushr 24), 0)
    eq("альфа зажата сверху", (SymbiosisTheme.soft(SymbiosisTheme.CYAN, 9f) ushr 24), 255)
    eq("яркость белого", SymbiosisTheme.luminance(0xFFFFFFFF.toInt()), 1.0f)
    eq("яркость чёрного", SymbiosisTheme.luminance(0xFF000000.toInt()), 0.0f)

    // ── 8. фон плитки ──────────────────────────────────────────────────
    val (top, bottom) = SymbiosisTheme.tileGradient("010093801237C000")
    check("у градиента два разных конца", top != bottom)
    check("низ плитки — обычная карточка", bottom == SymbiosisTheme.CARD)
    check("верх подкрашен цветом игры", top != SymbiosisTheme.CARD)
    check("подкраска слабая, текст читается", contrast(top, SymbiosisTheme.TEXT) > 8f,
        "%.1f".format(contrast(top, SymbiosisTheme.TEXT)))
    check("партнёр по градиенту отличается от основного", SymbiosisTheme.accentPartner("kenji") != SymbiosisTheme.accent("kenji"))

    // ── итог ───────────────────────────────────────────────────────────
    println()
    failures.forEach { println("FAIL  $it") }
    println("passed=$passed failed=${failures.size}")
    if (failures.isNotEmpty()) {
        kotlin.system.exitProcess(1)
    }
}

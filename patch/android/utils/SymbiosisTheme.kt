// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Палитра приложения: один источник цвета для всех экранов.
 *
 * ПОЧЕМУ ОТДЕЛЬНЫЙ ФАЙЛ, А НЕ colors.xml
 *   Цвет здесь зависит не от темы, а от данных: у каждой игры свой оттенок,
 *   у каждого файла свой цвет формата, у каждого раздела свой акцент. В XML
 *   такого не выразить — пришлось бы заводить сотню ресурсов и всё равно
 *   выбирать между ними в коде. Зато чистая функция от строки проверяется
 *   на хосте: одинаковый Title ID всегда даёт одинаковый цвет, и «плитка
 *   поменяла цвет после перезапуска» — это тест, а не жалоба.
 *
 * ТЁМНАЯ ОСНОВА НАМЕРЕННО
 *   Игра в эмуляторе — это тёмный экран; светлая панель вокруг него бьёт по
 *   глазам и съедает контраст картинки. Поэтому база почти чёрная с синим
 *   уклоном, а цвет приходит акцентами: полоса, кружок с буквой, чип, точка
 *   у строки настройки.
 *
 * ПРАВИЛО ПРО ЧИТАЕМОСТЬ
 *   Любой акцент проходит через [inkOn]: цвет текста поверх акцента
 *   выбирается по яркости, а не «белый, потому что красиво». Светло-жёлтый
 *   чип с белыми буквами — это ровно тот случай, когда «красочно» становится
 *   нечитаемо.
 */
object SymbiosisTheme {

    // ── основа ──────────────────────────────────────────────────────────
    val BASE = 0xFF0E1116.toInt()        // фон экрана
    val SURFACE = 0xFF151922.toInt()     // карточка
    val CARD = 0xFF1B2029.toInt()        // карточка поверх карточки
    val CARD_HIGH = 0xFF232936.toInt()   // нажатая карточка
    val TEXT = 0xFFEAF0F6.toInt()
    val TEXT_DIM = 0xFFA6B2C4.toInt()
    val OUTLINE = 0xFF2C3444.toInt()

    // ── акценты разделов ────────────────────────────────────────────────
    val CYAN = 0xFF22D3EE.toInt()      // библиотека
    val LIME = 0xFFA3E635.toInt()      // папки
    val AMBER = 0xFFFBBF24.toInt()     // конвертер, предупреждения
    val VIOLET = 0xFFA78BFA.toInt()    // ключи
    val PINK = 0xFFF472B6.toInt()      // инструменты, второе ядро
    val BLUE = 0xFF60A5FA.toInt()      // руководство
    val MINT = 0xFF34D399.toInt()      // «всё хорошо»
    val RED = 0xFFF87171.toInt()       // «сюда не пройти»

    /**
     * Цвет раздела руководства.
     *
     * Здесь именно соответствие по смыслу, а не хеш: «с чего начать» —
     * синий, «файл не запускается» — красный, «два ядра» — розовый, как
     * второе ядро на всех остальных экранах. Человек, листающий руководство,
     * узнаёт раздел по цвету раньше, чем прочитает заголовок, и цвет не
     * должен меняться от того, что раздел передвинули.
     */
    fun guideAccent(id: String): Int = when (id.lowercase()) {
        "start" -> BLUE
        "library" -> CYAN
        "dead" -> RED
        "map" -> LIME
        "cores" -> PINK
        "speed" -> AMBER
        "data" -> VIOLET
        "trouble" -> 0xFFFB7185.toInt()
        else -> accent(id)
    }

    /** Акцент раздела по его ключу. Неизвестный ключ получает свой оттенок. */
    fun sectionAccent(section: String): Int = when (section.lowercase()) {
        "library" -> CYAN
        "folders" -> LIME
        "converter" -> AMBER
        "keys" -> VIOLET
        "tools" -> PINK
        "guide" -> BLUE
        "engines" -> MINT
        "eden" -> 0xFF38BDF8.toInt()
        "kenji" -> PINK
        else -> accent(section)
    }

    // ── цвет игры ───────────────────────────────────────────────────────

    /**
     * Стабильный хеш строки (FNV-1a).
     *
     * Нужен свой, а не [String.hashCode]: тот зафиксирован спецификацией, но
     * читается как «магия», и на нём легко случайно получить соседние цвета
     * у соседних Title ID. FNV разбрасывает лучше, а результат всё равно
     * детерминирован — цвет не «прыгает» между запусками.
     */
    fun stableHash(key: String): Int {
        var h = -2128831035
        for (ch in key) {
            h = h xor ch.code
            h *= 16777619
        }
        return h and 0x7FFFFFFF
    }

    /**
     * Оттенок для игры: стабильный, яркий, но не кислотный.
     *
     * Насыщенность и светлота гуляют в узких пределах намеренно: цвета
     * должны различаться между собой, но не спорить с белым текстом поверх.
     */
    fun accent(key: String): Int {
        val h = stableHash(key)
        val hue = (h % 360).toFloat()
        val sat = 0.58f + (h / 360 % 14) / 100f     // 0.58 … 0.71
        val light = 0.58f + (h / 5040 % 8) / 100f   // 0.58 … 0.65
        return hsl(hue, sat, light)
    }

    /** Второй оттенок для градиента: тот же цвет, сдвинутый по кругу. */
    fun accentPartner(key: String): Int {
        val h = stableHash(key)
        val hue = ((h % 360) + 28).toFloat()
        return hsl(hue, 0.55f, 0.62f)
    }

    /**
     * Фон плитки: от почти чёрного к цвету игры.
     *
     * Градиент слабый (16 %) и вертикальный: карточка должна намекать на
     * цвет, а не перекрашиваться целиком — иначе названия игр перестают
     * читаться на пёстром фоне.
     */
    fun tileGradient(key: String): Pair<Int, Int> =
        mix(CARD, accent(key), 0.16f) to CARD

    // ── формат файла ────────────────────────────────────────────────────

    /**
     * Цвет по метке формата.
     *
     * Формат — единственное, что человек ищет глазами в списке из сотен
     * строк: `nsp` и `xci` должны различаться цветом, а не прочтением.
     */
    fun formatColor(badge: String): Int = when (badge.lowercase().trim()) {
        "nsp" -> CYAN
        "xci" -> LIME
        "nsz" -> AMBER
        "dlc", "nsp+dlc" -> VIOLET
        "upd", "обновление" -> BLUE
        "" -> TEXT_DIM
        else -> accent(badge)
    }

    // ── буква на плитке ─────────────────────────────────────────────────

    /**
     * Буква-монограмма для игры: первая осмысленная буква названия.
     *
     * Артикли пропускаются: у половины библиотеки это «The», и все плитки
     * получили бы одну и ту же букву. Если буквы нет вовсе — берётся первая
     * цифра, иначе решётка.
     */
    fun monogram(title: String): String {
        val cleaned = title.trim()
        if (cleaned.isEmpty()) return "#"
        val words = cleaned.split(' ', '\t', '\n').filter { it.isNotEmpty() }
        val skip = setOf("the", "a", "an", "super", "new")
        // Идём по словам, а не берём первое: «🎮 Mario» — первый токен вообще
        // не слово, и монограмма из решётки на половине библиотеки выглядела
        // бы как сломанная иконка.
        for (word in words) {
            if (word.lowercase().trim('.', ':', '-') in skip) continue
            val ch = word.firstOrNull { it.isLetterOrDigit() }
            if (ch != null) return ch.uppercase()
        }
        // Название целиком из артикля («The») — решётка была бы загадкой,
        // поэтому берём его первую букву.
        return cleaned.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "#"
    }

    // ── мелочи, без которых «красиво» ломается ──────────────────────────

    /**
     * Цвет текста поверх [background]: тёмный или светлый, по яркости.
     *
     * Порог 0.19 — не «эстетическая середина», а точка, где оба варианта
     * дают одинаковый контраст. При нём относительный контраст не падает
     * ниже 4.2:1 ни для одного из наших акцентов (проверяется тестом).
     * Порог 0.5, который так и просится, оставляет узкую полосу цветов,
     * где нечитаемо и белое, и тёмное.
     */
    fun inkOn(background: Int): Int = if (luminance(background) > 0.19f) BASE else TEXT

    /** Тот же цвет с прозрачностью — для подложек и обводок. */
    fun soft(color: Int, alpha: Float): Int {
        val a = (alpha.coerceIn(0f, 1f) * 255).roundToInt()
        return (a shl 24) or (color and 0x00FFFFFF)
    }

    /** Смешение двух цветов: t = 0 → [a], t = 1 → [b]. Альфа берётся у [a]. */
    fun mix(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        fun ch(shift: Int): Int {
            val ca = (a shr shift) and 0xFF
            val cb = (b shr shift) and 0xFF
            return (ca + (cb - ca) * k).roundToInt().coerceIn(0, 255)
        }
        return ((a ushr 24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** Относительная яркость по WCAG: по ней и решается, какой цвет текста. */
    fun luminance(color: Int): Float {
        fun lin(c: Int): Float {
            val v = c / 255f
            return if (v <= 0.03928f) v / 12.92f else Math.pow(((v + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
        }
        val r = lin((color shr 16) and 0xFF)
        val g = lin((color shr 8) and 0xFF)
        val b = lin(color and 0xFF)
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
    }

    /** HSL → ARGB. Своя, потому что android.graphics недоступен в тесте. */
    fun hsl(hue: Float, sat: Float, light: Float): Int {
        val h = ((hue % 360f) + 360f) % 360f / 60f
        val s = sat.coerceIn(0f, 1f)
        val l = light.coerceIn(0f, 1f)
        val c = (1f - abs(2f * l - 1f)) * s
        val x = c * (1f - abs(h % 2f - 1f))
        val (r1, g1, b1) = when {
            h < 1f -> Triple(c, x, 0f)
            h < 2f -> Triple(x, c, 0f)
            h < 3f -> Triple(0f, c, x)
            h < 4f -> Triple(0f, x, c)
            h < 5f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val m = l - c / 2f
        fun ch(v: Float) = ((v + m).coerceIn(0f, 1f) * 255f).roundToInt()
        return (255 shl 24) or (ch(r1) shl 16) or (ch(g1) shl 8) or ch(b1)
    }
}

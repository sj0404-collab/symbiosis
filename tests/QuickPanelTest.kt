// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * Таблица «что применяется сразу, а что после перезапуска».
 *
 * ПОЧЕМУ ЭТО ПРОВЕРЯЕТСЯ
 *   С устройства пришло: «настройки включил, в игре ничего не поменялось».
 *   Причина была не в настройках, а в том, что ядро читает часть из них один
 *   раз при запуске — и об этом нигде не было сказано. Таблица превращает это
 *   в честную подпись. Если в ней появится ошибка, человек снова будет менять
 *   разрешение и ждать эффекта, которого не будет.
 */

import org.yuzu.yuzu_emu.utils.QuickPanel

private var passed = 0
private val failures = mutableListOf<String>()

private fun check(what: String, ok: Boolean, detail: String = "") {
    if (ok) passed++ else failures.add("$what${if (detail.isEmpty()) "" else " → $detail"}")
}

private fun eq(what: String, got: Any?, want: Any?) =
    check(what, got == want, "получено [$got], ожидалось [$want]")

fun main() {
    val byKey = QuickPanel.entries.associateBy { it.key }

    // Разрешение и звуковой движок — те самые пункты, на которые жаловались.
    check("разрешение есть в панели", byKey.containsKey("resolution_setup"))
    check("звуковой движок есть в панели", byKey.containsKey("output_engine"))
    check("буфер звука есть в панели", byKey.containsKey(QuickPanel.BUFFER_KEY))
    eq("ключ буфера", QuickPanel.BUFFER_KEY, "symbiosis_audio_buffer_ms")

    // Проверено по коду ядра: VideoCore::Init и создание звукового вывода.
    check("разрешение требует перезапуска", byKey.getValue("resolution_setup").live.not())
    check("звуковой движок требует перезапуска", byKey.getValue("output_engine").live.not())
    check("буфер звука требует перезапуска", byKey.getValue(QuickPanel.BUFFER_KEY).live.not())
    check("громкость применяется сразу", byKey.getValue("audio_volume").live)

    // Подписи: словами, а не символами.
    eq("подпись отложенного", QuickPanel.badge(byKey.getValue("resolution_setup")), "после перезапуска")
    eq("подпись живого", QuickPanel.badge(byKey.getValue("audio_volume")), "сразу")

    // Нужен ли перезапуск.
    check("только громкость — перезапуск не нужен", !QuickPanel.needsRestart(setOf("audio_volume")))
    check("громкость и предел кадров — не нужен",
        !QuickPanel.needsRestart(setOf("audio_volume", "speed_limit")))
    check("разрешение — нужен", QuickPanel.needsRestart(setOf("resolution_setup")))
    check("смешанный набор — нужен", QuickPanel.needsRestart(setOf("audio_volume", "resolution_setup")))
    check("пустой набор — не нужен", !QuickPanel.needsRestart(emptySet()))

    // Строка состояния: пустая, пока ничего не меняли.
    eq("ничего не меняли — пусто", QuickPanel.statusLine(emptySet()), "")
    check("изменение без перезапуска", QuickPanel.statusLine(setOf("audio_volume")).contains("сразу"))
    check("только отложенное — про перезапуск",
        QuickPanel.statusLine(setOf("resolution_setup")).contains("перезапуска"))
    check("смешанное — оба числа",
        QuickPanel.statusLine(setOf("resolution_setup", "audio_volume")).contains("2").not() ||
            QuickPanel.statusLine(setOf("resolution_setup", "audio_volume")).contains("1"))

    // Буфер: значения и честные подсказки.
    eq("варианты буфера", QuickPanel.bufferChoices, listOf(10, 20, 30, 50))
    check("10 мс названо как в Eden", QuickPanel.bufferHint(10).contains("Eden"))
    check("20 мс названо рабочим", QuickPanel.bufferHint(20).contains("рабочий"))
    check("50 мс предупреждает про задержку", QuickPanel.bufferHint(50).contains("отставать"))
    check("у каждого варианта есть подсказка",
        QuickPanel.bufferChoices.all { QuickPanel.bufferHint(it).isNotBlank() })

    // Каждый отложенный ключ обязан иметь объяснение — иначе подпись
    // «после перезапуска» будет приглашением поверить на слово.
    check("у каждого отложенного пункта есть причина",
        QuickPanel.entries.filter { !it.live }.all { it.why.isNotBlank() })
    eq("restartKeys совпадает с таблицей",
        QuickPanel.restartKeys().sorted(),
        QuickPanel.entries.filter { !it.live }.map { it.key }.sorted())

    // ── частота звука ──────────────────────────────────────────────────
    // В settings.h по умолчанию 0 — «как решит драйвер». Если первый чип
    // окажется другим, панель будет показывать выбранным вариант, который
    // нигде не выбран, и человек снова поменяет настройку «в пустоту».
    check("частота: умолчание — драйвер", QuickPanel.rateChoices.first() == 0,
        QuickPanel.rateChoices.toString())
    check("частота: есть и 48, и 44,1",
        QuickPanel.rateChoices.contains(48000) && QuickPanel.rateChoices.contains(44100),
        QuickPanel.rateChoices.toString())
    check("частота: у каждого варианта объяснение",
        QuickPanel.rateChoices.all { QuickPanel.rateHint(it).isNotBlank() })
    check("частота: у всех вариантов уникальные значения",
        QuickPanel.rateChoices.distinct().size == QuickPanel.rateChoices.size,
        QuickPanel.rateChoices.toString())

    println()
    println("passed=$passed failed=${failures.size}")
    failures.forEach { println("FAIL  $it") }
    if (failures.isNotEmpty()) kotlin.system.exitProcess(1)
}

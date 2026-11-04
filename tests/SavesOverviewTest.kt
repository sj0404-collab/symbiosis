// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * Проверки сводки по сейвам. Запускаются на хосте, без Android.
 *
 * Здесь важно не «работает ли», а «не врёт ли»: «0 дней назад» вместо
 * «не запускалась», «сохранений нет» вместо «только что», дни в 24 часа
 * вместо календарных. Ошибка в этих строках меняет решение человека —
 * начинать заново или нет.
 */

import org.yuzu.yuzu_emu.utils.SavesOverview

private var passed = 0
private val failures = mutableListOf<String>()

private fun check(what: String, ok: Boolean, detail: String = "") {
    if (ok) passed++ else failures.add("$what${if (detail.isEmpty()) "" else " → $detail"}")
}

private fun <T> eq(what: String, got: T, want: T) =
    check(what, got == want, "получено [$got], ожидалось [$want]")

private const val HOUR = 3_600_000L
private const val DAY = 86_400_000L
private const val NOW = 1_700_000_000_000L

private fun slot(profile: String, vararg files: Pair<String, Pair<Long, Long>>) =
    SavesOverview.Slot(
        profile = profile,
        path = "/save/$profile",
        files = files.map { (name, pair) -> SavesOverview.Stamp(name, pair.first, pair.second) }
    )

fun main() {
    // ── пусто ───────────────────────────────────────────────────────────
    val empty = SavesOverview.summarise(emptyList(), NOW)
    check("пустой список — сохранений нет", !empty.hasSaves)
    eq("и нулевой размер", empty.totalBytes, 0L)
    eq("дней без игры неизвестно, а не ноль", empty.daysSincePlay, null)
    eq("часов без сохранения неизвестно", empty.hoursSinceSave, null)
    eq("это и написано словами", SavesOverview.ago(null), "не запускалась")
    eq("и про сохранения тоже", SavesOverview.sinceSaveText(null), "сохранений нет")

    // ── один слот ───────────────────────────────────────────────────────
    val one = SavesOverview.summarise(
        listOf(slot("A", "0000000000000001" to (2048L to NOW - 3 * DAY))),
        NOW
    )
    check("сейв найден", one.hasSaves)
    eq("один слот", one.savesCount, 1)
    eq("размер", one.totalBytes, 2048L)
    eq("дней без игры", one.daysSincePlay, 3)
    eq("это «3 дня назад»", SavesOverview.ago(one.daysSincePlay), "3 дня назад")

    // ── несколько профилей ──────────────────────────────────────────────
    val multi = SavesOverview.summarise(
        listOf(
            slot("A", "a1" to (100L to NOW - 10 * DAY), "a2" to (200L to NOW - 2 * DAY)),
            slot("B", "b1" to (300L to NOW - 30 * DAY))
        ),
        NOW
    )
    eq("два слота", multi.savesCount, 2)
    eq("сумма размеров", multi.totalBytes, 600L)
    eq("создан — по самому старому файлу", multi.created, NOW - 30 * DAY)
    eq("последнее сохранение — по самому новому", multi.lastSaved, NOW - 2 * DAY)
    eq("дней без игры — по последнему сохранению", multi.daysSincePlay, 2)

    // ── запуск новее сейва ──────────────────────────────────────────────
    // Играли час назад, а сохранялись две недели назад — это и есть
    // «десять часов на миссии и ничего не добился».
    val stuck = SavesOverview.summarise(
        listOf(slot("A", "s1" to (100L to NOW - 14 * DAY))),
        NOW,
        lastPlayed = NOW - HOUR
    )
    eq("дней без игры считается по запуску, если он новее", stuck.daysSincePlay, 0)
    eq("а «без сохранения» — по сейву", stuck.hoursSinceSave, 336)
    eq("и это сказано днями", SavesOverview.sinceSaveText(stuck.hoursSinceSave), "14 дней без сохранения")
    eq("заход сегодня", SavesOverview.ago(stuck.daysSincePlay), "сегодня")

    // ── границы «дней» ──────────────────────────────────────────────────
    eq("вчера", SavesOverview.ago(1), "вчера")
    eq("два дня", SavesOverview.ago(2), "2 дня назад")
    eq("четыре дня", SavesOverview.ago(4), "4 дня назад")
    eq("пять дней", SavesOverview.ago(5), "5 дней назад")
    eq("одиннадцать дней — не «дня»", SavesOverview.ago(11), "11 дней назад")
    eq("двадцать один день", SavesOverview.ago(21), "21 день назад")

    // ── «без сохранения» ────────────────────────────────────────────────
    eq("только что", SavesOverview.sinceSaveText(0), "только что")
    eq("один час", SavesOverview.sinceSaveText(1), "1 час без сохранения")
    eq("три часа", SavesOverview.sinceSaveText(3), "3 часа без сохранения")
    eq("двадцать часов", SavesOverview.sinceSaveText(20), "20 часов без сохранения")
    eq("ровно сутки", SavesOverview.sinceSaveText(24), "1 день без сохранения")
    eq("двое суток", SavesOverview.sinceSaveText(48), "2 дня без сохранения")
    eq("десять часов на миссии", SavesOverview.sinceSaveText(10), "10 часов без сохранения")
    eq("сутки с половиной", SavesOverview.sinceSaveText(30), "1 день 6 ч без сохранения")

    // ── размеры ─────────────────────────────────────────────────────────
    eq("байты", SavesOverview.humanBytes(512), "512 Б")
    eq("килобайты", SavesOverview.humanBytes(2048), "2 КБ")
    eq("мегабайты", SavesOverview.humanBytes(5 * 1024L * 1024L), "5.0 МБ")
    eq("гигабайты", SavesOverview.humanBytes(3L * 1024 * 1024 * 1024), "3.00 ГБ")
    eq("ноль", SavesOverview.humanBytes(0), "0 Б")

    // ── направление синхронизации ───────────────────────────────────────
    val older = slot("A", "x" to (1L to NOW - DAY))
    val newer = slot("A", "x" to (1L to NOW))
    eq(
        "локальный новее — заливаем",
        SavesOverview.direction(newer, older),
        SavesOverview.Direction.UPLOAD
    )
    eq(
        "облачный новее — тянем",
        SavesOverview.direction(older, newer),
        SavesOverview.Direction.DOWNLOAD
    )
    eq(
        "одинаковые — ничего не делаем",
        SavesOverview.direction(newer, newer),
        SavesOverview.Direction.SAME
    )
    eq(
        "нет локального — тянем",
        SavesOverview.direction(null, newer),
        SavesOverview.Direction.DOWNLOAD
    )
    eq(
        "нет облачного — заливаем",
        SavesOverview.direction(newer, null),
        SavesOverview.Direction.UPLOAD
    )
    eq(
        "нет ни там ни там — ничего",
        SavesOverview.direction(null, null),
        SavesOverview.Direction.SAME
    )

    // ── описание слота ──────────────────────────────────────────────────
    val described = SavesOverview.describeSlot(older, NOW)
    check("в описании есть число файлов", described.contains("1 файл"), described)
    check("и размер", described.contains("Б"), described)
    check("и когда изменён", described.contains("изменён"), described)
    check(
        "внешняя папка помечена",
        SavesOverview.describeSlot(
            SavesOverview.Slot(
                "внешняя", "/x",
                listOf(SavesOverview.Stamp("f", 10, NOW - DAY)),
                SavesOverview.ORIGIN_EXTERNAL
            ),
            NOW
        ).contains("внешняя папка")
    )

    // ── дата ────────────────────────────────────────────────────────────
    check("нулевая дата — прочерк", SavesOverview.dateText(0L) == "—")
    check(
        "дата в формате дд.мм.гггг чч:мм",
        Regex("^\\d{2}\\.\\d{2}\\.\\d{4} \\d{2}:\\d{2}$").matches(SavesOverview.dateText(NOW)),
        SavesOverview.dateText(NOW)
    )

    // ── склонения ───────────────────────────────────────────────────────
    eq("1 файл", SavesOverview.plural(1, "файл", "файла", "файлов"), "файл")
    eq("2 файла", SavesOverview.plural(2, "файл", "файла", "файлов"), "файла")
    eq("5 файлов", SavesOverview.plural(5, "файл", "файла", "файлов"), "файлов")
    eq("11 файлов", SavesOverview.plural(11, "файл", "файла", "файлов"), "файлов")
    eq("21 файл", SavesOverview.plural(21, "файл", "файла", "файлов"), "файл")

    println()
    println("passed=$passed failed=${failures.size}")
    failures.forEach { println("FAIL  $it") }
    if (failures.isNotEmpty()) kotlin.system.exitProcess(1)
}

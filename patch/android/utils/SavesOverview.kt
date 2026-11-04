// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

/**
 * Сводка по сейвам одной игры: когда создан, когда последний раз сохранялись,
 * сколько дней не заходили, сколько времени прошло с последнего сохранения.
 *
 * Почему это отдельный файл без Android. Сейвы — единственное, что нельзя
 * восстановить переустановкой, и всё, что о них показывается, должно быть
 * проверяемо без телефона: даты, «дней назад», «часов без сохранения» — это
 * арифметика над отметками времени, и её видно на хосте.
 *
 * ЧЕГО ЗДЕСЬ НЕТ И ПОЧЕМУ
 *   Процента прохождения «из сейва» тут нет, потому что его там нет. Сейв
 *   Switch — это каталог файлов игры в её собственном формате (часто
 *   сжатом или зашифрованном); «пройдено 42%» не лежит в нём отдельным
 *   полем, которое можно прочитать. Любое число, выведенное из размера
 *   файла или числа слотов, было бы выдумкой — а выдуманный прогресс хуже
 *   отсутствующего: по нему принимают решения.
 *
 *   Поэтому процент ставит человек (одним нажатием, [ProgressMarks]), а
 *   сводка даёт то, что действительно измеряется: сколько сохранений,
 *   когда последнее и сколько времени утекло после него. Именно последнее
 *   отвечает на «потратил 10 часов и ничего не добился» — видно, что игра
 *   не сохранялась 12 часов.
 */
object SavesOverview {

    /** Один файл внутри слота сохранения. */
    data class Stamp(val name: String, val bytes: Long, val modified: Long)

    /**
     * Слот — папка одного профиля с файлами одной игры.
     *
     * Профилей в Eden бывает несколько (каждый — отдельный пользователь
     * Switch), и у каждого свои сейвы. Показывать их вместе как «одну
     * папку» значит скрыть, что прогресс разный.
     */
    data class Slot(
        val profile: String,
        val path: String,
        val files: List<Stamp>,
        /** Откуда слот: NAND этого приложения, внешняя папка, облако. */
        val origin: String = ORIGIN_LOCAL
    ) {
        val bytes: Long get() = files.sumOf { it.bytes }
        val created: Long get() = files.minOfOrNull { it.modified } ?: 0L
        val modified: Long get() = files.maxOfOrNull { it.modified } ?: 0L
        val fileCount: Int get() = files.size
    }

    const val ORIGIN_LOCAL = "локально"
    const val ORIGIN_EXTERNAL = "внешняя папка"
    const val ORIGIN_CLOUD = "облако"

    /**
     * Полная сводка.
     *
     * [daysSincePlay] и [hoursSinceSave] — null, когда данных нет. Пустая
     * строка «0 дней» на экране читается как «играли сегодня», а это
     * неправда, если игра не запускалась ни разу.
     */
    data class Summary(
        val slots: List<Slot>,
        val totalBytes: Long,
        val savesCount: Int,
        val created: Long,
        val lastSaved: Long,
        val daysSincePlay: Int?,
        val hoursSinceSave: Int?,
        val lastPlayed: Long?
    ) {
        val hasSaves: Boolean get() = savesCount > 0
        val sizeText: String get() = humanBytes(totalBytes)
    }

    /**
     * Считает сводку.
     *
     * @param lastPlayed отметка последнего запуска из счётчика времени игры.
     *   Берётся отдельно от сейвов: игра могла запускаться и не сохраняться —
     *   именно этот случай и выглядит как «играю, а прогресса нет».
     */
    fun summarise(
        slots: List<Slot>,
        now: Long,
        lastPlayed: Long? = null
    ): Summary {
        val files = slots.flatMap { it.files }
        val lastSaved = files.maxOfOrNull { it.modified } ?: 0L
        val created = files.minOfOrNull { it.modified } ?: 0L

        // «Не заходили» считается по самой поздней из отметок: сохранение
        // тоже доказывает, что играли. Если запусков не записано, остаётся
        // только сейв — и это честно оговорено в интерфейсе.
        val lastTouch = maxOf(lastSaved, lastPlayed ?: 0L)
        val daysSincePlay = if (lastTouch > 0L && now >= lastTouch) {
            ((now - lastTouch) / MILLIS_IN_DAY).toInt()
        } else {
            null
        }
        val hoursSinceSave = if (lastSaved > 0L && now >= lastSaved) {
            ((now - lastSaved) / MILLIS_IN_HOUR).toInt()
        } else {
            null
        }
        return Summary(
            slots = slots,
            totalBytes = files.sumOf { it.bytes },
            savesCount = slots.count { it.files.isNotEmpty() },
            created = created,
            lastSaved = lastSaved,
            daysSincePlay = daysSincePlay,
            hoursSinceSave = hoursSinceSave,
            lastPlayed = lastPlayed
        )
    }

    const val MILLIS_IN_HOUR = 3_600_000L
    const val MILLIS_IN_DAY = 86_400_000L

    /**
     * «сегодня» / «вчера» / «12 дней назад».
     *
     * Словами, а не числом: «0 дней назад» человек читает как ошибку.
     */
    fun ago(days: Int?): String = when {
        days == null -> "не запускалась"
        days <= 0 -> "сегодня"
        days == 1 -> "вчера"
        // Склонение общее, а не «до пяти — дня»: 21 день, 22 дня, 25 дней.
        // Частный случай на пяти днях ломался ровно на двадцати одном.
        else -> "$days ${plural(days, "день", "дня", "дней")} назад"
    }

    /** «12 ч без сохранения» — ответ на «играю, а прогресса нет». */
    fun sinceSaveText(hours: Int?): String = when {
        hours == null -> "сохранений нет"
        hours <= 0 -> "только что"
        hours == 1 -> "1 час без сохранения"
        hours < 5 -> "$hours часа без сохранения"
        hours < 24 -> "$hours часов без сохранения"
        else -> {
            val days = hours / 24
            val rest = hours % 24
            if (rest == 0) "$days ${plural(days, "день", "дня", "дней")} без сохранения"
            else "$days ${plural(days, "день", "дня", "дней")} $rest ч без сохранения"
        }
    }

    /** Дата словами: 31.10.2026 14:05. Разметку даёт ОС, здесь — только вид. */
    fun dateText(millis: Long): String {
        if (millis <= 0L) return "—"
        val format = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.US)
        return format.format(java.util.Date(millis))
    }

    fun humanBytes(bytes: Long): String = when {
        bytes <= 0L -> "0 Б"
        bytes < 1024L -> "$bytes Б"
        bytes < 1024L * 1024L -> "%.0f КБ".format(bytes / 1024.0)
        bytes < 1024L * 1024L * 1024L -> "%.1f МБ".format(bytes / (1024.0 * 1024.0))
        else -> "%.2f ГБ".format(bytes / (1024.0 * 1024.0 * 1024.0))
    }

    fun plural(n: Int, one: String, few: String, many: String): String {
        val mod100 = n % 100
        val mod10 = n % 10
        return when {
            mod100 in 11..14 -> many
            mod10 == 1 -> one
            mod10 in 2..4 -> few
            else -> many
        }
    }

    /** Текст одной строки слота для списка. */
    fun describeSlot(slot: Slot, now: Long): String {
        val parts = mutableListOf<String>()
        parts.add("${slot.fileCount} ${plural(slot.fileCount, "файл", "файла", "файлов")}")
        parts.add(humanBytes(slot.bytes))
        parts.add("создан " + dateText(slot.created))
        if (slot.modified > 0L && now > slot.modified) {
            val days = ((now - slot.modified) / MILLIS_IN_DAY).toInt()
            parts.add("изменён " + ago(days))
        }
        if (slot.origin != ORIGIN_LOCAL) parts.add(slot.origin)
        return parts.joinToString(" · ")
    }

    /**
     * Сравнение локальных и облачных слотов: что новее.
     *
     * Нужно для синхронизации: заливать или тянуть. Правило простое и
     * проверяемое — побеждает более свежая отметка изменения; при равенстве
     * ничего не делаем, чтобы повторное нажатие не гоняло мегабайты.
     */
    enum class Direction { UPLOAD, DOWNLOAD, SAME }

    fun direction(local: Slot?, remote: Slot?): Direction {
        if (local == null && remote == null) return Direction.SAME
        if (remote == null) return Direction.UPLOAD
        if (local == null) return Direction.DOWNLOAD
        val l = local.modified
        val r = remote.modified
        return when {
            l > r -> Direction.UPLOAD
            r > l -> Direction.DOWNLOAD
            else -> Direction.SAME
        }
    }
}

// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

/**
 * Правила архива журналов ядра — без единого обращения к диску.
 *
 * ПОЧЕМУ ЖУРНАЛ ТЕРЯЛСЯ
 *
 *   Ядро само хранит ровно два файла: текущий сеанс и один прошлый (при
 *   старте `eden_log.txt` переименовывается в `eden_log.txt.old.txt`, а
 *   предыдущий `.old` удаляется). Значит третий запуск стирает первый
 *   безвозвратно. Для разбора «вчера работало, сегодня нет» это ровно те
 *   данные, которых не хватает, — и они исчезают не по воле человека, а
 *   сами.
 *
 * ЧТО ДЕЛАЕМ
 *
 *   Перед каждым запуском игры (и при открытии экрана журнала) текущий файл
 *   копируется в `log/sessions/<время последней записи>.txt`. Копия делается
 *   ДО того, как ядро переименует и перезапишет файл, поэтому каждый сеанс
 *   сохраняется целиком, один раз и навсегда.
 *
 *   Ключ копии — время последней записи исходника в секундах. По нему же
 *   видно, что копия уже сделана: повторное нажатие «запустить» не создаёт
 *   дубликатов, а два разных сеанса не склеиваются.
 *
 * ГРАНИЦЫ
 *
 *   Журналы не удаляются по расписанию. Ограничение есть только на совсем
 *   крайний случай — [MAX_FILES] файлов и [MAX_BYTES] суммарно: телефон не
 *   должен заполниться журналами за полгода игры. Удаляется самый старый, и
 *   только когда лимит уже превышен.
 */
object KernelLogArchive {

    /** Сколько сеансов держим. Полгода по одному запуску в день — это 180. */
    const val MAX_FILES = 200

    /** Предел суммарного размера архива: 200 МБ. */
    const val MAX_BYTES = 200L * 1024L * 1024L

    /**
     * Имя копии по времени последней записи, в секундах.
     *
     * Секунды, а не миллисекунды: файлы называются временем сеанса, и в
     * списке должно читаться «вчера», а не 13 цифр. Один сеанс не может
     * закончиться и начаться в одну и ту же секунду с точностью до миллисекунд,
     * а если и может — это тот же сеанс.
     */
    fun nameFor(modifiedMillis: Long): String = "session-${modifiedMillis / 1000}.txt"

    /**
     * Нужно ли копировать файл: он непустой и с таким временем копии ещё нет.
     *
     * [existing] — имена файлов, которые уже лежат в архиве.
     */
    fun shouldArchive(modifiedMillis: Long, sizeBytes: Long, existing: Collection<String>): Boolean {
        if (sizeBytes <= 0 || modifiedMillis <= 0) return false
        return nameFor(modifiedMillis) !in existing
    }

    /**
     * Что удалить, чтобы уложиться в лимиты: самые старые, по порядку.
     *
     * Принимает уже отсортированный по времени список (старые сверху) и
     * возвращает имена, которые надо убрать. Пустой ответ — ничего не
     * трогаем, и это нормальный случай.
     */
    fun toPrune(
        entries: List<Pair<String, Long>>,
        maxFiles: Int = MAX_FILES,
        maxBytes: Long = MAX_BYTES
    ): List<String> {
        var count = entries.size
        var total = entries.sumOf { it.second }
        val remove = ArrayList<String>()
        for ((name, size) in entries) {
            if (count <= maxFiles && total <= maxBytes) break
            remove.add(name)
            count--
            total -= size
        }
        return remove
    }

    /**
     * Подпись сеанса для списка: когда он был и сколько весит.
     *
     * Время берётся из имени файла, а не из `lastModified`: копия сделана
     * сейчас, и её `lastModified` — это «сейчас», а не время игры. Показывать
     * «только что» у вчерашнего сеанса хуже, чем не показывать ничего.
     */
    fun label(name: String, sizeBytes: Long, nowMillis: Long): String {
        val stamp = name.removePrefix("session-").removeSuffix(".txt").toLongOrNull()
        val kb = (sizeBytes / 1024).coerceAtLeast(1)
        if (stamp == null) return "$name · $kb КБ"
        val ago = nowMillis - stamp * 1000
        val minutes = ago / 60_000
        val hours = ago / 3_600_000
        val days = ago / 86_400_000
        val whenText = when {
            ago < 0 -> "сеанс"
            minutes < 1 -> "только что"
            minutes < 60 -> "$minutes мин назад"
            hours < 24 -> "$hours ч назад"
            days == 1L -> "вчера"
            else -> "$days дн назад"
        }
        return "$whenText · $kb КБ"
    }
}

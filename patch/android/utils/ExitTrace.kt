// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import android.content.Context
import androidx.preference.PreferenceManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.yuzu.yuzu_emu.YuzuApplication

/**
 * След последних действий: что приложение успело сделать до того, как упало.
 *
 * Зачем. «При выходе из игры выскакивает краш» — это описание симптома, а не
 * причина. По нему нельзя ни починить, ни даже уверенно сказать, чей это
 * сбой: ядра, драйвера, слоя или обычного исключения в Kotlin. Системный
 * диалог «приложение остановлено» ничего не объясняет, а logcat на телефоне
 * пользователь не читает.
 *
 * Здесь пишутся метки фаз: запуск игры такой-то, остановка эмуляции,
 * возврат в библиотеку. Сбой между двумя метками и есть место поиска: если
 * после «остановка эмуляции» метки «возврат в библиотеку» нет — падение
 * произошло при разрушении окна, а не при запуске. Это уже направление, а не
 * догадка.
 *
 * Пишется в SharedPreferences, потому что это должно успеть лечь на диск до
 * падения. Файл тоже подошёл бы, но `commit()` на маленькой строке дешевле и
 * надёжнее, чем открытие файла в момент, когда процесс уже умирает.
 */
object ExitTrace {

    private const val KEY = "symbiosis_exit_trace"
    private const val LIMIT = 8

    private fun prefs() =
        PreferenceManager.getDefaultSharedPreferences(YuzuApplication.appContext)

    private fun stamp(): String =
        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

    @Synchronized
    private fun append(line: String) {
        runCatching {
            val old = prefs().getString(KEY, "").orEmpty()
            val lines = (old.lines() + line).filter { it.isNotBlank() }.takeLast(LIMIT)
            // commit(), а не apply(): apply() пишет на диск асинхронно, и на
            // падении запись может не дойти — а нужен именно след.
            prefs().edit().putString(KEY, lines.joinToString("\n")).commit()
        }
    }

    /** Отметить фазу. */
    fun mark(phase: String) {
        append("${stamp()} · $phase")
    }

    /**
     * Выполнить шаг, записав его провал, но не роняя приложение.
     *
     * Обновление библиотеки после выхода из игры — не то место, где стоит
     * показывать пользователю системный «краш». Если что-то в нём сломалось,
     * экран остаётся рабочим, а причина попадает в след и в диагностику.
     */
    fun guarded(phase: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            append("${stamp()} · СБОЙ в «$phase»: ${t.javaClass.simpleName}: ${t.message}")
            Log.warning("[ExitTrace] сбой в «$phase»: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** Нет ли записи о сбое в этом запуске. */
    fun hasFailure(): Boolean {
        val text = prefs().getString(KEY, "").orEmpty()
        return text.contains("СБОЙ")
    }

    /** Текст для «Диагностики»: без выдуманных выводов, только факты. */
    fun report(): String {
        val lines = prefs().getString(KEY, "").orEmpty().lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return ""
        val crashed = runCatching { NativeSymbiosis.previousSessionCrashed() }.getOrDefault(false)
        return buildString {
            append("прошлая сессия завершилась сбоем: ")
            append(if (crashed) "да" else "нет")
            append('\n')
            append("последние шаги (снизу — самый свежий):\n")
            lines.takeLast(LIMIT).forEach { append("  ").append(it).append('\n') }
        }.trimEnd()
    }

    /** Очистить след: после «я всё видел» он только мешает. */
    fun clear() {
        runCatching { prefs().edit().remove(KEY).commit() }
    }

    /** Контекст оставлен для будущих записей рядом с файлами логов. */
    @Suppress("unused")
    fun dir(context: Context) = context.filesDir
}

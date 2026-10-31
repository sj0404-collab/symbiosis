// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import androidx.preference.PreferenceManager
import org.yuzu.yuzu_emu.YuzuApplication

/**
 * Процент прохождения, поставленный человеком.
 *
 * Почему вручную. Настоящий процент лежит внутри сейва в формате конкретной
 * игры — часто сжатом или зашифрованном. «42%» не является полем, которое
 * можно прочитать одинаково для всех игр. Любая автоматическая цифра была бы
 * оценкой, а оценка в этом месте вредна: по ней решают, стоит ли начинать
 * заново.
 *
 * Что здесь есть, чтобы ручное не было нудным: одна отметка на игру, ставится
 * в два нажатия с экрана сейвов (25 / 50 / 75 / 100 или своё число) и видна
 * на плитке библиотеки. Значение хранится по TitleID, поэтому переживает
 * переименование файла.
 */
object ProgressMarks {

    private const val KEY = "symbiosis_progress_"

    private fun prefs() =
        PreferenceManager.getDefaultSharedPreferences(YuzuApplication.appContext)

    /** Сколько процентов отмечено, или null, если не отмечено. */
    fun percent(titleId: String): Int? {
        if (titleId.isBlank()) return null
        val v = prefs().getInt(KEY + titleId.uppercase(), -1)
        return if (v in 0..100) v else null
    }

    /**
     * Ставит процент. Значение вне 0..100 и пустой TitleID — отказ, а не
     * «запишем как есть»: на плитке появилось бы «-1%».
     */
    fun set(titleId: String, percent: Int?): Boolean {
        if (titleId.isBlank()) return false
        val edit = prefs().edit()
        val key = KEY + titleId.uppercase()
        if (percent == null) {
            edit.remove(key)
        } else {
            if (percent !in 0..100) return false
            edit.putInt(key, percent)
        }
        return edit.commit()
    }

    fun clear(titleId: String) = set(titleId, null)

    /** Подпись для плитки: «45%», или пустая строка. */
    fun chip(titleId: String): String {
        val p = percent(titleId) ?: return ""
        return if (p >= 100) "пройдено" else "$p%"
    }

    /**
     * Ступени для быстрой отметки.
     *
     * Ноль включён намеренно: «не начато» — тоже отметка, и она отличается
     * от «не отмечено», когда игра пройдена и хочется это видеть.
     */
    val STEPS = listOf(0, 10, 25, 50, 75, 90, 100)
}

// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import java.io.File

/**
 * Последняя игра, которую запускали, — записанная до запуска, а не в памяти.
 *
 * ЗАЧЕМ. При перезапуске игры экран не создаётся заново: он получает новый
 * intent, останавливает сессию и запускает ту же игру ещё раз. Игра передаётся
 * в extras как объект, и если он не доехал — экран не находит игру и закрывает
 * себя. Снаружи это выглядит так, будто приложение «перезапускает, но найти
 * игру не может и само из неё выходит».
 *
 *   Поэтому путь к игре пишется в файл ДО запуска. Если объект не доехал, игру
 *   можно собрать заново по пути — файл на месте, это тот же самый файл.
 *
 * ПОЧЕМУ ФАЙЛ, А НЕ НАСТРОЙКИ. Настройки читаются через нативный слой, а он к
 * моменту перезапуска уже останавливается. Файл читается всегда.
 */
object LastGame {

    /** Записать путь запускаемой игры. Ошибки не бросаем: это подстраховка. */
    fun save(file: File, path: String) {
        runCatching {
            if (path.isBlank()) return
            file.parentFile?.mkdirs()
            file.writeText(path)
        }
    }

    /** Путь последней запущенной игры, или null, если записи нет. */
    fun load(file: File): String? = runCatching {
        if (!file.isFile) return null
        val text = file.readText().trim()
        if (text.isEmpty()) return null
        // Путь через SAF (content://) проверить нельзя — он не файл, но и
        // выбрасывать его нельзя: так открывают игры из «Документов».
        if (text.startsWith("content://") || text.startsWith("file://")) return text
        // Файл мог остаться от удалённой игры: тогда подсказка бесполезна и
        // только запутает, поэтому существование проверяется здесь.
        if (!File(text).isFile) return null
        text
    }.getOrNull()

    /** Забыть игру: вызывается, когда человек сам вышел из неё. */
    fun clear(file: File) {
        runCatching { file.delete() }
    }
}

// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import android.content.Context
import java.io.File

/**
 * «Эта игра на втором ядре не идёт — пусть идёт на Eden».
 *
 * ЗАЧЕМ. Второе ядро — не наш код: некоторые игры оно роняет внутри себя, на
 * вызове собственных сервисов (пример с телефона: TMNT Shredder's Revenge
 * встаёт через 20 секунд на `IAccountServiceForApplication`). Починить это
 * из приложения нельзя — исходников ядра у нас нет. Но и оставлять человеку
 * чёрный экран на минуту, а потом ещё раз запускать ту же игру тем же ядром
 * в следующий раз — тоже неправильно.
 *
 * Поэтому факт такого падения запоминается в файле, а основной процесс при
 * возврате в библиотеку переводит ЭТУ игру на Eden и говорит об этом.
 * Остальные игры продолжают идти на втором ядре: это адресное решение, а не
 * «выключить ядро целиком».
 *
 * Почему файл, а не SharedPreferences: плеер работает в процессе `:kenji`,
 * а решение принимает главный процесс. Кэш SharedPreferences между
 * процессами не синхронизируется, и запись из `:kenji` главный процесс
 * попросту не увидит.
 */
object CoreFallback {

    private fun file(context: Context): File =
        File(KenjiBridge.crashReportFile(context).parentFile, "core-fallback.txt")

    /**
     * Записать: игра упала внутри ядра.
     *
     * Вызывается из процесса `:kenji` сразу, как только журнал ядра сказал
     * про исключение в гостевом потоке, — до закрытия плеера.
     */
    fun note(context: Context, gamePath: String, reason: String) {
        if (gamePath.isEmpty()) return
        runCatching {
            val target = file(context)
            target.parentFile?.mkdirs()
            target.writeText(gamePath + "\n" + reason.replace('\n', ' ') + "\n")
        }
    }

    /**
     * Применить решение, если оно есть. Возвращает строку для отчёта —
     * пустую, когда переключать нечего.
     *
     * Вызывается из главного процесса: только он видит библиотеку и умеет
     * менять выбор ядра для игры.
     */
    fun applyIfAny(context: Context): String {
        val target = runCatching { file(context) }.getOrNull() ?: return ""
        if (!target.isFile) return ""
        val lines = runCatching { target.readLines() }.getOrNull().orEmpty()
        target.delete()
        val path = lines.getOrNull(0).orEmpty().trim()
        if (path.isEmpty()) return ""
        val wasEden = runCatching { EngineStore.coreFor(context, path) }
            .getOrNull() == EngineHybrid.Core.EDEN
        if (!wasEden) {
            runCatching { EngineStore.setCoreFor(context, path, EngineHybrid.Core.EDEN) }
        }
        return if (wasEden) {
            "Эта игра уже была переведена на Eden — выбор сохранён."
        } else {
            "Эта игра переведена на Eden: выбор сохранён за ней, " +
                "при следующем запуске она пойдёт на Eden."
        }
    }
}

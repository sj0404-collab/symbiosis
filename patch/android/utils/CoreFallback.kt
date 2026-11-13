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
 * встаёт через ~19 секунд на `IAccountServiceForApplication`). Починить это
 * из приложения нельзя — исходников ядра у нас нет.
 *
 * Почему файл, а не SharedPreferences: записку оставляет процесс `:kenji`, а
 * решение принимает главный. Кэш SharedPreferences между процессами не
 * синхронизируется, и запись из `:kenji` главный процесс попросту не увидит.
 *
 * ТРИ ПРАВИЛА, которые появились после разбора 05.11:
 *
 *  1. Записка живёт, пока игра на Eden не пойдёт успешно или человек сам не
 *     выберет ядро руками. Раньше она удалялась сразу после попытки
 *     применить выбор — и если запись выбора терялась, не оставалось ничего.
 *  2. «Переведена на Eden» говорится только после ПРОВЕРКИ: выбор читается
 *     обратно. Обещать непроверенное нельзя — уже обещали, а игра пошла на
 *     Kenji снова.
 *  3. Явный выбор человека сильнее записки: «Запустить на Kenji-NX» стирает
 *     её, но приложение говорит, что это его выбор, а не «так само вышло».
 */
object CoreFallback {

    private fun file(context: Context): File =
        File(KenjiBridge.crashReportFile(context).parentFile, "core-fallback.txt")

    private fun read(context: Context): String =
        runCatching { file(context).takeIf { it.isFile }?.readText().orEmpty() }
            .getOrDefault("")

    private fun write(context: Context, text: String) {
        runCatching {
            val target = file(context)
            target.parentFile?.mkdirs()
            target.writeText(text)
        }
    }

    /**
     * Записать: игра упала внутри ядра.
     *
     * Вызывается из процесса `:kenji` сразу, как только журнал ядра сказал
     * про исключение в гостевом потоке, — до закрытия плеера.
     */
    fun note(context: Context, gamePath: String, reason: String) {
        if (gamePath.isEmpty()) return
        write(context, CoreFallbackNotes.add(read(context), gamePath, reason))
    }

    /** Эта игра уже роняла второе ядро? Ответ — по файлу, без побочных действий. */
    fun isMarked(context: Context, gamePath: String): Boolean =
        CoreFallbackNotes.has(read(context), gamePath)

    /** Причина из последней записки про эту игру (пусто — записки нет). */
    fun noteFor(context: Context, gamePath: String): String =
        CoreFallbackNotes.reasonFor(read(context), gamePath)

    /**
     * Человек сам выбрал ядро — записка больше не действует.
     *
     * Иначе получилось бы, что приложение спорит с владельцем телефона:
     * он просит Kenji, а оно втихую запускает Eden.
     */
    fun clearFor(context: Context, gamePath: String) {
        val before = read(context)
        val after = CoreFallbackNotes.remove(before, gamePath)
        if (after != before) write(context, after)
    }

    /**
     * Применить записки: поставить этим играм Eden. Возвращает строку для
     * отчёта — пустую, когда записок нет.
     *
     * Вызывается из главного процесса: только он видит библиотеку и умеет
     * менять выбор ядра. Проверка результата обязательна: если выбор не
     * сохранился, записка остаётся, и это будет сказано словами.
     */
    fun applyIfAny(context: Context): String {
        val text = read(context)
        val notes = CoreFallbackNotes.entries(text)
        if (notes.isEmpty()) return ""

        var applied = 0
        var failed = 0
        var kept = text
        for ((path, _) in notes) {
            val ok = runCatching { EngineStore.setCoreFor(context, path, EngineHybrid.Core.EDEN) }
                .getOrDefault(false)
            // Проверка обратным чтением: «записали» и «сохранено» — разные вещи.
            val verified = ok && runCatching { EngineStore.coreFor(context, path) }
                .getOrNull() == EngineHybrid.Core.EDEN
            if (verified) {
                applied++
                kept = CoreFallbackNotes.remove(kept, path)
            } else {
                failed++
            }
        }
        if (kept != text) write(context, kept)

        return when {
            failed == 0 && applied > 0 ->
                "Для игры выбран Eden: значение прочитано сразу после записи. " +
                    "Следующий запуск и работа игры на Eden ещё не проверены."
            failed > 0 && applied > 0 ->
                "Часть выбора сохранить не удалось ($failed). Записка оставлена: " +
                    "при следующем запуске попробую снова."
            else ->
                "Не удалось подтвердить выбор Eden для игры. " +
                    "Попробуйте выбрать ядро долгим нажатием на плитке; причина не установлена."
        }
    }
}

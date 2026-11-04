// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/**
 * Чем система объясняет, что процесс второго ядра закончился.
 *
 * ЗАЧЕМ, ЕСЛИ ЕСТЬ СВОЙ ОТЧЁТ. Наш обработчик сигнала пишет шаг, номер
 * сигнала и кадры — но не текст, с которым процесс остановил себя сам. На
 * устройстве ядро умерло сразу после «core loaded and verified», не сказав в
 * свой вывод ни строки, и причина осталась неизвестной. Между тем Android с
 * версии 11 хранит для каждого процесса причину и снимок аварийного
 * завершения (tombstone) и отдаёт их приложению через ApplicationExitInfo —
 * в том числе строку «Abort message», то есть ровно то сообщение, которое
 * libc записала перед abort().
 *
 * ГРАНИЦЫ. Читается только свой пакет и только свой процесс (`:kenji`).
 * Снимок есть не всегда: у обычного выхода процесса вместо него пусто, и
 * тогда возвращается null — пусть окно честно молчит, а не выдумывает причину.
 */
object SysExit {

    // Коды причин — значения ApplicationExitInfo.REASON_* (API 30).
    //
    // ЧИСЛАМИ, А НЕ КОНСТАНТАМИ, И ЭТО ОСОЗНАННО. Написав
    // `ActivityManager.REASON_CRASH_NATIVE`, я получил бы неразрешимую ссылку:
    // эти константы объявлены в ApplicationExitInfo, а не в ActivityManager, и
    // сборка упала бы на компиляции. Коды — часть публичного контракта API и
    // не меняются; рядом с каждым стоит его настоящее имя.
    private const val REASON_EXIT_SELF = 1               // ApplicationExitInfo.REASON_EXIT_SELF
    private const val REASON_SIGNALED = 2                // ...REASON_SIGNALED
    private const val REASON_LOW_MEMORY = 3              // ...REASON_LOW_MEMORY
    private const val REASON_CRASH = 4                   // ...REASON_CRASH
    private const val REASON_CRASH_NATIVE = 5            // ...REASON_CRASH_NATIVE
    private const val REASON_ANR = 6                     // ...REASON_ANR
    private const val REASON_INITIALIZATION_FAILURE = 7  // ...REASON_INITIALIZATION_FAILURE
    private const val REASON_USER_REQUESTED = 10         // ...REASON_USER_REQUESTED
    private const val REASON_OTHER = 13                  // ...REASON_OTHER

    data class Snapshot(
        /** Причина словами, как её называет система. */
        val reason: String,
        /** Описание от системы: для нативного падения там сигнал. */
        val description: String,
        /** Когда это случилось (миллисекунды от начала эпохи). */
        val timestamp: Long,
        /** Tombstone целиком; пусто, если система его не отдала. */
        val tombstone: String
    )

    /** Снимок последнего завершения процесса с указанным суффиксом имени. */
    fun read(context: Context, processSuffix: String = ":kenji"): Snapshot? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return query(context, processSuffix)
    }

    private fun query(context: Context, processSuffix: String): Snapshot? = runCatching {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return@runCatching null
        val entries = manager.getHistoricalProcessExitReasons(context.packageName, 0, 10)
        val entry = entries
            .filter { it.processName?.endsWith(processSuffix) == true }
            .maxByOrNull { it.timestamp }
            ?: return@runCatching null

        val trace = runCatching {
            entry.traceInputStream?.use { it.bufferedReader().readText() }
        }.getOrNull().orEmpty()

        Snapshot(
            reason = reasonText(entry.reason),
            description = entry.description.orEmpty(),
            timestamp = entry.timestamp,
            tombstone = trace
        )
    }.getOrNull()

    /** Причина словами. Неизвестный код называется числом, а не выдумкой. */
    fun reasonText(reason: Int): String = when (reason) {
        REASON_CRASH_NATIVE -> "нативное падение — процесс остановил себя сигналом"
        REASON_CRASH -> "необработанное исключение в коде приложения"
        REASON_ANR -> "процесс перестал отвечать, система его сняла"
        REASON_LOW_MEMORY -> "система остановила процесс из-за нехватки памяти"
        REASON_EXIT_SELF -> "процесс завершился сам, без сигнала"
        REASON_SIGNALED -> "процесс завершён сигналом (тот же код даёт и abort() внутри процесса)"
        REASON_INITIALIZATION_FAILURE -> "процесс не смог инициализироваться"
        REASON_USER_REQUESTED -> "процесс остановлен по запросу"
        REASON_OTHER -> "система не назвала причину точнее"
        else -> "код причины $reason"
    }

    /**
     * Раздел отчёта: что система знает о конце процесса.
     *
     * Пустая строка — когда сказать нечего, чтобы вызывающий не добавлял
     * пустой заголовок в диалог.
     */
    fun describe(snapshot: Snapshot?): String {
        if (snapshot == null) return ""
        val lines = KenjiCrash.tombstoneLines(snapshot.tombstone)
        val head = StringBuilder()
        head.append("Что записала система о конце процесса :kenji:\n")
        head.append("• причина: ").append(snapshot.reason)
        if (snapshot.description.isNotBlank()) {
            head.append(" · ").append(snapshot.description.take(120))
        }
        if (lines.isEmpty()) {
            // Снимка нет — это отдельный факт, и он важнее молчания: значит
            // система не считает конец процесса аварийным либо не хранит
            // снимки на этой прошивке.
            head.append("\n• снимка аварийного завершения система не отдала")
            return head.toString()
        }
        head.append("\n• из снимка системы:")
        lines.forEach { head.append("\n    ").append(it) }
        return head.toString()
    }
}

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

    data class Snapshot(
        /** Причина словами, как её называет система. */
        val reason: String,
        /** Описание от системы: для нативного падения там сигнал. */
        val description: String,
        /** Когда это случилось (миллисекунды от начала эпохи). */
        val timestamp: Long,
        val pid: Int,
        /** Tombstone целиком; пусто, если система его не отдала. */
        val tombstone: String
    )

    /** Снимок последнего завершения процесса с указанным суффиксом имени. */
    fun read(context: Context, expectedPid: Int?, expectedStart: Long?, processSuffix: String = ":kenji"): Snapshot? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || expectedPid == null || expectedStart == null) return null
        return query(context, processSuffix, expectedPid, expectedStart)
    }

    private fun query(context: Context, processSuffix: String, expectedPid: Int, expectedStart: Long): Snapshot? = runCatching {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return@runCatching null
        val entries = manager.getHistoricalProcessExitReasons(context.packageName, 0, 10)
        val now = System.currentTimeMillis()
        val entry = entries
            .filter { it.processName?.endsWith(processSuffix) == true &&
                ProcessExitEvidence.matches(expectedPid, expectedStart, it.pid, it.timestamp, now) }
            .maxByOrNull { it.timestamp }
            ?: return@runCatching null

        val trace = runCatching {
            entry.traceInputStream?.use { it.bufferedReader().readText() }
        }.getOrNull().orEmpty()

        Snapshot(
            reason = ProcessExitEvidence.reasonText(entry.reason),
            description = entry.description.orEmpty(),
            timestamp = entry.timestamp,
            pid = entry.pid,
            tombstone = trace
        )
    }.getOrNull()

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
        val whenText = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm:ss", java.util.Locale.ROOT)
            .format(java.util.Date(snapshot.timestamp))
        head.append("Системная запись о завершении :kenji (PID и время совпали):\n")
        head.append("• PID: ").append(snapshot.pid).append(" · время: ").append(whenText).append('\n')
        head.append("• причина: ").append(snapshot.reason)
        if (snapshot.description.isNotBlank()) {
            head.append(" · ").append(snapshot.description)
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

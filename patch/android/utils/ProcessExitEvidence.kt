// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later
package org.yuzu.yuzu_emu.utils

/** Matches system exit records to the process and the observed start time. */
object ProcessExitEvidence {
    fun matches(expectedPid: Int?, expectedStart: Long?, actualPid: Int, exitTime: Long, now: Long): Boolean {
        if (expectedPid == null || expectedPid <= 0 || expectedStart == null || expectedStart <= 0 || now <= 0) return false
        // Filesystem timestamp precision can be coarse. PID and time are both
        // required; even matching them is supporting evidence, not proof of cause.
        return actualPid == expectedPid && exitTime >= expectedStart - 2000 && exitTime <= now + 5000
    }
    fun reasonText(reason: Int): String = when (reason) {
        1 -> "процесс завершился сам, без сигнала"
        2 -> "процесс завершён сигналом (этот код бывает и при abort())"
        3 -> "система остановила процесс из-за нехватки памяти"
        4 -> "необработанное исключение в коде приложения"
        5 -> "нативное аварийное завершение процесса"
        6 -> "процесс перестал отвечать, система его сняла"
        7 -> "процесс не смог инициализироваться"
        10 -> "процесс остановлен по запросу"
        12 -> "система завершила процесс после остановки его зависимости"
        13 -> "система не назвала причину точнее"
        else -> "код причины $reason"
    }
}

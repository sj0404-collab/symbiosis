// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later
import org.yuzu.yuzu_emu.utils.ProcessExitEvidence
fun main() {
    val started = 1_700_000_000_000L
    val now = started + 30_000
    fun match(pid: Int?, start: Long?, recordPid: Int, time: Long, current: Long = now) =
        ProcessExitEvidence.matches(pid, start, recordPid, time, current)
    check(match(123, started, 123, started + 24_000))
    check(match(123, started, 123, started - 2_000))
    check(!match(123, started, 123, started - 2_001))
    check(!match(123, started, 124, started + 24_000))
    check(!match(null, started, 123, started + 24_000))
    check(!match(123, null, 123, started + 24_000))
    check(!match(0, started, 0, started + 24_000))
    check(!match(123, started, 123, now + 5_001))
    check(ProcessExitEvidence.reasonText(12).contains("зависимости"))
    check(!ProcessExitEvidence.reasonText(12).contains("памят"))
    check(ProcessExitEvidence.reasonText(3).contains("памят"))
    check(ProcessExitEvidence.reasonText(99) == "код причины 99")
    println("ProcessExitEvidence: reason 12, PID and session timing checked")
}

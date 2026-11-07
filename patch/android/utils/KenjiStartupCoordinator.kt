// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later
package org.yuzu.yuzu_emu.utils

import java.util.concurrent.CountDownLatch

/**
 * Coordinates ONE player's initialization and stop workers. It does not claim
 * that the native core's internal threads have stopped: the caller must still
 * signal, join the actual loop thread and close the device before unloading.
 */
class KenjiStartupCoordinator {
    enum class Decision { ABORTED_BEFORE_LOOP, ENTERING_LOOP }
    private val decided = CountDownLatch(1)
    @Volatile private var cancelled = false
    private var decision: Decision? = null

    fun cancel() { synchronized(this) { cancelled = true } }
    fun isCancelled(): Boolean = cancelled

    /** Atomically commit to the loop or reject it after cancellation. */
    @Synchronized
    fun beginLoop(): Boolean {
        check(decision == null)
        decision = if (cancelled) Decision.ABORTED_BEFORE_LOOP else Decision.ENTERING_LOOP
        decided.countDown()
        return decision == Decision.ENTERING_LOOP
    }

    /** A failed/early-returning initializer must wake the stopper as well. */
    @Synchronized
    fun initializationEnded() {
        if (decision == null) {
            decision = Decision.ABORTED_BEFORE_LOOP
            decided.countDown()
        }
    }

    /** Wait off the UI thread; no native close may run during initialization. */
    fun awaitDecision(): Decision {
        decided.await()
        return synchronized(this) { checkNotNull(decision) }
    }
}

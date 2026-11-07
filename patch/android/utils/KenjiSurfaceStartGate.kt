// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later
package org.yuzu.yuzu_emu.utils

/**
 * Start only after Android reports a live surface and real non-zero dimensions.
 * SurfaceHolder callbacks normally arrive on the main thread, but stop can be
 * requested by another thread; synchronization keeps the decision atomic.
 * This does not implement reattachment after a running surface is destroyed.
 */
class KenjiSurfaceStartGate {
    private var available = false
    private var started = false
    private var stopped = false

    @Synchronized
    fun created() {
        if (!stopped) available = true
    }

    /** Returns true exactly once, for the first valid size of a live surface. */
    @Synchronized
    fun changed(width: Int, height: Int, valid: Boolean): Boolean {
        if (!available || started || stopped || !valid || width <= 0 || height <= 0) return false
        started = true
        return true
    }

    /** Destroying a surface before start must not permanently cancel startup. */
    @Synchronized
    fun destroyed(): Boolean {
        available = false
        if (!started || stopped) return false
        stopped = true
        return true
    }

    @Synchronized
    fun stop() {
        stopped = true
        available = false
    }
}

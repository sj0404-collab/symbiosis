// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later
package org.yuzu.yuzu_emu.utils

import org.kenjinx.android.KenjinxNative
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** One isolated player's UI channel. Stop never waits on native code or UI. */
class KenjiUiSession(
    postMain: (() -> Unit) -> Unit,
    present: (Long, KenjiUiRequest) -> Unit,
    dismiss: () -> Unit,
    onError: (String) -> Unit,
    private val activate: () -> Long,
    private val install: (KenjinxNative.UiCallback) -> Unit,
    private val setup: () -> Boolean,
    respond: (Long, Boolean, ByteArray) -> Boolean,
) : KenjinxNative.UiCallback {
    private val started = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)
    private val generation = AtomicLong(0)
    private val dispatcher = KenjiUiDispatcher(postMain, present, dismiss,
        { accepted, bytes -> respond(generation.get(), accepted, bytes) }, onError)

    /** Called by the initialization worker after the core is loaded, before device init.
     * If stop races setup, keep the listener installed and abort device startup;
     * the process owns it until exit because callback-thread shutdown is unproven. */
    fun start(): Boolean {
        if (!started.compareAndSet(false, true) || stopped.get()) return false
        val token = activate()
        if (token <= 0) return false
        generation.set(token)
        install(this)
        if (stopped.get()) return false
        val result = setup()
        return result && !stopped.get()
    }

    override fun onRequest(title: Long, message: Long, watermark: Long, type: Int,
                           min: Int, max: Int, mode: Int, subtitle: Long, initial: Long) =
        dispatcher.onRequest(title, message, watermark, type, min, max, mode, subtitle, initial)

    fun answer(id: Long, accepted: Boolean, text: String) = dispatcher.answer(id, accepted, text)
    fun stop() { stopped.set(true); dispatcher.stop() }

    /** Diagnostic only when callback-producing guest threads are known stopped. */
    fun closeAfterGuestStopped() = dispatcher.closeAfterGuestStopped()
}

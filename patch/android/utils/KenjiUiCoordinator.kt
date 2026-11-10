// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later
package org.yuzu.yuzu_emu.utils

import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Copied values only: the core's native pointers never leave the callback. */
data class KenjiUiRequest(
    val title: String,
    val message: String,
    val watermark: String,
    val type: Int,
    val min: Int,
    val max: Int,
    val mode: Int,
    val subtitle: String,
    val initialText: String,
    val error: String? = null,
)

/**
 * One player's dialog queue. Presentation is posted to the caller's main
 * thread; answers run on an independent serial worker, never on the UI thread
 * or the loader lock. No method assumes the worker has exited on a timeout.
 */
class KenjiUiCoordinator(
    private val postMain: (() -> Unit) -> Unit,
    private val present: (Long, KenjiUiRequest) -> Unit,
    private val dismiss: () -> Unit,
    private val reply: (Boolean, ByteArray) -> Boolean,
    private val onError: (String) -> Unit,
) {
    private data class Pending(val id: Long, val request: KenjiUiRequest)
    private val replies = Executors.newSingleThreadExecutor { task ->
        Thread(task, "kenji-ui-reply")
    }
    private val pending = ArrayDeque<Pending>()
    private var active: Pending? = null
    private var nextId = 0L
    private var sending = false
    private var stopping = false
    private var closed = false

    /** Runs on a core thread. The request must already own all of its text. */
    @Synchronized
    fun request(value: KenjiUiRequest) {
        if (closed) return // owner has declared every core caller stopped
        if (stopping) {
            if (value.type != 1) enqueueReply(false, byteArrayOf())
            return
        }
        if (value.error != null || value.type !in 1..2 ||
            (value.type == 2 && (value.min < 0 || value.max < value.min))) {
            if (value.type != 1) enqueueReply(false, byteArrayOf())
            postMain { onError(value.error ?: "Второе ядро запросило неподдерживаемый диалог.") }
            return
        }
        pending.addLast(Pending(++nextId, value))
        showNext()
    }

    // Called only while holding this monitor. Posted work verifies that it is
    // still current, so a stopped or replaced Activity cannot resurrect a UI.
    private fun showNext() {
        if (stopping || sending || active != null || pending.isEmpty()) return
        val current = pending.removeFirst()
        active = current
        postMain {
            val stillCurrent = synchronized(this) { !stopping && active?.id == current.id }
            if (stillCurrent) present(current.id, current.request)
        }
    }

    /** One response per request; invalid text stays editable instead of truncating. */
    @Synchronized
    fun answer(id: Long, accepted: Boolean, text: String): Boolean {
        val current = active ?: return false
        if (stopping || current.id != id) return false
        // Both pinned cores return from an error-message callback immediately.
        // Replying later writes to the shared input response channel and can
        // wake a different keyboard request (Android ARM mixed-applet test).
        // This does not establish semantics of every possible type-1 caller.
        if (current.request.type == 1) {
            active = null
            showNext()
            return true
        }
        val actual = if (accepted && current.request.type == 2) text else ""
        if (accepted && current.request.type == 2 &&
            !KenjiUiText.withinBounds(actual, current.request.min, current.request.max)) return false
        val bytes = KenjiUiText.encode(actual) ?: return false
        active = null
        sending = true
        replies.execute {
            val ok = runCatching { reply(accepted, bytes) }.getOrDefault(false)
            synchronized(this) {
                sending = false
                if (!ok && !stopping) postMain { onError("Не удалось передать ответ второму ядру.") }
                showNext()
            }
        }
        return true
    }

    // Called only from the monitor. The executor serializes cancellation with
    // an already selected answer; the reply is never made on the main thread.
    private fun enqueueReply(accepted: Boolean, bytes: ByteArray) {
        replies.execute {
            val ok = runCatching { reply(accepted, bytes) }.getOrDefault(false)
            if (!ok) postMain { onError("Не удалось отменить диалог второго ядра.") }
        }
    }

    /** Cancel immediately; do not wait here (this may be called on main). */
    @Synchronized
    fun stop() {
        if (stopping || closed) return
        stopping = true
        active?.let { if (it.request.type != 1) enqueueReply(false, byteArrayOf()) }
        active = null
        while (pending.isNotEmpty()) {
            if (pending.removeFirst().request.type != 1) enqueueReply(false, byteArrayOf())
        }
        postMain(dismiss)
    }

    /**
     * Wait for replies queued before this barrier. Call only on a stop worker,
     * never the Android main thread. This does NOT prove that the guest cannot
     * issue another callback later; its lifetime still belongs to the process.
     */
    fun awaitPendingReplies() {
        val barrier = synchronized(this) {
            check(stopping && !closed)
            replies.submit { }
        }
        barrier.get()
    }

    /** Only after the owner has joined all callback-producing native threads. */
    fun closeAfterGuestStopped() {
        synchronized(this) {
            check(stopping)
            if (!closed) {
                closed = true
                replies.shutdown()
            }
        }
        // Every caller waits, including one arriving after shutdown started:
        // returning early could let that caller unload an in-flight response.
        // Must run in teardown, never on Android's main thread. There is no
        // fabricated success timeout: a hung core callback keeps the lease.
        while (!replies.awaitTermination(1, TimeUnit.DAYS)) { /* wait */ }
    }
}

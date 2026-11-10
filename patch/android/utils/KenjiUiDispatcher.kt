// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later
package org.yuzu.yuzu_emu.utils

import org.kenjinx.android.KenjinxNative

/**
 * A session's callback adapter: copy native data on the calling thread before
 * returning to the core, then hand owned values to the UI coordinator.
 * The owner must install it before core UI setup, cancel on stop, and retain
 * it while any native callback could still arrive. It is not a core itself.
 */
class KenjiUiDispatcher(
    postMain: (() -> Unit) -> Unit,
    present: (Long, KenjiUiRequest) -> Unit,
    dismiss: () -> Unit,
    reply: (Boolean, ByteArray) -> Boolean,
    onError: (String) -> Unit,
) : KenjinxNative.UiCallback {
    private val coordinator = KenjiUiCoordinator(postMain, present, dismiss, reply, onError)

    private fun copy(pointer: Long): String =
        KenjiUiText.decode(KenjinxNative.nativeCopyUiText(pointer)
            ?: error("native string too long")) ?: error("invalid UTF-8")

    override fun onRequest(title: Long, message: Long, watermark: Long, type: Int,
                           min: Int, max: Int, mode: Int, subtitle: Long, initial: Long) {
        val request = try {
            KenjiUiRequest(copy(title), copy(message), copy(watermark), type, min,
                max, mode, copy(subtitle), copy(initial))
        } catch (_: Throwable) {
            // The coordinator cancels a waiting input on malformed data, but
            // never sends a response for a nonblocking type-1 notification.
            KenjiUiRequest("", "", "", type, min, max, mode, "", "",
                error = "Не удалось прочитать запрос второго ядра.")
        }
        coordinator.request(request)
    }

    fun answer(id: Long, accepted: Boolean, text: String): Boolean =
        coordinator.answer(id, accepted, text)

    fun stop() = coordinator.stop()
    fun awaitPendingReplies() = coordinator.awaitPendingReplies()
    fun closeAfterGuestStopped() = coordinator.closeAfterGuestStopped()
}

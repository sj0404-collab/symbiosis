// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.kenjinx.android

import java.util.concurrent.atomic.AtomicLong

/**
 * Callbacks the Kenji core looks up by this exact class name.
 * Without it javaInitialize / GetSurfacePtr crash or no-op.
 * This is a shim, not Kenji's UI.
 *
 * ЗАЧЕМ ЗДЕСЬ СЧЁТЧИКИ. «Игра идёт, но экран чёрный» невозможно отличить от
 * «ядро стоит на загрузке», пока не видно, доходят ли до нас его собственные
 * вызовы. Ядро зовёт `frameEnded()` на КАЖДЫЙ показанный кадр (это его
 * SetSwapBuffersCallback) и `getSurfacePtr()` один раз, когда создаёт
 * поверхность Vulkan. Значит:
 *
 *   кадров 0, окно спрашивали 0  — цикл не дошёл до отрисовки;
 *   кадров 0, окно спрашивали ≥1 — поверхность создана, но кадры не идут;
 *   кадров растёт, экран чёрный  — кадры есть, вопрос в выводе на экран.
 *
 * Три разных диагноза вместо одного «висит».
 */
object KenjinxNative {
    @Volatile var nativeSurface: Long = -1
    @Volatile var nativeWindow: Long = -1

    /** Сколько кадров ядро уже отрисовало. Растёт — значит цикл живой. */
    val frames = AtomicLong(0)

    /** Сколько раз ядро спрашивало указатель на поверхность. */
    val surfaceAsked = AtomicLong(0)

    /** Сколько раз ядро спрашивало дескриптор окна. */
    val windowAsked = AtomicLong(0)

    /** Что последним передавал обработчик прогресса загрузки. */
    @Volatile var lastProgress: Float = -1f

    @JvmStatic
    fun test() { /* official no-op */ }

    /**
     * Кадр показан. Вызывается ядром на каждый swap buffers.
     *
     * Тело намеренно короткое: это горячий путь, и любая работа здесь
     * отнимает кадровое время у самой игры.
     */
    @JvmStatic
    fun frameEnded() {
        frames.incrementAndGet()
    }

    /**
     * Прогресс загрузки от ядра: `updateProgress(infoPtr, progress)`.
     *
     * Не только запоминаем: в официальном приложении это полоса загрузки, и
     * именно по ней видно, что ядро ещё собирает шейдеры, а не висит.
     */
    @JvmStatic
    fun updateProgress(infoPtr: Long, progress: Float) {
        lastProgress = progress
    }

    @JvmStatic
    fun getSurfacePtr(): Long {
        surfaceAsked.incrementAndGet()
        return nativeSurface
    }

    @JvmStatic
    fun getWindowHandle(): Long {
        windowAsked.incrementAndGet()
        return nativeWindow
    }

    /** Must return only after the listener has copied any native strings.
     * Installed per isolated player session before uiHandlerSetup. */
    fun interface UiCallback {
        fun onRequest(title: Long, message: Long, watermark: Long, type: Int,
                      min: Int, max: Int, mode: Int, subtitle: Long, initial: Long)
    }
    @Volatile var uiCallback: UiCallback? = null

    /** Copies native bytes synchronously; decoding must use standard UTF-8. */
    @JvmStatic external fun nativeCopyUiText(pointer: Long): ByteArray?

    @JvmStatic
    fun updateUiHandler(
        newTitlePointer: Long,
        newMessagePointer: Long,
        newWatermarkPointer: Long,
        newType: Int,
        min: Int,
        max: Int,
        nMode: Int,
        newSubtitlePointer: Long,
        newInitialTextPointer: Long
    ) {
        uiCallback?.onRequest(newTitlePointer, newMessagePointer, newWatermarkPointer,
            newType, min, max, nMode, newSubtitlePointer, newInitialTextPointer)
    }

    /** Счётчики одной строкой — для статуса плеера и отчёта. */
    fun stats(): String =
        "кадров: ${frames.get()} · окно спрашивали: ${surfaceAsked.get()}/${windowAsked.get()}" +
            if (lastProgress >= 0f) " · загрузка: ${(lastProgress * 100).toInt()}%" else ""

    /** Сбросить счётчики перед новым запуском. */
    fun resetStats() {
        frames.set(0)
        surfaceAsked.set(0)
        windowAsked.set(0)
        lastProgress = -1f
    }
}

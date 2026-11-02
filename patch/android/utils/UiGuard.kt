// SPDX-FileCopyrightText: Copyright 2026 Symbiosis
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Записывает необработанное исключение приложения в файл.
 *
 * ЗАЧЕМ. Сообщение «Eden Symbiosis Debug остановлено» — это всё, что видит
 * человек, когда приложение падает в своём коде: система закрывает процесс и
 * ничего не показывает. Без записи причина теряется вместе с процессом, и
 * панель настроек можно править вслепую сколько угодно.
 *
 * Здесь ставится обработчик необработанных исключений: он успевает положить
 * тип, текст и стек в `symbiosis/ui-crash.txt` и только потом передаёт ошибку
 * системному обработчику — поведение приложения не меняется, добавляется
 * только запись.
 *
 * Нативный сбой ядра сюда не попадает — для него есть отчёт моста
 * ([KenjiCrash]); это два разных источника и оба нужны.
 */
object UiGuard {
    @Volatile
    private var installed = false

    /** Куда пишем: `<внешние файлы>/symbiosis/ui-crash.txt`. */
    fun file(context: Context): File =
        File(File(context.getExternalFilesDir(null), "symbiosis"), "ui-crash.txt")

    /**
     * Поставить обработчик один раз на процесс.
     *
     * Вызывается при запуске игры — это первый экран, где падение наиболее
     * заметно и наиболее обидно, и там точно есть контекст приложения.
     */
    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(app, thread, error) }
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
    }

    /**
     * Включить отчёт для НАТИВНЫХ падений основного процесса.
     *
     * Java-обработчик выше не видит SIGSEGV внутри libyuzu-android.so: процесс
     * умирает, не дойдя до Java. А именно так и выглядит падение эмуляции —
     * «Приложение остановлено» и ни слова больше. Нативный обработчик пишет в
     * тот же файл шапку и кадры стека: по ним видно библиотеку.
     */
    fun armNative(context: Context) {
        if (nativeArmed) return
        nativeArmed = true
        runCatching {
            val target = file(context)
            target.parentFile?.mkdirs()
            NativeSymbiosis.armMainCrashLog(target.absolutePath)
        }
    }

    @Volatile
    private var nativeArmed = false

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val target = file(context)
        target.parentFile?.mkdirs()
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        target.appendText(KenjiCrash.describeThrowable(thread.name, error, stamp) + "\n")
    }

    /**
     * Причина прошлого падения, если она есть.
     *
     * Читается при следующем запуске: человек должен узнать причину, а не
     * гадать по надписи в шторке. Возвращает null, когда падения не было.
     */
    fun pending(context: Context): String? = runCatching {
        val target = file(context)
        if (!target.isFile) return null
        val text = target.readText().trim()
        if (text.isEmpty()) return null
        text
    }.getOrNull()

    /**
     * Забрать запись: переименовать в `ui-crash-last.txt`.
     *
     * Нужно, чтобы одна и та же причина не показывалась при каждом запуске.
     * Файл не стирается — его видно в папке, и он остаётся для отчёта.
     */
    fun consume(context: Context) {
        runCatching {
            val target = file(context)
            if (!target.isFile) return@runCatching
            val last = File(target.parentFile, "ui-crash-last.txt")
            if (last.exists()) last.delete()
            target.renameTo(last)
        }
    }

    /** Последние строки записи — для показа рядом с отчётом о сбое ядра. */
    fun lastLines(context: Context, limit: Int = 12): List<String> = runCatching {
        val target = file(context)
        if (!target.isFile) return emptyList()
        target.readLines().filter { it.isNotBlank() }.takeLast(limit)
    }.getOrDefault(emptyList())
}

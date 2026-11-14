// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Причина прошлого запуска на втором ядре, пережившая смерть процесса.
 *
 * ЗАЧЕМ. Игра запускается в отдельном процессе `:kenji` — так задумано, чтобы
 * падение ядра не закрывало библиотеку. Побочная сторона: когда процесс
 * умирает, экран просто пропадает, и человек возвращается в список игр с
 * чёрным экраном в памяти и без единого слова о причине. Ровно это и
 * случилось при первом запуске TMNT: чёрный экран, вылет в лаунчер, отчёт
 * отсутствует, потому что до сих пор отчёт включала только «проверка ядра».
 *
 * Здесь две вещи, которых не хватало:
 *   1. метка «начали» пишется ДО первого касания ядра, а «вышли штатно» —
 *      только в `onDestroy` плеера. Пропала метка о штатном выходе — значит
 *      процесс умер, и это видно при возврате в библиотеку;
 *   2. сам отчёт собирается в ОСНОВНОМ процессе из файлов, которые оставил
 *      умерший: шаг, сигнал, кадры, stdout ядра и его собственный журнал.
 *
 * Показ — диалог с кнопкой «Скопировать»: причину нужно не только прочитать,
 * но и переслать, а выделять текст в диалоге на телефоне неудобно.
 */
object KenjiReport {

    private const val MARK_START = "начали"
    private const val MARK_CLEAN = "вышли штатно"

    private fun stamp(): String =
        SimpleDateFormat("dd.MM HH:mm:ss", Locale.ROOT).format(Date())

    /** Файл-метка рядом с отчётом о падении — во внешней папке `kenji`. */
    fun stateFile(context: Context): File =
        File(KenjiBridge.crashReportFile(context).parentFile, "play-state.txt")

    /**
     * Запуск начат. Пишется до первого касания ядра — иначе падение на
     * первых же вызовах останется без метки.
     */
    fun markStart(context: Context) {
        runCatching {
            stateFile(context).parentFile?.mkdirs()
            stateFile(context).writeText("$MARK_START ${stamp()}\n")
        }
        // Прошлый отчёт Java-ошибки в папке не трогаем, но и не выдаём за
        // сегодняшний: он переименовывается. Иначе в отчёте этого запуска
        // появляется падение ЧУЖОГО процесса из прошлой сессии — 05.11 так
        // и вышло: в причине стоял SIGSEGV в libyuzu-android.so, которого в
        // этом запуске не было.
        runCatching {
            val alive = UiGuard.file(context)
            if (alive.isFile && alive.length() > 0) {
                val prev = File(alive.parentFile, "ui-crash-prev.txt")
                if (prev.exists()) prev.delete()
                alive.renameTo(prev)
            }
        }
    }

    /** Плеер дошёл до своего `onDestroy` — запуск закончился штатно. */
    fun markClean(context: Context) {
        runCatching {
            stateFile(context).parentFile?.mkdirs()
            stateFile(context).writeText("$MARK_CLEAN ${stamp()}\n")
        }
    }

    /**
     * Файл наблюдения за идущей игрой.
     *
     * ЗАЧЕМ ОТДЕЛЬНЫЙ ФАЙЛ. Снимок экрана сделать некогда: игра закрывается
     * или висит, а человек не успевает нажать. Поэтому раз в три секунды
     * плеер пишет сюда, что видно: сколько идёт, растут ли кадры, спрашивало
     * ли ядро окно. После падения эти строки попадают в причину — их не надо
     * ни фотографировать, ни переписывать.
     */
    fun watchFile(context: Context): File =
        File(KenjiBridge.crashReportFile(context).parentFile, "play-watch.txt")

    /** Записать строку наблюдения. Держим только последние 12. */
    fun watch(context: Context, line: String) {
        runCatching {
            val file = watchFile(context)
            val old = if (file.isFile) file.readLines() else emptyList()
            val text = (old + "${stamp()} · $line").takeLast(12).joinToString("\n")
            file.writeText(text + "\n")
        }
    }

    /** Хвост наблюдения — для причины падения. */
    fun watchTail(context: Context, lines: Int = 3): String =
        runCatching {
            watchFile(context).takeIf { it.isFile }
                ?.readLines()?.filter { it.isNotBlank() }?.takeLast(lines)
                ?.joinToString("\n")
        }.getOrDefault("").orEmpty()

    /**
     * Причина прошлого запуска, если он не закончился штатно.
     *
     * Метка гасится ДО показа: `onResume` у фрагментов вызывается не один раз,
     * и без этого один и тот же диалог всплывал бы при каждом возврате.
     */
    fun pendingReason(context: Context): String? {
        val text = runCatching {
            stateFile(context).takeIf { it.isFile }?.readText().orEmpty().trim()
        }.getOrDefault("")
        if (!text.startsWith(MARK_START)) return null
        runCatching { stateFile(context).writeText("") }

        val report = runCatching { KenjiProbeService.crashMessage(context) }
            .getOrNull().orEmpty().trim()
        val watch = watchTail(context)
        val body = if (report.isEmpty()) {
            "Ядро остановилось до первой записи: ни шага, ни сигнала, ни журнала.\n" +
                "Это значит, что процесс завершился до того, как мост успел что-то записать. " +
                "Повторите запуск — на этот раз обязательно останется хотя бы шаг."
        } else {
            report
        }
        // Решение «эта игра больше не идёт этим ядром» принимает главный
        // процесс: он видит библиотеку. Плеер лишь оставил записку.
        val fallback = runCatching { CoreFallback.applyIfAny(context) }.getOrNull().orEmpty()
        return buildString {
            append(text).append("\n\n").append(body)
            if (watch.isNotEmpty()) {
                append("\n\nЧто было видно на экране перед остановкой:\n").append(watch)
            }
            if (fallback.isNotEmpty()) {
                append("\n\n").append(fallback)
            }
        }
    }

    private val reportWorkerActive = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Never read logs or conclude a session ended while its process owns the lease. */
    fun showPendingIfAny(context: Context) {
        if (!reportWorkerActive.compareAndSet(false, true)) return
        val app = context.applicationContext
        val activity = context as? android.app.Activity
        Thread({
            try {
                // onResume can happen before the isolated player has finished
                // teardown and written its clean marker. Wait off the UI thread.
                repeat(120) {
                    val start = runCatching { stateFile(app).readText() }.getOrDefault("")
                    if (!start.startsWith(MARK_START)) return@Thread
                    when (val result = GameSessionLock.tryAcquire(File(app.filesDir, GameSessionLock.FILE_NAME))) {
                        is GameSessionLock.Result.Acquired -> {
                            val text = try { pendingReason(app) } finally { result.lease.close() }
                            if (!text.isNullOrEmpty()) {
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    if (activity == null || (!activity.isFinishing && !activity.isDestroyed)) {
                                        runCatching { show(context, text) }
                                    }
                                }
                            }
                            return@Thread
                        }
                        is GameSessionLock.Result.Failed -> return@Thread
                        GameSessionLock.Result.Busy -> Thread.sleep(250)
                    }
                }
                // Unknown/still busy: leave the marker intact for a later resume.
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                reportWorkerActive.set(false)
            }
        }, "kenji-report-reader").start()
    }

    private fun show(context: Context, text: String) {
        val body = TextView(context).apply {
            this.text = text
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setPadding(24, 16, 24, 0)
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(context).apply { addView(body) }
        MaterialAlertDialogBuilder(context)
            .setTitle("Состояние предыдущего сеанса")
            .setView(scroll)
            .setPositiveButton("Скопировать", null)
            .setNegativeButton("Закрыть", null)
            .create()
            .apply {
                setOnShowListener {
                    getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val clipboard =
                            context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Symbiosis", text))
                        Toast.makeText(context, "Причина скопирована", Toast.LENGTH_SHORT).show()
                    }
                }
                show()
            }
    }
}

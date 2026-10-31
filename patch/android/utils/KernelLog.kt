// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import android.content.Context
import android.content.Intent
import java.io.File

/**
 * Журнал ядра: файл, который эмулятор пишет сам.
 *
 * ПОЧЕМУ ЭТО ПОНАДОБИЛОСЬ
 *
 *   «Ядро — нет нигде дебаг журнала». Так и было: ядро пишет в logcat, а
 *   logcat приложению недоступен — с Android 4.1 читать буфер может только
 *   система. Своих логов у оболочки было два: кольцевой буфер Symbiosis (сто
 *   последних решений слоя) и отчёт о падении, который собирается уже после
 *   беды. Обычный запуск, в котором «игра не пошла», не оставлял ничего.
 *
 *   На самом деле ядро файл уже пишет: в Eden есть FileBackend, он кладёт
 *   журнал в папку `log` и прошлый сеанс держит рядом с суффиксом `.old`.
 *   Не было только экрана, который это показывает. Здесь — чтение и разбор,
 *   экран — в KernelLogFragment.
 *
 * ПОЧЕМУ ПАПКА СПРАШИВАЕТСЯ У ЯДРА
 *   Корень данных настраивается (SharedDataDirectory), и после переноса в
 *   общую папку журнал лежит совсем не там, где его ищет догадка «Android/data
 *   /<пакет>/files/log». Один вопрос к ядру — и путь всегда верный.
 */
object KernelLog {

    /** Сколько последних байт показывать. Хвост важнее начала сеанса. */
    const val TAIL_BYTES = KernelLogFormat.TAIL_BYTES

    data class Chunk(
        /** Имя файла: текущий сеанс или прошлый (`*.old.txt`). */
        val name: String,
        /** Путь на диске. */
        val path: String,
        val text: String,
        val sizeBytes: Long,
        /** Сколько строк с ошибками и предупреждениями — для сводки. */
        val problems: Int
    )

    /** Папка журнала по мнению самого ядра, или null. */
    fun directory(): File? = runCatching { File(NativeSymbiosis.logDirectory()) }
        .getOrNull()
        ?.takeIf { it.isDirectory }

    /**
     * Файлы журнала, свежие сверху.
     *
     * Берутся только текстовые: ядро пишет `*.txt`, рядом могут лежать
     * дампы, и показывать в этом списке бинарник незачем.
     */
    fun files(): List<File> {
        val dir = directory() ?: return emptyList()
        return dir.listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension.equals("txt", ignoreCase = true) }
            .sortedByDescending { it.lastModified() }
    }

    /** Последний сеанс: текущий файл, а если его нет — прошлый. */
    fun newest(): Chunk? = files().firstOrNull()?.let { read(it) }

    fun read(file: File): Chunk? = runCatching {
        val text = tailOf(file, TAIL_BYTES)
        Chunk(
            name = file.name,
            path = file.absolutePath,
            text = text,
            sizeBytes = file.length(),
            problems = countProblems(text)
        )
    }.getOrNull()

    /** Хвост файла: см. [KernelLogFormat.tailOf] — там же объяснение. */
    fun tailOf(file: File, maxBytes: Int): String = KernelLogFormat.tailOf(file, maxBytes)

    /** Сколько ошибок в тексте: см. [KernelLogFormat.countProblems]. */
    fun countProblems(text: String): Int = KernelLogFormat.countProblems(text)

    /** Удалить все журналы. Возвращает, сколько файлов убрано. */
    fun clearAll(): Int {
        var removed = 0
        files().forEach { file ->
            if (file.delete()) removed++
        }
        return removed
    }

    /** Отдать журнал наружу: почта, мессенджер, «Сохранить в файлы». */
    fun share(context: Context, chunk: Chunk?) {
        val text = chunk?.text.orEmpty()
        if (text.isBlank()) return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Журнал ядра Eden — ${chunk!!.name}")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        runCatching {
            context.startActivity(
                Intent.createChooser(intent, "Отправить журнал ядра")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}

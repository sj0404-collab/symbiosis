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

    /**
     * Архив сеансов: `log/sessions/`.
     *
     * Ядро держит только текущий журнал и один прошлый — третий запуск стирает
     * первый. Поэтому каждый сеанс копируется сюда перед новым запуском, и
     * ничто в приложении этот архив не удаляет: только лимит по размеру,
     * см. [KernelLogArchive].
     */
    fun sessionsDir(): File? {
        val dir = directory() ?: return null
        val sessions = File(dir, "sessions")
        return if (sessions.isDirectory || runCatching { sessions.mkdirs() }.getOrDefault(false)) {
            sessions
        } else {
            null
        }
    }

    /** Сеансы, свежие сверху. */
    fun sessions(): List<File> {
        val dir = sessionsDir() ?: return emptyList()
        return dir.listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension.equals("txt", ignoreCase = true) }
            .sortedByDescending { it.name }
    }

    /**
     * Сохранить текущий журнал в архив, если он ещё не сохранён.
     *
     * Вызывается перед запуском игры — то есть ДО того, как ядро переименует
     * файл и начнёт новый сеанс. Возвращает имя созданной копии или null,
     * если копировать нечего (файла нет, он пуст или уже сохранён).
     */
    fun archiveCurrent(): String? {
        val dir = sessionsDir() ?: return null
        val source = files().firstOrNull() ?: return null
        val existing = dir.listFiles().orEmpty().map { it.name }.toHashSet()
        val modified = source.lastModified()
        val size = source.length()
        if (!KernelLogArchive.shouldArchive(modified, size, existing)) return null
        val name = KernelLogArchive.nameFor(modified)
        val target = File(dir, name)
        val copied = runCatching {
            source.copyTo(target, overwrite = false)
            true
        }.getOrDefault(false)
        if (!copied) return null
        prune(dir)
        return name
    }

    /** Убрать лишнее по лимитам. Ничего не делает, пока лимит не превышен. */
    private fun prune(dir: File) {
        val entries = dir.listFiles().orEmpty()
            .filter { it.isFile }
            .sortedBy { it.name }
            .map { it.name to it.length() }
        KernelLogArchive.toPrune(entries).forEach { old ->
            runCatching { File(dir, old).delete() }
        }
    }

    /**
     * Копия журнала в «Загрузки» — чтобы файл пережил удаление приложения.
     *
     * Единственный способ что-то потерять — удалить приложение вместе с его
     * папкой данных; копия в общей памяти переживает и это.
     */
    /**
     * Системный журнал приложения — запасной источник.
     *
     * ЗАЧЕМ. На устройстве экран «Журнал ядра» показал `eden_log.txt · 0 КБ ·
     * ошибок: 0` и «журнал пуст» — при том, что игры запускались. Значит файл
     * создан, а писать в него ядро не стало (свой файловый журнал включается
     * отдельно от системного). Совет «запустите игру» в этом случае неверен и
     * второй раз не поможет.
     *
     * А вот система всё это время писала своё: старт ядра, наши отметки
     * SymbiosisKenji, fatal-строки того же процесса. Это не журнал ядра, но
     * ответить на вопрос «что произошло» по нему можно.
     */
    fun systemLog(lines: Int = 400): String = runCatching {
        val process = ProcessBuilder("logcat", "-d", "-v", "brief", "-t", lines.toString())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        output
    }.getOrDefault("")

    fun exportToDownloads(context: Context, chunk: Chunk?): String? {
        val text = chunk?.text.orEmpty()
        if (text.isBlank()) return null
        return runCatching {
            val base = File(
                android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS
                ),
                "Symbiosis/logs"
            ).apply { mkdirs() }
            val target = File(base, "kernel-${chunk!!.name}")
            target.writeText(text)
            target.absolutePath
        }.getOrNull()
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

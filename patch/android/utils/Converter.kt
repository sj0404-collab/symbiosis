// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.yuzu.yuzu_emu.YuzuApplication

/**
 * NSZ/XCZ/NCZ → файл, который ядро умеет открыть.
 *
 * Три вещи здесь переделаны после отчёта с устройства:
 *
 *  1. **Куда попадают файлы.** Раньше результат складывался в
 *     `files/converter` — приватную папку, которую не видит ни один файловый
 *     менеджер. «Распаковалось, а где — неизвестно». Теперь результат идёт в
 *     `Download/Symbiosis`: видно в проводнике, в «Загрузках» и по кабелю.
 *     Если доступа к общей памяти нет, честно возвращаемся в приватную папку
 *     и говорим об этом, а не делаем вид.
 *  2. **Кто показывает прогресс.** Очередь больше не живёт в голом потоке:
 *     её ведёт [ConverterService] с уведомлением в шторке, так что уход с
 *     экрана не обрывает распаковку.
 *  3. **Что видно в приложении.** [listJson] отдаёт не только готовые файлы,
 *     но и текущее состояние: какой файл в работе, сколько в очереди и где
 *     лежит результат.
 */
object Converter {

    /** Итог последней очереди: для уведомления и для экрана. */
    data class Result(
        val ok: Boolean,
        val count: Int,
        val where: String,
        val message: String
    )

    @Volatile
    var lastResult: Result = Result(false, 0, "", "")
        private set

    @Volatile
    var busy: Boolean = false
        private set

    @Volatile
    var queueNote: String = ""
        private set

    /** Номер файла в работе и всего в очереди — для уведомления. */
    @Volatile
    var currentIndex: Int = 0
        private set

    @Volatile
    var currentTotal: Int = 0
        private set

    @Volatile
    var currentName: String = ""
        private set

    @Volatile
    private var stopRequested: Boolean = false

    private val pending = java.util.ArrayDeque<Pair<Uri, String>>()
    private val done = java.util.ArrayList<JSONObject>()

    // --- Куда складывать ---------------------------------------------------

    /**
     * Общая папка `Download/Symbiosis`.
     *
     * `canWrite()` на путях вне Android/data врёт так же, как врал при выборе
     * корня данных: он отвечает «да» без разрешения и «нет» с ним. Поэтому
     * проверка — настоящая запись пробного файла.
     */
    fun publicDir(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Symbiosis")

    private fun publicWritable(): Boolean = runCatching {
        val dir = publicDir()
        if (!dir.isDirectory && !dir.mkdirs()) return@runCatching false
        val probe = File(dir, ".write-probe")
        val ok = probe.createNewFile() || probe.exists()
        if (ok) probe.delete()
        ok
    }.getOrDefault(false)

    /** Приватная папка — запасной вариант, всегда доступна. */
    fun privateDir(): File =
        File(YuzuApplication.appContext.filesDir, "converter").also { it.mkdirs() }

    /** Куда пишем сейчас. */
    fun dir(): File = if (publicWritable()) publicDir() else privateDir()

    /** Где сейчас лежат файлы, словами для экрана и уведомления. */
    fun whereText(): String {
        val f = dir()
        return if (f == privateDir()) {
            "${f.absolutePath} (приватная папка приложения: нет доступа к «Загрузкам»)"
        } else {
            f.absolutePath
        }
    }

    /** Все папки с результатами: текущая и старая приватная. */
    private fun allDirs(): List<File> = listOf(dir(), privateDir()).distinctBy { it.absolutePath }

    // --- Состояние ---------------------------------------------------------

    @Synchronized
    fun progressText(): String {
        if (!busy) return if (lastResult.ok) "готово · ${lastResult.count}" else ""
        if (currentName.isBlank()) return "подготовка…"
        return if (currentTotal > 1) "$currentName · $currentIndex из $currentTotal" else currentName
    }

    fun queueJson(): String = JSONObject().apply {
        put("busy", busy)
        put("note", queueNote)
        put("pending", synchronized(pending) { pending.size })
        put("current", currentName)
        put("index", currentIndex)
        put("total", currentTotal)
        put("where", whereText())
        val arr = JSONArray()
        synchronized(done) { done.takeLast(12) }.forEach { arr.put(it) }
        put("done", arr)
    }.toString()

    /**
     * Список готовых файлов.
     *
     * Читается с диска, а не из памяти: файл мог остаться от прошлого запуска
     * или быть удалён через проводник, и список обязан это отражать.
     */
    fun listJson(): String {
        val arr = JSONArray()
        allDirs().forEach { d ->
            d.listFiles()?.filter { it.isFile && !it.name.startsWith("in-") }
                ?.sortedByDescending { it.lastModified() }
                ?.forEach { f -> arr.put(probeFile(f)) }
        }
        return JSONObject()
            .put("items", arr)
            .put("path", dir().absolutePath)
            .put("where", whereText())
            .put("busy", busy)
            .put("current", currentName)
            .put("index", currentIndex)
            .put("total", currentTotal)
            .toString()
    }

    // --- Очередь -----------------------------------------------------------

    /**
     * Ставит файлы в очередь и поднимает службу.
     *
     * Сама работа начинается в [ConverterService]: если запускать её здесь,
     * то при закрытии экрана очередь остаётся без процесса и без
     * уведомления — ровно то, на что жаловались.
     */
    fun enqueue(context: Context, uris: List<Uri>): String {
        uris.forEach { uri -> synchronized(pending) { pending.addLast(uri to displayName(context, uri)) } }
        stopRequested = false
        queueNote = "в очереди ${synchronized(pending) { pending.size }} · можно уйти с экрана"
        if (!busy) ConverterService.start(context)
        return queueJson()
    }

    /** Просьба остановиться после текущего файла. */
    fun requestStop() {
        stopRequested = true
        queueNote = "останавливаюсь после текущего файла"
    }

    /**
     * Разбирает очередь. Вызывается службой; повторный вход безопасен —
     * второй поток увидит `busy` и вернётся.
     */
    fun runQueue(context: Context, onProgress: (index: Int, total: Int, name: String) -> Unit = { _, _, _ -> }) {
        synchronized(this) {
            if (busy) return
            busy = true
        }
        var okCount = 0
        var lastError = ""
        var lastWhere = ""
        try {
            val total = synchronized(pending) { pending.size }
            currentTotal = total
            var index = 0
            while (!stopRequested) {
                val item = synchronized(pending) { if (pending.isEmpty()) null else pending.removeFirst() }
                    ?: break
                index++
                currentIndex = index
                currentName = item.second
                queueNote = "распаковка: ${item.second}" +
                    if (total > 1) " · $index из $total" else ""
                onProgress(index, total, item.second)

                val raw = importAndConvert(context, item.first)
                val obj = runCatching { JSONObject(raw) }.getOrDefault(
                    JSONObject().put("ok", false).put("message", raw)
                )
                obj.put("source", item.second)
                if (obj.optBoolean("ok")) {
                    okCount++
                } else {
                    lastError = obj.optString("message")
                }
                synchronized(done) { done.add(obj) }
            }
            lastWhere = dir().absolutePath
            currentName = ""
            queueNote = when {
                okCount > 0 -> "готово · $okCount · $lastWhere"
                stopRequested -> "остановлено"
                else -> lastError.ifBlank { "ничего не распаковано" }
            }
            lastResult = Result(okCount > 0, okCount, lastWhere, lastError)
        } finally {
            busy = false
            currentIndex = 0
            currentTotal = 0
            currentName = ""
        }
    }

    // --- Один файл ---------------------------------------------------------

    fun importAndConvert(context: Context, uri: Uri): String = runCatching {
        val name = displayName(context, uri).ifBlank { "dump.bin" }
        // Копия входящего — всегда в приватной папке: в «Загрузках» не
        // должно появляться временных `in-…` файлов, которые пользователь
        // увидит и не поймёт.
        val incoming = File(privateDir(), "in-${System.currentTimeMillis()}-${safe(name)}")
        val copied = context.contentResolver.openInputStream(uri)?.use { input ->
            incoming.outputStream().use { input.copyTo(it) }
            incoming.length() > 0
        } ?: false
        if (!copied) {
            incoming.delete()
            return@runCatching fail("не удалось прочитать файл", name)
        }
        val ext = name.substringAfterLast('.', "").lowercase()
        val outName = when (ext) {
            "nsz" -> stem(name) + ".nsp"
            "xcz" -> stem(name) + ".nsp"
            "ncz" -> stem(name) + ".nca"
            "xci", "nsp", "nca", "nro" -> safe(name)
            else -> stem(name) + ".bin"
        }
        val dest = File(dir(), outName)
        val (bytes, error) = NativeSymbiosis.decompress(incoming.absolutePath, dest.absolutePath)
        incoming.delete()
        if (bytes <= 0L || !dest.isFile) {
            dest.delete()
            return@runCatching fail(error.ifBlank { "конвертация не вышла" }, name)
        }
        val card = probeFile(dest)
        card.put("ok", card.optBoolean("canLaunch"))
        card.put("source", name)
        if (!card.optBoolean("canLaunch")) {
            card.put("message", card.optString("reason").ifBlank { "файл есть, но открыть нельзя" })
        } else {
            card.put("message", "Поздравляю: можно открыть · ${card.optString("title")}")
        }
        card.toString()
    }.getOrElse {
        fail(it.message ?: "сбой конвертера", "?")
    }

    /**
     * Кладёт готовый файл в «Загрузки» отдельной копией.
     *
     * Нужно, когда результат всё-таки оказался в приватной папке (нет
     * доступа к общей памяти): так файл всё равно можно достать, не
     * подключая телефон к компьютеру.
     */
    fun export(path: String): String = runCatching {
        val src = File(path)
        if (!src.isFile) return@runCatching JSONObject().put("ok", false)
            .put("message", "файла уже нет")
        val parent = target.parentFile ?: publicDir()
        if (!parent.isDirectory && !parent.mkdirs()) {
            return@runCatching JSONObject().put("ok", false).put("message", "нет доступа к «Загрузкам»")
        }
        src.copyTo(target, overwrite = true)
        JSONObject().put("ok", true).put("path", target.absolutePath)
            .put("message", "сохранено в ${target.absolutePath}")
    }.getOrElse {
        JSONObject().put("ok", false).put("message", it.message ?: "не удалось сохранить")
    }.toString()

    fun delete(path: String): String {
        val roots = allDirs().map { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }
        val f = File(path)
        if (!f.exists()) return fail("уже нет", path)
        val canonical = runCatching { f.canonicalPath }.getOrDefault(f.absolutePath)
        val inside = roots.any { canonical.startsWith(it) }
        val ok = inside && runCatching { f.delete() }.getOrDefault(false)
        return JSONObject().put("ok", ok).put("path", path).toString()
    }

    fun canOpen(path: String): Boolean =
        runCatching { GameMetadata.getIsValid(path) }.getOrDefault(false)

    private fun probeFile(f: File): JSONObject {
        val path = f.absolutePath
        val launch = canOpen(path)
        val title = if (launch) {
            runCatching { GameMetadata.getTitle(path) }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: f.name
        } else {
            f.name
        }
        val reason = if (launch) "" else runCatching {
            NativeSymbiosis.romProblem(path)
        }.getOrNull().orEmpty().ifBlank { "ядро не принимает этот файл" }
        return JSONObject().apply {
            put("ok", launch)
            put("canLaunch", launch)
            put("path", path)
            put("name", f.name)
            put("title", title)
            put("bytes", f.length())
            put("size", GameFolderScanner.humanSize(f.length()))
            put("reason", reason)
            put("message", if (launch) "можно открыть" else reason)
        }
    }

    private fun displayName(context: Context, uri: Uri): String {
        val q = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()
        return q?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "dump.bin"
    }

    private fun safe(name: String): String =
        name.substringAfterLast('/').map { if (it.isLetterOrDigit() || it in "._-") it else '_' }
            .joinToString("").take(80).ifBlank { "dump.bin" }

    private fun stem(name: String): String {
        val n = safe(name)
        return n.substringBeforeLast('.').ifBlank { n }
    }

    private fun fail(reason: String, file: String): String =
        JSONObject().put("ok", false).put("canLaunch", false).put("message", reason)
            .put("file", file).put("reason", reason).toString()
}

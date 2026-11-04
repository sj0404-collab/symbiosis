// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * «Открыть по частям»: локальный адрес, который отдаёт файл с сервера
 * кусками — ровно столько, сколько успел посмотреть плеер.
 *
 * ЗАЧЕМ ТАКОЙ СЕРВЕР ВНУТРИ ПРИЛОЖЕНИЯ
 *   Плеер (Media3/ExoPlayer) умеет запрашивать смещения `Range: bytes=…`.
 *   Открытые зеркала этот заголовок понимают, но не все: у части серверов
 *   нет CORS и нет прямого доступа из локального плеера, который ждёт
 *   обычный `http://` на 127.0.0.1. Поэтому здесь маленький сервер: он
 *   принимает запрос от плеера, переписывает его в запрос к источнику и
 *   переливает байты. Плеер видит «файл» с обычной поддержкой перемотки.
 *
 * ЧТО ПРОИСХОДИТ ПРИ ПЕРЕМОТКЕ
 *   Новый диапазон — новый запрос к источнику с `Range`. Ничего не качается
 *   заранее и целиком: два часа фильма не занимают место на телефоне.
 *
 * ОГРАНИЧЕНИЯ, О КОТОРЫХ ЧЕСТНО
 *   Источник без поддержки `Range` отдаётся с начала: перемотка вперёд будет
 *   перечитывать поток с нуля (так же ведёт себя любой плеер). Если сервер
 *   отвечает 403 без нужного заголовка — плеер получит ошибку с кодом, а
 *   экран покажет причину.
 */
object SourceRangeServer {

    /** Разобранный заголовок `Range`. Открытый конец → [end] = -1. */
    data class Range(val start: Long, val end: Long)

    /**
     * Разбор `Range: bytes=100-`, `bytes=0-1023`, `bytes=-500`.
     *
     * Возвращает null, если заголовка нет или он не про байты: тогда отдаём
     * файл целиком, как обычный сервер.
     */
    fun parseRange(header: String?, total: Long): Range? {
        if (header.isNullOrBlank()) return null
        val value = header.trim()
        if (!value.startsWith("bytes=", true)) return null
        // Множественные диапазоны («bytes=0-9,20-29») не поддерживаем: плееры
        // их не используют, а ответ на них — отдельный формат multipart.
        val spec = value.substringAfter('=').substringBefore(',').trim()
        val dash = spec.indexOf('-')
        if (dash < 0) return null
        val startText = spec.substring(0, dash).trim()
        val endText = spec.substring(dash + 1).trim()
        return if (startText.isEmpty()) {
            // «bytes=-500» — последние 500 байт.
            val suffix = endText.toLongOrNull() ?: return null
            if (suffix <= 0 || total <= 0) return null
            Range(maxOf(0, total - suffix), total - 1)
        } else {
            val start = startText.toLongOrNull() ?: return null
            if (start < 0) return null
            val end = if (endText.isEmpty()) -1L else (endText.toLongOrNull() ?: return null)
            if (end >= 0 && end < start) return null
            Range(start, if (end >= 0 && total > 0) minOf(end, total - 1) else end)
        }
    }

    /** Заголовок `Content-Range` для ответа 206. */
    fun contentRange(range: Range, total: Long): String {
        val end = if (range.end >= 0) range.end else total - 1
        return "bytes ${range.start}-$end/${if (total > 0) total else "*"}"
    }

    /** Что именно отдаём: живой источник или уже скачанный файл. */
    sealed class Source {
        data class Remote(val url: String, val headers: Map<String, String> = emptyMap()) : Source()
        data class Local(val file: File) : Source()
    }

    /**
     * Запущенный сервер. Один на файл; порт выбирается свободный, чтобы не
     * мешать другим плеерам и не требовать разрешений.
     */
    class Instance(
        private val source: Source,
        private val displayName: String,
        private val contentType: String
    ) {
        private var server: ServerSocket? = null
        private val stopped = AtomicBoolean(false)
        val isRunning: Boolean get() = server != null && !stopped.get()

        /** Адрес для плеера. */
        var url: String = ""
            private set

        fun start(): Result<String> = runCatching {
            val socket = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
            server = socket
            url = "http://127.0.0.1:${socket.localPort}/${encodeName(displayName)}"
            Thread {
                while (!stopped.get()) {
                    val client = try {
                        socket.accept()
                    } catch (e: Throwable) {
                        return@Thread
                    }
                    Thread { handle(client) }.apply { isDaemon = true }.start()
                }
            }.apply { isDaemon = true; name = "source-range"; start() }
            Log.info("[Source] открыт по частям: $url")
            url
        }

        fun stop() {
            stopped.set(true)
            runCatching { server?.close() }
            server = null
        }

        private fun handle(client: Socket) {
            try {
                client.use { socket ->
                    val input = socket.getInputStream()
                    val request = readRequest(input) ?: return
                    val output = BufferedOutputStream(socket.getOutputStream())
                    when (source) {
                        is Source.Local -> serveFile(source.file, request, output)
                        is Source.Remote -> serveRemote(source, request, output)
                    }
                    output.flush()
                }
            } catch (e: Throwable) {
                // Плеер закрыл соединение при перемотке — обычное дело.
            }
        }

        private fun serveFile(file: File, request: Request, output: OutputStream) {
            val total = file.length()
            val range = parseRange(request.range, total)
            if (range == null) {
                writeHeaders(output, 200, "OK", total, null)
                if (!request.headOnly) FileInputStream(file).use { it.copyTo(output, 64 * 1024) }
                return
            }
            val start = range.start
            val end = if (range.end >= 0) range.end else total - 1
            if (start >= total) {
                writeHeaders(output, 416, "Range Not Satisfiable", 0, "bytes */$total")
                return
            }
            writeHeaders(output, 206, "Partial Content", end - start + 1, contentRange(range, total))
            if (request.headOnly) return
            RandomAccessFileReader(file, start, end - start + 1).use { it.copyTo(output) }
        }

        private fun serveRemote(source: Source.Remote, request: Request, output: OutputStream) {
            val connection = URL(source.url).openConnection() as HttpURLConnection
            connection.connectTimeout = 12_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "Symbiosis/1.0")
            source.headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
            request.range?.let { connection.setRequestProperty("Range", it) }
            try {
                val code = connection.responseCode
                val total = connection.getHeaderField("Content-Range")
                    ?.substringAfter('/', "*")
                    ?.toLongOrNull()
                    ?: connection.contentLengthLong.takeIf { it > 0 }
                    ?: -1L
                val type = connection.contentType ?: contentType
                if (code == HttpURLConnection.HTTP_PARTIAL || code == HttpURLConnection.HTTP_OK) {
                    val status = if (code == HttpURLConnection.HTTP_PARTIAL) 206 else 200
                    val length = connection.contentLengthLong.takeIf { it > 0 } ?: -1L
                    val rangeHeader = if (status == 206) connection.getHeaderField("Content-Range") else null
                    writeHeaders(
                        output,
                        status,
                        if (status == 206) "Partial Content" else "OK",
                        length,
                        rangeHeader,
                        type
                    )
                    if (!request.headOnly) {
                        connection.inputStream.use { it.copyTo(output, 64 * 1024) }
                    }
                } else {
                    // Причина видна плееру и человеку, а не «файл не открылся».
                    val reason = connection.responseMessage ?: "ошибка $code"
                    writeHeaders(output, code, reason, 0, null, "text/plain; charset=utf-8")
                    output.write("источник ответил: $reason".toByteArray())
                }
            } finally {
                runCatching { connection.disconnect() }
            }
        }

        private fun writeHeaders(
            output: OutputStream,
            code: Int,
            reason: String,
            length: Long,
            contentRange: String?,
            type: String = contentType
        ) {
            val head = StringBuilder()
            head.append("HTTP/1.1 $code $reason\r\n")
            head.append("Content-Type: $type\r\n")
            head.append("Accept-Ranges: bytes\r\n")
            if (length >= 0) head.append("Content-Length: $length\r\n")
            if (contentRange != null) head.append("Content-Range: $contentRange\r\n")
            head.append("Connection: close\r\n\r\n")
            output.write(head.toString().toByteArray(Charsets.US_ASCII))
        }

        private data class Request(val range: String?, val headOnly: Boolean)

        private fun readRequest(input: InputStream): Request? {
            val builder = StringBuilder()
            val buffer = ByteArray(1)
            while (builder.length < 8192) {
                val read = input.read(buffer)
                if (read <= 0) break
                builder.append(buffer[0].toInt().toChar())
                if (builder.endsWith("\r\n\r\n")) break
            }
            if (builder.isEmpty()) return null
            val lines = builder.split("\r\n")
            val headOnly = lines.firstOrNull()?.startsWith("HEAD", true) == true
            val range = lines.firstOrNull { it.startsWith("Range:", true) }
                ?.substringAfter(':')?.trim()
            return Request(range, headOnly)
        }
    }

    /** Потоковое чтение куска файла без загрузки целиком в память. */
    private class RandomAccessFileReader(private val file: File, start: Long, private val count: Long) : AutoCloseable {
        private val stream = FileInputStream(file).apply { channel.position(start) }
        fun copyTo(output: OutputStream) {
            val buffer = ByteArray(64 * 1024)
            var left = count
            while (left > 0) {
                val read = stream.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                if (read <= 0) break
                output.write(buffer, 0, read)
                left -= read
            }
        }

        override fun close() = stream.close()
    }

    private fun encodeName(name: String): String =
        name.replace(" ", "%20").replace("#", "%23").replace("?", "%3F").ifBlank { "file" }

    /** Тип по расширению: плеер иначе отказывается открывать поток. */
    fun contentTypeOf(name: String): String = when (SourceIndex.extensionOf(name)) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "avi" -> "video/x-msvideo"
        "mov" -> "video/quicktime"
        "ts", "m2ts" -> "video/mp2t"
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "ogg", "opus" -> "audio/ogg"
        "wav" -> "audio/wav"
        "m4a" -> "audio/mp4"
        "pdf" -> "application/pdf"
        "epub" -> "application/epub+zip"
        else -> "application/octet-stream"
    }
}

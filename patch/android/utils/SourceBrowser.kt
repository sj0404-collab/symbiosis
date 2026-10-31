// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Открытые источники: по ссылке получить список файлов, скачать один файл
 * или открыть его по частям.
 *
 * ЧТО ЗНАЧИТ «ЛЮБОЙ САЙТ»
 *   Пользователь даёт адрес страницы. Дальше три возможных исхода, и все три
 *   должны быть видны человеку:
 *
 *   1. Это список файлов (nginx, Apache, Caddy, GitHub Releases, любой
 *      каталог) — показываем файлы: имя, вид, размер, кнопки «Скачать» и
 *      «Открыть по частям».
 *   2. Это прямая ссылка на файл — показываем один файл.
 *   3. Сайт не отдаёт список: 403, 401, требует JavaScript или вход. Тогда
 *      пишем честно ЧТО произошло, а не «ничего не найдено»: «сервер ответил
 *      403 — открытый список закрыт», «страница собирается скриптами, файлов
 *      в ней нет», «нужен вход в аккаунт».
 *
 * ЧЕГО ЗДЕСЬ НЕТ
 *   Поиска по пиратским каталогам и встроенных ссылок на них: адрес даёт
 *   человек. Здесь только чтение того, что он открыл.
 *
 * СКАЧИВАНИЕ
 *   Потоком на диск, с честным прогрессом и докачкой: если файл уже есть и
 *   сервер умеет Range, продолжаем с места, а не с нуля.
 */
object SourceBrowser {

    /** Что удалось понять про ссылку. */
    sealed class Opened {
        /** Открытый каталог: список файлов и папок. */
        data class Listing(
            val url: String,
            val title: String,
            val entries: List<SourceIndex.Entry>,
            val note: String = ""
        ) : Opened()

        /** Прямая ссылка на файл. */
        data class File(
            val url: String,
            val name: String,
            val sizeBytes: Long?,
            val kind: SourceIndex.Kind,
            val torrent: TorrentMeta.Meta? = null,
            val magnet: TorrentMeta.Magnet? = null
        ) : Opened()

        /** Не вышло — с причиной человеческим языком. */
        data class Failed(val message: String, val code: Int = 0) : Opened()
    }

    /** Итог скачивания одного файла. */
    data class Downloaded(val file: File, val bytes: Long, val resumed: Boolean)

    /**
     * Понять, что находится по ссылке.
     *
     * [progress] — текстовые шаги («запрашиваю страницу…», «разбираю
     * список…»): на телефоне ожидание без единой строчки выглядит зависанием.
     */
    fun open(url: String, progress: (String) -> Unit = {}): Opened {
        val clean = url.trim()
        if (clean.isEmpty()) return Opened.Failed("пустая ссылка")
        if (clean.startsWith("magnet:", true)) {
            val magnet = TorrentMeta.parseMagnet(clean).getOrElse {
                return Opened.Failed("magnet-ссылка не разобрана: ${it.message}")
            }
            return Opened.File(
                url = clean,
                name = magnet.name ?: "торрент ${magnet.hashPrefix()}",
                sizeBytes = null,
                kind = SourceIndex.Kind.TORRENT,
                magnet = magnet
            )
        }
        if (!clean.startsWith("http://", true) && !clean.startsWith("https://", true)) {
            return Opened.Failed("ссылка должна начинаться с http:// или https://")
        }

        // Сначала пробуем как каталог: так работает большинство открытых
        // хранилищ, и в этом случае мы получаем много файлов сразу.
        progress("запрашиваю страницу")
        val fetched = fetch(clean)
        if (fetched.failure != null) {
            // Даже при ошибке не сдаёмся сразу: если адрес заканчивается на
            // .torrent или на файл, его всё равно можно попробовать.
            val byName = guessByUrl(clean)
            if (byName != null) return byName
            return Opened.Failed(fetched.failure, fetched.code)
        }
        val body = fetched.body ?: ByteArray(0)
        val contentType = fetched.contentType.orEmpty().lowercase()
        val disposition = fetched.contentDisposition.orEmpty()

        // .torrent — это файл, его надо разобрать, а не показывать.
        if (looksLikeTorrent(clean, contentType, disposition, body)) {
            progress("разбираю торрент")
            val meta = TorrentMeta.parse(body).getOrElse {
                return Opened.Failed("файл .torrent повреждён: ${it.message}")
            }
            return Opened.File(
                url = clean,
                name = meta.name,
                sizeBytes = meta.totalLength,
                kind = SourceIndex.Kind.TORRENT,
                torrent = meta
            )
        }

        // Пришёл не текст — значит, скачался файл. Показываем одним файлом:
        // содержимое в память уже попало, но перекачивать его вторым запросом
        // незачем, размер известен из ответа.
        if (!contentType.contains("html") && !contentType.contains("text/plain") &&
            contentType.isNotEmpty() && !looksLikeHtml(body)
        ) {
            val name = SourceIndex.fileNameFromDisposition(disposition)
                ?: SourceIndex.fileNameOf(clean)
            return Opened.File(
                url = clean,
                name = name,
                sizeBytes = fetched.length ?: body.size.toLong(),
                kind = SourceIndex.kindOf(name)
            )
        }

        val html = body.toString(Charsets.UTF_8)
        progress("разбираю список файлов")
        val page = SourceIndex.parse(clean, html)
        if (page.entries.isEmpty()) {
            return Opened.Failed(explainEmpty(fetched.code, html, clean))
        }
        return Opened.Listing(
            url = clean,
            title = page.title.ifBlank { SourceIndex.fileNameOf(clean) },
            entries = page.entries
        )
    }

    /**
     * Скачать файл потоком на диск.
     *
     * [onProgress] получает (принято, всего или -1): экран показывает
     * проценты и скорость, а не «крутится вечно».
     */
    fun download(
        url: String,
        target: File,
        cancelled: AtomicBoolean? = null,
        headers: Map<String, String> = emptyMap(),
        // Лямбда прогресса — ПОСЛЕДНЕЙ: тогда вызов читается как
        // `download(url, file) { got, total -> … }`, а не с именованным
        // аргументом в середине.
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): Result<Downloaded> = runCatching {
        target.parentFile?.mkdirs()
        var existing = if (target.isFile) target.length() else 0L
        var connection = connect(url, existing, headers)
        var resumed = existing > 0 && connection.responseCode == HttpURLConnection.HTTP_PARTIAL

        if (existing > 0 && !resumed) {
            // Сервер не умеет докачку — начинаем заново, иначе файл будет
            // склеен из двух разных кусков.
            existing = 0
            target.delete()
            connection = connect(url, 0, headers)
        }

        val code = connection.responseCode
        if (code !in 200..299) {
            connection.disconnect()
            throw IllegalStateException(httpReason(code))
        }

        val total = if (connection.contentLengthLong > 0) {
            existing + connection.contentLengthLong
        } else {
            -1L
        }

        var written = existing
        connection.inputStream.use { input ->
            RandomAccessFile(target, "rw").use { out ->
                out.seek(existing)
                val buffer = ByteArray(64 * 1024)
                var lastReport = 0L
                while (true) {
                    if (cancelled?.get() == true) throw IllegalStateException("остановлено")
                    val read = input.read(buffer)
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                    written += read
                    val now = System.currentTimeMillis()
                    if (now - lastReport > 400) {
                        onProgress(written, total)
                        lastReport = now
                    }
                }
            }
        }
        connection.disconnect()
        onProgress(written, if (total > 0) total else written)
        Downloaded(target, written, resumed)
    }

    // --- сеть --------------------------------------------------------------

    private class Fetched(
        val body: ByteArray?,
        val contentType: String?,
        val contentDisposition: String?,
        val length: Long?,
        val code: Int,
        val failure: String?
    )

    private fun fetch(url: String): Fetched {
        var lastReason = ""
        var lastCode = 0
        // До трёх попыток: часть зеркал отвечает 5xx «сейчас перегружен»,
        // и второй запрос проходит.
        repeat(3) { attempt ->
            var connection: HttpURLConnection? = null
            try {
                connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 12_000
                connection.readTimeout = 15_000
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", USER_AGENT)
                connection.setRequestProperty(
                    "Accept",
                    "text/html,application/xhtml+xml,application/x-bittorrent,*/*;q=0.8"
                )
                connection.setRequestProperty("Accept-Language", "ru,en;q=0.7")
                val code = connection.responseCode
                lastCode = code
                if (code !in 200..299) {
                    lastReason = httpReason(code)
                    connection.disconnect()
                    if (code in 500..599 && attempt < 2) {
                        Thread.sleep(800L * (attempt + 1))
                        return@repeat
                    }
                    return Fetched(null, null, null, null, code, lastReason)
                }
                // Не читаем в память бесконечное: каталог — это сотни
                // килобайт, файл сюда попадать не должен.
                val limit = 3 * 1024 * 1024
                val body = connection.inputStream.use { readLimited(it, limit) }
                return Fetched(
                    body = body,
                    contentType = connection.contentType,
                    contentDisposition = connection.getHeaderField("Content-Disposition"),
                    length = connection.contentLengthLong.takeIf { it > 0 },
                    code = code,
                    failure = null
                )
            } catch (e: Throwable) {
                lastReason = networkReason(e)
                if (attempt >= 2) return Fetched(null, null, null, null, lastCode, lastReason)
                runCatching { connection?.disconnect() }
            }
        }
        return Fetched(null, null, null, null, lastCode, lastReason)
    }

    private fun connect(url: String, from: Long, headers: Map<String, String>): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 60_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", USER_AGENT)
        headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
        if (from > 0) connection.setRequestProperty("Range", "bytes=$from-")
        // Докачка имеет смысл только если сервер отдаёт смещения.
        connection.setRequestProperty("Accept-Encoding", "identity")
        return connection
    }

    private fun readLimited(input: InputStream, limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (out.size() < limit) {
            val read = input.read(buffer)
            if (read <= 0) break
            // Больше лимита не читаем: сервер, отдающий тело вечно, иначе
            // съест память телефона.
            val room = limit - out.size()
            out.write(buffer, 0, minOf(read, room))
            if (room <= read) break
        }
        return out.toByteArray()
    }

    // --- подсказки человеку -------------------------------------------------

    private fun guessByUrl(url: String): Opened? {
        val name = SourceIndex.fileNameOf(url)
        return if (name.endsWith(".torrent", true)) {
            Opened.Failed(
                "адрес похож на .torrent, но файл не скачался — проверьте, " +
                    "что ссылка открывается в браузере"
            )
        } else if (SourceIndex.isDirectFile(url)) {
            Opened.File(url, name, null, SourceIndex.kindOf(name))
        } else {
            null
        }
    }

    private fun looksLikeTorrent(url: String, contentType: String, disposition: String, body: ByteArray): Boolean {
        if (url.substringBefore('?').endsWith(".torrent", true)) return true
        if (disposition.contains(".torrent", true)) return true
        if (contentType.contains("x-bittorrent")) return true
        // Содержимое надёжнее расширения: словарь info первым ключом.
        return body.size > 2 && body[0] == 'd'.code.toByte() &&
            TorrentBencode.infoSpan(body) != null
    }

    private fun looksLikeHtml(body: ByteArray): Boolean {
        val head = body.toString(Charsets.UTF_8).trimStart().take(64).lowercase()
        return head.startsWith("<!doctype") || head.startsWith("<html") || head.contains("<head")
    }

    private fun explainEmpty(code: Int, html: String, url: String): String {
        val lower = html.lowercase()
        return when {
            code == 401 || lower.contains("sign in") || lower.contains("войти") ->
                "сайт требует вход в аккаунт — откройте его в браузере и получите прямую ссылку"
            code == 403 ->
                "сервер ответил 403: открытый список файлов закрыт (часто так делают " +
                    "хранилища, которым нужен ключ в адресе)"
            lower.contains("<script") && lower.count { it == '\n' } < 20 ->
                "страница собирается скриптами — в её тексте нет ссылок на файлы. " +
                    "Так бывает у облачных дисков и каталогов с поиском"
            else ->
                "на странице нет ни одной ссылки на файл. Возможно, это статья " +
                    "или вход в поиск, а не каталог"
        }
    }

    private fun httpReason(code: Int): String = when (code) {
        401 -> "сервер требует вход (401)"
        403 -> "сервер запретил доступ (403) — возможно, нужен ключ в ссылке"
        404 -> "страница не найдена (404) — проверьте адрес"
        410 -> "страница удалена (410)"
        429 -> "сервер просит подождать: слишком много запросов (429)"
        in 500..599 -> "сервер источника не отвечает (ошибка $code)"
        else -> "сервер ответил кодом $code"
    }

    private fun networkReason(e: Throwable): String = when {
        e is java.net.UnknownHostException -> "адрес не найден — проверьте интернет и имя сайта"
        e is java.net.SocketTimeoutException -> "сайт не ответил вовремя"
        e is javax.net.ssl.SSLException -> "не удалось установить защищённое соединение с сайтом"
        e is java.io.IOException -> "сеть недоступна: ${e.message ?: "нет связи"}"
        else -> "не удалось открыть: ${e.message ?: e.javaClass.simpleName}"
    }

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0 Mobile Safari/537.36 Symbiosis"
}

/** Короткий отпечаток hash для подписи magnet без имени. */
fun TorrentMeta.Magnet.hashPrefix(): String =
    infoHash.take(4).joinToString("") { "%02x".format(it) }

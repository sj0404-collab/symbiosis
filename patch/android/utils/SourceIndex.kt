// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

/**
 * «Дай ссылку — покажу, что там лежит».
 *
 * РАЗБОР СТРАНИЦЫ, А НЕ ПАРСИНГ САЙТОВ
 *   Открытый каталог (`Index of /…`, FTP-зеркало, файлопомойка, страница
 *   релиза) — это обычный HTML со ссылками. Отдельный «парсер для каждого
 *   сайта» ломается в тот день, когда сайт меняет вёрстку, поэтому здесь
 *   одно правило: собрать все ссылки страницы, привести относительные к
 *   абсолютным, отбросить навигацию, а остальное показать списком с видом
 *   файла по расширению.
 *
 *   Размеры берутся из текста рядом со ссылкой, если они там есть: и nginx, и
 *   Apache, и зеркала печатают «1.2G» или «345M» в той же строке. Нет размера —
 *   не выдумываем.
 *
 * ЧЕГО ЗДЕСЬ НЕТ
 *   Никакого «поиска по всем трекерам» и обхода чужих сайтов: разбирается
 *   ровно та страница, ссылку на которую дал человек.
 */
object SourceIndex {

    enum class Kind(val label: String) {
        VIDEO("видео"),
        AUDIO("аудио"),
        BOOK("книга"),
        GAME("игра"),
        IMAGE("картинка"),
        ARCHIVE("архив"),
        TORRENT("торрент"),
        DIRECTORY("папка"),
        OTHER("файл")
    }

    data class Entry(
        val name: String,
        val url: String,
        val kind: Kind,
        val sizeBytes: Long? = null,
        val isDirectory: Boolean = false
    ) {
        val isStreamable: Boolean
            get() = kind == Kind.VIDEO || kind == Kind.AUDIO
        val isTorrent: Boolean
            get() = kind == Kind.TORRENT || url.startsWith("magnet:", ignoreCase = true)

        /** «Вверх», «Parent Directory», сортировка — не файлы. */
        val isNavigation: Boolean
            get() = name.equals("..", true) || name.equals("Parent Directory", true) ||
                name.startsWith("Вверх", true) || name == "." ||
                url.startsWith("mailto:", true) || url.startsWith("javascript:", true) ||
                url.startsWith("#")
    }

    data class Page(
        val title: String,
        val entries: List<Entry>,
        val looksLikeListing: Boolean
    )

    private val videoExt = setOf("mp4", "mkv", "avi", "mov", "webm", "m4v", "ts", "mpg", "mpeg", "flv", "wmv", "m2ts")
    private val audioExt = setOf("mp3", "flac", "ogg", "opus", "wav", "m4a", "aac", "wma", "ape")
    private val bookExt = setOf("epub", "fb2", "pdf", "mobi", "azw3", "djvu", "cbz", "cbr", "txt", "rtf", "doc", "docx", "odt")
    private val gameExt = setOf("nsp", "xci", "nsz", "xcz", "nca", "cia", "3ds", "gba", "nds", "iso", "bin", "cue", "chd", "rvz", "wbfs")
    private val imageExt = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "avif")
    private val archiveExt = setOf("zip", "7z", "rar", "tar", "gz", "xz", "bz2", "zst", "apk")

    /** Расширение из адреса, без учёта параметров и якоря. */
    fun extensionOf(url: String): String {
        val path = url.substringBefore('#').substringBefore('?').substringAfterLast('/')
        val dot = path.lastIndexOf('.')
        if (dot <= 0 || dot == path.length - 1) return ""
        return path.substring(dot + 1).lowercase()
    }

    fun kindOf(url: String, isDirectory: Boolean = false): Kind {
        if (isDirectory || url.endsWith("/")) return Kind.DIRECTORY
        val ext = extensionOf(url)
        return when {
            ext in videoExt -> Kind.VIDEO
            ext in audioExt -> Kind.AUDIO
            ext in bookExt -> Kind.BOOK
            ext in gameExt -> Kind.GAME
            ext in imageExt -> Kind.IMAGE
            ext in archiveExt -> Kind.ARCHIVE
            ext == "torrent" -> Kind.TORRENT
            else -> Kind.OTHER
        }
    }

    /** Ссылка сама по себе — файл, а не страница. По расширению. */
    fun looksLikeFile(url: String): Boolean = kindOf(url) != Kind.OTHER

    /**
     * Имя из адреса: последний сегмент, раскодированный.
     *
     * `https://x/y/My%20Film.mkv?token=1` → «My Film.mkv». Имя нужно, чтобы
     * сохранить файл под правильным именем, а не «download(3)».
     */
    fun fileNameOf(url: String): String {
        val tail = url.substringBefore('#').substringBefore('?').substringAfterLast('/')
        if (tail.isEmpty()) return "файл"
        val decoded = runCatching { java.net.URLDecoder.decode(tail, "UTF-8") }.getOrDefault(tail)
        return decoded.ifBlank { "файл" }
    }

    /** Имя из `Content-Disposition`, если сервер его прислал. */
    fun fileNameFromDisposition(header: String?): String? {
        if (header.isNullOrBlank()) return null
        val star = Regex("filename\\*=UTF-8''([^;]+)", RegexOption.IGNORE_CASE).find(header)?.groupValues?.get(1)
        if (star != null) {
            return runCatching { java.net.URLDecoder.decode(star.trim(), "UTF-8") }.getOrNull()
        }
        val plain = Regex("filename=\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(header)?.groupValues?.get(1)
        return plain?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * Собрать список файлов со страницы.
     *
     * [baseUrl] нужен для относительных ссылок: `href="linux/ubuntu.iso"` на
     * `https://mirror/` — это `https://mirror/linux/ubuntu.iso`.
     */
    fun parse(baseUrl: String, html: String): Page {
        val title = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)?.groupValues?.get(1)?.let { unescape(stripTags(it)).trim() }.orEmpty()

        // СНАЧАЛА ИЩЕМ САМ СПИСОК, А НЕ ВСЕ ССЫЛКИ СТРАНИЦЫ.
        //
        // У настоящих каталогов (Apache, nginx, Lighttpd) файлы лежат в
        // одном контейнере: <table id="indexlist"> или <pre>. Всё остальное
        // на такой странице — меню, «как пользоваться», ссылки на вики
        // проекта. Без этого отбора в списке появлялись «bittorrent»,
        // «FAQ» и «Debian CD team» — слова из текста, а не файлы.
        val region = listingRegion(html) ?: html

        val links = Regex("<a\\s+[^>]*href\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>(.*?)</a>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .findAll(region)
            .mapNotNull { match ->
                val href = unescape(match.groupValues[1].trim())
                val text = unescape(stripTags(match.groupValues[2])).trim()
                // Размер печатается РЯДОМ со ссылкой, а не внутри неё: у
                // Apache и nginx это отдельная ячейка таблицы. Поэтому
                // смотрим ещё немного вперёд по тексту — до следующей
                // ссылки, но не дальше пары сотен символов.
                val after = region.substring(
                    match.range.last + 1,
                    minOf(region.length, match.range.last + 1 + 240)
                )
                val row = after.substringBefore("<a", after).substringBefore("</tr", after)
                buildEntry(baseUrl, href, text + " " + unescape(stripTags(row)))
            }
            .toList()

        // На одну строку каталога приходится ДВЕ ссылки: иконка и имя, обе
        // ведут на тот же файл. Размер напечатан рядом с именем, поэтому при
        // склейке побеждает запись, у которой размер известен, — иначе в
        // списке оставалась пустая «иконка» без размера.
        val entries = links
            .filterNotNull()
            .filterNot { it.isNavigation }
            .groupBy { it.url }
            .map { (_, same) ->
                same.firstOrNull { it.sizeBytes != null } ?: same.first()
            }

        val hasListingMarkers = html.contains("Index of", ignoreCase = true) ||
            html.contains("Directory listing", ignoreCase = true) ||
            entries.count { it.isDirectory } >= 2
        return Page(title, entries, hasListingMarkers)
    }

    /**
     * Найти на странице контейнер со списком файлов.
     *
     * Порядок проверок — от самого точного признака к самому общему:
     * таблица автоиндекса Apache, блок `<pre>` nginx и прочих, и только
     * если ничего не нашлось — вся страница.
     */
    private fun listingRegion(html: String): String? {
        val lower = html.lowercase()
        val tableStart = lower.indexOf("<table")
        if (tableStart >= 0) {
            val tableOpen = html.substring(tableStart, minOf(html.length, tableStart + 200)).lowercase()
            val tableEnd = lower.indexOf("</table>", tableStart)
            val looksLikeIndex = tableOpen.contains("index") || tableOpen.contains("listing") ||
                tableOpen.contains("dir")
            if (tableEnd > tableStart && (looksLikeIndex || countLinks(html, tableStart, tableEnd) >= 3)) {
                return html.substring(tableStart, tableEnd)
            }
        }
        val preStart = lower.indexOf("<pre")
        if (preStart >= 0) {
            val preEnd = lower.indexOf("</pre>", preStart)
            if (preEnd > preStart && countLinks(html, preStart, preEnd) >= 3) {
                return html.substring(preStart, preEnd)
            }
        }
        return null
    }

    private fun countLinks(html: String, from: Int, to: Int): Int {
        val part = html.substring(from, minOf(html.length, to)).lowercase()
        var count = 0
        var at = part.indexOf("<a ")
        while (at >= 0 && count < 50) {
            count++
            at = part.indexOf("<a ", at + 2)
        }
        return count
    }

    /**
     * Одна ссылка страницы.
     *
     * [text] — то, что видно около ссылки. Из него берутся имя (если текст
     * информативнее адреса) и размер.
     */
    fun buildEntry(baseUrl: String, href: String, text: String): Entry? {
        if (href.isBlank()) return null
        if (href.startsWith("#") || href.startsWith("javascript:", true) ||
            href.startsWith("mailto:", true)) return null
        // Навигацию каталога определяем по ТЕКСТУ ссылки, а не по её адресу.
        // «Parent Directory» ведёт на ../, и после приведения к абсолютному
        // виду имя из адреса получается «games» — такое отсеивалось уже
        // поздно, когда рядом с файлами стояла лишняя строка.
        // Сортировка каталога («?C=N;O=D») — это не файл.
        if (href.startsWith("?")) return null
        val navText = text.trim().lowercase()
        if (navText == ".." || navText == "." || navText.startsWith("parent directory") ||
            navText.startsWith("вверх")) {
            return null
        }
        val absolute = resolve(baseUrl, href) ?: return null
        // Ссылка на ЧУЖОЙ сайт без расширения файла — это почти всегда
        // «руководство», «FAQ» или «команда проекта», а не файл в каталоге.
        // Свои страницы без расширения оставляем: это могут быть подпапки.
        val baseHost = baseUrl.substringAfter("://").substringBefore('/')
        val targetHost = absolute.substringAfter("://").substringBefore('/')
        if (!targetHost.equals(baseHost, true) && extensionOf(absolute).isEmpty() &&
            !absolute.endsWith("/")
        ) {
            return null
        }
        val isDirectory = href.endsWith("/") || (absolute.endsWith("/") && !looksLikeFile(absolute))
        val urlName = fileNameOf(absolute)
        // Текст ссылки часто обрезан («…»), поэтому имя берём из адреса,
        // если оно похоже на имя файла.
        val name = when {
            urlName.isNotBlank() && urlName != "файл" -> urlName
            else -> text.substringBefore(' ').ifBlank { href }
        }
        val size = parseSizeIn(text)
        return Entry(
            name = name,
            url = absolute,
            kind = if (isDirectory) Kind.DIRECTORY else kindOf(name),
            sizeBytes = size,
            isDirectory = isDirectory
        )
    }

    /**
     * Абсолютный адрес из относительного.
     *
     * Разбираем руками, без `java.net.URI`: в каталогах встречаются пробелы и
     * кириллица в href, и `URI.create` на них падает, а `URL` — тянет
     * сетевые классы туда, где нужна арифметика над строкой.
     */
    fun resolve(base: String, href: String): String? {
        if (href.startsWith("http://", true) || href.startsWith("https://", true) ||
            href.startsWith("ftp://", true) || href.startsWith("magnet:", true)) {
            return href
        }
        if (!base.startsWith("http", true)) return null
        val scheme = base.substringBefore("://")
        val afterScheme = base.substringAfter("://")
        val host = afterScheme.substringBefore('/')
        val basePath = "/" + afterScheme.substringAfter('/', "")
        if (href.startsWith("//")) return "$scheme:$href"
        if (href.startsWith("/")) return "$scheme://$host$href"

        // Путь каталога: всё до последнего слэша. Слэш добавляется всегда:
        // иначе «https://x/a/b» + «../d.txt» склеивалось в «/a/b../d.txt».
        val dir = if (basePath.endsWith("/")) basePath else basePath.substringBeforeLast('/', "") + "/"
        var path = dir + href
        if (path.isEmpty()) path = "/"
        // Схлопываем ./ и ../ — иначе один и тот же файл получит разные адреса
        // и попадёт в список дважды.
        val parts = ArrayList<String>()
        for (segment in path.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(segment)
            }
        }
        return "$scheme://$host/" + parts.joinToString("/")
    }

    /** «1.2G», «345M», «1 024 K», «12,5 МБ» → байты. */
    fun parseSizeIn(text: String): Long? {
        val pattern = Regex(
            "(\\d{1,3}(?:[ \\u00A0]\\d{3})+|\\d+(?:[.,]\\d+)?)\\s*([KMGTkmgt])?(i?B)?"
        )
        // Сначала ищем размер С БУКВОЙ (1.2G, 345K): он однозначен.
        val withUnit = pattern.findAll(text).firstOrNull { it.groupValues[2].isNotEmpty() }
        val match = withUnit ?: run {
            // Затем — голое число в КОНЦЕ строки: «… 18:16 628» у Apache
            // означает 628 байт. Голое число из середины (год, время,
            // номер) за размер не считаем, иначе «2026» станет размером.
            val lastToken = text.trim().split(Regex("\\s+")).lastOrNull().orEmpty()
            if (lastToken.isNotEmpty() && lastToken.all { it.isDigit() } && lastToken.length <= 15) {
                val bytes = lastToken.toLongOrNull()
                return if (bytes != null && bytes > 0) bytes else null
            }
            return null
        }
        val number = match.groupValues[1].replace(" ", "").replace("\u00A0", "").replace(',', '.')
        val value = number.toDoubleOrNull() ?: return null
        val multiplier = when (match.groupValues[2].uppercase()) {
            "K" -> 1024.0
            "M" -> 1024.0 * 1024
            "G" -> 1024.0 * 1024 * 1024
            "T" -> 1024.0 * 1024 * 1024 * 1024
            else -> return null
        }
        return (value * multiplier).toLong()
    }

    /**
     * Похоже ли на «дай мне файл» — по расширению адреса.
     *
     * Нужно, чтобы ссылка на `film.mkv` сразу открывала загрузку, а не
     * показывала список из одной строки.
     */
    fun isDirectFile(url: String): Boolean {
        val ext = extensionOf(url)
        return ext.isNotEmpty() && (ext in videoExt || ext in audioExt || ext in bookExt ||
            ext in gameExt || ext in imageExt || ext in archiveExt || ext == "torrent")
    }

    fun stripTags(html: String): String =
        html.replace(Regex("<[^>]*>"), "")

    fun unescape(text: String): String = text
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&#x27;", "'")
        .replace(Regex("&#(\\d+);")) { m ->
            m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
        }
}

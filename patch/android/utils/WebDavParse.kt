// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Разбор ответов WebDAV и сборка путей.
 *
 * Отдельно от сети, потому что именно здесь ошибаются незаметно: путь с
 * двойным слэшем, имя файла с пробелом без кодирования, дата в формате,
 * который `SimpleDateFormat` разбирает молча неправильно. Всё это — обычные
 * функции над строкой, и на них есть тесты.
 *
 * Формат ответа: `207 Multi-Status` с XML, где каждый ресурс описан блоком
 * `<d:response>`. Пространства имён бывают `D:`, `d:` и без префикса вовсе —
 * зависит от сервера (Nextcloud, Яндекс.Диск и Box отвечают по-разному),
 * поэтому префикс не фиксируется.
 */
object WebDavParse {

    /** Один ресурс в листинге папки. */
    data class Entry(
        val href: String,
        val name: String,
        val isCollection: Boolean,
        val length: Long,
        val modified: Long
    )

    /**
     * Сколько ресурсов разобрано.
     *
     * Нужно, чтобы отличить «папка пуста» от «ответ не разобрался»: молча
     * пустой список в синхронизации выглядит как «в облаке ничего нет», и
     * следующий шаг — залить туда всё заново.
     */
    fun parseMultiStatus(xml: String): List<Entry> {
        val out = ArrayList<Entry>()
        // Блоки response могут быть в любом порядке и с любым префиксом.
        val blocks = Regex(
            "<(?:[A-Za-z0-9]+:)?response\\b[^>]*>(.*?)</(?:[A-Za-z0-9]+:)?response>",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        ).findAll(xml)

        for (block in blocks) {
            val body = block.groupValues[1]
            val href = tag(body, "href") ?: continue
            val isCollection = body.contains(
                Regex("<(?:[A-Za-z0-9]+:)?collection\\s*/?>", RegexOption.IGNORE_CASE)
            )
            val length = tag(body, "getcontentlength")?.trim()?.toLongOrNull() ?: 0L
            val modified = parseHttpDate(tag(body, "getlastmodified")?.trim().orEmpty()) ?: 0L
            out.add(
                Entry(
                    href = href,
                    name = decodeName(href),
                    isCollection = isCollection,
                    length = length,
                    modified = modified
                )
            )
        }
        return out
    }

    private fun tag(body: String, name: String): String? = Regex(
        "<(?:[A-Za-z0-9]+:)?$name\\b[^>]*>(.*?)</(?:[A-Za-z0-9]+:)?$name>",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    ).find(body)?.groupValues?.get(1)?.let { unescapeXml(it) }

    private fun unescapeXml(text: String): String = text
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")

    /**
     * Имя ресурса из href.
     *
     * `href` — закодированный путь: `%20` вместо пробела, иногда с хвостовым
     * слэшем у папок. Пользователю показывается уже раскодированное имя, а
     * сравнение идёт по раскодированному же — иначе «Save Data» и
     * «Save%20Data» считались бы разными папками.
     */
    fun decodeName(href: String): String {
        val trimmed = href.trimEnd('/')
        val tail = trimmed.substringAfterLast('/')
        return runCatching { java.net.URLDecoder.decode(tail, "UTF-8") }.getOrDefault(tail)
    }

    /** Дата ответа WebDAV: `Wed, 21 Oct 2015 07:28:00 GMT`. */
    fun parseHttpDate(text: String): Long? {
        if (text.isBlank()) return null
        val patterns = listOf(
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "EEE, dd MMM yyyy HH:mm:ss Z",
            "yyyy-MM-dd'T'HH:mm:ss'Z'"
        )
        for (p in patterns) {
            val parsed = runCatching {
                SimpleDateFormat(p, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("GMT")
                    isLenient = false
                }.parse(text)?.time
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return null
    }

    // ── Пути ───────────────────────────────────────────────────────────

    /** Убирает хвостовой слэш и повторные слэши внутри адреса. */
    fun normaliseBase(url: String): String {
        val noSpaces = url.trim()
        if (noSpaces.isEmpty()) return ""
        val withScheme = when {
            noSpaces.startsWith("http://", true) || noSpaces.startsWith("https://", true) ->
                noSpaces
            // Человек вводит «webdav.yandex.ru» — это не адрес, пока нет схемы.
            else -> "https://" + noSpaces
        }
        return withScheme.replace(Regex("(?<!:)/{2,}"), "/").trimEnd('/')
    }

    /**
     * Складывает базовый адрес и сегменты пути.
     *
     * Сегменты кодируются по одному: закодировать весь путь целиком значило
     * бы превратить разделители `/` в `%2F` и получить одну длинную папку.
     */
    fun join(base: String, vararg parts: String): String {
        val cleanBase = base.trimEnd('/')
        val tail = parts.filter { it.isNotBlank() }
            .map { encodeSegment(it) }
            .joinToString("/")
        return if (tail.isEmpty()) cleanBase else "$cleanBase/$tail"
    }

    /** Кодирование одного сегмента пути: пробел, кириллица, `#`, `?`. */
    fun encodeSegment(segment: String): String =
        java.net.URLEncoder.encode(segment, "UTF-8").replace("+", "%20")

    /** Куда кладутся сейвы: `<корень>/saves/<TitleID>/<профиль>/<файл>`. */
    fun savePath(root: String, titleId: String, profile: String, file: String): String =
        join(root, "saves", titleId.uppercase(), profile, file)

    /** Куда кладутся образы игр: `<корень>/games/<TitleID>/<имя файла>`. */
    fun gamePath(root: String, titleId: String, fileName: String): String =
        join(root, "games", titleId.uppercase().ifBlank { "unknown" }, fileName)

    /** Папки, которые надо создать до загрузки: от корня до листа. */
    fun ancestorsOf(path: String): List<String> {
        val clean = path.trimEnd('/')
        val schemeEnd = clean.indexOf("://")
        if (schemeEnd < 0) return emptyList()
        val start = clean.indexOf('/', schemeEnd + 3)
        if (start < 0) return emptyList()
        val out = ArrayList<String>()
        var at = start
        while (true) {
            val next = clean.indexOf('/', at + 1)
            if (next < 0) break
            out.add(clean.substring(0, next))
            at = next
        }
        return out
    }

    /** Приводит href из ответа к абсолютному адресу для сравнения с базой. */
    fun absoluteHref(base: String, href: String): String {
        val h = href.trim()
        if (h.startsWith("http://", true) || h.startsWith("https://", true)) return h.trimEnd('/')
        val root = runCatching {
            val u = java.net.URI(base)
            "${u.scheme}://${u.authority}"
        }.getOrNull() ?: return h
        return (root + (if (h.startsWith("/")) h else "/$h")).trimEnd('/')
    }
}

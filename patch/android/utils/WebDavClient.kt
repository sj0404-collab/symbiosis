// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import androidx.preference.PreferenceManager
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.yuzu.yuzu_emu.YuzuApplication

/**
 * Облако на WebDAV: хранение сейвов и образов игр.
 *
 * ПОЧЕМУ WEВDAV, А НЕ «ОБЛАКО КОМПАНИИ ИКС»
 *   Google Drive и Dropbox требуют OAuth-клиента, зарегистрированного в их
 *   консоли: без него кнопка «войти» не работает ни у кого, кроме владельца
 *   проекта. WebDAV работает с логином и паролем приложения и поддерживается
 *   Яндекс.Диском, Nextcloud, ownCloud, Box, Mail.ru и домашним сервером —
 *   то есть одним кодом покрывает почти всё, к чему у человека есть доступ.
 *
 * ЧТО ЭТО НЕ ДЕЛАЕТ
 *   Запуск игры «прямо из облака», как в Epic на ПК, здесь невозможен:
 *   эмулятору нужен локальный файл с произвольным доступом на десятки
 *   гигабайт и десятки тысяч чтений в секунду. Разумный вариант — скачать,
 *   запустить, а при выходе предложить залить сохранения обратно. Так и
 *   сделано; обещание «стриминга» было бы ложью.
 *
 * ГДЕ ЛЕЖИТ ПАРОЛЬ
 *   В SharedPreferences приложения, в приватной папке. Это тот же уровень,
 *   что у любого приложения с «сохранённым входом»: без root прочитать
 *   файл нельзя. Пароль не пишется в логи и не попадает в отчёты.
 */
object CloudSettings {

    private const val KEY_URL = "symbiosis_cloud_url"
    private const val KEY_USER = "symbiosis_cloud_user"
    private const val KEY_PASS = "symbiosis_cloud_pass"
    private const val KEY_ROOT = "symbiosis_cloud_root"
    private const val KEY_ON = "symbiosis_cloud_enabled"

    /** Готовые адреса: человек выбирает сервис, а не вспоминает путь. */
    data class Preset(val id: String, val label: String, val url: String, val hint: String)

    val PRESETS = listOf(
        Preset(
            "yandex",
            "Яндекс.Диск",
            "https://webdav.yandex.ru",
            "Включите доступ по WebDAV в настройках Диска и создайте пароль приложения"
        ),
        Preset(
            "nextcloud",
            "Nextcloud / ownCloud",
            "https://example.com/remote.php/dav/files/ВАШ_ЛОГИН",
            "Адрес из настроек файлов, в конце — ваш логин"
        ),
        Preset(
            "mailru",
            "Облако Mail.ru",
            "https://webdav.cloud.mail.ru",
            "Включите WebDAV в настройках облака"
        ),
        Preset(
            "box",
            "Box",
            "https://dav.box.com/dav",
            "Включается в настройках Box"
        ),
        Preset(
            "own",
            "Свой сервер",
            "",
            "Адрес вида https://сервер/dav — подойдёт любой WebDAV, включая домашний"
        )
    )

    private fun prefs() =
        PreferenceManager.getDefaultSharedPreferences(YuzuApplication.appContext)

    var url: String
        get() = prefs().getString(KEY_URL, "") ?: ""
        set(v) = prefs().edit().putString(KEY_URL, v.trim()).apply()

    var user: String
        get() = prefs().getString(KEY_USER, "") ?: ""
        set(v) = prefs().edit().putString(KEY_USER, v.trim()).apply()

    var password: String
        get() = prefs().getString(KEY_PASS, "") ?: ""
        set(v) = prefs().edit().putString(KEY_PASS, v).apply()

    /** Папка внутри хранилища: `symbiosis` по умолчанию. */
    var root: String
        get() = prefs().getString(KEY_ROOT, "symbiosis")?.ifBlank { "symbiosis" } ?: "symbiosis"
        set(v) = prefs().edit().putString(KEY_ROOT, v.trim().trim('/')).apply()

    var enabled: Boolean
        get() = prefs().getBoolean(KEY_ON, false)
        set(v) = prefs().edit().putBoolean(KEY_ON, v).apply()

    /**
     * Настроено ли всё, без чего подключение не имеет смысла.
     *
     * Отдельная функция, потому что «кнопка нажимается и ничего не делает»
     * уже случалась: проверка должна быть одна и вызываться до кнопки.
     */
    fun configured(): Boolean =
        url.isNotBlank() && user.isNotBlank() && password.isNotBlank()

    /** Что не задано — по одной причине за раз, чтобы не гадать. */
    fun missing(): String = when {
        url.isBlank() -> "не указан адрес сервера"
        user.isBlank() -> "не указан логин"
        password.isBlank() -> "не указан пароль"
        else -> ""
    }

    /** Папка задач в облаке — то, что видит пользователь. */
    fun whereText(): String = if (url.isBlank()) "не настроено" else "${WebDavParse.normaliseBase(url)}/$root"
}

/**
 * Минимальный клиент WebDAV: листинг, создание папок, загрузка, скачивание,
 * удаление. Без сторонних библиотек — их подключение в этом проекте стоит
 * отдельной правки gradle, а нужно отсюда четыре метода.
 */
object WebDavClient {

    /** Итог операции: без исключений наружу, с человеческим текстом отказа. */
    data class Result(val ok: Boolean, val message: String, val code: Int = 0)

    private const val CONNECT_TIMEOUT = 20_000
    private const val READ_TIMEOUT = 60_000

    private fun authHeader(): String {
        val raw = "${CloudSettings.user}:${CloudSettings.password}"
        // android.util.Base64, а не java.util: второй появился только в
        // API 26 и на старых прошивках падает NoClassDefFoundError.
        val encoded = android.util.Base64.encodeToString(
            raw.toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP
        )
        return "Basic $encoded"
    }

    private fun open(url: String, method: String): HttpURLConnection {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            instanceFollowRedirects = true
            setRequestProperty("Authorization", authHeader())
            setRequestProperty("User-Agent", "Symbiosis/1.0 (WebDAV)")
        }
        return conn
    }

    /**
     * Проверка связи.
     *
     * PROPFIND на корень папки задачи: он же отвечает на вопрос «существует
     * ли папка», и если её нет — создаёт. Один запрос вместо двух.
     */
    fun test(): Result {
        if (!CloudSettings.configured()) {
            return Result(false, "не хватает данных: ${CloudSettings.missing()}")
        }
        val base = WebDavParse.normaliseBase(CloudSettings.url)
        val target = WebDavParse.join(base, CloudSettings.root)
        val (probe, _) = propfind(target, depth = 0)
        if (probe.ok) {
            return Result(true, "связь есть · папка «${CloudSettings.root}» доступна")
        }
        if (probe.code == 404 || probe.code == 409) {
            val made = mkdirs(target)
            return if (made.ok) {
                Result(true, "связь есть · папка «${CloudSettings.root}» создана")
            } else {
                Result(false, "сервер отвечает, но папку создать не дал: ${made.message}", made.code)
            }
        }
        return probe
    }

    /**
     * PROPFIND с разбором ответа.
     *
     * Возвращает и результат, и разобранный список: листинг нужен почти
     * всегда рядом с «получилось ли», а два отдельных запроса на одно
     * действие — лишний круг по сети.
     */
    private fun propfind(url: String, depth: Int): Pair<Result, List<WebDavParse.Entry>> {
        val conn = runCatching { open(url, "PROPFIND") }.getOrElse { return failure(it) to emptyList() }
        return runCatching {
            conn.setRequestProperty("Depth", depth.toString())
            conn.setRequestProperty("Content-Type", "application/xml; charset=utf-8")
            conn.doOutput = true
            val body = """<?xml version="1.0" encoding="utf-8"?>
                |<d:propfind xmlns:d="DAV:"><d:prop>
                |<d:getcontentlength/><d:getlastmodified/><d:resourcetype/>
                |</d:prop></d:propfind>""".trimMargin()
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                return@runCatching Result(false, "сервер ответил $code", code) to emptyList()
            }
            val text = conn.inputStream.bufferedReader().readText()
            Result(true, "ok", code) to WebDavParse.parseMultiStatus(text)
        }.getOrElse { failure(it) to emptyList() }
    }

    /** Список файлов в папке. */
    fun list(url: String): Pair<Result, List<WebDavParse.Entry>> = propfind(url, depth = 1)

    /** Создаёт все недостающие папки по пути. */
    fun mkdirs(path: String): Result {
        for (dir in WebDavParse.ancestorsOf(path) + path) {
            val conn = runCatching { open(dir, "MKCOL") }.getOrElse { return failure(it) }
            val code = runCatching { conn.responseCode }.getOrElse { return failure(it) }
            // 201 — создана, 405 — уже есть, 301/302 — уже есть у части серверов
            if (code !in listOf(201, 405, 301, 302, 200, 204)) {
                return Result(false, "не удалось создать папку ($code)", code)
            }
        }
        return Result(true, "папки готовы")
    }

    /**
     * Загружает файл.
     *
     * `Content-Length` ставится явно: часть серверов без него отвечает 411, а
     * потоковая передача без размера превращает загрузку 30 ГБ в лотерею с
     * таймаутом.
     */
    fun upload(local: File, url: String, onProgress: ((Long) -> Unit)? = null): Result {
        if (!local.isFile) return Result(false, "файла нет: ${local.name}")
        val parent = url.substringBeforeLast('/', "")
        if (parent.isNotEmpty()) {
            val made = mkdirs(parent)
            if (!made.ok) return made
        }
        return runCatching {
            val conn = open(url, "PUT")
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/octet-stream")
            conn.setFixedLengthStreamingMode(local.length())
            var sent = 0L
            local.inputStream().use { input ->
                conn.outputStream.use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        sent += n
                        onProgress?.invoke(sent)
                    }
                    out.flush()
                }
            }
            val code = conn.responseCode
            if (code in 200..299) Result(true, "залито · ${SavesOverview.humanBytes(sent)}", code)
            else Result(false, "сервер ответил $code", code)
        }.getOrElse { failure(it) }
    }

    /** Скачивает файл во временный и только потом переименовывает. */
    fun download(url: String, target: File, onProgress: ((Long) -> Unit)? = null): Result {
        return runCatching {
            target.parentFile?.mkdirs()
            val part = File(target.parentFile, target.name + ".part")
            val conn = open(url, "GET")
            val code = conn.responseCode
            if (code !in 200..299) {
                part.delete()
                return@runCatching Result(false, "сервер ответил $code", code)
            }
            var got = 0L
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        got += n
                        onProgress?.invoke(got)
                    }
                }
            }
            if (got <= 0L) {
                part.delete()
                return@runCatching Result(false, "сервер отдал пустой файл")
            }
            if (!part.renameTo(target)) {
                part.delete()
                return@runCatching Result(false, "не удалось положить файл на место")
            }
            Result(true, "скачано · ${SavesOverview.humanBytes(got)}")
        }.getOrElse { failure(it) }
    }

    fun delete(url: String): Result = runCatching {
        val conn = open(url, "DELETE")
        val code = conn.responseCode
        if (code in 200..299 || code == 404) Result(true, "удалено", code)
        else Result(false, "сервер ответил $code", code)
    }.getOrElse { failure(it) }

    /**
     * Отказ словами.
     *
     * Имя класса исключения сюда не попадает никогда: «UnknownHostException»
     * человеку не говорит ничего, а «сервер не найден» — говорит всё.
     */
    private fun failure(t: Throwable): Result {
        val text = t.message.orEmpty()
        val human = when {
            t is java.net.UnknownHostException -> "сервер не найден — проверьте адрес"
            t is java.net.SocketTimeoutException -> "сервер не ответил вовремя"
            t is javax.net.ssl.SSLException -> "ошибка защищённого соединения"
            text.contains("401") || text.contains("Unauthorized", true) ->
                "логин или пароль не подошли"
            text.contains("403") -> "сервер запретил доступ к этой папке"
            text.contains("507") -> "в облаке кончилось место"
            else -> text.ifBlank { "не удалось связаться с сервером" }
        }
        return Result(false, human)
    }
}

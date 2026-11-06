// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

/**
 * Что внутри файла — без ключей и без ядра, по байтам контейнера.
 *
 * ЗАЧЕМ ЭТО ЗАВЕДЕНО
 *   На телефоне библиотека пометила две игры «ядро не прочитало заголовок ·
 *   проверьте ключи», а экран «Ключи и прошивка» в это же время показывал
 *   prod.keys на 204 ключа, title.keys и прошивку 22.0.0, и сам спрашивал у
 *   ядра: «core reports keys present: true». Одно из двух было неправдой, и
 *   человеку предлагалось разбираться самому.
 *
 *   Ключи для чтения КОНТЕЙНЕРА не нужны. У NSP (PFS0) таблица имён лежит
 *   открытым текстом, а билет (.tik) внутри содержит идентификатор игры
 *   (Rights ID) тоже открытым текстом. Значит можно точно сказать:
 *
 *     - контейнер целый, а внутри сжатые NCA (это NSZ, переименованный в NSP);
 *     - расширение одно, содержимое другое (ядро выбирает загрузчик по
 *       расширению и потому отказывает);
 *     - файл обрезан или это не образ вовсе;
 *     - всё в порядке, билет есть, идентификатор известен — и тогда отказ
 *       ядра не про ключи, а про что-то другое, и это уже наш дефект.
 *
 * ПОЧЕМУ ОТДЕЛЬНЫМ ОБЪЕКТОМ
 *   Это чистая функция от байтов: источник — интерфейс [Bytes], поэтому
 *   таблица разбора проверяется на хосте синтетическими файлами, без
 *   телефона и без гигабайтов образов.
 */
object NspProbe {

    /** Что за контейнер. */
    enum class Container {
        /** PFS0 без сжатых частей — обычный NSP. */
        NSP,

        /** PFS0, внутри котором есть .ncz — это NSZ. */
        NSZ,

        /** Образ картриджа (XCI), возможно сжатый (XCZ). */
        XCI,

        /** Один NCA-файл. */
        NCA,

        /** Пока ничего не понятно. */
        UNKNOWN
    }

    /** Части образа: по идентификатору видно, игра это или дополнение. */
    enum class Part { GAME, UPDATE, DLC, UNKNOWN }

    /**
     * Итог разбора.
     *
     * @param titleId 16 hex-символов или пустая строка
     * @param problem что не так с файлом; пусто — с контейнером всё в порядке
     */
    data class Result(
        val container: Container,
        val part: Part,
        val titleId: String,
        val hasTicket: Boolean,
        val entries: Int,
        val compressed: Boolean,
        val problem: String,
    ) {
        /** Контейнер читается без ключей — значит отказ ядра не про контейнер. */
        val readable: Boolean get() = problem.isEmpty() && container != Container.UNKNOWN
    }

    /** Источник байтов файла: смещение и длина. */
    fun interface Bytes {
        fun readAt(offset: Long, size: Int): ByteArray
    }

    private const val PFS0_ENTRY = 0x18
    private const val TICKET_RIGHTS_ID = 0x2A0L
    private const val TICKET_RIGHTS_LEN = 16
    private const val MAX_ENTRIES = 512
    private const val MAX_NAME = 0x200

    /**
     * Разобрать файл.
     *
     * @param fileName имя с расширением — нужно, чтобы заметить расхождение
     *                 между расширением и содержимым
     */
    fun probe(bytes: Bytes, fileName: String): Result {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val head = runCatching { bytes.readAt(0, 0x200) }.getOrDefault(ByteArray(0))

        when {
            head.isEmpty() -> return empty(Container.UNKNOWN, "файл пуст или не читается")

            // "HEAD" на 0x100 — образ картриджа. Расширение может быть любым.
            head.size >= 0x104 && ascii(head, 0x100, 4) == "HEAD" -> {
                val problem = if (ext == "nsp" || ext == "nsz") {
                    "внутри образ картриджа (XCI), а расширение «.$ext»: ядро выбирает " +
                        "загрузчик по расширению и поэтому не находит игру"
                } else ""
                return Result(
                    container = Container.XCI,
                    part = Part.UNKNOWN,
                    titleId = "",
                    hasTicket = false,
                    entries = 0,
                    compressed = ext == "xcz",
                    problem = problem,
                )
            }

            // "NCA0"…"NCA3" на 0x200 — файл сам является NCA.
            head.size >= 0x204 && ascii(head, 0x200, 3) == "NCA" -> {
                val compressed = ascii(head, 0x200, 4) == "NCZS"
                return Result(
                    container = Container.NCA,
                    part = Part.UNKNOWN,
                    titleId = "",
                    hasTicket = false,
                    entries = 0,
                    compressed = compressed,
                    problem = if (compressed) "сжатый NCA (NCZ) — сначала распаковать" else "",
                )
            }

            // "PFS0" — контейнер NSP.
            head.size >= 0x10 && ascii(head, 0, 4) == "PFS0" -> return probePfs0(bytes, ext)

            else -> {
                val what = ascii(head, 0, 4).filter { it.code in 32..126 }
                return empty(
                    Container.UNKNOWN,
                    "это не образ Nintendo" +
                        if (what.isNotEmpty()) " (первые байты «$what»)" else " (подпись не опознана)"
                )
            }
        }
    }

    private fun probePfs0(bytes: Bytes, ext: String): Result {
        val header = runCatching { bytes.readAt(0, 0x10) }.getOrDefault(ByteArray(0))
        if (header.size < 0x10) return empty(Container.UNKNOWN, "файл обрезан: нет заголовка")

        val count = u32(header, 4)
        val stringTableSize = u32(header, 8)
        if (count <= 0 || count > MAX_ENTRIES) {
            return empty(Container.UNKNOWN, "заголовок контейнера повреждён (файлов: $count)")
        }
        if (stringTableSize <= 0) {
            return empty(Container.UNKNOWN, "заголовок контейнера повреждён (нет таблицы имён)")
        }

        val table = runCatching { bytes.readAt(0x10L, count * PFS0_ENTRY) }.getOrDefault(ByteArray(0))
        if (table.size < count * PFS0_ENTRY) {
            return empty(Container.UNKNOWN, "файл обрезан: таблица файлов не дочитана")
        }
        val names = runCatching {
            bytes.readAt(0x10L + count.toLong() * PFS0_ENTRY, stringTableSize)
        }.getOrDefault(ByteArray(0))

        var compressed = false
        var hasTicket = false
        var titleId = ""
        var fromName = ""
        var firstOffset = -1L

        for (i in 0 until count) {
            val base = i * PFS0_ENTRY
            val offset = u64(table, base)
            val size = u64(table, base + 8)
            val nameOffset = u32(table, base + 0x10)
            val name = cString(names, nameOffset)
            if (firstOffset < 0) firstOffset = offset
            if (name.endsWith(".ncz", ignoreCase = true)) compressed = true
            if (name.endsWith(".tik", ignoreCase = true)) {
                hasTicket = true
                if (titleId.isEmpty() && size >= TICKET_RIGHTS_ID + TICKET_RIGHTS_LEN) {
                    // Rights ID билета — это и есть идентификатор игры. Лежит
                    // открытым текстом: расшифровывать его не нужно.
                    val rights = runCatching { bytes.readAt(offset + TICKET_RIGHTS_ID, TICKET_RIGHTS_LEN) }
                        .getOrDefault(ByteArray(0))
                    // Rights ID — 16 байт: первые восемь и есть идентификатор
                    // игры, остальные восемь нулевые. В текст идёт только он,
                    // иначе получится 32 символа вместо 16.
                    val candidate = hex(if (rights.size >= 8) rights.copyOfRange(0, 8) else rights)
                    if (candidate.length == 16 && candidate.any { it != '0' }) titleId = candidate
                }
            }
            // Часть дампов называет NCA идентификатором игры, а не хешем. Но
            // это только запасной источник: в образе лежат и NCA обновления
            // (…a800), и тогда имя файла назовёт не тот идентификатор. Билет
            // главнее — он один и указывает именно на основную игру.
            if (fromName.isEmpty() && name.endsWith(".nca", ignoreCase = true)) {
                val stem = name.dropLast(4).lowercase()
                if (stem.length == 16 && stem.all { it.isDigit() || it in 'a'..'f' }) {
                    fromName = stem
                }
            }
        }
        if (titleId.isEmpty()) titleId = fromName

        if (count > 0 && firstOffset == 0L && names.isEmpty()) {
            return empty(Container.UNKNOWN, "файл обрезан сразу после заголовка")
        }

        val container = if (compressed || ext == "nsz") Container.NSZ else Container.NSP
        val problem = when {
            compressed || ext == "nsz" ->
                "внутри сжатые части (NCZ) — это NSZ, его сначала распаковывают"
            ext == "xci" ->
                "внутри контейнер NSP, а расширение «.xci»: ядро выбирает загрузчик по расширению"
            !hasTicket && titleId.isEmpty() ->
                "в образе нет билета (.tik): нужен ключ этой игры в title.keys"
            else -> ""
        }

        return Result(
            container = container,
            part = partOf(titleId),
            titleId = titleId,
            hasTicket = hasTicket,
            entries = count,
            compressed = compressed || ext == "nsz",
            problem = problem,
        )
    }

    /**
     * Игра, обновление или дополнение — по хвосту идентификатора.
     *
     * Так это устроено на консоли: базовая игра заканчивается на 000,
     * обновление — на 800, дополнения идут выше.
     */
    fun partOf(titleId: String): Part {
        if (titleId.length != 16) return Part.UNKNOWN
        val tail = titleId.takeLast(3).lowercase()
        return when {
            tail == "000" -> Part.GAME
            tail == "800" -> Part.UPDATE
            titleId.startsWith("0100") && tail != "000" && tail != "800" -> Part.DLC
            else -> Part.UNKNOWN
        }
    }

    /**
     * Человеческая причина отказа — для плитки и диалога.
     *
     * Отдельно от [Result.problem]: там короткая суть для разбора, здесь —
     * фраза, которую не стыдно показать.
     */
    fun verdict(result: Result, keysPresent: Boolean): String = when {
        result.container == Container.UNKNOWN -> result.problem

        result.compressed ->
            "Это сжатый образ (NSZ): ядро открывает только распакованные. " +
                "Нажмите «Распаковать» — конвертер сделает обычный NSP."

        result.problem.isNotEmpty() -> result.problem

        result.titleId.isNotEmpty() ->
            "Контейнер читается: игра ${result.titleId}, частей ${result.entries}" +
                (if (result.hasTicket) ", билет внутри есть" else ", билета нет") +
                ". " + if (keysPresent) {
                "Ключи на месте — значит ядро отказало не из-за них, и это наш дефект."
            } else {
                "Ключей консоли нет — поэтому ядро и не смогло открыть игру."
            }

        else ->
            "Контейнер читается (${result.entries} частей), но идентификатор игры без ключей " +
                "не достаётся. " + if (keysPresent) {
                "Ключи на месте, поэтому причина отказа не в них."
            } else {
                "Установите prod.keys — без них игра не откроется."
            }
    }

    // ── разбор байтов ──────────────────────────────────────────────────

    private fun empty(container: Container, problem: String) = Result(
        container = container,
        part = Part.UNKNOWN,
        titleId = "",
        hasTicket = false,
        entries = 0,
        compressed = false,
        problem = problem,
    )

    private fun ascii(data: ByteArray, offset: Int, length: Int): String {
        if (offset + length > data.size) return ""
        return String(data, offset, length, Charsets.US_ASCII)
    }

    private fun u32(data: ByteArray, offset: Int): Int {
        if (offset + 4 > data.size) return -1
        return (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or
            ((data[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun u64(data: ByteArray, offset: Int): Long {
        if (offset + 8 > data.size) return -1
        var value = 0L
        for (i in 7 downTo 0) {
            value = (value shl 8) or (data[offset + i].toLong() and 0xFF)
        }
        return value
    }

    private fun cString(data: ByteArray, offset: Int): String {
        if (offset < 0 || offset >= data.size) return ""
        var end = offset
        val limit = minOf(data.size, offset + MAX_NAME)
        while (end < limit && data[end] != 0.toByte()) end++
        return String(data, offset, end - offset, Charsets.US_ASCII)
    }

    private fun hex(data: ByteArray): String =
        if (data.isEmpty()) "" else data.joinToString("") { "%02x".format(it) }
}

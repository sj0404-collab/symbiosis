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
     * @param fault   короткий код неполадки: по нему ставится пометка на
     *                плитке. Отдельно от [problem], потому что одна и та же
     *                фраза может быть и бедой, и подсказкой.
     */
    data class Result(
        val container: Container,
        val part: Part,
        val titleId: String,
        val hasTicket: Boolean,
        val entries: Int,
        val compressed: Boolean,
        val problem: String,
        val fault: String = "",
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
            head.isEmpty() -> return empty(Container.UNKNOWN, "файл пуст или не читается", "empty")

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
                    fault = if (problem.isEmpty()) "" else "ext-mismatch",
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
                    fault = if (compressed) "compressed" else "",
                )
            }

            // "PFS0" — контейнер NSP.
            head.size >= 0x10 && ascii(head, 0, 4) == "PFS0" -> return probePfs0(bytes, ext, fileName)

            else -> {
                val what = ascii(head, 0, 4).filter { it.code in 32..126 }
                return empty(
                    Container.UNKNOWN,
                    "это не образ Nintendo" +
                        if (what.isNotEmpty()) " (первые байты «$what»)" else " (подпись не опознана)",
                    "not-image"
                )
            }
        }
    }

    private fun probePfs0(bytes: Bytes, ext: String, fileNameOf: String): Result {
        val header = runCatching { bytes.readAt(0, 0x10) }.getOrDefault(ByteArray(0))
        if (header.size < 0x10) return empty(Container.UNKNOWN, "файл обрезан: нет заголовка", "truncated")

        val count = u32(header, 4)
        val stringTableSize = u32(header, 8)
        if (count <= 0 || count > MAX_ENTRIES) {
            return empty(Container.UNKNOWN, "заголовок контейнера повреждён (файлов: $count)", "header")
        }
        if (stringTableSize <= 0) {
            return empty(Container.UNKNOWN, "заголовок контейнера повреждён (нет таблицы имён)", "header")
        }

        val table = runCatching { bytes.readAt(0x10L, count * PFS0_ENTRY) }.getOrDefault(ByteArray(0))
        if (table.size < count * PFS0_ENTRY) {
            return empty(Container.UNKNOWN, "файл обрезан: таблица файлов не дочитана", "truncated")
        }
        val names = runCatching {
            bytes.readAt(0x10L + count.toLong() * PFS0_ENTRY, stringTableSize)
        }.getOrDefault(ByteArray(0))

        // Смещения в таблице PFS0 ОТНОСИТЕЛЬНЫЕ: они считаются от начала
        // области данных, а не от начала файла. Именно на этом я и ошибся в
        // сборке §49 — читал «по смещению части» от нуля файла, попадал в
        // случайные байты и объявлял собранным неверно каждый живой файл:
        // у Blade Chimera вышло «Yj», у DLC «Б5», у Vault of Power «C».
        val dataStart = 0x10L + count.toLong() * PFS0_ENTRY + stringTableSize

        var compressed = false
        var hasTicket = false
        var titleId = ""
        var fromName = ""
        var firstMagic = ""

        for (i in 0 until count) {
            val base = i * PFS0_ENTRY
            val offset = u64(table, base)
            val size = u64(table, base + 8)
            // u64 отдаёт -1, если поля не дочитались: тогда и смещение, и
            // размер бессмысленны, и читать по ним нельзя.
            if (offset < 0 || size < 0) continue
            val nameOffset = u32(table, base + 0x10)
            val name = cString(names, nameOffset)
            if (name.endsWith(".ncz", ignoreCase = true)) compressed = true
            // Части проверяем у ПЕРВОГО NCA, а не у первой записи: в
            // контейнере первыми обычно идут .cert, .tik и .xml, и метка NCA
            // стоит не в них.
            if (firstMagic.isEmpty() && name.endsWith(".nca", ignoreCase = true)) {
                val magic = runCatching { bytes.readAt(dataStart + offset + 0x200L, 4) }
                    .getOrDefault(ByteArray(0))
                // Только печатное: нули в заголовке иначе выглядели бы как
                // «метка есть», и файл объявлялся бы собранным неверно.
                firstMagic = ascii(magic, 0, 4).filter { it.code in 32..126 }
            }
            if (name.endsWith(".tik", ignoreCase = true)) {
                hasTicket = true
                if (titleId.isEmpty() && size >= TICKET_RIGHTS_ID + TICKET_RIGHTS_LEN) {
                    // Rights ID билета — это и есть идентификатор игры. Лежит
                    // открытым текстом: расшифровывать его не нужно.
                    val rights = runCatching {
                        bytes.readAt(dataStart + offset + TICKET_RIGHTS_ID, TICKET_RIGHTS_LEN)
                    }.getOrDefault(ByteArray(0))
                    // Rights ID — 16 байт: первые восемь и есть идентификатор
                    // игры, остальные восемь нулевые. В текст идёт только он,
                    // иначе получится 32 символа вместо 16.
                    val candidate = hex(if (rights.size >= 8) rights.copyOfRange(0, 8) else rights)
                    // Билет без права — обычное дело у дампов из образов
                    // картриджа: Rights ID там забит FF. Такой
                    // «идентификатор» нельзя ни показать, ни применить —
                    // на телефоне в диалоге он вылез как ffffffffffffffff.
                    if (TitleKeys.isPlausible(candidate)) titleId = candidate
                }
            }
            // Часть дампов называет NCA идентификатором игры, а не хешем. Но
            // это только запасной источник: в образе лежат и NCA обновления
            // (…a800), и тогда имя файла назовёт не тот идентификатор. Билет
            // главнее — он один и указывает именно на основную игру.
            if (fromName.isEmpty() && name.endsWith(".nca", ignoreCase = true)) {
                val stem = name.dropLast(4).lowercase()
                if (TitleKeys.isPlausible(stem)) fromName = stem
            }
        }
        if (titleId.isEmpty()) titleId = fromName
        // Последний источник — имя файла: у дампов, названных идентификатором,
        // это единственное место, где он вообще есть.
        if (titleId.isEmpty()) {
            val fromFileName = Regex("(0100[0-9a-fA-F]{12})").find(fileNameOf)?.value?.lowercase()
            if (fromFileName != null && TitleKeys.isPlausible(fromFileName)) titleId = fromFileName
        }

        if (count > 0 && names.isEmpty()) {
            return empty(Container.UNKNOWN, "файл обрезан сразу после заголовка", "truncated")
        }

        val container = if (compressed || ext == "nsz") Container.NSZ else Container.NSP
        val problem = when {
            compressed || ext == "nsz" ->
                "внутри сжатые части (NCZ) — это NSZ, его сначала распаковывают"
            ext == "xci" ->
                "внутри контейнер NSP, а расширение «.xci»: ядро выбирает загрузчик по расширению"
            firstMagic == "NCZS" ->
                "части внутри всё ещё сжаты (NCZS): распаковка не состоялась"
            firstMagic.isNotEmpty() && !firstMagic.startsWith("NCA") ->
                "по смещению части лежит не NCA («$firstMagic»): контейнер собран неверно"
            !hasTicket && titleId.isEmpty() ->
                "в образе нет билета (.tik): нужен ключ этой игры в title.keys"
            else -> ""
        }
        val fault = when {
            compressed || ext == "nsz" -> "compressed"
            ext == "xci" -> "ext-mismatch"
            firstMagic == "NCZS" -> "compressed"
            firstMagic.isNotEmpty() && !firstMagic.startsWith("NCA") -> "not-nca"
            !hasTicket && titleId.isEmpty() -> "no-ticket"
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
            fault = fault,
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
     * Короткая пометка на плитке — то, что видно до нажатия.
     *
     * Раньше на всех нерaспознанных файлах стояло «нет ключей». На телефоне
     * ключи были на месте (204 записи), поэтому пометка была неправдой.
     */
    fun label(result: Result, keysPresent: Boolean, titleKeyPresent: Boolean? = null): String = when {
        result.fault == "compressed" || result.compressed -> "сжатый"
        result.fault == "truncated" -> "обрезан"
        result.fault == "empty" -> "пустой файл"
        result.fault == "header" -> "заголовок битый"
        result.fault == "ext-mismatch" -> "другой формат"
        result.fault == "not-image" -> "не образ"
        result.fault == "not-nca" -> "собран неверно"
        // Пометки «файл в порядке» больше нет: целый контейнер — это ещё не
        // рабочая игра. 06.11 такое «в порядке» стояло на игре, которую ядро
        // не открыло из-за отсутствующего ключа.
        !result.hasTicket -> if (titleKeyPresent == true) "ключ в title.keys" else "нет ключа игры"
        result.titleId.isEmpty() -> "билет без права"
        titleKeyPresent == false -> "нет ключа игры"
        !keysPresent -> "нет ключей"
        else -> "ключи на месте"
    }

    /**
     * Человеческая причина отказа — для плитки и диалога.
     *
     * Отдельно от [Result.problem]: там короткая суть для разбора, здесь —
     * фраза, которую не стыдно показать.
     */

    fun verdict(
        result: Result,
        keysPresent: Boolean,
        titleKeyPresent: Boolean? = null
    ): String = when {
        result.container == Container.UNKNOWN -> result.problem

        result.compressed ->
            "Это сжатый образ (NSZ): ядро открывает только распакованные. " +
                "Нажмите «Распаковать» — конвертер сделает обычный NSP."

        result.problem.isNotEmpty() -> result.problem

        // Дальше контейнер цел — значит вопрос в ключе игры, а не в prod.keys.
        //
        // 204 ключа консоли и «core reports keys present: true» ничего не
        // говорят о конкретной игре: её ключ лежит либо в билете внутри
        // образа, либо строкой «0100… = 32hex» в title.keys. 06.11 в диалоге
        // было «дело не в ключах, это наш дефект» — и это была неправда.
        !result.hasTicket ->
            summary(result) + ". Билета внутри нет, поэтому ключ может быть только в " +
                "title.keys" + when (titleKeyPresent) {
                false -> ": а записи для ${result.titleId} там нет. Добавьте строку " +
                    "${result.titleId} = <32 hex> или положите файл с ней в keys/extra."
                true -> ": и он там есть — тогда причина отказа не в ключе."
                null -> "."
            }

        result.titleId.isEmpty() ->
            summary(result) + ". Права в билете нет (Rights ID пустой), идентификатор игры " +
                "из него не выводится — такой файл открывается только ключом из title.keys."

        titleKeyPresent == false ->
            summary(result) + ". Билет без права: ключ из него не выводится, поэтому нужен " +
                "ключ игры ${result.titleId} в title.keys — записи для неё там нет. " +
                if (keysPresent) {
                    "Ключи консоли установлены, но этого не заменяют."
                } else {
                    "Ключей консоли тоже нет."
                }

        titleKeyPresent == true ->
            summary(result) + ". Ключ для ${result.titleId} в title.keys есть — значит отказ " +
                "ядра не про ключи; пришлите, пожалуйста, этот текст, и я возьмусь за чтение."

        else ->
            summary(result) + ". Ключ берётся из билета, а если его нет — из title.keys; " +
                "проверить это можно в разделе «Ключи и прошивка»."
    }

    /** Первая фраза вердикта: что за контейнер и из чего он состоит. */
    private fun summary(result: Result): String =
        "Контейнер читается: частей ${result.entries}" +
            (if (result.titleId.isNotEmpty()) ", игра ${result.titleId}" else "") +
            (if (result.hasTicket) ", билет внутри есть" else "")

    // ── разбор байтов ──────────────────────────────────────────────────

    private fun empty(container: Container, problem: String, fault: String = "") = Result(
        container = container,
        part = Part.UNKNOWN,
        titleId = "",
        hasTicket = false,
        entries = 0,
        compressed = false,
        problem = problem,
        fault = fault,
    )

    private fun ascii(data: ByteArray, offset: Int, length: Int): String {
        // Пустой или короткий ответ readAt — это «не прочитали», а не «прочли
        // пустоту»: иначе обрезанный файл назывался бы «собран неверно».
        if (data.size < offset + length) return ""
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

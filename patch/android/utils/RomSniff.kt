// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

/**
 * Что за файл выбрали для распаковки — по первым байтам, а не по имени.
 *
 * ЗАЧЕМ ЭТО ЗДЕСЬ
 *   На устройстве конвертер ответил «распаковка закончилась без результата»,
 *   и понять, что произошло, было нельзя: имя файла в отчёте не сохранялось,
 *   а причина терялась по дороге. При этом самый частый случай — не битый
 *   NSZ, а вообще не NSZ: человек выбирает `.zip` с образом внутри, ждёт
 *   распаковку и получает ничего.
 *
 *   Ядро отвечает на такой файл «Not an NSZ, XCZ or NCZ file» — формально
 *   верно и совершенно бесполезно. Расширение тоже не ответ: `.nsz`,
 *   переименованный из `.zip`, встречается. Поэтому смотрим первые байты:
 *   контейнеры Nintendo и архивы имеют разные и ни с чем не похожие подписи.
 *
 * ПОЧЕМУ ОТДЕЛЬНЫМ ОБЪЕКТОМ
 *   Это чистая функция от байтов, без Android: таблица проверяется тестом на
 *   хосте. Ошибка в ней стоит дорого — правильный NSZ объявили бы архивом и
 *   не стали распаковывать.
 */
object RomSniff {

    /** Что это оказалось. */
    enum class Kind {
        /** Сжатый образ Nintendo — то, ради чего конвертер и существует. */
        NSZ, XCZ, NCZ,

        /** Уже распакованный образ: ядро открывает его как есть. */
        NSP, XCI, NCA, NRO,

        /** Обычный архив: внутри может быть что угодно, но не в нынешнем виде. */
        ZIP, RAR, SEVEN_Z, GZIP, TAR,

        /** Пустой или нечитаемый файл. */
        EMPTY, UNKNOWN
    }

    /** Сколько байт нужно, чтобы отличить всё перечисленное. */
    const val HEADER_BYTES = 16

    /**
     * Определить вид файла.
     *
     * Порядок важен: сначала подпись содержимого (её нельзя подделать
     * переименованием), и только если содержимое ни о чём не говорит —
     * расширение. Обратный порядок и давал «NSZ», внутри которого zip.
     */
    fun kind(header: ByteArray, name: String): Kind {
        val ext = name.substringAfterLast('.', "").lowercase()

        // Архивы: подписи короткие, но однозначные.
        if (startsWith(header, 0x50, 0x4B, 0x03, 0x04) || startsWith(header, 0x50, 0x4B, 0x05, 0x06)) {
            return Kind.ZIP
        }
        if (startsWith(header, 0x52, 0x61, 0x72, 0x21)) return Kind.RAR
        if (startsWith(header, 0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C)) return Kind.SEVEN_Z
        if (startsWith(header, 0x1F, 0x8B)) return Kind.GZIP

        // Контейнеры Nintendo.
        if (startsWith(header, 0x48, 0x45, 0x41, 0x44)) {
            // "HEAD" — образ картриджа. XCZ отличается только расширением:
            // сжатие живёт внутри разделов, заголовок тот же.
            return if (ext == "xcz") Kind.XCZ else Kind.XCI
        }
        if (startsWith(header, 0x50, 0x46, 0x53, 0x30)) {
            // "PFS0" — тоже только по расширению: NSZ это тот же контейнер,
            // у которого часть файлов сжата.
            return if (ext == "nsz") Kind.NSZ else Kind.NSP
        }
        // NCA: "NCA0"…"NCA3" — первые три буквы общие.
        if (startsWith(header, 0x4E, 0x43, 0x41)) {
            return if (ext == "ncz") Kind.NCZ else Kind.NCA
        }
        // NRO: старшие байты "NRO0", младший порядок — 0x4F 0x52 0x4E 0x00.
        if (startsWith(header, 0x4E, 0x52, 0x4F, 0x30)) return Kind.NRO

        if (header.isEmpty()) return Kind.EMPTY

        // Подпись не опознана. Расширение ещё может спасти: у части дампов
        // заголовок переставлен, но файл при этом настоящий.
        return when (ext) {
            "nsz" -> Kind.NSZ
            "xcz" -> Kind.XCZ
            "ncz" -> Kind.NCZ
            "nsp" -> Kind.NSP
            "xci" -> Kind.XCI
            "nca" -> Kind.NCA
            "nro" -> Kind.NRO
            else -> Kind.UNKNOWN
        }
    }

    /** Сжатый образ: внутри есть что распаковывать. */
    fun needsDecompress(kind: Kind): Boolean =
        kind == Kind.NSZ || kind == Kind.XCZ || kind == Kind.NCZ

    /** Готовый образ: ядро открывает его как есть, распаковывать нечего. */
    fun isReadyToPlay(kind: Kind): Boolean =
        kind == Kind.NSP || kind == Kind.XCI || kind == Kind.NCA || kind == Kind.NRO

    /**
     * Есть ли смысл запускать обработку.
     *
     * Сжатые образы распаковываются, готовые просто копируются в папку
     * результата (так файл оказывается в одном месте, где его видно, - это
     * прежнее поведение и его незачем ломать). Архивы и мусор отсекаются
     * сразу: раньше они доходили до нативной распаковки, она отвечала «Not an
     * NSZ, XCZ or NCZ file», и человек ждал сорок минут, чтобы получить
     * «ничего».
     */
    fun shouldProcess(kind: Kind): Boolean = needsDecompress(kind) || isReadyToPlay(kind)

    /**
     * Что сказать человеку про этот файл, если распаковывать нечего.
     *
     * Простыми словами и с действием, а не «неизвестный формат».
     */
    fun advice(kind: Kind, name: String): String = when (kind) {
        Kind.NSZ, Kind.XCZ, Kind.NCZ -> ""

        Kind.ZIP, Kind.RAR, Kind.SEVEN_Z, Kind.GZIP, Kind.TAR -> {
            val what = when (kind) {
                Kind.ZIP -> "ZIP"
                Kind.RAR -> "RAR"
                Kind.SEVEN_Z -> "7z"
                Kind.GZIP -> "GZ"
                else -> "TAR"
            }
            "«$name» — это архив $what, а не образ. Распакуйте его в проводнике и " +
                "выберите оттуда файл .nsp, .xci, .nsz или .xcz."
        }

        Kind.NSP, Kind.XCI, Kind.NCA, Kind.NRO ->
            "«$name» уже распакован — распаковывать нечего, файл можно открывать как есть."

        Kind.EMPTY ->
            "«$name» пустой: в нём нет ни байта. Скачивание не состоялось."

        Kind.UNKNOWN ->
            "«$name» не похож ни на NSZ/XCZ/NCZ, ни на образ: первые байты файла " +
                "не совпадают ни с одним контейнером. Возможно, скачивание не завершилось."
    }

    private fun startsWith(data: ByteArray, vararg bytes: Int): Boolean {
        if (data.size < bytes.size) return false
        for (i in bytes.indices) {
            if (data[i].toInt() and 0xFF != bytes[i]) return false
        }
        return true
    }
}

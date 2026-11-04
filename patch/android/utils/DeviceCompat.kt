// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

/**
 * Совместимость второго ядра с конкретным телефоном.
 *
 * Зачем отдельный файл, а не одна проверка в загрузчике. «Оригинал работает,
 * а у вас не работает» — это не жалоба, а факт: официальный APK Kenji и наше
 * скачанное ядро попадают на устройство по-разному. Официальный APK несёт
 * свои библиотеки внутри и устанавливается штатно; мы кладём один `.so` в
 * папку приложения и грузим его через `System.load`. У этого пути три
 * собственные точки отказа, и каждая проверяется до скачивания, а не после:
 *
 *  1. **Архитектура.** Ядро собрано под arm64. На 32-битном процессе
 *     `System.load` отвечает «is 32-bit instead of 64-bit» — после 57 МБ
 *     загрузки. Проверяется заранее.
 *  2. **Страницы памяти.** С Android 15 часть устройств работает со
 *     страницами 16 КБ. Библиотека, выровненная на 4 КБ, на таком телефоне
 *     не загрузится вообще (`empty/misaligned DT_LOAD`). Официальный APK
 *     собран с 16-КБ выравниванием, а наш релизный `.so` — не обязательно.
 *     Здесь это читается из заголовков ELF до загрузки.
 *  3. **Версия системы.** Ядро требует минимум, ниже которого не запустится
 *     ни у кого.
 *
 * Всё, что можно проверить без Android, проверяется без Android: разбор ELF
 * и таблицы auxv — обычные функции над `ByteArray`, и на них есть тесты.
 * Иначе «несовместимо» снова стало бы словом без доказательства.
 */
object DeviceCompat {

    /** Минимальная версия Android, на которой ядро вообще стартует. */
    const val MIN_SDK = 29

    /** Размер страницы, начиная с которого выравнивание 4 КБ недостаточно. */
    const val PAGE_16K = 16384L

    /** Что известно об устройстве. Заполняется из `Build` и `/proc/self/auxv`. */
    data class Device(
        val abis: List<String>,
        val sdkInt: Int,
        val pageSizeBytes: Long,
        val model: String = "",
        /**
         * 64-битный ли НАШ процесс.
         *
         * Не то же самое, что «устройство 64-битное»: приложение может быть
         * установлено 32-битным, и тогда `dlopen` 64-битной библиотеки
         * отвечает «is 32-bit instead of 64-bit», сколько бы ядер ни
         * поддерживал процессор. Официальный APK этим не страдает — он
         * ставится тем же комплектом, что и система выбирает.
         */
        val is64Process: Boolean = true
    ) {
        val isArm64: Boolean get() = abis.any { it == "arm64-v8a" }
        val needs16kAlign: Boolean get() = pageSizeBytes >= PAGE_16K
    }

    /**
     * Итог проверки.
     *
     * [supported] — «скачивать имеет смысл». [advice] заполняется, только
     * когда делать что-то нужно: пустой совет лучше, чем «всё хорошо,
     * ничего не делайте» в трёх местах экрана.
     */
    data class Verdict(
        val supported: Boolean,
        val headline: String,
        val details: List<String>,
        val advice: String = ""
    )

    /**
     * Проверка до скачивания: файла ещё нет, поэтому выравнивание неизвестно.
     *
     * Отсутствие файла — не причина отказывать: именно за ним и идут. Но если
     * устройство заведомо не подходит по архитектуре или версии, честнее
     * сказать это до 57 МБ трафика.
     */
    fun checkBeforeDownload(device: Device): Verdict {
        val details = describe(device)
        if (!device.isArm64) {
            return Verdict(
                supported = false,
                headline = "ядро не запустится: процессор не arm64",
                details = details,
                advice = "установите APK Kenji от разработчика — он ставится как обычное " +
                    "приложение и работает на любом поддерживаемом устройстве"
            )
        }
        if (!device.is64Process) {
            // Ядро только 64-битное, а приложение может быть установлено
            // 32-битным: Android выбирает ABI по устройству, но 32-битные
            // сборки живут на 64-битных телефонах и грузить 64-битные
            // библиотеки не могут.
            return Verdict(
                supported = false,
                headline = "приложение работает 32-битным процессом",
                details = details + "процесс: 32-битный, ядро: 64-битное",
                advice = "официальный APK Kenji в этом случае подходит: он ставится " +
                    "64-битным и запускает те же игры"
            )
        }
        if (device.sdkInt < MIN_SDK) {
            return Verdict(
                supported = false,
                headline = "ядро требует Android ${sdkName(MIN_SDK)} или новее",
                details = details,
                advice = "используйте установленный APK Kenji — он поддерживает более старые версии"
            )
        }
        return Verdict(true, "устройство подходит", details)
    }

    /**
     * Проверка после скачивания: файл есть, и его выравнивание известно.
     *
     * Именно здесь ловится «скачалось, а не запускается» на Android 15 с
     * 16-КБ страницами. Сообщение называет причину, а не «ошибка загрузки».
     */
    fun checkCore(device: Device, maxAlign: Long?): Verdict {
        val base = checkBeforeDownload(device)
        if (!base.supported) return base
        if (maxAlign == null) {
            return base.copy(
                headline = "устройство подходит",
                details = base.details + "ядро: заголовок ELF не прочитан"
            )
        }
        if (device.needs16kAlign && maxAlign < PAGE_16K) {
            return Verdict(
                supported = false,
                headline = "ядро не загрузится: собрано под страницы 4 КБ",
                details = base.details + "ядро: выравнивание ${maxAlign / 1024} КБ, устройство " +
                    "работает со страницами ${device.pageSizeBytes / 1024} КБ",
                advice = "это ограничение сборки ядра, а не телефона: официальный APK Kenji " +
                    "собран с выравниванием 16 КБ и на этом устройстве работает"
            )
        }
        return Verdict(
            supported = true,
            headline = "устройство подходит",
            details = base.details + "ядро: выравнивание ${maxAlign / 1024} КБ"
        )
    }

    /** Строки для экрана: факты, по которым видно, что именно проверено. */
    fun describe(device: Device): List<String> = buildList {
        add("процессор: ${if (device.abis.isEmpty()) "неизвестен" else device.abis.joinToString(", ")}")
        add("процесс приложения: ${if (device.is64Process) "64-битный" else "32-битный"}")
        add("Android: ${device.sdkInt} (${sdkName(device.sdkInt)})")
        add("страницы памяти: ${device.pageSizeBytes / 1024} КБ")
        if (device.model.isNotBlank()) add("устройство: ${device.model}")
    }

    fun sdkName(sdk: Int): String = when {
        sdk >= 36 -> "16"
        sdk == 35 -> "15"
        sdk == 34 -> "14"
        sdk == 33 -> "13"
        sdk == 32 -> "12L"
        sdk == 31 -> "12"
        sdk == 30 -> "11"
        sdk == 29 -> "10"
        sdk == 28 -> "9"
        else -> "API $sdk"
    }

    // ── Разбор ELF ─────────────────────────────────────────────────────

    /**
     * Максимальное выравнивание сегментов `PT_LOAD` в ELF-файле.
     *
     * Нужен ровно один ответ: 4 КБ или 16 КБ. Android при загрузке библиотеки
     * смотрит на `p_align` загружаемых сегментов; если он меньше размера
     * страницы устройства, библиотека не отображается в память.
     *
     * Возвращает null, если это не ELF, файл обрезан или сегментов нет —
     * «не знаю» честнее выдуманного числа.
     */
    fun elfMaxAlign(bytes: ByteArray): Long? {
        if (bytes.size < 64) return null
        if (bytes[0] != 0x7F.toByte() || bytes[1] != 'E'.code.toByte() ||
            bytes[2] != 'L'.code.toByte() || bytes[3] != 'F'.code.toByte()
        ) return null

        val is64 = when (bytes[4].toInt() and 0xFF) {
            2 -> true   // ELFCLASS64
            1 -> false  // ELFCLASS32
            else -> return null
        }
        // Порядок байт тоже бывает разный; библиотеки Android — LE, но
        // проверка дешёвая и избавляет от чтения мусора.
        val littleEndian = when (bytes[5].toInt() and 0xFF) {
            1 -> true
            2 -> false
            else -> return null
        }

        val phoff: Long
        val phentsize: Int
        val phnum: Int
        if (is64) {
            phoff = readLong(bytes, 0x20, littleEndian) ?: return null
            phentsize = readShort(bytes, 0x36, littleEndian) ?: return null
            phnum = readShort(bytes, 0x38, littleEndian) ?: return null
        } else {
            phoff = (readInt(bytes, 0x1C, littleEndian) ?: return null).toLong()
            phentsize = readShort(bytes, 0x2A, littleEndian) ?: return null
            phnum = readShort(bytes, 0x2C, littleEndian) ?: return null
        }
        if (phnum <= 0 || phentsize < 32) return null

        var best = 0L
        for (i in 0 until phnum) {
            val at = phoff + i.toLong() * phentsize
            if (at + phentsize > bytes.size) break
            val type = readInt(bytes, at.toInt(), littleEndian) ?: continue
            if (type != 1) continue               // PT_LOAD
            val align = if (is64) {
                readLong(bytes, at.toInt() + 0x30, littleEndian) ?: continue
            } else {
                (readInt(bytes, at.toInt() + 0x1C, littleEndian) ?: continue).toLong()
            }
            if (align > best) best = align
        }
        return if (best > 0) best else null
    }

    // ── Размер страницы ────────────────────────────────────────────────

    /**
     * Размер страницы из `/proc/self/auxv` (`AT_PAGESZ`).
     *
     * Берётся из ядра, потому что `Build.VERSION` его не сообщает:
     * на Android 15 одни устройства работают с 4 КБ, другие с 16 КБ, и
     * различить их по версии системы нельзя.
     *
     * Файл — последовательность пар «тип, значение». Разрядность неизвестна
     * заранее, поэтому разбираются оба варианта, а принимается только
     * осмысленный результат: степень двойки от 4 до 64 КБ.
     */
    fun auxvPageSize(bytes: ByteArray): Long? {
        fun plausible(v: Long) = v in 4096..65536 && (v and (v - 1)) == 0L

        // 64-битная запись: 16 байт на пару, значение — вторые 8.
        var i = 0
        while (i + 16 <= bytes.size) {
            val type = readLong(bytes, i, littleEndian = true) ?: break
            val value = readLong(bytes, i + 8, littleEndian = true) ?: break
            if (type == 6L && plausible(value)) return value
            if (type == 0L) break              // AT_NULL
            i += 16
        }
        // 32-битная: 8 байт на пару.
        i = 0
        while (i + 8 <= bytes.size) {
            val type = (readInt(bytes, i, littleEndian = true) ?: break).toLong()
            val value = (readInt(bytes, i + 4, littleEndian = true) ?: break).toLong()
            if (type == 6L && plausible(value)) return value
            if (type == 0L) break
            i += 8
        }
        return null
    }

    // ── Чтение чисел ───────────────────────────────────────────────────

    private fun readShort(bytes: ByteArray, at: Int, littleEndian: Boolean): Int? {
        if (at < 0 || at + 2 > bytes.size) return null
        val a = bytes[at].toInt() and 0xFF
        val b = bytes[at + 1].toInt() and 0xFF
        return if (littleEndian) (b shl 8) or a else (a shl 8) or b
    }

    private fun readInt(bytes: ByteArray, at: Int, littleEndian: Boolean): Int? {
        if (at < 0 || at + 4 > bytes.size) return null
        var out = 0L
        for (k in 0 until 4) {
            val byte = (bytes[at + k].toInt() and 0xFF).toLong()
            val shift = if (littleEndian) k * 8 else (3 - k) * 8
            out = out or (byte shl shift)
        }
        return out.toInt()
    }

    private fun readLong(bytes: ByteArray, at: Int, littleEndian: Boolean): Long? {
        if (at < 0 || at + 8 > bytes.size) return null
        var out = 0L
        for (k in 0 until 8) {
            val byte = (bytes[at + k].toInt() and 0xFF).toLong()
            val shift = if (littleEndian) k * 8 else (7 - k) * 8
            out = out or (byte shl shift)
        }
        return out
    }
}

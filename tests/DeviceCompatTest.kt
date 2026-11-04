// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * Проверки совместимости ядра. Запускаются на хосте, без Android.
 *
 * Смысл в том, что «несовместимо с устройством» — это утверждение, которое
 * нужно доказывать. Здесь проверяется само доказательство: разбор ELF
 * (выравнивание сегментов), разбор таблицы auxv (размер страницы) и вывод
 * «подходит / не подходит» с причиной, а не с общей фразой.
 *
 * Заодно проверяется главное требование: на телефоне, где оригинальный APK
 * Kenji работает, наш путь не должен отказывать без объяснения.
 */

import org.yuzu.yuzu_emu.utils.DeviceCompat

private var passed = 0
private val failures = mutableListOf<String>()

private fun check(what: String, ok: Boolean, detail: String = "") {
    if (ok) {
        passed++
    } else {
        failures.add("$what${if (detail.isEmpty()) "" else " → $detail"}")
    }
}

private fun <T> eq(what: String, got: T, want: T) =
    check(what, got == want, "получено [$got], ожидалось [$want]")

// ── Синтетические файлы ────────────────────────────────────────────────

/** ELF64 little-endian с заданным выравниванием сегментов PT_LOAD. */
private fun elf64(alings: List<Long>): ByteArray {
    val phentsize = 56
    val phoff = 64
    val size = phoff + phentsize * alings.size
    val b = ByteArray(size)

    fun put8(at: Int, v: Long) {
        for (k in 0 until 8) b[at + k] = ((v shr (k * 8)) and 0xFF).toByte()
    }
    fun put4(at: Int, v: Int) {
        for (k in 0 until 4) b[at + k] = ((v shr (k * 8)) and 0xFF).toByte()
    }
    fun put2(at: Int, v: Int) {
        b[at] = (v and 0xFF).toByte()
        b[at + 1] = ((v shr 8) and 0xFF).toByte()
    }

    b[0] = 0x7F
    b[1] = 'E'.code.toByte()
    b[2] = 'L'.code.toByte()
    b[3] = 'F'.code.toByte()
    b[4] = 2            // ELFCLASS64
    b[5] = 1            // little endian
    put8(0x20, phoff.toLong())
    put2(0x36, phentsize)
    put2(0x38, alings.size)

    alings.forEachIndexed { i, align ->
        val at = phoff + i * phentsize
        put4(at, 1)              // PT_LOAD
        put8(at + 0x30, align)   // p_align
    }
    return b
}

/** Таблица auxv в 64-битном виде. */
private fun auxv64(pairs: List<Pair<Long, Long>>): ByteArray {
    val b = ByteArray(pairs.size * 16)
    fun put8(at: Int, v: Long) {
        for (k in 0 until 8) b[at + k] = ((v shr (k * 8)) and 0xFF).toByte()
    }
    pairs.forEachIndexed { i, (type, value) ->
        put8(i * 16, type)
        put8(i * 16 + 8, value)
    }
    return b
}

private fun device(
    abis: List<String> = listOf("arm64-v8a", "armeabi-v7a"),
    sdk: Int = 34,
    page: Long = 4096,
    model: String = "Xiaomi 14",
    is64Process: Boolean = true
) = DeviceCompat.Device(abis, sdk, page, model, is64Process)

fun main() {
    // ── ELF: выравнивание ───────────────────────────────────────────────
    eq("выравнивание 4 КБ читается", DeviceCompat.elfMaxAlign(elf64(listOf(4096))), 4096L)
    eq("выравнивание 16 КБ читается", DeviceCompat.elfMaxAlign(elf64(listOf(16384))), 16384L)
    eq(
        "берётся максимум по сегментам, а не первый",
        DeviceCompat.elfMaxAlign(elf64(listOf(4096, 16384, 4096))),
        16384L
    )
    eq("не ELF — не выдумываем число", DeviceCompat.elfMaxAlign(ByteArray(128)), null)
    eq("пустой файл", DeviceCompat.elfMaxAlign(ByteArray(0)), null)
    eq("обрезанный файл", DeviceCompat.elfMaxAlign(elf64(listOf(4096)).copyOfRange(0, 70)), null)
    eq("мусор вместо заголовка", DeviceCompat.elfMaxAlign("MZ\u0000\u0000".toByteArray()), null)

    // ── auxv: размер страницы ───────────────────────────────────────────
    eq(
        "страница 16 КБ читается",
        DeviceCompat.auxvPageSize(auxv64(listOf(3L to 1L, 6L to 16384L))),
        16384L
    )
    eq(
        "страница 4 КБ читается",
        DeviceCompat.auxvPageSize(auxv64(listOf(6L to 4096L))),
        4096L
    )
    eq(
        "нет AT_PAGESZ — не выдумываем",
        DeviceCompat.auxvPageSize(auxv64(listOf(3L to 1L, 0L to 0L))),
        null
    )
    eq(
        "неправдоподобный размер отбрасывается",
        DeviceCompat.auxvPageSize(auxv64(listOf(6L to 12345L))),
        null
    )
    eq("пустой auxv", DeviceCompat.auxvPageSize(ByteArray(0)), null)

    // ── до скачивания ───────────────────────────────────────────────────
    val plain = DeviceCompat.checkBeforeDownload(device())
    check("обычный arm64-телефон подходит", plain.supported)
    check("причина не выдумана, когда всё в порядке", plain.advice.isEmpty())

    val arm32 = DeviceCompat.checkBeforeDownload(
        device(abis = listOf("armeabi-v7a"), sdk = 29)
    )
    check("32-битное устройство отклоняется заранее", !arm32.supported)
    check(
        "32-битному предлагают официальный APK, а не «попробуйте ещё раз»",
        arm32.advice.contains("APK Kenji"),
        arm32.advice
    )

    val app32 = DeviceCompat.checkBeforeDownload(device(is64Process = false))
    check("32-битный процесс при 64-битном телефоне отклоняется", !app32.supported)
    check(
        "и это объясняется разрядностью процесса, а не телефона",
        app32.headline.contains("процесс"),
        app32.headline
    )
    check(
        "совет снова про официальный APK",
        app32.advice.contains("APK Kenji"),
        app32.advice
    )

    val old = DeviceCompat.checkBeforeDownload(device(sdk = 27))
    check("слишком старый Android отклоняется", !old.supported)
    check("и это сказано версией", old.headline.contains("Android"), old.headline)

    // ── после скачивания ────────────────────────────────────────────────
    val ok = DeviceCompat.checkCore(device(), 16384L)
    check("ядро с 16-КБ выравниванием подходит везде", ok.supported, ok.headline)

    val old4k = DeviceCompat.checkCore(device(page = 4096), 4096L)
    check("на 4-КБ страницах ядро с 4-КБ выравниванием подходит", old4k.supported)

    val new4k = DeviceCompat.checkCore(device(sdk = 35, page = 16384), 4096L)
    check("на 16-КБ страницах ядро с 4-КБ выравниванием отклоняется", !new4k.supported)
    check(
        "причина названа выравниванием, а не «ошибкой»",
        new4k.headline.contains("4 КБ"),
        new4k.headline
    )
    check(
        "в подробностях видно и устройство, и ядро",
        new4k.details.any { it.contains("16 КБ") } && new4k.details.any { it.contains("4 КБ") },
        new4k.details.joinToString(" | ")
    )
    check(
        "совет отделяет телефон от сборки",
        new4k.advice.contains("ограничение сборки"),
        new4k.advice
    )

    val unknown = DeviceCompat.checkCore(device(page = 16384), null)
    check("неизвестное выравнивание не блокирует загрузку", unknown.supported)
    check(
        "но и не выдаётся за проверенное",
        unknown.details.any { it.contains("не прочитан") },
        unknown.details.joinToString(" | ")
    )

    // ── строки для экрана ───────────────────────────────────────────────
    val lines = DeviceCompat.describe(device())
    eq("на экране пять фактов", lines.size, 5)
    check("архитектура названа", lines[0].contains("arm64-v8a"), lines[0])
    check("версия Android названа человеческой", lines[2].contains("14"), lines[2])
    check("разрядность процесса названа", lines[1].contains("64-битный"), lines[1])
    check("страницы памяти названы", lines[3].contains("4 КБ"), lines[3])

    eq("Android 15 называется 15", DeviceCompat.sdkName(35), "15")
    eq("Android 16 называется 16", DeviceCompat.sdkName(36), "16")
    eq("старый API не выдумывает версию", DeviceCompat.sdkName(21), "API 21")
    eq("Android 9 называется 9", DeviceCompat.sdkName(28), "9")

    println()
    println("passed=$passed failed=${failures.size}")
    failures.forEach { println("FAIL  $it") }
    if (failures.isNotEmpty()) kotlin.system.exitProcess(1)
}

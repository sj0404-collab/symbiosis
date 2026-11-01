// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * Проверки перевода причин отказа конвертера. На хосте, без Android.
 *
 * Зачем это тестировать: сообщение — единственное, что человек получает
 * вместо игры. «Распаковка не удалась» на недокачанном файле отправляет его
 * искать причину в приложении; «файл обрезан: скачивание не завершилось»
 * отправляет докачивать. Разница между этими двумя фразами — весь смысл
 * таблицы.
 */

import org.yuzu.yuzu_emu.utils.ConverterReasons

private var passed = 0
private val failures = mutableListOf<String>()

private fun check(what: String, ok: Boolean, detail: String = "") {
    if (ok) {
        passed++
    } else {
        failures.add("$what${if (detail.isEmpty()) "" else " → $detail"}")
    }
}

private fun says(error: String, mustContain: String) {
    val text = ConverterReasons.text(error)
    check(
        "«$error» → содержит «$mustContain»",
        text.contains(mustContain, ignoreCase = true),
        "получено: $text"
    )
}

fun main() {
    // Настоящие сообщения из C++ (ncz.cpp / rom_tools.cpp).
    says(
        "This file has no NCZSECTN section table, so it is not compressed.",
        "не сжатый файл"
    )
    says("NCZBLOCK claims 4 blocks, which is not plausible.", "заголовок сжатых блоков")
    says("The NCZBLOCK size table is truncated.", "поврежд")
    says("Section 2 has an implausible size.", "таблица секций")
    says("The zstd stream ends before the NCA is complete.", "обрезан")
    says("Block 7 is damaged: Data corruption detected", "данные")
    says("PFS0 file extends past the archive.", "контейнер NSP")
    says("XCZ has no secure partition.", "раздел с игрой")
    says("Could not write the decompressed NCA: storage may be full.", "мало места")
    says("This build has no zstd decoder, so compressed blocks cannot be read.", "ошибка сборки")
    // Раньше здесь ожидалось общее «распаковка не удалась»: причина была
    // известна, но не называлась. На устройстве это выглядело как
    // «закончилось без результата» без единой подсказки, поэтому теперь у
    // «не тот файл» есть своя фраза.
    says("Not an NSZ, XCZ or NCZ file.", "не сжатый образ")

    // Пустое сообщение — не повод показать пустоту.
    check("пустое сообщение даёт фразу", ConverterReasons.text("").isNotBlank())

    // Никаких имён классов и английских слов на экране.
    val samples = listOf(
        "This file has no NCZSECTN section table, so it is not compressed.",
        "PFS0 file extends past the archive.",
        "java.io.IOException: broken pipe",
        "NativeSymbiosis.decompress failed"
    )
    // Названия форматов (NCZ, NSP, XCI) — часть ответа, а не внутренности:
    // человек ищет в поиске именно их. Английских слов и имён классов быть
    // не должно.
    val allowed = listOf("NCZSECTN", "NCZ", "NSP", "XCI")
    samples.forEach { raw ->
        val text = ConverterReasons.text(raw)
        var rest = text
        allowed.forEach { rest = rest.replace(it, " ") }
        val latin = Regex("[A-Za-z]{2,}").find(rest)?.value
        check(
            "нет английских слов и имён классов в ответе на «$raw»",
            latin == null && !text.contains("Exception"),
            "лишнее [$latin] в: $text"
        )
    }

    println()
    println("passed=$passed failed=${failures.size}")
    failures.forEach { println("FAIL  $it") }
    if (failures.isNotEmpty()) kotlin.system.exitProcess(1)
}

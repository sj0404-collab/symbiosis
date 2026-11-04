// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * Проверки чтения журнала ядра. Запускаются на хосте, без Android.
 *
 * Зачем отдельные проверки у «просто чтения файла»:
 *
 *   * журнал за долгий сеанс бывает в сотни мегабайт, и наивное чтение
 *     целиком — это OutOfMemory ровно в тот момент, когда пользователь
 *     пришёл выяснять, почему игра не идёт;
 *   * обрезанное начало файла — половина строки; показать её значит
 *     выглядеть как повреждённый журнал;
 *   * «ошибок: 0» при семи ошибках в файле хуже, чем ничего: человек решает,
 *     что проблема не в ядре.
 */

import java.io.File
import org.yuzu.yuzu_emu.utils.KernelLogFormat

private var passed = 0
private val failures = mutableListOf<String>()

private fun check(what: String, ok: Boolean, detail: String = "") {
    if (ok) {
        passed++
    } else {
        failures.add("$what${if (detail.isEmpty()) "" else " → $detail"}")
    }
}

private fun eq(what: String, got: Any?, want: Any?) =
    check(what, got == want, "получено [$got], ожидалось [$want]")

private fun tempFile(text: String): File =
    File.createTempFile("kernellog", ".txt").apply {
        writeText(text)
        deleteOnExit()
    }

fun main() {
    // --- хвост ----------------------------------------------------------
    val small = tempFile("строка 1\nстрока 2\n")
    eq(
        "файл меньше лимита читается целиком",
        KernelLogFormat.tailOf(small, 1024),
        "строка 1\nстрока 2\n"
    )

    val lines = (1..50).joinToString("\n") { "строка $it" }
    val big = tempFile(lines)
    val tail = KernelLogFormat.tailOf(big, 40)
    check(
        "хвост короче файла",
        tail.length <= 40,
        "длина ${tail.length}"
    )
    check(
        "хвост заканчивается последней строкой файла",
        tail.trimEnd().endsWith("строка 50"),
        tail
    )
    check(
        "обрезанная первая строка выброшена",
        !tail.startsWith("рока"),
        tail
    )

    eq(
        "точный размер не портит текст",
        KernelLogFormat.tailOf(big, big.length().toInt()),
        lines
    )

    // --- подсчёт ошибок --------------------------------------------------
    val log = """
        [    1.000000] Core <Info> core.cpp:10: запуск
        [    2.000000] Loader <Error> loader.cpp:42: файл не читается
        [    3.000000] Service <Warning> sm.cpp:7: медленно
        [    4.000000] Kernel <Critical> panic: сбой
        [    5.000000] Loader <Error> loader.cpp:50: и снова
    """.trimIndent()

    eq("ошибки и критические посчитаны", KernelLogFormat.countProblems(log), 3)
    eq("чистый журнал даёт ноль", KernelLogFormat.countProblems("всё хорошо\nвсё хорошо"), 0)
    eq("пустой текст даёт ноль", KernelLogFormat.countProblems(""), 0)
    check(
        "слово Error в тексте сообщения не считается ошибкой уровня",
        KernelLogFormat.countProblems("[ 1.0] Core <Info> x.cpp:1: ErrorHandler готов") == 0
    )

    println()
    println("passed=$passed failed=${failures.size}")
    failures.forEach { println("FAIL  $it") }
    if (failures.isNotEmpty()) kotlin.system.exitProcess(1)
}

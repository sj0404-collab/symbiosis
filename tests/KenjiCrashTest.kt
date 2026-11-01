// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * Разбор отчёта о падении второго ядра.
 *
 * ПОЧЕМУ ЭТОТ ФАЙЛ СУЩЕСТВУЕТ
 *   Раньше на падение процесса `:kenji` приложение отвечало заранее
 *   написанной фразой, в которой было утверждение, никем не проверенное
 *   («официальный Kenji на этом устройстве уже играет»). Отчёт с устройства
 *   его не подтверждал. Теперь ответ строится из файла, который процесс
 *   успевает записать перед смертью, - и разбор этого файла обязан быть
 *   устойчивым: его пишет обработчик сигнала, у которого нет права на ошибку,
 *   а читает обычный код после смерти процесса.
 */

import org.yuzu.yuzu_emu.utils.KenjiCrash

private var passed = 0
private val failures = mutableListOf<String>()

private fun check(what: String, ok: Boolean, detail: String = "") {
    if (ok) passed++ else failures.add("$what${if (detail.isEmpty()) "" else " → $detail"}")
}

private fun eq(what: String, got: Any?, want: Any?) =
    check(what, got == want, "получено [$got], ожидалось [$want]")

fun main() {
    // ── полный отчёт ───────────────────────────────────────────────────
    val full = """
        step=javaInitialize
        debug_break=3
        crash signal=11 name=SIGSEGV addr=0x0 code=1 step=javaInitialize
        pc=libkenji.so+0x1a2b3
        pc=libsymbiosis_kenji.so+0x4d1
        pc=libc.so+0x3fd70
    """.trimIndent()

    val report = KenjiCrash.parse(full)
    check("полный отчёт разбирается", report != null)
    eq("шаг", report?.step, "javaInitialize")
    eq("сигнал", report?.signal, 11)
    eq("имя сигнала", report?.signalName, "SIGSEGV")
    eq("адрес", report?.address, "0x0")
    eq("кадров", report?.frames?.size, 3)
    eq("первый кадр", report?.frames?.first(), "libkenji.so+0x1a2b3")
    eq("debug_break", report?.debugBreaks, listOf(3))
    check("отчёт не пуст", report?.isEmpty == false)

    val text = KenjiCrash.format(report)
    check("в тексте назван шаг", text.contains("javaInitialize"), text)
    check("в тексте назван сигнал", text.contains("SIGSEGV"), text)
    check("в тексте названы кадры", text.contains("libkenji.so+0x1a2b3"), text)
    check("в тексте нет прежнего утверждения",
        !text.contains("уже играет") && !text.contains("Официальный Kenji"), text)
    check("в тексте сказано, что делать", text.contains("Скопировать"), text)

    // Падение внутри ядра и в нашей части различаются - от этого зависит,
    // чинить файл ядра или мост.
    check("виновато ядро", KenjiCrash.verdict(report!!).contains("libkenji.so"))
    val ours = KenjiCrash.parse("""
        step=javaInitialize
        crash signal=11 name=SIGSEGV addr=0x10 code=2 step=javaInitialize
        pc=libsymbiosis_kenji.so+0x44
    """.trimIndent())
    check("виноват мост", KenjiCrash.verdict(ours!!).contains("нашей части"))

    // debug_break перед падением - отдельный вывод: ядро само сказало об ошибке.
    check("учтён debug_break", KenjiCrash.verdict(report).contains("debug_break"))

    // ── что бывает на самом деле ───────────────────────────────────────
    check("пустой файл — отчёта нет", KenjiCrash.parse("") == null)
    check("пробелы — отчёта нет", KenjiCrash.parse("  \n\n ") == null)
    check("мусор — отчёта нет", KenjiCrash.parse("что-то пошло не так\nещё строка") == null)

    // Процесс убили посреди записи: остался один заголовок без кадров.
    val truncated = KenjiCrash.parse("step=dlopen\ncrash signal=11 name=SIGSEGV")
    check("обрезанный отчёт читается", truncated != null)
    eq("шаг из обрезанного", truncated?.step, "dlopen")
    eq("кадров нет", truncated?.frames?.size, 0)

    // Шага отдельной строкой нет, но он есть в crash-строке.
    val fromCrashLine = KenjiCrash.parse("crash signal=6 name=SIGABRT addr=(nil) step=deviceInitialize")
    eq("шаг из crash-строки", fromCrashLine?.step, "deviceInitialize")
    eq("SIGABRT", fromCrashLine?.signalName, "SIGABRT")

    // Повреждённые числа не должны превращаться в выдуманный сигнал.
    val badNumbers = KenjiCrash.parse("crash signal=abc name= addr= code= step=")
    check("числа не разобрались — отчёта нет", badNumbers == null)

    // ── отчёта нет вовсе ───────────────────────────────────────────────
    val none = KenjiCrash.format(null, "javaInitialize")
    check("без отчёта назван шаг", none.contains("javaInitialize"), none)
    check("без отчёта сказано про память", none.contains("памяти"), none)
    check("без отчёта нет выдуманного сигнала", !none.contains("SIGSEGV"), none)

    // ── подписи шагов ──────────────────────────────────────────────────
    val stepText = KenjiCrash.format(
        KenjiCrash.Report("deviceInitialize", 11, "SIGSEGV", "0x0", listOf("libkenji.so+0x1"), emptyList())
    )
    check("шаг объяснён словами", stepText.contains("deviceInitialize — настройка устройства"), stepText)

    println()
    println("passed=$passed failed=${failures.size}")
    failures.forEach { println("FAIL  $it") }
    if (failures.isNotEmpty()) kotlin.system.exitProcess(1)
}

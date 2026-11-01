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

    // ── кто виноват: решает ВЕРХНИЙ кадр ──────────────────────────────
    //
    // Отчёт с устройства: сверху стоял наш же обработчик сигнала, ниже —
    // libkenji.so. Прежняя проверка «есть ли libkenji где-нибудь» обвинила
    // ядро по кадру, который всего лишь был в стеке.
    val handlerOnTop = KenjiCrash.Report(
        step = "javaInitialize", signal = 6, signalName = "SIGABRT", address = "0x2e5300005fb0",
        frames = listOf(
            "libsymbiosis_kenji.so+0x2b968",
            "[vdso]+0x89c",
            "libc.so+0x530f4",
            "libkenji.so+0x1a2b3"
        ),
        debugBreaks = emptyList()
    )
    val handlerVerdict = KenjiCrash.verdict(handlerOnTop)
    check("верхний кадр — наш: виноват мост", handlerVerdict.contains("нашей части"), handlerVerdict)
    // Именно фраза обвинения, а не слово «ядро»: последняя строка совета
    // говорит «чинить файл ядра или мост» и остаётся всегда.
    check("ядро не обвинено по нижнему кадру",
        !handlerVerdict.contains("Падение внутри libkenji.so"), handlerVerdict)

    val coreOnTop = handlerOnTop.copy(
        frames = listOf("libkenji.so+0x1a2b3", "libsymbiosis_kenji.so+0x44")
    )
    check("верхний кадр — ядро: виноват файл ядра",
        KenjiCrash.verdict(coreOnTop).contains("файл ядра"))

    val systemOnTop = handlerOnTop.copy(frames = listOf("libc.so+0x530f4", "libkenji.so+0x1"))
    check("верхний кадр — система", KenjiCrash.verdict(systemOnTop).contains("вне обеих"))

    // SIGABRT — это не сбой памяти, и это должно быть сказано отдельно.
    check("SIGABRT объяснён", handlerVerdict.contains("остановка по требованию"), handlerVerdict)
    check("SIGABRT не объясняется как порча памяти", !handlerVerdict.contains("память побилась"))

    // ── журнал системы ─────────────────────────────────────────────────
    val logcat = """
        11-01 15:27:10.000  1234  1250 F libc    : Fatal signal 6 (SIGABRT), code -1 (SI_QUEUE) in tid 1250
        11-01 15:27:10.000  1234  1250 I DotNet  : Unhandled exception. System.DllNotFoundException
        11-01 15:27:10.000  1234  1250 I DotNet  : Unhandled exception. System.DllNotFoundException
        11-01 15:27:10.000  1234  1250 I chatty  : uid=10200 identical 2 lines
        11-01 15:27:09.000  1234  1234 I SymbiosisKenji: core loaded and verified
        11-01 15:27:09.000  1234  1234 I unrelated: something else entirely
    """.trimIndent()

    val picked = KenjiCrash.fatalLines(logcat)
    check("журнал отфильтрован", picked.size in 1..8, picked.toString())
    check("в журнале есть исключение .NET",
        picked.any { it.contains("Unhandled exception") }, picked.toString())
    check("повторы убраны",
        picked.count { it.contains("Unhandled exception") } == 1, picked.toString())
    check("посторонние строки не попали",
        picked.none { it.contains("unrelated") }, picked.toString())
    check("лимит соблюдён", KenjiCrash.fatalLines(logcat, limit = 2).size <= 2)
    check("пустой журнал — пустой список", KenjiCrash.fatalLines("").isEmpty())

    println()
    println("passed=$passed failed=${failures.size}")
    failures.forEach { println("FAIL  $it") }
    if (failures.isNotEmpty()) kotlin.system.exitProcess(1)
}

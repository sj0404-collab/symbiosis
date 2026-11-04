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

    // «Система» — это библиотека, которая не наша и не ядро. libc сюда не
    // годится: она механизм abort/raise и пропускается намеренно.
    val systemOnTop = handlerOnTop.copy(frames = listOf("libc.so+0x530f4", "libart.so+0x1"))
    check("верхний кадр — система", KenjiCrash.verdict(systemOnTop).contains("вне обеих"),
        KenjiCrash.verdict(systemOnTop))

    // SIGABRT — это не сбой памяти, и это должно быть сказано отдельно.
    check("SIGABRT объяснён", handlerVerdict.contains("остановка по требованию"), handlerVerdict)
    check("SIGABRT не объясняется как порча памяти", !handlerVerdict.contains("память побилась"))

    // ── настоящий отчёт с устройства ───────────────────────────────────
    //
    // Пришло ровно это: сверху два кадра libc (abort и raise), ниже — ядро.
    // Такая картина обязана читаться как «ядро само вызвало abort()», а не
    // «виновата система».
    val realReport = KenjiCrash.parse(
        """
        step=javaInitialize
        crash signal=6 name=SIGABRT addr=0x2e53000005a6 code=-6 step=javaInitialize
        pc=libc.so+0x530f4
        pc=libc.so+0x530c8
        pc=libkenji.so+0x173c214
        """.trimIndent()
    )
    check("настоящий отчёт разобран", realReport != null)
    val realVerdict = KenjiCrash.verdict(realReport!!)
    check("libc не обвинён", !realVerdict.contains("виновата система"), realVerdict)
    check("сказано, что abort из ядра", realVerdict.contains("abort() вызван изнутри libkenji.so"),
        realVerdict)
    check("назван шаг словами", realVerdict.contains("передача ядру пути к данным"), realVerdict)

    // ── перехват abort: виновник назван адресом возврата ───────────────
    //
    // Ядро вызывает abort() из своей общей функции фатальных ошибок, поэтому
    // первый адрес возврата один и тот же у всех восемнадцати мест. Полезен
    // следующий кадр — он и есть место отказа.
    val interposed = KenjiCrash.parse(
        """
        step=javaInitialize
        crash signal=6 name=SIGABRT addr=0x2e53000005a6 code=-1 step=javaInitialize
        abort-caller=libkenji.so+0x173c210
        abort-pc=0x7a1b2c3d40
        abort-pc=0x7a1b2c3e10
        pc=libc.so+0x530f4
        """.trimIndent()
    )
    check("перехватчик разобран", interposed?.abortCaller == "libkenji.so+0x173c210",
        interposed?.abortCaller ?: "null")
    check("цепочка от перехватчика собрана", interposed?.abortFrames?.size == 2,
        interposed?.abortFrames?.toString() ?: "null")
    val interposedVerdict = KenjiCrash.verdict(interposed!!)
    check("виновник назван первым", interposedVerdict.startsWith("Остановку потребовал"),
        interposedVerdict)
    check("в вердикте адрес возврата", interposedVerdict.contains("libkenji.so+0x173c210"),
        interposedVerdict)
    check("в вердикте место вызова", interposedVerdict.contains("0x7a1b2c3e10"), interposedVerdict)
    check("libc не назван виновником", !interposedVerdict.startsWith("Остановку потребовал код в кадре libc"),
        interposedVerdict)
    check("сказано, что это не сбой памяти", interposedVerdict.contains("не сбой памяти"),
        interposedVerdict)
    // Отчёт целиком: строка перехватчика обязана дойти до человека.
    val interposedText = KenjiCrash.format(interposed)
    check("в тексте отчёта строка перехватчика", interposedText.contains("abort() позвал кадр"),
        interposedText)
    check("в тексте отчёта цепочка выше", interposedText.contains("выше него"), interposedText)

    // Сигнал не 6 — про abort не говорим.
    val segv = realReport.copy(signal = 11, signalName = "SIGSEGV")
    check("без SIGABRT про abort молчим", !KenjiCrash.verdict(segv).contains("abort()"))

    // ── журнал системы ─────────────────────────────────────────────────
    val logcat = """
        11-01 15:27:10.000  1234  1250 F libc    : Fatal signal 6 (SIGABRT), code -1 (SI_QUEUE) in tid 1250
        11-01 15:27:10.000  1234  1250 I DotNet  : Unhandled exception. System.DllNotFoundException
        11-01 15:27:10.000  1234  1250 I DotNet  : Unhandled exception. System.DllNotFoundException
        11-01 15:27:10.000  1234  1250 I chatty  : uid=10200 identical 2 lines
        11-01 15:27:09.000  1234  1234 I SymbiosisKenji: core loaded and verified
        11-01 15:27:10.000  1234  1250 F DEBUG   : Abort message: 'Unhandled exception. System.TypeInitializationException'
        11-01 15:27:09.000  1234  1234 I unrelated: something else entirely
    """.trimIndent()

    val picked = KenjiCrash.fatalLines(logcat)
    check("журнал отфильтрован", picked.size in 1..8, picked.toString())
    check("в журнале есть исключение .NET",
        picked.any { it.contains("Unhandled exception") }, picked.toString())
    check("Abort message не потерян",
        picked.any { it.contains("Abort message") }, picked.toString())
    // Повтор одной и той же строки — это дубль; разные строки про одно и то
    // же исключение (само сообщение и Abort message) обе полезны.
    check("повторы убраны",
        picked.count { it.contains("Unhandled exception. System.DllNotFoundException") } == 1,
        picked.toString())
    check("посторонние строки не попали",
        picked.none { it.contains("unrelated") }, picked.toString())
    check("лимит соблюдён", KenjiCrash.fatalLines(logcat, limit = 2).size <= 2)
    check("пустой журнал — пустой список", KenjiCrash.fatalLines("").isEmpty())

    // ── что сказало само ядро (stderr процесса :kenji) ────────────────
    val stderr = """
        05-01 19:04:11.001  4628  4650 I SymbiosisKenji: core loaded and verified
        Unhandled exception. System.TypeInitializationException: The type initializer for 'X' threw an exception.
         ---> System.DllNotFoundException: Unable to load shared library 'libkenjinxjni' or one of its dependencies.
           at X..cctor()
           --- End of inner exception stack trace ---
           at Interop.SystemFunctionTable..ctor()
    """.trimIndent()

    val said = KenjiCrash.exceptionLines(stderr)
    check("исключение из stderr найдено", said.any { it.contains("Unhandled exception") }, said.toString())
    check("внутренняя причина тоже", said.any { it.contains("DllNotFoundException") }, said.toString())
    check("кадры сохранены", said.any { it.trimStart().startsWith("at ") }, said.toString())
    check("шапка «core loaded» отброшена", said.none { it.contains("core loaded") }, said.toString())
    check("лимит соблюдён", KenjiCrash.exceptionLines(stderr, limit = 2).size <= 2)
    check("пустой stderr — пустой список", KenjiCrash.exceptionLines("").isEmpty())
    check("без исключения — пусто", KenjiCrash.exceptionLines("some random line\nanother").isEmpty())

    // ── журнал только своего процесса ─────────────────────────────────
    //
    // В отчёте с устройства были строки PID 4628, а процесс имел 24319.
    // Такой журнал хуже пустого: он показывает чужую причину.
    val args = KenjiCrash.logcatArgs()
    // --pid убран осознанно: на телефоне logcat его не принял, напечатал
    // ошибку в поток ошибок, и весь журнал был отфильтрован как «чужой».
    check("без --pid: фильтруем сами", args.none { it.startsWith("--pid") }, args.toString())
    check("формат threadtime: в нём есть колонка PID", args.contains("threadtime"), args.toString())
    check("буфер задаётся", KenjiCrash.logcatArgs(buffer = "crash").contains("crash"))
    check("без буфера флага нет", !args.contains("-b"))

    val log = """
        05-01 19:04:11.001  4628  4650 I SymbiosisKenji: чужое прошлое падение
        05-01 19:04:12.000 24319 24360 I SymbiosisKenji: наше сообщение
        	at Our.Frame()
        05-01 19:04:13.000 24319 24360 E AndroidRuntime: наша ошибка
        05-01 19:04:14.000  4628  4650 I SymbiosisKenji: снова чужое
    """.trimIndent()
    // brief — второй формат, которым отвечает logcat на части прошивок.
    val brief = """
        D/SymbiosisKenji( 4628): чужое прошлое падение
        D/SymbiosisKenji(24319): наше сообщение
        	at Our.Frame()
        E/AndroidRuntime(24319): наша ошибка
        D/SymbiosisKenji( 4628): снова чужое
    """.trimIndent()
    val briefKept = KenjiCrash.filterByPid(brief, 24319)
    check("brief: своя строка осталась", briefKept.contains("наше сообщение"), briefKept)
    check("brief: чужая отброшена", !briefKept.contains("чужое"), briefKept)
    check("brief: продолжение своей строки", briefKept.contains("at Our.Frame"), briefKept)

    val kept = KenjiCrash.filterByPid(log, 24319)
    check("чужая строка отброшена", !kept.contains("чужое"), kept)
    check("своя строка осталась", kept.contains("наше сообщение"), kept)
    check("продолжение своей строки осталось", kept.contains("at Our.Frame"), kept)
    check("ещё одна своя", kept.contains("наша ошибка"), kept)
    check("хвост чужого не просочился", !kept.contains("снова чужое"), kept)
    check("пустой журнал — пусто", KenjiCrash.filterByPid("", 1).isEmpty())
    check("без дат ничего не выдумываем", KenjiCrash.filterByPid("просто текст", 1).isEmpty())

    // ── запись необработанного исключения ─────────────────────────────
    val thrown = runCatching { error("панель упала") }.exceptionOrNull()!!
    val described = KenjiCrash.describeThrowable("main", thrown, "2026-11-01 20:00:00")
    check("время в записи", described.startsWith("2026-11-01 20:00:00"), described)
    check("поток в записи", described.contains("поток=main"), described)
    check("тип исключения", described.contains("IllegalStateException"), described)
    check("текст исключения", described.contains("панель упала"), described)
    check("стек записан", described.contains("KenjiCrashTest"), described)

    // ── .NET пишет в журнал, а не в stderr ─────────────────────────────
    //
    // С телефона: «ядро ничего не написало в свой вывод» + «журнала нет».
    // Оба верны и оба бесполезны: .NET на Android перенаправляет
    // Console/stderr в logd, метка DOTNET. Ищем именно это.
    val dotnetLog = """
        05-02 14:09:58.101  8674  8674 I DOTNET  : Unhandled Exception:
        05-02 14:09:58.101  8674  8674 I DOTNET  : System.DllNotFoundException: libkenjinxjni
        05-02 14:09:58.102  8674  8674 I SymbiosisKenji: step=javaInitialize
    """.trimIndent()
    val dotnetPicked = KenjiCrash.fatalLines(dotnetLog)
    check("строка DOTNET попадает в отчёт",
        dotnetPicked.any { it.contains("DOTNET") }, dotnetPicked.toString())
    check("тип исключения тоже",
        dotnetPicked.any { it.contains("DllNotFoundException") }, dotnetPicked.toString())

    // ── выжимка для диалога ───────────────────────────────────────────
    val nativeDump = """
        crash signal=11 name=SIGSEGV addr=0x1 code=1 step=приложение
        pc=libyuzu-android.so+0x1a2b3
        pc=libc.so+0x530f4
        pc=libyuzu-android.so+0x991
    """.trimIndent()
    val nativeBrief = KenjiCrash.uiSummary(nativeDump, limit = 2)
    check("шапка в выжимке", nativeBrief.startsWith("crash signal=11"), nativeBrief)
    check("кадров не больше лимита",
        nativeBrief.lines().count { it.trim().startsWith("pc=") } == 2, nativeBrief)
    check("сказано, сколько скрыто", nativeBrief.contains("ещё 1"), nativeBrief)

    val javaDump = """
        2026-11-02 14:10:00 поток=main
        java.lang.IllegalStateException: панель
        	at org.yuzu.yuzu_emu.fragments.EmulationFragment.x(EmulationFragment.kt:1)
        	at android.os.Handler.dispatchMessage(Handler.java:106)
    """.trimIndent()
    val briefJava = KenjiCrash.uiSummary(javaDump)
    check("тип исключения в выжимке", briefJava.contains("IllegalStateException"), briefJava)
    check("кадры Java тоже", briefJava.contains("Handler.dispatchMessage"), briefJava)
    check("пустое — пусто", KenjiCrash.uiSummary("   \n \n").isEmpty())

    // ── вывод ядра без знакомых слов ───────────────────────────────────
    //
    // С устройства пришёл случай, ради которого это и написано: файл вывода
    // создан, строки в нём есть, но ни одна не похожа на «Unhandled
    // exception». Отбор выбрасывал всё, и окно говорило «ядро ничего не
    // написало» — причина падения терялась в фильтре, а не в отсутствии
    // текста. Теперь последние строки показываются как есть.
    // Ни одного слова из списка отбора — ни «exception», ни «fatal»: именно
    // такой текст и терялся.
    val foreign = """
        Some native runtime said this before stopping
        runtime trap at 0x2e5300005a55
        extra trailer
    """.trimIndent()
    val fallback = KenjiCrash.consoleLines(foreign)
    check("незнакомый текст не теряется", fallback.any { it.contains("runtime trap") },
        fallback.toString())
    check("хвост сохранён", fallback.last() == "extra trailer", fallback.toString())
    check("лимит соблюдён и здесь",
        KenjiCrash.consoleLines(foreign, limit = 1).size == 1,
        KenjiCrash.consoleLines(foreign, limit = 1).toString())
    check("пустой файл — пусто", KenjiCrash.consoleLines("").isEmpty())
    check("пробелы не считаются строками", KenjiCrash.consoleLines("  \n\t\n").isEmpty())
    check("знакомое по-прежнему отбирается по смыслу",
        KenjiCrash.consoleLines(stderr).any { it.contains("DllNotFoundException") },
        KenjiCrash.consoleLines(stderr).toString())
    check("шум снова отброшен",
        KenjiCrash.consoleLines(stderr).none { it.contains("core loaded") },
        KenjiCrash.consoleLines(stderr).toString())

    // ── снимок системы (tombstone) ─────────────────────────────────────
    //
    // Это единственное место, где есть «Abort message» — текст, с которым
    // процесс остановил себя сам. Заголовок tombstone места не заслуживает.
    val tombstone = """
        *** *** *** *** *** *** *** *** *** *** *** *** *** *** *** ***
        Build fingerprint: 'itel/S666LN/S666LN:13/TP1A.220624.014/eng.2025:user/release-keys'
        Cmdline: org.yuzu.yuzu_emu:kenji
        Abort message: 'Fatal error. Invalid Program: attempted to call a UnmanagedCallersOnly method from managed code.'
        backtrace:
            #00 pc 00000000000530f4  /apex/com.android.runtime/lib64/bionic/libc.so (abort+164)
            #01 pc 000000000173c214  /data/app/~~x/lib/arm64/libkenji.so (javaInitialize+0x1c0)
    """.trimIndent()
    val shot = KenjiCrash.tombstoneLines(tombstone)
    check("начинается с Abort message", shot.first().contains("Abort message"), shot.toString())
    check("причина внутри", shot.any { it.contains("UnmanagedCallersOnly") }, shot.toString())
    check("кадры с функциями", shot.any { it.contains("javaInitialize") }, shot.toString())
    check("служебная шапка отброшена",
        shot.none { it.contains("*** *** ***") || it.startsWith("Cmdline:") }, shot.toString())
    check("лимит снимка", KenjiCrash.tombstoneLines(tombstone, limit = 2).size == 2,
        KenjiCrash.tombstoneLines(tombstone, limit = 2).toString())
    check("пустой снимок — пусто", KenjiCrash.tombstoneLines("").isEmpty())
    // Снимок без Abort message (обычный выход процесса) не должен пропадать.
    val quiet = KenjiCrash.tombstoneLines("reason: 1\nsome line")
    check("снимок без abort сохранён", quiet.isNotEmpty(), quiet.toString())

    // ── регистры из отчёта ─────────────────────────────────────────────
    //
    // Обработчик пишет их отдельной строкой regs: по ним видно, с чем именно
    // ядро не справилось. Они не кадры и не должны попадать в список кадров,
    // но обязаны быть в тексте для человека.
    val withRegs = """
        step=javaInitialize
        regs x0=7f1234 x1=0 x2=0 x3=0 x29=7ff000
        crash signal=6 name=SIGABRT addr=0x2e53000011c6 code=-6 step=javaInitialize
        pc=libc.so+0x530f4
        pc=libkenji.so+0x173c214
    """.trimIndent()
    val regsReport = KenjiCrash.parse(withRegs)
    check("регистры разобраны", regsReport?.registers?.contains("x0=7f1234") == true,
        regsReport?.registers.toString())
    eq("регистры не стали кадром", regsReport?.frames?.size, 2)
    val regsText = KenjiCrash.format(regsReport)
    check("регистры в тексте", regsText.contains("регистры: x0=7f1234"), regsText)

    // ── перехват JNI: что ядро спрашивало у Java ──────────────────────
    //
    // Файл перехвата большой и шумный: вызовы CallObjectMethod и
    // NewGlobalRef ничего не объясняют. Значение имеют запросы имён и
    // отметки об исключении.
    val jniDump = """
        NewGlobalRef
        FindClass(org/kenji/android/KenjiActivity)
        GetMethodID(getContext, ()Landroid/content/Context;)
        CallObjectMethod
        GetStaticMethodID(getFilesDir, ()Ljava/io/File;)
        ExceptionCheck -> в очереди ИСКЛЮЧЕНИЕ
        FindClass(android/os/Build)
    """.trimIndent()
    val asked = KenjiCrash.jniLines(jniDump)
    check("классы видны", asked.any { it.contains("FindClass(org/kenji") }, asked.toString())
    check("методы видны", asked.any { it.contains("GetMethodID") }, asked.toString())
    check("исключение видно", asked.any { it.contains("ИСКЛЮЧЕНИЕ") }, asked.toString())
    check("шум отброшен", asked.none { it == "CallObjectMethod" || it == "NewGlobalRef" },
        asked.toString())
    check("хвост, а не начало",
        KenjiCrash.jniLines(jniDump, limit = 2).last().contains("android/os/Build"),
        KenjiCrash.jniLines(jniDump, limit = 2).toString())
    check("пустой перехват — пусто", KenjiCrash.jniLines("").isEmpty())

    // ── формулировки самого рантайма .NET ──────────────────────────────
    //
    // NativeAOT пишет «Process terminated. …» и «Fatal error. Invalid Program:
    // …». Ни одного из этих слов не было в отборе, поэтому сообщение
    // выбрасывалось ещё до показа. Теперь они — часть отбора, а не повод для
    // запасного пути.
    check("Process terminated узнаётся",
        KenjiCrash.exceptionLines("Process terminated. Something went wrong")
            .any { it.contains("Process terminated") },
        KenjiCrash.exceptionLines("Process terminated. Something went wrong").toString())
    check("Fatal error узнаётся",
        KenjiCrash.exceptionLines("Fatal error. Invalid Program: attempted to call a method")
            .any { it.contains("Invalid Program") },
        KenjiCrash.exceptionLines("Fatal error. Invalid Program: attempted").toString())

    // Хвост журнала без отбора: нужен, когда знакомых слов нет вовсе.
    val journal = (1..20).joinToString("\n") { "05-02 14:09:58.10$it  23125  23125 I K: строка $it" }
    val tail = KenjiCrash.tailLines(journal, limit = 3)
    eq("хвост журнала — три строки", tail.size, 3)
    check("в хвосте самые последние", tail.last().endsWith("строка 20"), tail.toString())
    eq("пустой журнал — пустой хвост", KenjiCrash.tailLines(""), emptyList<String>())

    // ── взведён ли перехват: два отчёта не должны быть неразличимы ──────
    //
    // Отчёт с устройства пришёл без строки `abort-caller=`, а по виду был
    // неотличим от отчёта сборки, где перехватчика не было вовсе. Теперь мост
    // пишет состояние, и окно его показывает.
    val armed = KenjiCrash.parse(
        """
        step=javaInitialize
        crash signal=6 name=SIGABRT addr=0x2e53000060bc code=-1 step=javaInitialize
        abort interposer: активен
        got patch: abort — подменено слотов: 1
        abort-caller=libkenji.so+0x173c210
        abort-pc=0x7a1b2c3d40
        abort-pc=0x7a1b2c3e10
        pc=libc.so+0x530f4
        """.trimIndent()
    )
    check("состояние перехвата разобрано", armed?.trapState?.size == 2, armed?.trapState?.toString() ?: "null")
    val armedText = KenjiCrash.format(armed!!)
    check("состояние перехвата в тексте", armedText.contains("перехват abort: "), armedText)
    check("правка таблицы в тексте", armedText.contains("подменено слотов"), armedText)
    check("виновник всё равно назван", KenjiCrash.verdict(armed).contains("Остановку потребовал"),
        KenjiCrash.verdict(armed))

    val notArmed = KenjiCrash.parse(
        """
        step=javaInitialize
        crash signal=6 name=SIGABRT addr=0x2e53000060bc code=-1 step=javaInitialize
        abort interposer: не активен
        got patch: abort — нет таблицы переходов
        pc=libc.so+0x530f4
        pc=libc.so+0x530c8
        pc=libkenji.so+0x173c214
        """.trimIndent()
    )
    val notArmedVerdict = KenjiCrash.verdict(notArmed!!)
    check("перехват не взведён — сказано прямо", notArmedVerdict.contains("не взведён"),
        notArmedVerdict)
    check("и причина правки таблицы названа", notArmedVerdict.contains("нет таблицы переходов"),
        notArmedVerdict)

    // ── файл дописывается: показывать надо последний прогон ─────────────
    //
    // С устройства пришёл отчёт, который оказался старым, и отличить его от
    // нового было нельзя. Причина: файл дописывается, а разбор шёл по всем
    // строкам подряд — то есть показывал ПЕРВОЕ падение за всю историю файла.
    val twoRuns = """
        abort interposer: активен
        got patch: abort — подменено слотов: 1
        step=javaInitialize
        crash signal=6 name=SIGABRT addr=0x1111 code=-1 step=javaInitialize
        pc=libkenji.so+0xAAAA

        abort interposer: активен
        got patch: abort — подменено слотов: 1
        step=javaInitialize
        crash signal=6 name=SIGABRT addr=0x2222 code=-1 step=javaInitialize
        pc=libkenji.so+0xBBBB
        abort-caller=libkenji.so+0xBBBB
    """.trimIndent()
    val latest = KenjiCrash.parse(twoRuns)
    check("разобран последний прогон", latest?.address == "0x2222", latest?.address ?: "null")
    check("кадры только последнего прогона", latest?.frames == listOf("libkenji.so+0xBBBB"),
        latest?.frames?.toString() ?: "null")
    check("перехват из последнего прогона", latest?.abortCaller == "libkenji.so+0xBBBB",
        latest?.abortCaller ?: "null")

    // ── штамп сборки ────────────────────────────────────────────────────
    val stamped = KenjiCrash.format(latest!!, buildStamp = "0.2.1-257-ga1b2c3d (20261102), файл отчёта изменён 02.11 21:04")
    check("в отчёте названа сборка", stamped.contains("• сборка: 0.2.1-257"), stamped)
    check("в отчёте время файла", stamped.contains("файл отчёта изменён"), stamped)
    check("без штампа строка не появляется",
        !KenjiCrash.format(latest).contains("• сборка:"), KenjiCrash.format(latest))

    // ── ответ инициализации ядра ────────────────────────────────────────
    //
    // Ядро само зовёт свою инициализацию и на ненулевом ответе останавливается.
    // Мы её оборачиваем: ответ записывается и печатается, работа продолжается.
    val initReport = KenjiCrash.parse(
        """
        init guard: обёрнута инициализация ядра
        init-callback=-1 calls=1
        step=javaInitialize
        crash signal=6 name=SIGABRT addr=0x2e5300002300 code=-1 step=javaInitialize
        pc=libc.so+0x530f4
        """.trimIndent()
    )
    check("ответ инициализации разобран", initReport?.initResult == "-1 calls=1",
        initReport?.initResult ?: "null")
    val initText = KenjiCrash.format(initReport!!)
    check("ответ инициализации в отчёте", initText.contains("инициализация ядра ответила"), initText)
    check("ненулевой ответ объяснён", initText.contains("ненулевой ответ означает отказ"), initText)
    check("обёртка названа в состоянии", initReport.trapState.any { it.contains("init guard") },
        initReport.trapState.toString())

    // ── что ядро просило у загрузчика ───────────────────────────────────
    //
    // Инициализация вернула -1 без текста. Единственный точный ответ на «чего
    // не хватило» — список функций, которые ядро искало и не нашло.
    val loaderReport = KenjiCrash.parse(
        """
        init guard: обёрнута инициализация ядра
        init-callback-fn=libkenji.so+0x26ff4d4
        dlreq dlsym Java_org_kenji_Native_getEnv NULL
        dlreq dlsym opus_decoder_create ok
        dlreq dlsym nativeAttach NULL
        init-callback=-1 calls=1
        step=javaInitialize
        crash signal=11 name=SIGSEGV addr=0x0 code=1 step=javaInitialize
        pc=libkenji.so+0x174a09c
        """.trimIndent()
    )!!
    check("отказы загрузчика собраны", loaderReport.loaderMisses.size == 2,
        loaderReport.loaderMisses.toString())
    check("имя первой ненайденной функции", loaderReport.loaderMisses[0] == "dlsym Java_org_kenji_Native_getEnv",
        loaderReport.loaderMisses.toString())
    check("смещение отказавшей инициализации разобрано",
        loaderReport.initCallbackAt == "libkenji.so+0x26ff4d4", loaderReport.initCallbackAt)
    val loaderText = KenjiCrash.format(loaderReport)
    check("в отчёте список ненайденных", loaderText.contains("ядро не нашло функций"), loaderText)
    check("в отчёте имена названы", loaderText.contains("nativeAttach"), loaderText)
    check("в отчёте смещение инициализации", loaderText.contains("0x26ff4d4"), loaderText)

    // ── что передали ядру и сколько оно спросило у загрузчика ───────────
    val argsReport = KenjiCrash.parse(
        """
        init-args: path=/data/user/0/x/files/kenji exists=1 dir=1 env=0x7bcb9ef7b0
        loader tap: dlopen ok, dlsym ok
        dlreq dlsym opus_decoder_create ok
        dlreq dlsym Java_org_kenji_Native_getEnv NULL
        dlreq dlopen libkenjinxjni ok
        init-callback=-1 calls=1
        step=javaInitialize
        crash signal=11 name=SIGSEGV addr=0x0 code=1 step=javaInitialize
        pc=libkenji.so+0x174a09c
        """.trimIndent()
    )!!
    check("аргументы инициализации разобраны", argsReport.initArgs.contains("dir=1"), argsReport.initArgs)
    check("все запросы к загрузчику посчитаны", argsReport.loaderCalls == 3,
        argsReport.loaderCalls.toString())
    check("ненайденное отделено от найденного", argsReport.loaderMisses.size == 1,
        argsReport.loaderMisses.toString())
    check("состояние метчика в отчёте", argsReport.trapState.any { it.startsWith("loader tap:") },
        argsReport.trapState.toString())
    val argsText = KenjiCrash.format(argsReport)
    check("в отчёте путь", argsText.contains("передано ядру"), argsText)
    check("в отчёте счётчик загрузчика", argsText.contains("запросов к загрузчику: 3"), argsText)
    check("в отчёте ненайденное", argsText.contains("не найдено: 1"), argsText)

    // Отбор строк по ярлыку: ядро подписывает системе свои сообщения задолго
    // до того, как заведёт файл журнала, и для отказа это единственный след.
    val tagLog = """
        05-01 19:04:11.001  4628  4650 I RyujinxLog: Using base path: /x
        05-01 19:04:11.002  4628  4650 W RyujinxLog: Custom Data Directory missing
        05-01 19:04:11.003  4628  4650 I Choreographer: Skipped 42 frames
        05-01 19:04:11.004  4628  4650 E AndroidRuntime: FATAL EXCEPTION
        I/RyujinxLog( 4628): brief format line
    """.trimIndent()
    val tagged = KenjiCrash.linesByTag(tagLog, "RyujinxLog")
    check("ярлык: обе строки threadtime и одна brief", tagged.size == 3, tagged.toString())
    check("ярлык: чужая подпись не попала", tagged.none { it.contains("Choreographer") }, tagged.toString())
    check("ярлык: текст сохранён", tagged.any { it.contains("Custom Data Directory missing") },
        tagged.toString())
    check("ярлык: пустой ввод даёт пусто", KenjiCrash.linesByTag("", "RyujinxLog").isEmpty(), "не пусто")
    check("ярлык: чужое имя не совпадает", KenjiCrash.linesByTag(tagLog, "Ryujinx").isEmpty(),
        KenjiCrash.linesByTag(tagLog, "Ryujinx").toString())
    check("ярлык: предел соблюдён", KenjiCrash.linesByTag(tagLog, "RyujinxLog", limit = 2).size == 2,
        KenjiCrash.linesByTag(tagLog, "RyujinxLog", limit = 2).toString())

    // Окружение, которое проверяет ядро: строки pal: (наш повтор его же
    // проверок) и call: (настоящие вызовы самого ядра).
    val palReport = KenjiCrash.parse(
        """
        got patch: mlock — подменено слотов: 1
        init guard: обёрнута инициализация ядра
        step=javaInitialize
        pal: page(40)=4096 page(_SC_PAGESIZE)=4096 cpus(96)=8
        pal: mmap(4096)=ok
        pal: mlock=FAIL errno=12
        call: sysconf(40) = 4096
        call: mmap(4096) = ok
        call: sigaction(11) = 0
        init-callback=-1 calls=1
        crash signal=11 name=SIGSEGV addr=0x0 code=1 step=javaInitialize
        """.trimIndent()
    )!!
    check("окружение разобрано", palReport.palLines.size == 6, palReport.palLines.toString())
    check("отказ mlock назван", palReport.palLines.any { it.contains("mlock=FAIL errno=12") },
        palReport.palLines.toString())
    check("вызовы ядра помечены стрелкой", palReport.palLines.any { it.startsWith("→ sysconf(40)") },
        palReport.palLines.toString())
    val palText = KenjiCrash.format(palReport)
    check("в отчёте раздел окружения", palText.contains("окружение, которое проверяет ядро"), palText)
    check("в отчёте назван отказ mlock", palText.contains("mlock=FAIL errno=12"), palText)
    check("в отчёте виден последний вызов ядра", palText.contains("→ sigaction(11) = 0"), palText)

    println()
    println("passed=$passed failed=${failures.size}")
    failures.forEach { println("FAIL  $it") }
    if (failures.isNotEmpty()) kotlin.system.exitProcess(1)
}

// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

// Проверки разбора паники гостя — без телефона.
//
// Журнал, на котором это проверяется, взят с устройства 06.11 дословно:
//
//   SendSyncRequest() = Success (5.701 s)
//   WaitSynchronization() = TimedOut
//   SleepThread(timeout: 0x989680)
//   SvcBreak reason=0x0 pid=109 titleId=0x01004F302847E000 name=Vault of Power
//            tid=28 pc=0x139553C0
//   WaitProcessWideKeyAtomic() = TerminationRequested
//
// Он же и есть приёмка: раньше этот текст наш разбор не понимал вовсе и на
// экране оставалось «кадров нет». Ниже проверяется, что теперь по нему
// называется приговор, время, поток и адрес, и что шум в причину не попадает.

import org.yuzu.yuzu_emu.utils.EngineHybrid
import org.yuzu.yuzu_emu.utils.KenjiCrash
import org.yuzu.yuzu_emu.utils.KenjiData
import org.yuzu.yuzu_emu.utils.KernelLogFormat

private var passed = 0
private var failed = 0

private fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) {
        passed++
        println("ok   $name")
    } else {
        failed++
        println("FAIL $name" + if (detail.isEmpty()) "" else " → $detail")
    }
}

private val deviceLog = """
    |00:00:05.701 |I| HLE.KernelSvc SendSyncRequest() = Success (handle 0x00010001)
    |00:00:05.704 |I| HLE.KernelSvc WaitSynchronization() = TimedOut (handleIndex 0)
    |00:00:05.706 |I| HLE.KernelSvc SleepThread(timeout: 0x989680)
    |00:00:15.725 |I| HLE.KernelSvc SvcBreak reason=0x0 pid=109 titleId=0x01004F302847E000 name=Vault of Power tid=28 pc=0x139553C0
    |00:00:15.726 |I| HLE.KernelSvc WaitProcessWideKeyAtomic() = TerminationRequested
""".trimMargin()

fun main() {
    // ── 1. приговор по журналу с телефона ──────────────────────────────
    val panic = KenjiCrash.guestPanic(deviceLog)
    check("паника найдена", panic.isNotEmpty())
    check("сказано, что остановила себя игра", panic.contains("остановила себя"), panic)
    check("назван системный вызов", panic.contains("svcBreak"), panic)
    check("названо время", panic.contains("15.7 с от старта"), panic)
    check("назван поток", panic.contains("поток 28"), panic)
    check("назван адрес", panic.contains("0x139553C0"), panic)
    check("причина не выдумана", panic.contains("первопричина по этой строке неизвестна"), panic)
    val screenshot = "00:00:15.931 |E| HLE.GuestThread.28 KernelSvc Break: SvcBreak reason=0x0000000000000000 pid=109 titleId=0x01004F302847E000 name=Vault of Power tid=28 pc=0x139553C0"
    val screenshotPanic = KenjiCrash.guestPanic(screenshot)
    check("строка снимка с 16 нулями распознана как паника", screenshotPanic.contains("паника:"), screenshotPanic)
    val hugeLog = java.io.File.createTempFile("kenji-busy-log", ".log")
    try {
        hugeLog.writeText(("обычная запись без ошибки\n".repeat(20000)) + screenshot + "\n")
        val oldChunk = hugeLog.inputStream().use { stream ->
            val bytes = ByteArray(256 * 1024)
            val n = stream.read(bytes)
            KernelLogFormat.completeLines(bytes, n).first
        }
        check("первая порция отстаёт от паники", KenjiCrash.guestPanic(oldChunk).isEmpty())
        val recent = KernelLogFormat.tailOf(hugeLog, KernelLogFormat.TAIL_BYTES)
        check("свежий хвост обнаруживает панику при большой очереди", KenjiCrash.guestPanic(recent).contains("паника:"))
    } finally { hugeLog.delete() }
    val audioAndGraphics = "00:00 |I| AudioRenderer ReleaseSessionId: Unregistered output (0)\n00:00 |I| Graphics: Vulkan"
    val graphicsOnly = KernelLogFormat.pickRendererLines(audioAndGraphics, listOf("Renderer", "Graphics"), 3)
    check("звук не показывается как графика", graphicsOnly.size == 1 && graphicsOnly[0].contains("Graphics"), graphicsOnly.toString())

    // ── 2. причины паники по номеру ─────────────────────────────────────
    val assert = KenjiCrash.guestPanic("SvcBreak reason=0x1 pid=3 tid=7 pc=0x100")
    check("reason 1 — проверка-утверждение", assert.contains("утверждение"), assert)
    val cpp = KenjiCrash.guestPanic("SvcBreak reason=0x7 tid=12")
    check("reason 7 — исключение C++", cpp.contains("C++"), cpp)
    val odd = KenjiCrash.guestPanic("SvcBreak reason=0x51 tid=4")
    check("неизвестный номер назван как есть", odd.contains("0x51"), odd)

    // ── 3. чего в журнале нет — того не выдумываем ──────────────────────
    check("без SvcBreak — пусто", KenjiCrash.guestPanic("Application Loaded: ok").isEmpty())
    check("пустой журнал — пусто", KenjiCrash.guestPanic("").isEmpty())
    val dotnet = "Kernel ThreadStart: Unhandled exception in guest thread: " +
        "System.NullReferenceException at IAccountServiceForApplication.IsUserAccountSwitchLocked"
    check("исключение .NET паникой не считается", KenjiCrash.guestPanic(dotnet).isEmpty())
    check(
        "и исключение .NET по-прежнему разбирается",
        KenjiCrash.guestFailure(dotnet).isNotEmpty()
    )

    // ── 4. строки вокруг паники ─────────────────────────────────────────
    val context = KenjiCrash.panicContext(deviceLog)
    // В этом журнале перед паникой только обычная работа ядра: показывать её
    // как «причину» нельзя, поэтому список пуст — экран не врёт.
    check("сплошной шум причиной не считается", context.isEmpty(), context.joinToString(" | "))
    check(
        "шум в причину не попал",
        context.none { it.contains("SleepThread") || it.contains("SendSyncRequest") }
    )

    val withError = """
        |00:00:09.100 |E| HLE.OsThread.28 ResultFsNotImplemented 0x2a2 came from game
        |00:00:09.200 |I| HLE.KernelSvc SleepThread(timeout: 0x989680)
        |00:00:15.700 |I| HLE.KernelSvc SvcBreak reason=0x0 tid=28 pc=0x139553C0
        |00:00:15.701 |I| HLE.KernelSvc WaitProcessWideKeyAtomic() = TerminationRequested
    """.trimMargin()
    val withErrorContext = KenjiCrash.panicContext(withError)
    check(
        "ошибка перед паникой сохраняется",
        withErrorContext.any { it.contains("ResultFsNotImplemented") },
        withErrorContext.joinToString(" | ")
    )
    check("не больше девяти строк", withErrorContext.size <= 9, withErrorContext.size.toString())
    check("строки не длиннее 160 знаков", withErrorContext.all { it.length <= 160 })

    val evidenceLog = """
        |00:00:15.000 |I| HLE.OsThread.11 ServiceAm SetTerminateResult: Result = 0x000002a2 (ResultErrApplicationAborted (2162-0001)).
        |X[1]: 0x0000000012345678
        |LR: 0x000000001387e828 (nnSdk:0xab828) => nn::diag::detail::Abort(nn::Result const*):0x0018
        |0x0000000012340000 (main:0x1234)
        |00:00:16.300 |E| HLE.GuestThread.28 KernelSvc Break: SvcBreak reason=0x0000000000000000 pid=109 tid=28 pc=0x139553C0
    """.trimMargin()
    val evidence = KenjiCrash.panicEvidence(evidenceLog)
    check("терминальный результат сохранён дословно", evidence.any { it.contains("2162-0001") })
    check("строка Abort сохранена", evidence.any { it.contains("Abort(nn::Result const*)") })
    check("кадр main сохранён", evidence.any { it.contains("main:0x1234") })
    check("регистр сохранён как адрес, не как результат", evidence.any { it.contains("X[1]: 0x0000000012345678") })
    check("без SvcBreak нет доказательств паники", KenjiCrash.panicEvidence("SetTerminateResult: 0x123").isEmpty())

    val hidLog = """
        |00:00:16.360 |I| {Common} ServiceAm SetTerminateResult: Result = 0x00058cca (2202-0710).
        |00:00:16.500 |E| HLE.GuestThread.28 KernelSvc Break: SvcBreak reason=0x0000000000000000 pid=109 tid=28 pc=0x139553C0
    """.trimMargin()
    check("результат HID распознан", KenjiCrash.panicResultHint(hidLog).contains("ошибка HID NpadNotConnected"))
    check("нет выдуманной причины без строки результата", KenjiCrash.panicResultHint("SvcBreak reason=0 2202-0710").isEmpty())
    check("другой результат не подписывается как HID", KenjiCrash.panicResultHint(evidenceLog).isEmpty())
    val hidState = "00:00:05.100 |I| Hid Configure: Configured Controller ProController to Player1\n" +
        "00:00:05.200 |W| Hid Remap: No matching controllers found\n" + hidLog
    val hidEvidence = KenjiCrash.panicEvidence(hidState)
    check("конфигурация контроллера сохраняется", hidEvidence.any { it.contains("Configured Controller") })
    check("предупреждение о контроллере сохраняется", hidEvidence.any { it.contains("No matching controllers") })

    // ── 5. служба-заглушка: переключатель доходит до ядра ───────────────
    //
    // В Kenji Space это ровно та же настройка (`ignoreMissingServices`), и она
    // выключена по умолчанию — значит и у нас должна быть выключена, пока
    // человек не включил её сам.
    val base = EngineHybrid.KenjiConfig()
    check("по умолчанию службы не терпим", !base.ignoreMissingServices)
    val on = EngineHybrid.apply(base, "kenji.services", "true")
    check("переключатель включается", on?.ignoreMissingServices == true)
    check(
        "и обратно читается тем же ключом",
        on != null && EngineHybrid.raw(on, "services") == "true"
    )
    check("мусор в значении не ломает настройку", EngineHybrid.apply(base, "kenji.services", "может быть") == null)
    check("поле есть на экране «Ядра»", EngineHybrid.FIELDS.any { it.id == "services" })

    // ── 6. прошивка: обе раскладки считаются ────────────────────────────
    //
    // Kenji читает прошивку подпапками внутри `registered` и плоскими `.nca`
    // прямо в `bis/system/Contents`; Eden кладёт её плоско в `registered`.
    // Пока считались только файлы в корне `registered`, папка, подготовленная
    // Kenji Space, читалась как «прошивки нет».
    val tmp = java.io.File(System.getProperty("java.io.tmpdir"), "sym-fw-" + System.nanoTime())
    val registered = KenjiData.firmwareDir(tmp).apply { mkdirs() }
    java.io.File(registered, "0000000000000010.nca").writeText("x")
    java.io.File(java.io.File(registered, "0000000000000011.nca"), "00").apply {
        parentFile?.mkdirs()
        writeText("x")
    }
    java.io.File(KenjiData.firmwareContentsDir(tmp), "0000000000000012.nca").writeText("x")
    check(
        "прошивка: файл, папка и плоский NCA в Contents",
        KenjiData.firmwareCount(tmp) == 3,
        KenjiData.firmwareCount(tmp).toString()
    )
    tmp.deleteRecursively()

    // ── 7. журнал читается кусками: строка не должна разрезаться ────────
    //
    // Ядро дописывает журнал во время игры, поэтому кусок почти всегда
    // обрывается посреди последней строки. На телефоне 06.11 строка паники
    // попала в два чтения — «SvcBr» и «eak reason=…» — и поиск её не нашёл.
    val bytes = deviceLog.toByteArray()
    val token = "SvcBreak".toByteArray()
    var at = -1
    for (i in 0..bytes.size - token.size) {
        if ((token.indices).all { k -> bytes[i + k] == token[k] }) {
            at = i
            break
        }
    }
    check("строка паники найдена в журнале для разреза", at > 0)
    val cut = at + 5 // рубим посреди слова SvcBreak
    val naive = String(bytes, 0, cut)
    check(
        "дыра, которую это исправляет: разрезанная строка не видна",
        KenjiCrash.guestPanic(naive).isEmpty(),
        naive.takeLast(30)
    )
    val (first, used) = KernelLogFormat.completeLines(bytes, cut)
    check("кусок 1 не заходит за последний перевод строки", used <= cut, "$used из $cut")
    check("кусок 1 кончается целой строкой", first.endsWith("\n"), first.takeLast(20))
    check("кусок 1 не содержит полустроки SvcBreak", !first.contains("SvcBr"), first.takeLast(40))
    // Ядро дописало строку — следующее чтение начинается с огрызка.
    val rest = String(bytes, used, bytes.size - used) + "\n"
    val (second, used2) = KernelLogFormat.completeLines(rest.toByteArray(), rest.toByteArray().size)
    check("кусок 2 дочитан целиком", used2 == rest.toByteArray().size, "$used2 из ${rest.length}")
    check("паника находится после второго чтения", KenjiCrash.guestPanic(second).isNotEmpty(), second)
    check(
        "ничего не потеряно: два куска дают весь журнал",
        (first + second) == deviceLog.trimEnd() + "\n",
        "первый ${first.length} + второй ${second.length} против ${deviceLog.trimEnd().length + 1}"
    )
    check(
        "кусок без переводов строк ничего не съедает",
        KernelLogFormat.completeLines("abc".toByteArray(), 3).second == 0
    )

    // ── 8. слова ядра про отрисовку ────────────────────────────────────
    val render = """
        |00:00:02.100 |I| HLE.Gpu.GpuContext Renderer: Vulkan
        |00:00:03.000 |I| HLE.KernelSvc SleepThread(timeout: 0x989680)
        |00:00:04.500 |W| HLE.Vi.ViDevice OpenDisplay: no display found
        |00:00:04.500 |W| HLE.Vi.ViDevice OpenDisplay: no display found
    """.trimMargin()
    val picked = KernelLogFormat.pickLines(
        render, listOf("Renderer", "Vulkan", "Display", "SleepThread"), 3
    )
    check("строки про отрисовку выбраны", picked.size == 3, picked.joinToString(" | "))
    check("повторы убраны", picked.count { it.contains("OpenDisplay") } == 1, picked.joinToString(" | "))
    check("порядок сохранён — последние ближе к концу", picked.last().contains("OpenDisplay"), picked.last())

    // ── 9. падение самого ядра (.NET TP Worker → abort) ────────────────
    //
    // Дословно из отчёта 07.11 09:56 — наш же вызов синхронизации рендерера.
    val hostLog = """
        |00:00:03.572 |E| .NET TP Worker Unhandled exception. System.NullReferenceException: Object reference not set to an instance of an object.
        |00:00:03.572 |E| .NET TP Worker    at LibKenjinx.LibKenjinx.SetVsyncState(VSyncMode) + 0x80
        |00:00:03.572 |E| .NET TP Worker    at LibKenjinx.LibKenjinx.JnaSetVsyncStateNative(Int32) + 0x324
    """.trimMargin()
    val host = KenjiCrash.hostCrash(hostLog)
    check("падение ядра найдено", host.isNotEmpty(), host)
    check("назван вызов", host.contains("SetVsyncState"), host)
    check("сказано, что это не файл игры", host.contains("не файл игры"), host)
    check(
        "сказано, где искать виноватого",
        host.contains("виноват наш вызов") || host.contains("ошибка в самом ядре"),
        host
    )
    check(
        "гостевое исключение сюда не попадает",
        KenjiCrash.hostCrash(dotnet).isEmpty()
    )
    check("пустой журнал — пусто", KenjiCrash.hostCrash("").isEmpty())
    check(
        "паника гостя падением ядра не считается",
        KenjiCrash.hostCrash("SvcBreak reason=0x0 tid=1").isEmpty()
    )

    val hidTrace = """
        |00:00:01.100 |T| {Hid} KernelIpc CallCmifMethod: IHidServer: SetSupportedNpadStyleSet
        |00:00:03.000 |T| Application JniUpdateInput: Jni Function Call
        |00:00:02.000 |T| {Hid} KernelIpc CallCmifMethod: IHidServer: ActivateNpad
        |00:00:04.000 |T| {Hid} KernelIpc CallCmifMethod: IHidServer: SetSupportedNpadStyleSet
    """.trimMargin()
    val ops = KenjiCrash.mergeHidOperations(emptyMap(), hidTrace)
    check("HID вызовы собраны, повтор схлопнут", ops.size == 2, ops.toString())
    check("последний вызов сохранён", ops.values.last().startsWith("00:00:04"), ops.toString())
    val oldRead = "00:00:01.200 |T| {Hid} KernelIpc CallCmifMethod: IHidServer: SetSupportedNpadStyleSet"
    val afterOld = KenjiCrash.mergeHidOperations(ops, oldRead)
    check("старое чтение не затёрло новый вызов", afterOld.values.last().startsWith("00:00:04"), afterOld.toString())
    check("шум JniUpdateInput не попал в HID", KenjiCrash.hidOperations(hidTrace).none { it.contains("JniUpdateInput") })
    check("ограничение истории", KenjiCrash.mergeHidOperations(emptyMap(), hidTrace, 1).size == 1)
    check("нулевой предел", KenjiCrash.mergeHidOperations(emptyMap(), hidTrace, 0).isEmpty())
    val stub = "00:00:06.994 |S| {Hid} ServiceHid SetSupportedNpadIdType: Stubbed. 2 Players: Player1,Handheld"
    check("параметры поддерживаемых игроков сохраняются", KenjiCrash.hidOperations(stub).single().contains("Player1"))
    val params = KenjiCrash.mergeHidOperations(ops, stub)
    check("параметры не подменяются строкой вызова", params.values.any { it.contains("2 Players") })

    println()
    println("passed=$passed failed=$failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}

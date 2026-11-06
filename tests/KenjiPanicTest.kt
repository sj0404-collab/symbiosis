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
    check("сказано, что ядро игру уже загрузило", panic.contains("уже загрузило"), panic)
    check(
        "ключи и прошивка прямо исключены",
        panic.contains("прошивка тут ни при чём"),
        panic
    )

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

    println()
    println("passed=$passed failed=$failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}

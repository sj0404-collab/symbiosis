// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * Правила архива журналов ядра. На хосте, без Android.
 *
 * Почему это важно: ядро держит ровно два файла журнала, и третий запуск
 * стирает первый без участия человека. Архив — единственное, что этому
 * противостоит, поэтому его правила (что копировать, что уже скопировано,
 * что удалять при переполнении) обязаны быть проверены, а не «на глаз».
 */

import org.yuzu.yuzu_emu.utils.KernelLogArchive

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

fun main() {
    // --- имя копии --------------------------------------------------------
    eq(
        "имя копии — время последней записи",
        KernelLogArchive.nameFor(1_700_000_000_000),
        "session-1700000000.txt"
    )
    check(
        "имена двух разных сеансов различаются",
        KernelLogArchive.nameFor(1_700_000_000_000) != KernelLogArchive.nameFor(1_700_000_001_000)
    )

    // --- когда копировать -------------------------------------------------
    check(
        "непустой и ещё не скопированный — копируем",
        KernelLogArchive.shouldArchive(1_700_000_000_000, 4096, emptyList())
    )
    check(
        "пустой файл не копируем",
        !KernelLogArchive.shouldArchive(1_700_000_000_000, 0, emptyList())
    )
    check(
        "уже скопированный не копируем повторно",
        !KernelLogArchive.shouldArchive(
            1_700_000_000_000, 4096,
            listOf("session-1700000000.txt")
        )
    )
    check(
        "новый сеанс копируется рядом со старым",
        KernelLogArchive.shouldArchive(
            1_700_000_500_000, 8192,
            listOf("session-1700000000.txt")
        )
    )
    check(
        "файл без времени записи не копируем",
        !KernelLogArchive.shouldArchive(0, 4096, emptyList())
    )

    // --- лимиты -----------------------------------------------------------
    val few = listOf("a" to 10L, "b" to 10L)
    eq("под лимитом ничего не удаляется", KernelLogArchive.toPrune(few, 10, 1000), emptyList<String>())

    val many = listOf("1" to 10L, "2" to 10L, "3" to 10L, "4" to 10L)
    eq(
        "по числу удаляются самые старые",
        KernelLogArchive.toPrune(many, maxFiles = 2, maxBytes = 1000),
        listOf("1", "2")
    )
    eq(
        "по размеру удаляются самые старые",
        KernelLogArchive.toPrune(many, maxFiles = 100, maxBytes = 25),
        listOf("1", "2")
    )
    eq(
        "лимит 0 удаляет всё — но это только при явном нуле",
        KernelLogArchive.toPrune(many, 0, 0),
        listOf("1", "2", "3", "4")
    )

    // --- подпись ----------------------------------------------------------
    val now = 1_700_000_000_000L
    check(
        "подпись содержит размер",
        KernelLogArchive.label("session-1700000000.txt", 2_048, now).contains("2 КБ"),
        KernelLogArchive.label("session-1700000000.txt", 2_048, now)
    )
    check(
        "свежий сеанс — «только что»",
        KernelLogArchive.label("session-1700000000.txt", 2048, now).startsWith("только что"),
        KernelLogArchive.label("session-1700000000.txt", 2048, now)
    )
    check(
        "вчерашний сеанс — «вчера»",
        KernelLogArchive.label("session-1700000000.txt", 2048, now + 86_400_000L).startsWith("вчера"),
        KernelLogArchive.label("session-1700000000.txt", 2048, now + 86_400_000L)
    )
    check(
        "часовой сеанс — часы",
        KernelLogArchive.label("session-1700000000.txt", 2048, now + 3 * 3_600_000L)
            .startsWith("3 ч назад"),
        KernelLogArchive.label("session-1700000000.txt", 2048, now + 3 * 3_600_000L)
    )
    check(
        "имя без метки времени не ломает подпись",
        KernelLogArchive.label("странное-имя.txt", 1024, now).contains("КБ")
    )

    println()
    println("passed=$passed failed=${failures.size}")
    failures.forEach { println("FAIL  $it") }
    if (failures.isNotEmpty()) kotlin.system.exitProcess(1)
}

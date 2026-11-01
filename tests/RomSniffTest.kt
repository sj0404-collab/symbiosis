// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * Определение формата по первым байтам.
 *
 * ПОЧЕМУ ЭТО ВАЖНО ПРОВЕРЯТЬ
 *   Ошибка здесь стоит дорого в обе стороны. Принять NSZ за архив — значит
 *   отказать человеку в распаковке его же файла. Принять ZIP за образ —
 *   значит отправить его в сорокаминутную распаковку, которая закончится
 *   «ничего».
 */

import org.yuzu.yuzu_emu.utils.RomSniff

private var passed = 0
private val failures = mutableListOf<String>()

private fun check(what: String, ok: Boolean, detail: String = "") {
    if (ok) passed++ else failures.add("$what${if (detail.isEmpty()) "" else " → $detail"}")
}

private fun eq(what: String, got: Any?, want: Any?) =
    check(what, got == want, "получено [$got], ожидалось [$want]")

private fun head(vararg bytes: Int) = ByteArray(bytes.size) { bytes[it].toByte() }

fun main() {
    // ── сжатые образы Nintendo ─────────────────────────────────────────
    val pfs0 = head(0x50, 0x46, 0x53, 0x30)
    val head4 = head(0x48, 0x45, 0x41, 0x44)
    val nca = head(0x4E, 0x43, 0x41, 0x30)

    eq("NSZ по расширению и PFS0", RomSniff.kind(pfs0, "Game.nsz"), RomSniff.Kind.NSZ)
    eq("NSP — тоже PFS0", RomSniff.kind(pfs0, "Game.nsp"), RomSniff.Kind.NSP)
    eq("XCZ по расширению и HEAD", RomSniff.kind(head4, "Game.xcz"), RomSniff.Kind.XCZ)
    eq("XCI — тоже HEAD", RomSniff.kind(head4, "Game.xci"), RomSniff.Kind.XCI)
    eq("NCZ по расширению и NCA", RomSniff.kind(nca, "Game.ncz"), RomSniff.Kind.NCZ)
    eq("NCA без сжатия", RomSniff.kind(nca, "Game.nca"), RomSniff.Kind.NCA)

    check("NSZ распаковывается", RomSniff.needsDecompress(RomSniff.Kind.NSZ))
    check("XCZ распаковывается", RomSniff.needsDecompress(RomSniff.Kind.XCZ))
    check("NCZ распаковывается", RomSniff.needsDecompress(RomSniff.Kind.NCZ))
    check("NSP не распаковывается, но обрабатывается",
        RomSniff.shouldProcess(RomSniff.Kind.NSP) && !RomSniff.needsDecompress(RomSniff.Kind.NSP))

    // ── архивы: главный источник «ничего не распаковалось» ─────────────
    val zip = head(0x50, 0x4B, 0x03, 0x04)
    val rar = head(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07)
    val sevenZ = head(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C)
    val gzip = head(0x1F, 0x8B, 0x08)

    eq("ZIP по подписи", RomSniff.kind(zip, "Game.zip"), RomSniff.Kind.ZIP)
    eq("RAR по подписи", RomSniff.kind(rar, "Game.rar"), RomSniff.Kind.RAR)
    eq("7z по подписи", RomSniff.kind(sevenZ, "Game.7z"), RomSniff.Kind.SEVEN_Z)
    eq("GZ по подписи", RomSniff.kind(gzip, "Game.gz"), RomSniff.Kind.GZIP)

    // Переименованный архив: расширение врёт, подпись — нет.
    eq("zip, названный .nsz, остаётся архивом",
        RomSniff.kind(zip, "Game.nsz"), RomSniff.Kind.ZIP)
    check("архив не идёт в распаковку", !RomSniff.shouldProcess(RomSniff.Kind.ZIP))
    check("про архив сказано словами",
        RomSniff.advice(RomSniff.Kind.ZIP, "Game.zip").contains("архив ZIP"),
        RomSniff.advice(RomSniff.Kind.ZIP, "Game.zip"))
    check("про архив сказано, что делать",
        RomSniff.advice(RomSniff.Kind.RAR, "Game.rar").contains(".nsz"),
        RomSniff.advice(RomSniff.Kind.RAR, "Game.rar"))

    // ── пустой и мусор ─────────────────────────────────────────────────
    eq("пустой файл", RomSniff.kind(ByteArray(0), "Game.nsz"), RomSniff.Kind.EMPTY)
    eq("мусор без расширения", RomSniff.kind(head(0x00, 0x01, 0x02), "dump"), RomSniff.Kind.UNKNOWN)
    check("мусор не идёт в распаковку", !RomSniff.shouldProcess(RomSniff.Kind.UNKNOWN))
    check("про пустой сказано, что скачивание не состоялось",
        RomSniff.advice(RomSniff.Kind.EMPTY, "Game.nsz").contains("пустой"))
    check("про мусор сказано про первые байты",
        RomSniff.advice(RomSniff.Kind.UNKNOWN, "dump.bin").contains("первые байты"))

    // ── короткая подпись не должна читаться за пределами буфера ────────
    eq("два байта вместо четырёх — не ZIP",
        RomSniff.kind(head(0x50, 0x4B), "a.nsz"), RomSniff.Kind.NSZ)
    eq("три байта NCA", RomSniff.kind(head(0x4E, 0x43, 0x41), "a.ncz"), RomSniff.Kind.NCZ)

    // ── расширение спасает, когда подпись переставлена ─────────────────
    eq("незнакомая подпись + .nsz", RomSniff.kind(head(0xAB, 0xCD), "Game.nsz"), RomSniff.Kind.NSZ)
    eq("незнакомая подпись + .xci", RomSniff.kind(head(0xAB, 0xCD), "Game.XCI"), RomSniff.Kind.XCI)

    println()
    println("passed=$passed failed=${failures.size}")
    failures.forEach { println("FAIL  $it") }
    if (failures.isNotEmpty()) kotlin.system.exitProcess(1)
}

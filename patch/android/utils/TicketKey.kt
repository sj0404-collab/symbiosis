// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import java.io.File
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Ключ игры из билета (.tik), который лежит внутри образа.
 *
 * ЗАЧЕМ
 *   Человек спросил прямо: «раз есть билет, значит ключ должен быть внутри
 *   него, верно?» — и он прав. Билет устроен так: в нём лежит блок
 *   `title key block` (0x100 байт), а в нём — ключ игры, зашифрованный
 *   ключом `titlekek` нужной ревизии. `titlekek` у человека есть: в его
 *   `prod.keys` лежат `titlekek_00…titlekek_20`. Значит ключ из обычного
 *   билета можно достать прямо на телефоне, без ядра и без интернета.
 *
 *   Раньше приложение этого не делало и на любой такой файл отвечало «билет
 *   без права, нужен ключ в title.keys». Формально верно, по сути — отписка:
 *   ключ лежал в самом файле.
 *
 * КОГДА НЕЛЬЗЯ (и это не наша вина)
 *   - `title key type = 1` — «персонализированный» билет: ключ упакован
 *     RSA-2048 под приватный ключ консоли. Без этой консоли — никак.
 *   - блок нулевой или Rights ID забит FF — билет есть, права нет (так
 *     бывает у дампов картриджа).
 *
 * РАЗМЕТКА (Nintendo Switch Brew, «Ticket»)
 *   Билет = подпись (длина зависит от типа) + данные 0x2C0 байт:
 *     +0x00  Issuer (0x40)
 *     +0x40  Title key block (0x100)      ← здесь зашифрованный ключ
 *     +0x140 Ticket version
 *     +0x141 Title key type               ← 0 — обычный, 1 — под консоль
 *     +0x145 Master key revision          ← какую titlekek брать
 *     +0x160 Rights ID (0x10)             ← первые 8 байт и есть игра
 */
object TicketKey {

    /** Что удалось прочитать из билета — без криптографии. */
    data class Parsed(
        /** Смещение начала данных билета (после подписи). */
        val dataStart: Int,
        val keyType: Int,
        val revision: Int,
        /** Первые 16 байт блока ключа: зашифрованный ключ игры. */
        val encrypted: ByteArray,
        /** Rights ID: 16 байт, первые 8 — идентификатор игры. */
        val rightsId: ByteArray
    ) {
        val titleId: String
            get() = if (rightsId.size >= 8) hex(rightsId.copyOfRange(0, 8)) else ""

        /** Похож ли билет на «права нет»: всё в FF или блок нулевой. */
        val isBlank: Boolean
            get() = encrypted.all { it == 0.toByte() } ||
                rightsId.all { it == 0xFF.toByte() }
    }

    /**
     * Разобрать билет.
     *
     * Длина подписи определяется её типом: 0x0004 — RSA-2048 (0x100 байт),
     * 0x0005 — RSA-4096 (0x200). Данные выравниваются на 0x40, поэтому
     * данные начинаются с 0x140 или 0x240, а не всегда с 0x140 — старый код
     * читал Rights ID по жёсткому 0x2A0 и на «длинных» билетах попадал в
     * середину чужого поля.
     */
    fun parse(ticket: ByteArray): Parsed? {
        if (ticket.size < 0x180) return null
        val sigType = (ticket[3].toInt() and 0xFF)
        val sigSize = when (sigType) {
            0x04 -> 0x100
            0x05 -> 0x200
            else -> if (ticket.size >= 0x340) 0x200 else 0x100
        }
        val dataStart = align(4 + sigSize, 0x40)
        if (ticket.size < dataStart + 0x180) return null
        val keyType = ticket[dataStart + 0x141].toInt() and 0xFF
        val revision = ticket[dataStart + 0x145].toInt() and 0xFF
        val encrypted = ticket.copyOfRange(dataStart + 0x40, dataStart + 0x50)
        val rightsId = ticket.copyOfRange(dataStart + 0x160, dataStart + 0x170)
        return Parsed(dataStart, keyType, revision, encrypted, rightsId)
    }

    /** Результат попытки достать ключ: либо ключ, либо честная причина. */
    data class Derived(val key: String, val note: String)

    /**
     * Достать ключ игры из билета.
     *
     * @param ticket содержимое .tik целиком
     * @param prodKeys ключи консоли: имя → 16 байт
     */
    fun derive(ticket: ByteArray, prodKeys: Map<String, ByteArray>): Derived? {
        val parsed = parse(ticket) ?: return null
        if (parsed.isBlank) return null
        if (parsed.keyType == 1) {
            return null // персонализированный: нужен ключ консоли, которого нет
        }
        val titlekek = titleKek(prodKeys, parsed.revision) ?: return null
        val key = runCatching { aesEcbDecrypt(titlekek, parsed.encrypted) }.getOrNull() ?: return null
        // Расшифровка «получилась» даже при неверном ключе: AES не проверяет
        // данные. Поэтому мусор отсеиваем: настоящий ключ не бывает нулевым
        // и не бывает одинаковым во всех байтах.
        if (key.all { it == 0.toByte() }) return null
        if (key.toSet().size == 1) return null
        val hex = hex(key)
        if (!TitleKeys.isPlausible(parsed.titleId)) return null
        return Derived(
            hex,
            "ключ взят из билета внутри файла (ревизия ключей ${parsed.revision})"
        )
    }

    /** Самая свежая ревизия ключей в наборе; -1, если ревизий нет вовсе. */
    fun maxRevision(prodKeys: Map<String, ByteArray>): Int {
        val regex = Regex("^titlekek_([0-9a-f]{2})$")
        return prodKeys.keys.mapNotNull { regex.find(it)?.groupValues?.get(1)?.toInt(16) }
            .maxOrNull() ?: -1
    }

    /** Насколько набор ключей старее нужного: 0 — хватает. */
    fun revisionGap(prodKeys: Map<String, ByteArray>, needed: Int): Int {
        val max = maxRevision(prodKeys)
        if (max < 0) return 0
        return (needed - max).coerceAtLeast(0)
    }

    /**
     * Почему ключ не достался — словами, а не молчанием.
     *
     * Отдельно про ревизию: «нет titlekek_16» человеку ничего не говорит, а
     * на телефоне 06.11 это был единственный настоящий ответ. Поэтому
     * называем и цифру, и что она значит: ключи консоли старее игры, их надо
     * обновить до версии установленной прошивки.
     */
    fun refusal(ticket: ByteArray, prodKeys: Map<String, ByteArray>): String {
        val parsed = parse(ticket) ?: return "билет не разобран: слишком короткий"
        if (parsed.isBlank) {
            return "у билета нет прав (Rights ID пустой или FF), ключа в нём нет"
        }
        if (parsed.keyType == 1) {
            return "билет персонализированный: ключ в нём заперт под вашу консоль, " +
                "разобрать его можно только на ней"
        }
        if (titleKek(prodKeys, parsed.revision) == null) {
            val needed = "%02x".format(parsed.revision)
            val max = maxRevision(prodKeys)
            return if (max < 0) {
                "в prod.keys нет ни одного titlekek — нужен файл ключей целиком"
            } else {
                "игре нужны ключи консоли ревизии 0x$needed, а в ваших prod.keys " +
                    "самая свежая — 0x" + "%02x".format(max) +
                    ". Ключи старее игры: обновите prod.keys до версии установленной " +
                    "прошивки (я установленную прошивку не заменяю — ядро читает ключи " +
                    "из prod.keys)"
            }
        }
        return ""
    }

    /** `titlekek_XX`, а если его нет — вывод из `titlekek_source` + `master_key_XX`. */
    fun titleKek(prodKeys: Map<String, ByteArray>, revision: Int): ByteArray? {
        prodKeys["titlekek_" + "%02x".format(revision)]?.let { return it }
        val source = prodKeys["titlekek_source"] ?: return null
        val master = prodKeys["master_key_" + "%02x".format(revision)] ?: return null
        return runCatching { aesEcbDecrypt(master, source) }.getOrNull()
    }

    /** AES-128-ECB без выравнивания: так устроен блок ключа в билете. */
    fun aesEcbDecrypt(key: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key.copyOf(16), "AES"))
        return cipher.doFinal(data.copyOf(16))
    }

    /** Обратная операция — только для тестов и для проверки своей же логики. */
    fun aesEcbEncrypt(key: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.copyOf(16), "AES"))
        return cipher.doFinal(data.copyOf(16))
    }

    /**
     * Прочитать `prod.keys` в память: имя → 16 байт.
     *
     * Читается только то, что нужно для билета (`titlekek_*`,
     * `titlekek_source`, `master_key_*`), и только шестнадцатеричные строки
     * нужной длины: остальное — чужие форматы, их не трогаем.
     */
    fun readProdKeys(keysDir: File?): Map<String, ByteArray> {
        if (keysDir == null || !keysDir.isDirectory) return emptyMap()
        val file = File(keysDir, "prod.keys")
        if (!file.isFile) return emptyMap()
        val out = HashMap<String, ByteArray>()
        runCatching {
            file.readLines().forEach { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#") || !line.contains('=')) return@forEach
                val name = line.substringBefore('=').trim().lowercase()
                val value = line.substringAfter('=').filterNot { it.isWhitespace() }
                if (value.length == 32 && value.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
                    out[name] = hexToBytes(value)
                }
            }
        }
        return out
    }

    fun hexToBytes(text: String): ByteArray =
        ByteArray(text.length / 2) { i ->
            text.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }

    fun hex(bytes: ByteArray): String = buildString {
        bytes.forEach { append("%02x".format(it.toInt() and 0xFF)) }
    }

    private fun align(value: Int, to: Int): Int = (value + to - 1) / to * to
}

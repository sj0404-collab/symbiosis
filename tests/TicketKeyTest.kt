// Тест «ключ из билета»: собираем билет по разметке и проверяем, что ключ
// достаётся ровно тем путём, каким это делает приложение.

import org.yuzu.yuzu_emu.utils.TicketKey
import org.yuzu.yuzu_emu.utils.TitleKeys

var passed = 0
var failed = 0

fun check(name: String, ok: Boolean, extra: String = "") {
    if (ok) {
        passed++
    } else {
        failed++
        println("FAIL  $name" + if (extra.isNotEmpty()) " → $extra" else "")
    }
}

fun eq(name: String, got: Any?, want: Any?) = check(name, got == want, "получено $got, ожидалось $want")

fun hex16(text: String): ByteArray = TicketKey.hexToBytes(text)

/**
 * Собрать билет.
 *
 * @param keyType 0 — обычный (ключ заперт titlekek), 1 — под консоль
 * @param rights права: 16 байт
 * @param block блок ключа (0x100)
 */
fun ticket(
    sigType: Int = 0x04,
    keyType: Int = 0,
    revision: Int = 0,
    rights: String = "01004f302847e000000000000000000e",
    block: ByteArray
): ByteArray {
    val sigSize = if (sigType == 0x04) 0x100 else 0x200
    val dataStart = (4 + sigSize + 0x3F) / 0x40 * 0x40
    val out = ByteArray(dataStart + 0x2C0)
    out[0] = 0
    out[1] = 1
    out[2] = 0
    out[3] = sigType.toByte()
    val issuer = "Root-CA00000003-XS0000000c".toByteArray()
    System.arraycopy(issuer, 0, out, dataStart, issuer.size)
    System.arraycopy(block, 0, out, dataStart + 0x40, block.size)
    out[dataStart + 0x140] = 2
    out[dataStart + 0x141] = keyType.toByte()
    out[dataStart + 0x145] = revision.toByte()
    val rightsBytes = hex16(rights)
    System.arraycopy(rightsBytes, 0, out, dataStart + 0x160, rightsBytes.size)
    return out
}

fun main() {
    val titlekek = hex16("11111111111111111111111111111111")
    val master = hex16("22222222222222222222222222222222")
    val titleKey = hex16("a1b2c3d4e5f60718293a4b5c6d7e8f90")
    val block = ByteArray(0x100)
    System.arraycopy(TicketKey.aesEcbEncrypt(titlekek, titleKey), 0, block, 0, 16)

    // ── обычный билет: ключ достаётся ───────────────────────────────────
    val common = ticket(block = block)
    val parsed = TicketKey.parse(common)!!
    eq("данные начинаются после подписи", parsed.dataStart, 0x140)
    eq("идентификатор из прав", parsed.titleId, "01004f302847e000")
    eq("ревизия ключа", parsed.revision, 0)
    eq("тип билета — обычный", parsed.keyType, 0)

    val keys = mapOf("titlekek_00" to titlekek, "master_key_00" to master)
    val derived = TicketKey.derive(common, keys)
    check("ключ извлечён", derived != null)
    eq("ключ верный", derived?.key, "a1b2c3d4e5f60718293a4b5c6d7e8f90")
    check("в объяснении сказано, откуда ключ",
        derived?.note?.contains("из билета") == true, derived?.note ?: "")

    // ── titlekek выводится из titlekek_source, если отдельного нет ──────
    // Так у людей, у которых prod.keys старого формата: только source и
    // master_key_XX. Формула та же, что в hactool: расшифровать source
    // ключом master_key.
    val source = hex16("33333333333333333333333333333333")
    // source лежит в prod.keys уже зашифрованным мастер-ключом: приложение
    // расшифрует его и получит titlekek, а им уже откроет блок ключа.
    val titlekekFromSource = source
    val derivedKek = ByteArray(0x100)
    System.arraycopy(TicketKey.aesEcbEncrypt(titlekekFromSource, titleKey), 0, derivedKek, 0, 16)
    val sourceInFile = TicketKey.aesEcbEncrypt(master, source)
    val viaSource = TicketKey.derive(
        ticket(block = derivedKek),
        mapOf("titlekek_source" to sourceInFile, "master_key_00" to master)
    )
    eq("titlekek выведен из source", viaSource?.key, "a1b2c3d4e5f60718293a4b5c6d7e8f90")

    // ── ревизия: берётся titlekek_XX этой ревизии ───────────────────────
    val kekRev5 = hex16("55555555555555555555555555555555")
    val blockRev5 = ByteArray(0x100)
    System.arraycopy(TicketKey.aesEcbEncrypt(kekRev5, titleKey), 0, blockRev5, 0, 16)
    val rev5 = TicketKey.derive(
        ticket(revision = 5, block = blockRev5),
        mapOf("titlekek_00" to titlekek, "titlekek_05" to kekRev5)
    )
    eq("ревизия учитывается", rev5?.key, "a1b2c3d4e5f60718293a4b5c6d7e8f90")

    // ── длинный билет (RSA-4096): данные с 0x240 ───────────────────────
    val long = ticket(sigType = 0x05, block = block)
    eq("длинная подпись учтена", TicketKey.parse(long)?.dataStart, 0x240)
    eq("и права читаются там же", TicketKey.parse(long)?.titleId, "01004f302847e000")
    eq("ключ из длинного билета", TicketKey.derive(long, keys)?.key,
        "a1b2c3d4e5f60718293a4b5c6d7e8f90")

    // ── персонализированный билет: честный отказ, а не тишина ──────────
    val personalised = ticket(keyType = 1, block = block)
    check("под консоль — не выводится", TicketKey.derive(personalised, keys) == null)
    val refusal = TicketKey.refusal(personalised, keys)
    check("и сказано почему", refusal.contains("персонализированный"), refusal)

    // ── билет без прав (FF) ────────────────────────────────────────────
    val blank = ticket(
        rights = "ffffffffffffffffffffffffffffffff",
        block = ByteArray(0x100)
    )
    check("билет без прав не даёт ключа", TicketKey.derive(blank, keys) == null)
    check("и это названо", TicketKey.refusal(blank, keys).contains("нет прав"),
        TicketKey.refusal(blank, keys))

    // ── нет titlekek: говорим, какого не хватает ───────────────────────
    val noKek = TicketKey.refusal(ticket(block = block), emptyMap())
    check("назван отсутствующий titlekek", noKek.contains("titlekek_00"), noKek)

    // ── мусорный «ключ» не должен выдаваться за настоящий ──────────────
    // AES не проверяет данные: при неверном titlekek расшифровка «удаётся».
    // Настоящий ключ не бывает нулевым — такой ответ отбрасываем.
    val zeroBlock = ByteArray(0x100)
    val zeroKey = TicketKey.derive(ticket(block = zeroBlock), keys)
    check("нулевой блок не выдаётся за ключ", zeroKey == null)

    // ── короткий/битый билет ───────────────────────────────────────────
    check("обрезанный билет не разбирается", TicketKey.parse(ByteArray(0x40)) == null)
    check("пустой билет не разбирается", TicketKey.parse(ByteArray(0)) == null)

    // ── идентификатор игры из прав ─────────────────────────────────────
    eq("права 16 байт дают 16-значный идентификатор",
        TicketKey.parse(common)?.titleId?.length, 16)
    check("идентификатор пригоден для title.keys",
        TitleKeys.isPlausible(TicketKey.parse(common)!!.titleId))

    println("passed=$passed failed=$failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}

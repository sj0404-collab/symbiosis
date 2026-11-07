// Host-only tests of text encoding and reference length semantics.
import org.yuzu.yuzu_emu.utils.KenjiUiText

fun main() {
    val examples = listOf("", "hello", "Привет", "日本語", "café", "😀", "👩‍👩‍👧‍👦")
    examples.forEach { value ->
        val bytes = checkNotNull(KenjiUiText.encode(value))
        check(KenjiUiText.decode(bytes) == value)
        check(bytes.contentEquals(value.toByteArray(Charsets.UTF_8)))
    }
    check(KenjiUiText.encode("a\u0000b") == null)
    check(KenjiUiText.encode("\uD800") == null) // lone high surrogate
    check(KenjiUiText.encode("\uDC00") == null) // lone low surrogate
    check(KenjiUiText.decode(byteArrayOf(65, 0, 66)) == null)
    check(KenjiUiText.decode(byteArrayOf(0xC0.toByte(), 0x80.toByte())) == null) // overlong NUL
    check(KenjiUiText.decode(byteArrayOf(0xF0.toByte(), 0x9F.toByte())) == null) // incomplete
    check(KenjiUiText.withinBounds("", 0, 0))
    check(!KenjiUiText.withinBounds("x", 0, 0))
    check(KenjiUiText.withinBounds("😀", 2, 2)) // Java length, not 1 code point or 4 UTF-8 bytes
    check(!KenjiUiText.withinBounds("😀", 1, 1))
    check(!KenjiUiText.withinBounds("x", -1, 2))
    check(!KenjiUiText.withinBounds("x", 3, 2))
    println("KenjiUiText: UTF-8, invalid input and UTF-16 bounds passed")
}

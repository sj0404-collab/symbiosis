import org.yuzu.yuzu_emu.utils.KenjiInputMap

fun main() {
    val expected = listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 16, 15, 13, 11, 14, 12)
    expected.forEachIndexed { eden, kenji -> check(KenjiInputMap.button(eden) == kenji) { "button $eden" } }
    for (i in 16..21) check(KenjiInputMap.button(i) == -1) { "unmapped $i" }
    listOf(-100, -1, 22, 100).forEach { check(KenjiInputMap.button(it) == -1) }
    check(KenjiInputMap.stick(0) == 1)
    check(KenjiInputMap.stick(1) == 2)
    check(KenjiInputMap.stick(-1) == -1 && KenjiInputMap.stick(2) == -1)
    check(KenjiInputMap.validButtons().toSet() == expected.toSet())
    println("KenjiInputMapTest: 30 checks passed")
}

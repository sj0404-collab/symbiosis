// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later
package org.yuzu.yuzu_emu.utils

/** Eden NativeButton/NativeAnalog -> related Kenji GamepadButtonInputId/StickInputId.
 * SL/SR, Home and Capture are not mapped by Kenji's default Pro configuration.
 * Never pass an unknown value through as it could become a different button.
 */
object KenjiInputMap {
    private val buttons = intArrayOf(
        1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 16, 15,
        13, 11, 14, 12, -1, -1, -1, -1, -1, -1
    )
    fun button(eden: Int): Int = buttons.getOrElse(eden) { -1 }
    fun stick(eden: Int): Int = when (eden) { 0 -> 1; 1 -> 2; else -> -1 }
    fun validButtons(): List<Int> = buttons.filter { it >= 0 }.distinct()
}

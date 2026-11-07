// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later
package org.yuzu.yuzu_emu.utils

import android.os.Bundle
import org.yuzu.yuzu_emu.features.settings.model.BooleanSetting
import org.yuzu.yuzu_emu.features.settings.model.IntSetting
import org.yuzu.yuzu_emu.overlay.model.OverlayControlData

/** A snapshot avoids relying on process-local Eden config caches in :kenji. */
object KenjiOverlaySnapshot {
    private val bools = arrayOf(BooleanSetting.SHOW_INPUT_OVERLAY, BooleanSetting.TOUCHSCREEN,
        BooleanSetting.HAPTIC_FEEDBACK, BooleanSetting.DPAD_SLIDE,
        BooleanSetting.JOYSTICK_REL_CENTER)
    private val ints = arrayOf(IntSetting.OVERLAY_SCALE, IntSetting.OVERLAY_OPACITY)

    fun capture(): Bundle = Bundle().apply {
        putBooleanArray("bools", bools.map { it.getBoolean() }.toBooleanArray())
        putIntArray("ints", ints.map { it.getInt() }.toIntArray())
        val controls = ArrayList<Bundle>()
        NativeConfig.getOverlayControlData().forEach { data ->
            controls.add(Bundle().apply {
                putString("id", data.id); putBoolean("enabled", data.enabled)
                putDoubleArray("positions", doubleArrayOf(data.landscapePosition.first,
                    data.landscapePosition.second, data.portraitPosition.first,
                    data.portraitPosition.second, data.foldablePosition.first, data.foldablePosition.second))
                putFloat("scale", data.individualScale)
            })
        }
        putParcelableArrayList("controls", controls)
    }

    @Suppress("DEPRECATION")
    fun restore(snapshot: Bundle): List<String> {
        val b = requireNotNull(snapshot.getBooleanArray("bools"))
        val n = requireNotNull(snapshot.getIntArray("ints"))
        val controls = requireNotNull(snapshot.getParcelableArrayList<Bundle>("controls"))
        require(b.size == bools.size && n.size == ints.size)
        val unsupported = mutableListOf<String>()
        val decoded = controls.map { item ->
            val pos = requireNotNull(item.getDoubleArray("positions"))
            val id = requireNotNull(item.getString("id"))
            require(pos.size == 6)
            // The related Kenji Pro config has no mappings for these; do not
            // draw controls that would silently do nothing in this process.
            val disabled = id == "button_home" || id == "button_capture"
            if (disabled && item.getBoolean("enabled")) unsupported.add(id)
            OverlayControlData(id, item.getBoolean("enabled") && !disabled,
                Pair(pos[0], pos[1]), Pair(pos[2], pos[3]), Pair(pos[4], pos[5]), item.getFloat("scale"))
        }
        bools.forEachIndexed { i, v -> v.setBoolean(b[i]) }
        ints.forEachIndexed { i, v -> v.setInt(n[i]) }
        NativeConfig.setOverlayControlData(decoded.toTypedArray())
        return unsupported
    }
}

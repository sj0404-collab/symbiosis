package org.yuzu.yuzu_emu.features.settings.model
enum class BooleanSetting { SHOW_INPUT_OVERLAY, TOUCHSCREEN, HAPTIC_FEEDBACK, DPAD_SLIDE, JOYSTICK_REL_CENTER;
    private var value = false
    fun getBoolean() = value
    fun setBoolean(v: Boolean) { value = v }
}
enum class IntSetting { OVERLAY_SCALE, OVERLAY_OPACITY;
    private var value = 0
    fun getInt() = value
    fun setInt(v: Int) { value = v }
}

package org.yuzu.yuzu_emu.utils
import org.yuzu.yuzu_emu.overlay.model.OverlayControlData
object NativeConfig {
    private var controls: Array<OverlayControlData> = emptyArray()
    fun getOverlayControlData() = controls
    fun setOverlayControlData(v: Array<OverlayControlData>) { controls = v }
}

import android.os.Bundle
import org.yuzu.yuzu_emu.features.settings.model.BooleanSetting
import org.yuzu.yuzu_emu.features.settings.model.IntSetting
import org.yuzu.yuzu_emu.overlay.model.OverlayControlData
import org.yuzu.yuzu_emu.utils.KenjiOverlaySnapshot
import org.yuzu.yuzu_emu.utils.NativeConfig
fun main() {
    val original = OverlayControlData("button_a", true, .1 to .2, .3 to .4, .5 to .6, 1.2f)
    val home = original.copy(id = "button_home")
    NativeConfig.setOverlayControlData(arrayOf(original, home))
    BooleanSetting.TOUCHSCREEN.setBoolean(true)
    BooleanSetting.DPAD_SLIDE.setBoolean(true)
    IntSetting.OVERLAY_SCALE.setInt(75)
    IntSetting.OVERLAY_OPACITY.setInt(40)
    val snapshot = KenjiOverlaySnapshot.capture()
    original.landscapePosition = 0.9 to 0.9
    NativeConfig.setOverlayControlData(emptyArray())
    BooleanSetting.TOUCHSCREEN.setBoolean(false)
    BooleanSetting.DPAD_SLIDE.setBoolean(false)
    IntSetting.OVERLAY_SCALE.setInt(3)
    IntSetting.OVERLAY_OPACITY.setInt(4)
    val unsupported = KenjiOverlaySnapshot.restore(snapshot)
    check(unsupported == listOf("button_home"))
    val all = NativeConfig.getOverlayControlData()
    check(!all.single { it.id == "button_home" }.enabled)
    val data = all.single { it.id == "button_a" }
    check(data.id == "button_a" && data.enabled)
    check(data.landscapePosition == .1 to .2 && data.portraitPosition == .3 to .4 && data.foldablePosition == .5 to .6)
    check(data.individualScale == 1.2f)
    check(BooleanSetting.TOUCHSCREEN.getBoolean() && BooleanSetting.DPAD_SLIDE.getBoolean())
    check(IntSetting.OVERLAY_SCALE.getInt() == 75 && IntSetting.OVERLAY_OPACITY.getInt() == 40)
    check(runCatching { KenjiOverlaySnapshot.restore(Bundle()) }.isFailure)
    println("KenjiOverlaySnapshotTest: round-trip and invalid bundle passed")
}

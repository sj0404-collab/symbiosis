package org.yuzu.yuzu_emu.overlay.model
data class OverlayControlData(val id: String, var enabled: Boolean,
    var landscapePosition: Pair<Double,Double>, var portraitPosition: Pair<Double,Double>,
    var foldablePosition: Pair<Double,Double>, var individualScale: Float)

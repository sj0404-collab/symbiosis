// CI-only launcher. It asks the real app to construct the real player intent;
// it is copied into the disposable diagnostic build, never a release source.
package dev.symbiosis.playerruntime

import android.app.Activity
import android.os.Bundle
import android.util.Log
import org.yuzu.yuzu_emu.activities.KenjiPlayerActivity
import java.io.File

class DiagnosticLauncher : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        try {
            val kind = intent.getStringExtra("test_kind").orEmpty()
            if (kind == "core-update") {
                Thread {
                    try {
                        val expected = intent.getStringExtra("ci_expected_core_sha").orEmpty()
                        check(expected.matches(Regex("[0-9a-f]{64}")))
                        if (expected == "d781048671e4cef1cde2ec15db8fe29b949df9949d83569ceaf776ad12901590") {
                            val previous = checkNotNull(org.yuzu.yuzu_emu.utils.CoreUpdateStore.active(
                                filesDir, org.yuzu.yuzu_emu.utils.CoreTestUpdates.key()))
                            check(previous.core.sequence == 1L && previous.core.sha256 ==
                                "969431b3962408dd02ce38bd2cd4b2a954d11e5212ddef3f65c80a0b419918b8")
                            Log.i("PlayerRuntimeCI", "CORE_UPDATE_CI_PREVIOUS_V1_OK")
                        }
                        val first = org.yuzu.yuzu_emu.utils.CoreTestUpdates.install(this)
                        check(first.ok) { first.message }
                        val second = org.yuzu.yuzu_emu.utils.CoreTestUpdates.install(this)
                        check(second.ok && second.message.contains("уже установлена")) { second.message }
                        val active = checkNotNull(org.yuzu.yuzu_emu.utils.CoreUpdateStore.active(
                            filesDir, org.yuzu.yuzu_emu.utils.CoreTestUpdates.key()))
                        check(active.core.sha256 == expected)
                        if (expected == "d781048671e4cef1cde2ec15db8fe29b949df9949d83569ceaf776ad12901590") check(active.core.sequence == 2L)
                        check(org.yuzu.yuzu_emu.utils.EngineLoader.coreFile(this, org.yuzu.yuzu_emu.utils.EngineLoader.Engine.KENJI) == active.file)
                        check(org.yuzu.yuzu_emu.utils.EngineLoader.state(this, org.yuzu.yuzu_emu.utils.EngineLoader.Engine.KENJI) is org.yuzu.yuzu_emu.utils.EngineLoader.State.Ready)
                        File(filesDir, "ci-core-update-ok.txt").writeText("OK version=${active.core.sequence} sha256=${active.core.sha256}\n")
                        Log.i("PlayerRuntimeCI", "CORE_UPDATE_CI_OK version=${active.core.sequence}")
                    } catch (t: Throwable) {
                        File(filesDir, "ci-core-update-fail.txt").writeText(t.message.orEmpty())
                        Log.e("PlayerRuntimeCI", "CORE_UPDATE_CI_FAILED", t)
                    } finally { runOnUiThread { finish() } }
                }.start()
                return
            }
            val filename = when (kind) {
                "graphics" -> "graphics-probe.nro"
                "shader" -> "shader-probe.nro"
                "tls" -> "guest-tls-probe.nro"
                "network" -> "guest-network-probe.nro"
                "account" -> "guest-account-probe.nro"
                "hid-long" -> "hid-long-probe.nro"
                "tls-parallel" -> "tls-parallel-probe.nro"
                else -> "swkbd-probe.nro"
            }
            val game = File(filesDir, filename)
            check(game.isFile) { "missing test NRO" }
            val config = intent.getStringExtra("test_config").orEmpty()
            val playerIntent = KenjiPlayerActivity.intent(this, game.absolutePath, "Keyboard test", config)
            // Guest test sentinels use Guest logging; regular users default to errors only.
            playerIntent.putExtra(KenjiPlayerActivity.EXTRA_VERBOSE_LOG, !intent.getBooleanExtra("ci_quiet_log", false))
            playerIntent.putExtra("ci_test_stop_probe", intent.getBooleanExtra("ci_test_stop_probe", false))
            playerIntent.putExtra("ci_delay_cancel", intent.getBooleanExtra("ci_delay_cancel", false))
            startActivity(playerIntent)
            Log.i("PlayerRuntimeCI", "REAL_PLAYER_LAUNCH_REQUESTED kind=$kind config=$config")
            finish()
        } catch (t: Throwable) {
            Log.e("PlayerRuntimeCI", "REAL_PLAYER_LAUNCH_FAILED", t)
            finish()
        }
    }
}

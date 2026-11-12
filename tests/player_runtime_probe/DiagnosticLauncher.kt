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

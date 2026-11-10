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
            val game = File(filesDir, "swkbd-probe.nro")
            check(game.isFile) { "missing test NRO" }
            val intent = KenjiPlayerActivity.intent(this, game.absolutePath, "Keyboard test")
            startActivity(intent)
            Log.i("PlayerRuntimeCI", "REAL_PLAYER_LAUNCH_REQUESTED")
            finish()
        } catch (t: Throwable) {
            Log.e("PlayerRuntimeCI", "REAL_PLAYER_LAUNCH_FAILED", t)
            finish()
        }
    }
}

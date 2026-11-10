// CI-only: independent OS lock contender in the launcher/main process.
package dev.symbiosis.playerruntime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File
import org.yuzu.yuzu_emu.utils.GameSessionLock

class LockProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = GameSessionLock.tryAcquire(File(context.filesDir, GameSessionLock.FILE_NAME))
        val status = when (result) {
            is GameSessionLock.Result.Acquired -> { result.lease.close(); "acquired" }
            GameSessionLock.Result.Busy -> "busy"
            is GameSessionLock.Result.Failed -> "failed"
        }
        Log.i("PlayerLockCI", "CI_LOCK result=$status pid=${android.os.Process.myPid()}")
    }
}

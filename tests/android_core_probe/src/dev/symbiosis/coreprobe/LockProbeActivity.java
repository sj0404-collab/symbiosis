package dev.symbiosis.coreprobe;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import org.yuzu.yuzu_emu.utils.GameSessionLock;

// Runs in the test APK's main process and tries the production OS session lock.
public final class LockProbeActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        GameSessionLock.Result result = GameSessionLock.tryAcquire(new File(getFilesDir(), "ci-session.lock"));
        String answer = result instanceof GameSessionLock.Result.Acquired ? "acquired" :
                result instanceof GameSessionLock.Result.Busy ? "busy" : "error";
        if (result instanceof GameSessionLock.Result.Acquired)
            ((GameSessionLock.Result.Acquired)result).getLease().close();
        try (FileOutputStream output = new FileOutputStream(new File(getFilesDir(), "lock-probe.txt"))) {
            output.write(answer.getBytes(StandardCharsets.UTF_8));
            output.flush();
        } catch (Exception e) { Log.e("SymEngineStage", "LOCK_PROBE_FAIL", e); }
        Log.i("SymEngineStage", "LOCK_PROBE " + answer);
        finish();
    }
}

package dev.symbiosis.coreprobe;

import android.util.Log;
import org.yuzu.yuzu_emu.utils.KenjiBridge;
import java.nio.charset.StandardCharsets;

/** Disposable automated user response; never compiled into the release APK. */
public final class KeyboardFixture {
    private static volatile KenjiBridge bridge;
    private static volatile long generation;
    public static void configure(KenjiBridge value, long token) {
        bridge = value;
        generation = token;
    }
    public static void onRequest(long title, long message, long watermark, int type,
            int min, int max, int mode, long subtitle, long initial) {
        final KenjiBridge target = bridge;
        final long token = generation;
        Log.i("SymEngineStage", "UI_TEST_CALLBACK type=" + type + " min=" + min + " max=" + max + " mode=" + mode);
        if (target == null || token <= 0) {
            Log.e("SymEngineStage", "UI_TEST_FAIL not configured");
            return;
        }
        new Thread(new Runnable() { @Override public void run() {
            boolean result = target.nativeUiRespond(token, true, "CI_Arena".getBytes(StandardCharsets.UTF_8));
            Log.i("SymEngineStage", "UI_TEST_RESPONSE_RETURN result=" + result);
        }}, "test-ui-response").start();
    }
}

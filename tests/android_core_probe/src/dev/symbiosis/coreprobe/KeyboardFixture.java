package dev.symbiosis.coreprobe;

import android.util.Log;
import org.yuzu.yuzu_emu.utils.KenjiBridge;
import org.kenjinx.android.KenjinxNative;
import java.nio.charset.StandardCharsets;

/** Disposable automated user response; never compiled into the release APK. */
public final class KeyboardFixture {
    private static volatile KenjiBridge bridge;
    private static volatile long generation;
    private static volatile boolean realUi;
    public static void configure(KenjiBridge value, long token, boolean useRealUi) {
        bridge = value;
        generation = token;
        deviceClosed = false;
        callbacksAfterClose.set(0);
        realUi = useRealUi;
        KenjinxNative.INSTANCE.setUiCallback(new KenjinxNative.UiCallback() {
            @Override public void onRequest(long title, long message, long watermark, int type,
                    int min, int max, int mode, long subtitle, long initial) {
                KeyboardFixture.onRequest(title, message, watermark, type, min, max, mode, subtitle, initial);
            }
        });
    }
    public static void onRequest(long title, long message, long watermark, int type,
            int min, int max, int mode, long subtitle, long initial) {
        if (deviceClosed) {
            callbacksAfterClose.incrementAndGet();
            Log.e("SymEngineStage", "UI_CALLBACK_AFTER_CLOSE");
        }
        if (realUi) {
            KeyboardUiFixture.request(title, message, watermark, type, min, max, mode, subtitle, initial);
            return;
        }
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

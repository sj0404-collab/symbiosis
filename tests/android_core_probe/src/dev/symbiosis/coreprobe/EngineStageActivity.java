package dev.symbiosis.coreprobe;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import java.io.File;
import org.kenjinx.android.KenjinxNative;
import org.yuzu.yuzu_emu.utils.KenjiBridge;

/** Disposable, isolated CI diagnostic. Real cores and bridge; no games, keys,
 * firmware or fake success. A returned renderer-init is not proof of frames. */
public final class EngineStageActivity extends Activity {
    private static final String TAG = "SymEngineStage";
    private boolean started;
    private String flavor;
    private void step(String name) { Log.i(TAG, "ENGINE_STAGE core=" + flavor + " " + name); }
    private boolean check(String name, String value) {
        if (value == null || !value.isEmpty()) {
            Log.e(TAG, "ENGINE_FAIL core=" + flavor + " " + name + " result=" + value);
            return false;
        }
        step(name + "_RETURNED_OK");
        return true;
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        flavor = getIntent().getStringExtra("core");
        if (!"sym".equals(flavor) && !"space".equals(flavor)) {
            Log.e(TAG, "ENGINE_FAIL invalid core"); return;
        }
        SurfaceView view = new SurfaceView(this);
        view.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder holder) {
                if (started) return; started = true;
                runProbe(holder);
            }
            @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int he) {}
            @Override public void surfaceDestroyed(SurfaceHolder h) {}
        });
        setContentView(view);
    }
    private void runProbe(SurfaceHolder holder) {
        try {
            step("START no_firmware_no_keys_no_game");
            System.loadLibrary("symbiosis_kenji");
            KenjiBridge bridge = new KenjiBridge();
            bridge.nativeSetCrashLog(new File(getFilesDir(), "engine-crash.txt").getAbsolutePath());
            File core = new File(getApplicationInfo().nativeLibraryDir, "libcore_" + flavor + ".so");
            if (!check("load", bridge.nativeLoad(core.getAbsolutePath()))) return;
            File data = new File(getFilesDir(), "engine-data-" + flavor);
            if (!data.mkdirs() && !data.isDirectory()) { Log.e(TAG, "ENGINE_FAIL data directory"); return; }
            if (!check("javaInitialize", bridge.nativeInitialize(data.getAbsolutePath()))) return;
            // Values are the defaults from EngineHybrid.KenjiConfig.
            step("BEFORE_graphicsInitialize");
            if (!check("graphicsInitialize", bridge.nativeGraphicsInit(1f, 0f, true, true,
                    false, true, true, false, 1))) return;
            long window = bridge.nativeWindowOf(holder.getSurface());
            if (window <= 0) { Log.e(TAG, "ENGINE_FAIL native window unavailable"); return; }
            KenjinxNative.INSTANCE.setNativeSurface(window);
            KenjinxNative.INSTANCE.setNativeWindow(window);
            step("BEFORE_graphicsInitializeRenderer");
            if (!check("graphicsInitializeRenderer", bridge.nativeAttachSurface(holder.getSurface(), 1280, 720))) return;
            step("BEFORE_deviceInitialize");
            if (!check("deviceInitialize", bridge.nativeDeviceInit(2, false, 0, 1, 1, 0,
                    false, true, false, false, false, false, 0, "UTC", false))) return;
            bridge.nativeSetWindowHandle(window);
            step("BEFORE_inputInitialize");
            if (!check("inputInitialize", bridge.nativeInputInit(1280, 720))) return;
            int pad = bridge.nativeConnectPad(0);
            step("inputConnectGamepad id=" + pad);
            if (pad < 0 || !bridge.nativeUpdateInput()) { Log.e(TAG, "ENGINE_FAIL input setup"); return; }
            Log.i(TAG, "ENGINE_STAGES_OK core=" + flavor + " no_game=true no_firmware=true frames_tested=false");
        } catch (Throwable t) { Log.e(TAG, "ENGINE_FAIL Java exception", t); }
    }
}

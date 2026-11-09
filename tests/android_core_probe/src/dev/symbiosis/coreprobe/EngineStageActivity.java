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
    private static native int nativeWindowWidth(long handle);
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
    private void startHomebrew(KenjiBridge bridge) {
        // Real, hash-pinned open-source NRO, copied only into this disposable
        // CI app. Never included in a release and never a commercial game.
        new Thread(new Runnable() { public void run() {
            try (android.os.ParcelFileDescriptor fd = android.os.ParcelFileDescriptor.open(
                    new File(getFilesDir(), "homebrew.nro"), android.os.ParcelFileDescriptor.MODE_READ_ONLY)) {
                step("BEFORE_homebrewLoad_NRO");
                if (!check("homebrewLoad", bridge.nativeLoadGame(fd.getFd(), 3))) return;
                step("BEFORE_rendererKick");
                if (!check("rendererKick", bridge.nativeRendererKick(1280, 720, 0))) return;
                new Thread(new Runnable() { public void run() {
                    try {
                        for (int i = 0; i < 60; i++) {
                            Thread.sleep(1000);
                            long frames = KenjinxNative.INSTANCE.getFrames().get();
                            if (frames > 0) {
                                Log.i(TAG, "HOMEBREW_FRAME_CALLBACK core=" + flavor + " frames=" + frames);
                                if (getIntent().getBooleanExtra("input_probe", false)) {
                                    // Test directly through the production JNI bridge, not
                                    // through the Eden overlay. Ordinal 16 is Plus in the
                                    // related Ryujinx input enum; behavior must be observed.
                                    Thread.sleep(3000);
                                    step("BEFORE_plus_press");
                                    bridge.nativeInputButton(16, 0, true);
                                    step("AFTER_plus_press BEFORE_update_pressed");
                                    bridge.nativeUpdateInput();
                                    step("AFTER_update_pressed");
                                    Thread.sleep(1500);
                                    step("BEFORE_plus_release");
                                    bridge.nativeInputButton(16, 0, false);
                                    step("AFTER_plus_release BEFORE_update_released");
                                    bridge.nativeUpdateInput();
                                    step("AFTER_update_released");
                                    Log.i(TAG, "HOMEBREW_INPUT_SENT core=" + flavor + " plus=16 pad=0");
                                }
                                return;
                            }
                        }
                        Log.e(TAG, "ENGINE_FAIL homebrew no frame callback in 60 seconds");
                    } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }}, "frame-observer").start();
                step("BEFORE_realRendererRunLoop");
                String result = bridge.nativeRunLoop();
                Log.e(TAG, "ENGINE_FAIL renderer loop returned result=" + result);
            } catch (Throwable t) { Log.e(TAG, "ENGINE_FAIL homebrew exception", t); }
        }}, "real-homebrew-engine").start();
    }
    private void runProbe(SurfaceHolder holder) {
        try {
            step("START no_firmware_no_keys_no_game");
            System.loadLibrary("symbiosis_kenji");
            System.loadLibrary("graphics_reference_probe");
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
            // Native pointers are bit patterns, not signed Java numbers. ARM64
            // top-byte tags may set the sign bit. JNI returns -1 on failure.
            step("nativeWindow valid=" + holder.getSurface().isValid() +
                    " bits=0x" + Long.toHexString(window));
            if (window == 0 || window == -1) { Log.e(TAG, "ENGINE_FAIL native window unavailable"); return; }
            int width = nativeWindowWidth(window);
            step("nativeWindowWidth=" + width);
            if (width <= 0) { Log.e(TAG, "ENGINE_FAIL invalid native window width"); return; }
            // Acquire and release a second real reference via the production
            // bridge, retaining the first reference throughout the probe.
            long second = bridge.nativeWindowOf(holder.getSurface());
            if (second == 0 || second == -1) { Log.e(TAG, "ENGINE_FAIL second window"); return; }
            bridge.nativeReleaseWindow(second);
            if (nativeWindowWidth(window) != width) { Log.e(TAG, "ENGINE_FAIL window after release"); return; }
            step("nativeWindowSecondReferenceRelease_RETURNED_OK");
            KenjinxNative.INSTANCE.setNativeSurface(window);
            KenjinxNative.INSTANCE.setNativeWindow(window);
            step("BEFORE_graphicsInitializeRenderer");
            if (!check("graphicsInitializeRenderer", bridge.nativeAttachSurface(holder.getSurface(), 1280, 720))) return;
            if (getIntent().getBooleanExtra("ui_setup", false)) {
                if (getIntent().getBooleanExtra("ui_response", false)) {
                    long generation = bridge.nativeUiActivate();
                    if (generation <= 0) { Log.e(TAG, "ENGINE_FAIL UI response activation"); return; }
                    KeyboardFixture.configure(bridge, generation);
                    step("realUiResponse_ACTIVATED");
                }
                step("BEFORE_realUiSetup");
                if (!bridge.nativeUiSetup()) { Log.e(TAG, "ENGINE_FAIL UI setup"); return; }
                step("realUiSetup_RETURNED_OK");
            }
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
            if (getIntent().getBooleanExtra("homebrew", false)) startHomebrew(bridge);
        } catch (Throwable t) { Log.e(TAG, "ENGINE_FAIL Java exception", t); }
    }
}

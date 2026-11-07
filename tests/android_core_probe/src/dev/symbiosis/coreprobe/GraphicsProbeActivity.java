package dev.symbiosis.coreprobe;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import java.io.File;

/** CI-only: executes a pinned reference helper against a synthetic window. */
public final class GraphicsProbeActivity extends Activity {
    private boolean ran;
    private static native String nativeRun(String libraryPath, android.view.Surface surface);

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            System.loadLibrary("graphics_reference_probe");
            SurfaceView view = new SurfaceView(this);
            view.getHolder().addCallback(new SurfaceHolder.Callback() {
                @Override public void surfaceCreated(SurfaceHolder holder) {
                    if (ran) return;
                    ran = true;
                    try {
                        String path = new File(getApplicationInfo().nativeLibraryDir,
                                "libreference_kenji_jni.so").getAbsolutePath();
                        String error = nativeRun(path, holder.getSurface());
                        if (error == null || !error.isEmpty()) {
                            Log.e("SymGraphicsProbe", "GRAPHICS_PROBE_FAIL " + error);
                        } else {
                            Log.i("SymGraphicsProbe", "GRAPHICS_PROBE_OK");
                        }
                    } catch (Throwable t) {
                        Log.e("SymGraphicsProbe", "GRAPHICS_PROBE_FAIL Java error", t);
                    }
                    finish();
                }
                @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}
                @Override public void surfaceDestroyed(SurfaceHolder holder) {}
            });
            setContentView(view);
        } catch (Throwable t) {
            Log.e("SymGraphicsProbe", "GRAPHICS_PROBE_FAIL initialization", t);
            finish();
        }
    }
}

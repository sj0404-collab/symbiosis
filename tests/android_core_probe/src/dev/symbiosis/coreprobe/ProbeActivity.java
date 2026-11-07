package dev.symbiosis.coreprobe;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import org.yuzu.yuzu_emu.utils.KenjiBridge;
import java.io.File;

/** Disposable CI-only probe, no keys, firmware, games, graphics or input. */
public final class ProbeActivity extends Activity {
    private static final String TAG = "SymCoreProbe";

    private void fail(String message) {
        Log.e(TAG, "PROBE_FAIL " + message);
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        String flavor = getIntent().getStringExtra("core");
        if (!"sym".equals(flavor) && !"space".equals(flavor) && !"mock".equals(flavor)) {
            fail("invalid core selector");
            return;
        }
        Log.i(TAG, "START core=" + flavor + " supportedAbis=" + java.util.Arrays.toString(android.os.Build.SUPPORTED_ABIS));
        try {
            System.loadLibrary("symbiosis_kenji");
            KenjiBridge bridge = new KenjiBridge();
            // Probe files belong only to this disposable test app on the runner.
            bridge.nativeSetCrashLog(new File(getFilesDir(), "probe-crash.txt").getAbsolutePath());
            String missing = bridge.nativeLoad(getFilesDir() + "/missing-test-core.so");
            if (missing == null || missing.isEmpty() || !missing.contains("missing-test-core.so")) {
                fail("missing library did not return a useful error: " + missing);
                return;
            }
            Log.i(TAG, "MISSING_PATH_OK");
            File core = new File(getApplicationInfo().nativeLibraryDir, "libcore_" + flavor + ".so");
            Log.i(TAG, "BEFORE_LOAD " + core.getName());
            String result = bridge.nativeLoad(core.getAbsolutePath());
            if (result == null || !result.isEmpty()) {
                fail("load returned: " + result);
                return;
            }
            int count = bridge.nativeSymbolCount();
            if (count != 11) {
                fail("entry points " + count + "/11");
                return;
            }
            Log.i(TAG, "LOAD_OK symbols=" + count);
            File data = new File(getFilesDir(), "core-data");
            if (!data.mkdirs() && !data.isDirectory()) {
                fail("unable to create test data directory");
                return;
            }
            for (String name : new String[]{"Logs", "Games", "Profiles", "Keys"}) {
                File dir = new File(data, name);
                if (!dir.mkdirs() && !dir.isDirectory()) {
                    fail("unable to create " + name);
                    return;
                }
            }
            Log.i(TAG, "BEFORE_JAVA_INITIALIZE");
            result = bridge.nativeInitialize(data.getAbsolutePath());
            if (result == null || !result.isEmpty()) {
                fail("javaInitialize returned: " + result);
                return;
            }
            if ("mock".equals(flavor)) {
                // Actual production JNI functions; only the dynamically loaded
                // core is a disposable mock. No games or firmware are involved.
                long generation = bridge.nativeUiActivate();
                if (generation <= 0 || !bridge.nativeUiSetup() ||
                    !bridge.nativeUiRespond(generation, true,
                        "Привет🎮".getBytes(java.nio.charset.StandardCharsets.UTF_8)) ||
                    !bridge.nativeUiRespond(generation, false, new byte[0])) {
                    fail("mock core UI setup or response rejected");
                    return;
                }
                bridge.nativeUnload();
                if (bridge.nativeUiRespond(generation, true, new byte[0])) {
                    fail("response allowed after unload");
                    return;
                }
                Log.i(TAG, "MOCK_JNI_UI_OK");
            }
            Log.i(TAG, "PROBE_OK core=" + flavor + " javaInitialize=returned-success");
        } catch (Throwable t) {
            Log.e(TAG, "PROBE_FAIL Java exception", t);
        }
    }
}

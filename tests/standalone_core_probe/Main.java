package dev.symbiosis.standalone;

import java.io.FileWriter;
import java.io.IOException;

/** CI-only app_process entry: no APK, Activity, keys, firmware or games. */
public final class Main {
    private static native int initialize(String core, String data);

    private static void mark(String path, String line) {
        try (FileWriter writer = new FileWriter(path, true)) {
            writer.write(line + "\n");
        } catch (IOException e) {
            System.err.println("JAVA_MARKER_FAIL " + e);
        }
        System.out.println(line);
    }

    public static void main(String[] args) {
        if (args.length == 2 && "--smoke".equals(args[0])) {
            mark(args[1], "JAVA_SMOKE_OK");
            return;
        }
        if (args.length != 3) {
            System.err.println("usage: Main /path/libstandalonejni.so /path/core.so /path/data");
            System.exit(2);
        }
        String marker = args[2] + "/java-stages.txt";
        try {
            mark(marker, "JAVA_STAGE load_jni");
            System.load(args[0]);
            mark(marker, "JAVA_STAGE JNI_loaded");
            int result = initialize(args[1], args[2]);
            mark(marker, "JAVA_RESULT status=" + result);
            System.exit(result);
        } catch (Throwable t) {
            mark(marker, "JAVA_FAIL " + t);
            System.exit(6);
        }
    }
}

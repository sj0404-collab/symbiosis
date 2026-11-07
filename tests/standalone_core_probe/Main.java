package dev.symbiosis.standalone;

/** CI-only app_process entry: no APK, Activity, keys, firmware or games. */
public final class Main {
    private static native int initialize(String core, String data);

    public static void main(String[] args) {
        if (args.length != 3) {
            System.err.println("usage: Main /path/libstandalonejni.so /path/core.so /path/data");
            System.exit(2);
        }
        try {
            System.out.println("JAVA_STAGE load_jni");
            System.load(args[0]);
            System.out.println("JAVA_STAGE JNI_loaded");
            int result = initialize(args[1], args[2]);
            System.out.println("JAVA_RESULT status=" + result);
            System.exit(result);
        } catch (Throwable t) {
            System.err.println("JAVA_FAIL " + t);
            System.exit(6);
        }
    }
}

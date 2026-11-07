package org.yuzu.yuzu_emu.utils;

/** Test-only Java entry points into the production C++ bridge. */
public final class KenjiBridge {
    public native void nativeSetCrashLog(String path);
    public native String nativeLoad(String path);
    public native int nativeSymbolCount();
    public native String nativeInitialize(String dataPath);
    public native long nativeUiActivate();
    public native boolean nativeUiSetup();
    public native boolean nativeUiRespond(long generation, boolean accepted, byte[] bytes);
    public native void nativeUnload();
}

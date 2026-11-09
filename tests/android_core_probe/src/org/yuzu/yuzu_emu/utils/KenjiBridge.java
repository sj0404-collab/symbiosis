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
    public native String nativeGraphicsInit(float scale, float anisotropy, boolean fastGpu,
        boolean fastCopy, boolean macroJit, boolean macroHle, boolean shaderCache,
        boolean textureRecompression, int threading);
    public native String nativeAttachSurface(android.view.Surface surface, int width, int height);
    public native String nativeDeviceInit(int memoryMode, boolean useNce, int memoryConfig,
        int language, int region, int vsync, boolean docked, boolean ptc, boolean lowPtc,
        boolean evict, boolean internet, boolean integrity, int fsLog, String timezone,
        boolean ignoreMissingServices);
    public native long nativeWindowOf(android.view.Surface surface);
    public native void nativeSetWindowHandle(long handle);
    public native String nativeInputInit(int width, int height);
    public native int nativeConnectPad(int index);
    public native boolean nativeUpdateInput();
}

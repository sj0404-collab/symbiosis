package org.yuzu.yuzu_emu.utils;

/** Test-only Java entry points into the production C++ bridge. */
public final class KenjiBridge {
    public native String nativeLoad(String path);
    public native int nativeSymbolCount();
    public native String nativeInitialize(String dataPath);
}

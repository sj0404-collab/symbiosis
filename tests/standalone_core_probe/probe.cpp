// Standalone diagnostic only. No APK, game, keys, fake core, or core calls.
// Build for Android ARM64 (NDK) or host Linux (for regression tests).
#include <dlfcn.h>
#include <sys/auxv.h>
#include <sys/utsname.h>
#include <cstdio>

int main(int argc, char** argv) {
    if (argc != 2) {
        std::fprintf(stderr, "usage: standalone-core-probe /absolute/path/to/core.so\n");
        return 2;
    }
    // Flush each stage: core constructors or the dynamic linker can terminate.
    std::setvbuf(stdout, nullptr, _IONBF, 0);
    struct utsname info{};
    if (uname(&info) == 0) std::printf("SYSTEM kernel=%s machine=%s\n", info.release, info.machine);
    const unsigned long hw = getauxval(AT_HWCAP);
#if defined(__aarch64__)
    constexpr unsigned long need = (1ul << 1) | (1ul << 7) | (1ul << 8) | (1ul << 12);
    std::printf("ARM_HWCAP=0x%lx REQUIRED_PINNED=0x%lx MISSING=0x%lx\n", hw, need, need & ~hw);
    std::printf("ARM_FEATURES ASIMD=%d CRC32=%d ATOMICS=%d ASIMDRDM=%d\n",
                !!(hw & (1ul << 1)), !!(hw & (1ul << 7)),
                !!(hw & (1ul << 8)), !!(hw & (1ul << 12)));
#else
    // Linux x86 HWCAP bits have different semantics. Never label them ARM.
    std::printf("HOST_HWCAP=0x%lx (not ARM; no ARM capability conclusion)\n", hw);
#endif
    std::printf("STAGE dlopen path=%s\n", argv[1]);
    dlerror();
    void* handle = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
    if (!handle) {
        const char* error = dlerror();
        std::fprintf(stderr, "DLOPEN_FAIL %s\n", error ? error : "unknown loader error");
        return 3;
    }
    std::puts("DLOPEN_OK (no JNI, no device, no game initialized)");
    constexpr const char* symbols[] = {
        "javaInitialize", "deviceInitialize", "deviceLoadDescriptor",
        "deviceCloseEmulation", "deviceSignalEmulationClose", "graphicsInitialize",
        "graphicsInitializeRenderer", "graphicsRendererRunLoop", "graphicsRendererSetSize",
        "inputInitialize", "deviceGetGameFrameRate"
    };
    int missing = 0;
    for (const char* name : symbols) {
        dlerror();
        void* ptr = dlsym(handle, name);
        const char* error = dlerror();
        const bool found = ptr && !error;
        std::printf("SYMBOL %s %s\n", name, found ? "ok" : "missing");
        if (!found) ++missing;
    }
    std::printf("RESULT symbols=%zu/%zu missing=%d initialization=not-attempted\n",
                sizeof(symbols) / sizeof(symbols[0]) - missing,
                sizeof(symbols) / sizeof(symbols[0]), missing);
    // Process isolation per core: avoid making assumptions about unloading a
    // managed runtime. OS cleanup happens at process exit.
    return missing ? 4 : 0;
}

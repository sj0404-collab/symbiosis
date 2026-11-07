// Disposable Android CI fixture. Not a real core and NEVER part of a release.
#include <android/log.h>
#include <cstring>
#include <sys/auxv.h>

extern "C" {
bool javaInitialize(void*, void*) {
    // Observe the environment through the same ARM ABI as the core; never
    // intercept or alter getauxval. This is diagnostic output, not a CPU fix.
    const unsigned long hwcap = getauxval(AT_HWCAP);
    constexpr unsigned long required = (1ul << 1) | (1ul << 7) | (1ul << 8) | (1ul << 12);
    __android_log_print(ANDROID_LOG_INFO, "SymMockCore",
        "ARM_HWCAP value=0x%lx needed=0x%lx missing=0x%lx ASIMD=%d CRC32=%d ATOMICS=%d ASIMDRDM=%d",
        hwcap, required, required & ~hwcap,
        !!(hwcap & (1ul << 1)), !!(hwcap & (1ul << 7)),
        !!(hwcap & (1ul << 8)), !!(hwcap & (1ul << 12)));
    return true;
}
// These exports satisfy only the loader test; this fixture never executes them.
void deviceInitialize() {}
void deviceLoadDescriptor() {}
void deviceCloseEmulation() {}
void deviceSignalEmulationClose() {}
void graphicsInitialize() {}
void graphicsInitializeRenderer() {}
void graphicsRendererRunLoop() {}
void graphicsRendererSetSize() {}
void inputInitialize() {}
void deviceGetGameFrameRate() {}

void uiHandlerSetup() {
    __android_log_print(ANDROID_LOG_INFO, "SymMockCore", "MOCK_UI_SETUP_OK");
}
void uiHandlerSetResponse(bool accepted, const char* text) {
    const bool valid = text && ((accepted && std::strcmp(text, u8"Привет🎮") == 0) ||
                                (!accepted && std::strcmp(text, "") == 0));
    __android_log_print(valid ? ANDROID_LOG_INFO : ANDROID_LOG_ERROR, "SymMockCore",
                        "%s accepted=%d", valid ? "MOCK_UI_RESPONSE_OK" : "MOCK_UI_RESPONSE_FAIL", accepted);
}
}

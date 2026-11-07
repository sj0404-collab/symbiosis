// Disposable Android CI fixture. Not a real core and NEVER part of a release.
#include <android/log.h>
#include <cstring>

extern "C" {
bool javaInitialize(void*, void*) { return true; }
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

// CI-only linker wrapper around the public Android window API. Never shipped.
#include <android/native_window.h>

namespace {
int calls;
int lastTransform;
int lastResult;
ANativeWindow* lastWindow;
bool synthetic;
}
extern "C" int __real_ANativeWindow_setBuffersTransform(ANativeWindow*, int32_t);
extern "C" int __wrap_ANativeWindow_setBuffersTransform(ANativeWindow* window, int32_t transform) {
    ++calls;
    lastWindow = window;
    lastTransform = transform;
    lastResult = synthetic ? 0 : __real_ANativeWindow_setBuffersTransform(window, transform);
    return lastResult;
}
extern "C" __attribute__((visibility("default"))) void probeTransformReset(int fake) {
    calls = 0; lastTransform = -1; lastResult = -1; lastWindow = nullptr; synthetic = fake != 0;
}
extern "C" __attribute__((visibility("default"))) int probeTransformCalls() { return calls; }
extern "C" __attribute__((visibility("default"))) int probeTransformValue() { return lastTransform; }
extern "C" __attribute__((visibility("default"))) int probeTransformResult() { return lastResult; }
extern "C" __attribute__((visibility("default"))) ANativeWindow* probeTransformWindow() { return lastWindow; }

// Android CI-only experiment: compares the pinned SymbEK JNI binary against
// an independent mapping table. No production APK includes this test helper.
#include <jni.h>
#include <android/native_window_jni.h>
#include <android/native_window.h>
#include <android/log.h>
#include <cstdarg>
#include <cstdint>
#include <cstring>
#include <dlfcn.h>
#include <limits>

namespace {
int op_seen = -1;
int transform_seen = -1;
void* window_seen = nullptr;

int syntheticPerform(void* window, int operation, ...) {
    va_list args;
    va_start(args, operation);
    const int transform = va_arg(args, int);
    va_end(args);
    window_seen = window;
    op_seen = operation;
    transform_seen = transform;
    return 0;
}

// Independent expectation inferred from the exact pinned binary. The input
// domain is NOT assumed to be a Vulkan enum; odd inputs share even branches.
int expected(int value, bool initiallyFlipped) {
    if (value < 0) return 0;
    switch (value / 2) {
        case 2: return 4;
        case 4: return initiallyFlipped ? 0 : 3;
        case 8: return 7;
        case 16: return 1;
        case 32: return 5;
        case 64: return 2;
        case 128: return 6;
        default: return 0;
    }
}

jstring failure(JNIEnv* env, const char* message) { return env->NewStringUTF(message); }
} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_dev_symbiosis_coreprobe_GraphicsProbeActivity_nativeRun(JNIEnv* env, jclass,
                                                             jstring path, jobject surface) {
    const char* chars = env->GetStringUTFChars(path, nullptr);
    if (!chars) return failure(env, "reference path unavailable");
    void* library = dlopen(chars, RTLD_NOW | RTLD_LOCAL);
    env->ReleaseStringUTFChars(path, chars);
    if (!library) {
        __android_log_print(ANDROID_LOG_ERROR, "SymGraphicsProbe", "dlopen: %s", dlerror());
        return failure(env, "reference JNI library did not load");
    }
    using Reference = void (*)(long, int);
    auto reference = reinterpret_cast<Reference>(dlsym(library, "setCurrentTransform"));
    auto flipped = reinterpret_cast<unsigned char*>(dlsym(library, "isInitialOrientationFlipped"));
    if (!reference || !flipped || *flipped != 1) {
        dlclose(library);
        return failure(env, "reference symbols or default orientation differ");
    }
    // Test-only fake: the pinned binary reads its perform pointer at offset
    // 0x98. It is never handed to Android and is not a real ANativeWindow.
    alignas(16) unsigned char fake[0xa0] = {};
    using Perform = int (*)(void*, int, ...);
    Perform perform = syntheticPerform;
    static_assert(sizeof(perform) == 8, "arm64 function pointers required");
    std::memcpy(fake + 0x98, &perform, sizeof(perform));
    int cases = 0;
    for (int orientation = 0; orientation <= 1; ++orientation) {
        *flipped = static_cast<unsigned char>(orientation);
        for (int i = -3; i <= 513; ++i) {
            op_seen = -1; transform_seen = -1; window_seen = nullptr;
            reference(reinterpret_cast<long>(fake), i);
            if (op_seen != 6 || window_seen != fake || transform_seen != expected(i, orientation != 0)) {
                __android_log_print(ANDROID_LOG_ERROR, "SymGraphicsProbe",
                    "mismatch value=%d flip=%d op=%d got=%d expected=%d", i, orientation,
                    op_seen, transform_seen, expected(i, orientation != 0));
                dlclose(library);
                return failure(env, "reference transform mapping mismatch");
            }
            ++cases;
        }
    }
    // The exact binary ignores null and sentinel -1 without dereferencing.
    op_seen = -1;
    reference(0, 4);
    reference(-1, 4);
    if (op_seen != -1) { dlclose(library); return failure(env, "invalid pointer handling differed"); }
    dlclose(library);

    ANativeWindow* nativeWindow = ANativeWindow_fromSurface(env, surface);
    if (!nativeWindow) return failure(env, "test surface unavailable");
    for (int transform = 0; transform < 8; ++transform) {
        int rc = ANativeWindow_setBuffersTransform(nativeWindow, transform);
        if (rc != 0) {
            __android_log_print(ANDROID_LOG_ERROR, "SymGraphicsProbe",
                "public API rejected transform=%d rc=%d", transform, rc);
            ANativeWindow_release(nativeWindow);
            return failure(env, "public API rejected a transform on test surface");
        }
    }
    ANativeWindow_setBuffersTransform(nativeWindow, 0);
    ANativeWindow_release(nativeWindow);
    __android_log_print(ANDROID_LOG_INFO, "SymGraphicsProbe",
                        "GRAPHICS_REFERENCE_OK cases=%d public_surface=8", cases);
    return env->NewStringUTF("");
}

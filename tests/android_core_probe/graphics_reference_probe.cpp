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
#include <pthread.h>
#include <time.h>
#include <string>
#include "kenji_transform_map.h"

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


struct ThreadProbe {
    void (*reference)();
    void (*candidate)();
    pthread_t* referenceId;
    pthread_t* candidateId;
    std::int64_t* referenceTime;
    std::int64_t* candidateTime;
    bool ok;
};

std::int64_t monotonicNanos() {
    timespec ts{};
    if (clock_gettime(CLOCK_MONOTONIC, &ts) != 0) return -1;
    return std::int64_t(ts.tv_sec) * 1000000000LL + ts.tv_nsec;
}

// Run on two genuinely different pthreads: the activity's and a new worker.
// Compare identities and monotonic timestamps, not numeric thread IDs to tids.
void* exerciseThreadState(void* input) {
    auto* p = static_cast<ThreadProbe*>(input);
    const pthread_t expected = pthread_self();
    const auto start = monotonicNanos();
    p->reference();
    const auto between = monotonicNanos();
    p->candidate();
    const auto end = monotonicNanos();
    p->ok = start > 0 && start <= between && between <= end &&
        pthread_equal(*p->referenceId, expected) && pthread_equal(*p->candidateId, expected) &&
        *p->referenceTime >= start && *p->referenceTime <= between &&
        *p->candidateTime >= between && *p->candidateTime <= end;
    return nullptr;
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
    // Compare actual reference state storage with the actual production JNI
    // bridge. Both are local distinct libraries in a disposable test process.
    const char* pathChars = env->GetStringUTFChars(path, nullptr);
    if (!pathChars) { dlclose(library); return failure(env, "candidate path unavailable"); }
    std::string candidatePath(pathChars);
    env->ReleaseStringUTFChars(path, pathChars);
    const auto slash = candidatePath.find_last_of('/');
    if (slash == std::string::npos) { dlclose(library); return failure(env, "candidate directory unavailable"); }
    candidatePath = candidatePath.substr(0, slash + 1) + "libkenjinxjni.so";
    void* candidate = dlopen(candidatePath.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (!candidate) {
        __android_log_print(ANDROID_LOG_ERROR, "SymGraphicsProbe", "candidate dlopen: %s", dlerror());
        dlclose(library);
        return failure(env, "candidate JNI did not load");
    }
    auto getFn = [](void* h) { return reinterpret_cast<void (*)()>(dlsym(h, "setRenderingThread")); };
    auto getId = [](void* h) { return reinterpret_cast<pthread_t*>(dlsym(h, "_renderingThreadId")); };
    auto getTime = [](void* h) { return reinterpret_cast<std::int64_t*>(dlsym(h, "_currentTimePoint")); };
    ThreadProbe threadProbe{getFn(library), getFn(candidate), getId(library), getId(candidate),
                            getTime(library), getTime(candidate), false};
    if (!threadProbe.reference || !threadProbe.candidate || !threadProbe.referenceId ||
        !threadProbe.candidateId || !threadProbe.referenceTime || !threadProbe.candidateTime ||
        threadProbe.referenceId == threadProbe.candidateId || threadProbe.referenceTime == threadProbe.candidateTime) {
        dlclose(candidate); dlclose(library);
        return failure(env, "thread callback symbols missing or interposed");
    }
    exerciseThreadState(&threadProbe);
    if (!threadProbe.ok) { dlclose(candidate); dlclose(library); return failure(env, "render thread state differs on activity thread"); }
    pthread_t worker;
    if (pthread_create(&worker, nullptr, exerciseThreadState, &threadProbe) != 0) {
        dlclose(candidate); dlclose(library); return failure(env, "render worker did not start");
    }
    pthread_join(worker, nullptr);
    if (!threadProbe.ok) { dlclose(candidate); dlclose(library); return failure(env, "render thread state differs on worker thread"); }
    __android_log_print(ANDROID_LOG_INFO, "SymGraphicsProbe", "RENDER_THREAD_REFERENCE_OK threads=2");
    auto productionTransform = reinterpret_cast<Reference>(dlsym(candidate, "setCurrentTransform"));
    std::string wrappedPath = candidatePath.substr(0, candidatePath.find_last_of('/') + 1) + "libtransform_wrapped_test.so";
    void* wrapped = dlopen(wrappedPath.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (!wrapped || !productionTransform) {
        if (wrapped) dlclose(wrapped);
        dlclose(candidate); dlclose(library); return failure(env, "transform bridge unavailable");
    }
    auto wrappedTransform = reinterpret_cast<Reference>(dlsym(wrapped, "setCurrentTransform"));
    auto reset = reinterpret_cast<void (*)(int)>(dlsym(wrapped, "probeTransformReset"));
    auto calls = reinterpret_cast<int (*)()>(dlsym(wrapped, "probeTransformCalls"));
    auto value = reinterpret_cast<int (*)()>(dlsym(wrapped, "probeTransformValue"));
    auto result = reinterpret_cast<int (*)()>(dlsym(wrapped, "probeTransformResult"));
    auto window = reinterpret_cast<ANativeWindow* (*)()>(dlsym(wrapped, "probeTransformWindow"));
    if (!wrappedTransform || !reset || !calls || !value || !result || !window) {
        dlclose(wrapped); dlclose(candidate); dlclose(library);
        return failure(env, "transform wrapper symbols unavailable");
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
            const int mappedCandidate = Symbiosis::SymbekTransformCandidate(i, orientation != 0);
            if (op_seen != 6 || window_seen != fake || transform_seen != expected(i, orientation != 0) ||
                transform_seen != mappedCandidate) {
                __android_log_print(ANDROID_LOG_ERROR, "SymGraphicsProbe",
                    "mismatch value=%d flip=%d op=%d got=%d expected=%d", i, orientation,
                    op_seen, transform_seen, expected(i, orientation != 0));
                dlclose(library);
                return failure(env, "reference transform mapping mismatch");
            }
            if (orientation == 1) {
                // Link-time wrapper intercepts the public API: compare the
                // actual bridge's call, not just a duplicate mapping table.
                reset(1);
                wrappedTransform(reinterpret_cast<long>(fake), i);
                if (calls() != 1 || window() != reinterpret_cast<ANativeWindow*>(fake) ||
                    value() != transform_seen || result() != 0) {
                    dlclose(wrapped); dlclose(candidate); dlclose(library);
                    return failure(env, "production callback differs from reference mapping");
                }
            }
            ++cases;
        }
    }
    // The exact binary ignores null and sentinel -1 without dereferencing.
    op_seen = -1;
    reference(0, 4);
    reference(-1, 4);
    if (op_seen != -1) { dlclose(library); return failure(env, "invalid pointer handling differed"); }
    reset(1); wrappedTransform(0, 4); wrappedTransform(-1, 4);
    if (calls() != 0) { dlclose(wrapped); dlclose(candidate); dlclose(library); return failure(env, "invalid bridge pointer handling differed"); }
    dlclose(library);

    ANativeWindow* nativeWindow = ANativeWindow_fromSurface(env, surface);
    if (!nativeWindow) return failure(env, "test surface unavailable");
    // Invoke actual bridge (both normal and observationally wrapped builds)
    // on the genuine Android Surface. Wrapper forwards to the public API and
    // records its return, so success is not a fake API response.
    const int inputs[] = {0, 4, 8, 16, 32, 64, 128, 256};
    for (int input : inputs) {
        reset(0);
        wrappedTransform(reinterpret_cast<long>(nativeWindow), input);
        if (calls() != 1 || window() != nativeWindow || value() != expected(input, true) || result() != 0) {
            ANativeWindow_release(nativeWindow); dlclose(wrapped); dlclose(candidate);
            return failure(env, "production transform rejected on Android surface");
        }
        productionTransform(reinterpret_cast<long>(nativeWindow), input);
    }
    __android_log_print(ANDROID_LOG_INFO, "SymGraphicsProbe", "PRODUCTION_TRANSFORM_REFERENCE_OK fake_cases=517 real_surface=8");
    dlclose(wrapped); dlclose(candidate);
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

// This pointer comes directly from ANativeWindow_fromSurface in the bridge.
// A positive width proves that Android can actually use that returned pointer,
// including on ARM64 when its top byte makes the corresponding jlong negative.
extern "C" JNIEXPORT jint JNICALL
Java_dev_symbiosis_coreprobe_EngineStageActivity_nativeWindowWidth(JNIEnv*, jclass, jlong handle) {
    if (handle == 0 || handle == -1) return -1;
    return ANativeWindow_getWidth(reinterpret_cast<ANativeWindow*>(handle));
}

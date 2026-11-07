// Test-only JNI entry for a fresh Android app_process. No bridge interception,
// no altered CPU features, no games or keys. Core may abort; caller isolates it.
#include <jni.h>
#include <dlfcn.h>
#include <sys/auxv.h>
#include <cstdio>

extern "C" JNIEXPORT jint JNICALL
Java_dev_symbiosis_standalone_Main_initialize(JNIEnv* env, jclass, jstring core_path, jstring data_path) {
    std::setvbuf(stdout, nullptr, _IONBF, 0);
    const char* core = env->GetStringUTFChars(core_path, nullptr);
    const char* data = env->GetStringUTFChars(data_path, nullptr);
    if (!core || !data) {
        if (core) env->ReleaseStringUTFChars(core_path, core);
        if (data) env->ReleaseStringUTFChars(data_path, data);
        std::puts("JNI_FAIL cannot read paths");
        return 2;
    }
    std::printf("JNI_ARM_HWCAP=0x%lx\n", getauxval(AT_HWCAP));
    std::printf("JNI_STAGE dlopen path=%s\n", core);
    void* handle = dlopen(core, RTLD_NOW | RTLD_LOCAL);
    if (!handle) {
        const char* error = dlerror();
        std::fprintf(stderr, "JNI_DLOPEN_FAIL %s\n", error ? error : "unknown");
        env->ReleaseStringUTFChars(core_path, core);
        env->ReleaseStringUTFChars(data_path, data);
        return 3;
    }
    dlerror();
    using Initialize = bool (*)(void*, void*);
    auto initialize = reinterpret_cast<Initialize>(dlsym(handle, "javaInitialize"));
    const char* error = dlerror();
    if (!initialize || error) {
        std::fprintf(stderr, "JNI_SYMBOL_FAIL javaInitialize: %s\n", error ? error : "null symbol");
        env->ReleaseStringUTFChars(core_path, core);
        env->ReleaseStringUTFChars(data_path, data);
        return 4;
    }
    std::printf("JNI_STAGE BEFORE_JAVA_INITIALIZE path=%s\n", data);
    const bool ok = initialize(const_cast<char*>(data), env);
    std::printf("JNI_RESULT javaInitialize=%s\n", ok ? "true" : "false");
    env->ReleaseStringUTFChars(core_path, core);
    env->ReleaseStringUTFChars(data_path, data);
    // No dlclose: one isolated process per invocation, the core owns state.
    return ok ? 0 : 5;
}

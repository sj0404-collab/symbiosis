// Test-only JNI entry for a fresh Android app_process. No bridge interception,
// no altered CPU features, no games or keys. Core may abort; caller isolates it.
#include <jni.h>
#include <dlfcn.h>
#include <sys/auxv.h>
#include <cstdio>
#include <string>

extern "C" JNIEXPORT jint JNICALL
Java_dev_symbiosis_standalone_Main_initialize(JNIEnv* env, jclass, jstring core_path, jstring data_path, jboolean preload_tls) {
    std::setvbuf(stdout, nullptr, _IONBF, 0);
    const char* core = env->GetStringUTFChars(core_path, nullptr);
    const char* data = env->GetStringUTFChars(data_path, nullptr);
    if (!core || !data) {
        if (core) env->ReleaseStringUTFChars(core_path, core);
        if (data) env->ReleaseStringUTFChars(data_path, data);
        std::puts("JNI_FAIL cannot read paths");
        return 2;
    }
    const std::string marker_path = std::string(data) + "/jni-stages.txt";
    FILE* marker = std::fopen(marker_path.c_str(), "a");
    if (!marker) {
        std::fprintf(stderr, "JNI_FAIL cannot open marker\n");
        env->ReleaseStringUTFChars(core_path, core);
        env->ReleaseStringUTFChars(data_path, data);
        return 2;
    }
#define MARK(...) do { std::printf(__VA_ARGS__); std::fprintf(marker, __VA_ARGS__); std::fflush(marker); } while (0)
    MARK("JNI_ARM_HWCAP=0x%lx\n", getauxval(AT_HWCAP));
    void* tls_crypto = nullptr;
    void* tls_ssl = nullptr;
    if (preload_tls) {
        // Same public loader operations and same exact bundled files as the
        // production bridge, in this separate diagnostic process only.
        // Never modify, fake, or replace an OpenSSL response.
        const std::string path(core);
        const size_t slash = path.find_last_of('/');
        const std::string dir = slash == std::string::npos ? "." : path.substr(0, slash);
        for (const char* name : {"libcrypto.so", "libssl.so"}) {
            std::string absolute = dir + "/" + name;
            dlerror();
            void* tls = dlopen(absolute.c_str(), RTLD_NOW | RTLD_GLOBAL);
            const char* error = dlerror();
            MARK("TLS_PRELOAD %s %s%s%s\n", name, tls ? "ok" : "fail",
                 !tls && error ? ": " : "", !tls && error ? error : "");
            if (tls) {
                if (name[3] == 'c') tls_crypto = tls;
                else tls_ssl = tls;
                const char* probe = name[3] == 'c' ? "OpenSSL_version_num" : "SSL_CTX_new";
                dlerror();
                void* symbol = dlsym(tls, probe);
                const char* symerror = dlerror();
                Dl_info origin{};
                const char* image = symbol && dladdr(symbol, &origin) && origin.dli_fname ? origin.dli_fname : "unknown";
                MARK("TLS_SYMBOL %s %s origin=%s\n", probe, symbol && !symerror ? "ok" : "missing", image);
            }
        }
    } else {
        MARK("TLS_PRELOAD not-attempted\n");
    }
    MARK("JNI_STAGE dlopen path=%s\n", core);
    void* handle = dlopen(core, RTLD_NOW | RTLD_LOCAL);
    if (!handle) {
        const char* error = dlerror();
        MARK("JNI_DLOPEN_FAIL %s\n", error ? error : "unknown");
        std::fclose(marker);
        env->ReleaseStringUTFChars(core_path, core);
        env->ReleaseStringUTFChars(data_path, data);
        return 3;
    }
    dlerror();
    using Initialize = bool (*)(void*, void*);
    auto initialize = reinterpret_cast<Initialize>(dlsym(handle, "javaInitialize"));
    const char* error = dlerror();
    if (!initialize || error) {
        MARK("JNI_SYMBOL_FAIL javaInitialize: %s\n", error ? error : "null symbol");
        std::fclose(marker);
        env->ReleaseStringUTFChars(core_path, core);
        env->ReleaseStringUTFChars(data_path, data);
        return 4;
    }
    MARK("JNI_STAGE BEFORE_JAVA_INITIALIZE path=%s\n", data);
    const bool ok = initialize(const_cast<char*>(data), env);
    MARK("JNI_RESULT javaInitialize=%s\n", ok ? "true" : "false");
    bool tls_ok = !preload_tls;
    if (ok && preload_tls && tls_crypto && tls_ssl) {
        // Genuine no-network TLS context after javaInitialize, not a fake
        // cryptographic result. This does not prove a complete handshake.
        using VersionFn = unsigned long (*)();
        using MethodFn = const void* (*)();
        using NewFn = void* (*)(const void*);
        using FreeFn = void (*)(void*);
        auto version = reinterpret_cast<VersionFn>(dlsym(tls_crypto, "OpenSSL_version_num"));
        auto method = reinterpret_cast<MethodFn>(dlsym(tls_ssl, "TLS_method"));
        auto make_context = reinterpret_cast<NewFn>(dlsym(tls_ssl, "SSL_CTX_new"));
        auto free_context = reinterpret_cast<FreeFn>(dlsym(tls_ssl, "SSL_CTX_free"));
        if (version) MARK("TLS_OPENSSL_VERSION=0x%lx\n", version());
        if (version && method && make_context && free_context) {
            const void* protocol = method();
            void* context = protocol ? make_context(protocol) : nullptr;
            if (context) {
                free_context(context);
                tls_ok = true;
            }
        }
        MARK("TLS_CONTEXT_SMOKE=%s\n", tls_ok ? "ok" : "fail");
    } else if (preload_tls && ok) {
        MARK("TLS_CONTEXT_SMOKE=fail (preload unavailable)\n");
    }
    std::fclose(marker);
    env->ReleaseStringUTFChars(core_path, core);
    env->ReleaseStringUTFChars(data_path, data);
    // No dlclose: one isolated process per invocation, the core owns state.
    return !ok ? 5 : (tls_ok ? 0 : 8);
#undef MARK
}

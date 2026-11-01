// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later
//
// A bridge to the Kenji-NX core, built so a failure inside it cannot take the
// app down with it.
//
// WHAT THE CORE ACTUALLY IS, read out of the built library rather than from
// its source tree:
//
//   * 60 exported functions, all flat C, all named in camelCase -
//     deviceInitialize, graphicsInitialize, deviceLoadDescriptor and so on.
//     The C# source also carries snake_case names (device_initialize) on a
//     second set of attributes; THOSE ARE NOT IN THE BUILD. Wiring dlsym to
//     them would return null for every single call.
//   * NEEDED: liblog, libdl, libz, libm, libc. Nothing else - no .NET runtime
//     to ship, no Mono, no extra payload.
//   * 249 undefined symbols, every one of them ordinary libc/libm/libz.
//
// THE PART THAT WOULD HAVE CRASHED
//   The core calls back OUT through three DllImport("libkenjinxjni") symbols:
//
//       void setRenderingThread();
//       void debug_break(int code);
//       void setCurrentTransform(long native_window, int transform);
//
//   debug_break is called from deviceInitialize itself. NativeAOT resolves a
//   DllImport by dlopen()ing the named library at first use, so if no
//   libkenjinxjni exists the very first device call aborts the process. They
//   are not optional and they are not weak - checked in the binary: all three
//   names are present as import strings.
//
//   So this file IS libkenjinxjni. It provides those three symbols and nothing
//   else needs to exist for the core to initialise.
//
// HOW A CRASH IS CONTAINED
//   A SIGSEGV in a shared library kills the whole process; there is no
//   try/catch for that in either Kotlin or C++. The only real containment on
//   Android is a separate process, so the emulation side runs in one (see
//   android:process in the manifest patch). This file adds the second layer:
//   every entry point is guarded, the core is probed before it is trusted, and
//   a fault is reported as a message rather than a tombstone.

#include <jni.h>
#include <android/native_window_jni.h>
#include <fcntl.h>
#include <unistd.h>
#include <dlfcn.h>
#include <android/log.h>
#include <csetjmp>
#include <csignal>
#include <cstring>
#include <unwind.h>
#include <link.h>
#include <string>
#include <mutex>
#include <atomic>

#define LOG_TAG "SymbiosisKenji"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// ── what the core imports from us ───────────────────────────────────
//
// Defined before anything else, because the core reaches for them during its
// own initialisation. Empty bodies are correct: on the Kenji build these hook
// a debugger and a window transform, neither of which applies here. What
// matters is that dlsym finds them instead of failing.

std::mutex g_lock;
void* g_core = nullptr;          // handle of the loaded core
std::string g_lastError;

// Set when the core is inside a call, so a fault can be blamed on the right
// thing rather than on the app.
volatile sig_atomic_t g_inCore = 0;

// ── отчёт о падении ─────────────────────────────────────────────────
//
// Процесс :kenji умирает от сигнала, и приложение узнаёт только «связь
// потеряна». Ни номера сигнала, ни кадра стека Android наружу не отдаёт.
// Поэтому отчёт пишется ДО смерти процесса в файл, который переживает его:
//
//   step=<шаг>              — пишет Kotlin перед каждым вызовом в ядро
//   crash signal=… addr=…   — пишет обработчик сигнала
//   pc=<библиотека>+0x…     — кадры стека, сверху вниз
//
// Всё это делается примитивами, разрешёнными в обработчике сигнала: open,
// write, close. Никаких std::string и malloc — после SIGSEGV куча может быть
// уже испорчена, и тогда обработчик упадёт вместо записи.

char g_crashPath[512] = {0};
char g_step[96] = "не указан";
std::atomic<int> g_inHandler{0};

const char* SignalName(int sig) {
    switch (sig) {
    case SIGSEGV: return "SIGSEGV";
    case SIGBUS:  return "SIGBUS";
    case SIGILL:  return "SIGILL";
    case SIGFPE:  return "SIGFPE";
    case SIGABRT: return "SIGABRT";
    default:      return "signal";
    }
}

int OpenCrashFile() {
    if (g_crashPath[0] == '\0') return -1;
    return ::open(g_crashPath, O_WRONLY | O_CREAT | O_APPEND, 0600);
}

/** Дописать строку в отчёт. Безопасно вызывать когда файл не задан. */
void AppendCrashLine(const char* line) {
    const int fd = OpenCrashFile();
    if (fd < 0) return;
    ::write(fd, line, ::strlen(line));
    ::close(fd);
}

struct UnwindState {
    int fd;
    int depth;
};

_Unwind_Reason_Code UnwindCallback(_Unwind_Context* ctx, void* arg) {
    auto* state = static_cast<UnwindState*>(arg);
    const uintptr_t pc = _Unwind_GetIP(ctx);
    if (pc != 0) {
        Dl_info info{};
        char line[320];
        const int n = ::dladdr(reinterpret_cast<void*>(pc - 1), &info) != 0 && info.dli_fname
                          ? ::snprintf(line, sizeof(line), "pc=%s+0x%lx\n",
                                       ::strrchr(info.dli_fname, '/') ? ::strrchr(info.dli_fname, '/') + 1
                                                                     : info.dli_fname,
                                       static_cast<unsigned long>(pc -
                                           reinterpret_cast<uintptr_t>(info.dli_fbase)))
                          : ::snprintf(line, sizeof(line), "pc=0x%lx\n",
                                       static_cast<unsigned long>(pc));
        if (n > 0) ::write(state->fd, line, static_cast<size_t>(n));
    }
    return ++state->depth < 12 ? _URC_NO_REASON : _URC_END_OF_STACK;
}

void CrashHandler(int sig, siginfo_t* info, void*) {
    // Обработчик не должен вызываться вложенно: второе падение внутри него
    // оставило бы процесс в неопределённом состоянии вместо честной смерти.
    if (g_inHandler.exchange(1) != 0) {
        ::signal(sig, SIG_DFL);
        ::raise(sig);
        return;
    }

    const int fd = OpenCrashFile();
    if (fd >= 0) {
        // Шаг отдельной строкой: файл читают и глазами, а разбор в Kotlin
        // берёт его и отсюда, и из строки crash - если одна строка не дошла
        // (процесс убили посреди записи), шаг всё равно известен.
        char stepLine[128];
        const int sn = ::snprintf(stepLine, sizeof(stepLine), "step=%s\n", g_step);
        if (sn > 0) ::write(fd, stepLine, static_cast<size_t>(sn));

        char head[320];
        const int n = ::snprintf(head, sizeof(head),
                                 "crash signal=%d name=%s addr=%p code=%d step=%s\n", sig,
                                 SignalName(sig), info ? info->si_addr : nullptr,
                                 info ? info->si_code : 0, g_step);
        if (n > 0) ::write(fd, head, static_cast<size_t>(n));
        UnwindState state{fd, 0};
        _Unwind_Backtrace(UnwindCallback, &state);
        ::close(fd);
    }

    // Отчёт записан - отдаём сигнал системе, чтобы она сделала свой tombstone.
    // Глотать сигнал нельзя: приложение продолжило бы работу с испорченной
    // памятью ядра и упало бы позже и непонятнее.
    ::signal(sig, SIG_DFL);
    ::raise(sig);
}

/** Сменить текущий шаг отчёта. Строка копируется, владение не передаётся. */
void NoteStep(const char* name) {
    if (name == nullptr) return;
    ::strncpy(g_step, name, sizeof(g_step) - 1);
    g_step[sizeof(g_step) - 1] = '\0';
}

void InstallCrashHandlers() {
    struct sigaction action {};
    action.sa_sigaction = CrashHandler;
    action.sa_flags = SA_SIGINFO | SA_ONSTACK;
    ::sigemptyset(&action.sa_mask);
    for (int sig : {SIGSEGV, SIGBUS, SIGILL, SIGFPE, SIGABRT}) {
        struct sigaction old {};
        ::sigaction(sig, &action, &old);
    }
}

} // namespace

extern "C" {

// Called by the core on its render thread. Kenji uses it to mark the thread
// for its own bookkeeping; there is nothing to do here, but the symbol must
// exist or deviceInitialize aborts.
JNIEXPORT void setRenderingThread() {
    // Intentionally empty - see the note above.
}

// Called by the core at points it considers worth breaking on, including from
// deviceInitialize. Logging it turns an invisible abort into a breadcrumb.
JNIEXPORT void debug_break(int code) {
    LOGI("core debug_break(%d)", code);
    // То же самое - в отчёт о падении. Если ядро зовёт debug_break и следом
    // умирает, код остаётся в файле: «упало после debug_break(1)» говорит
    // гораздо больше, чем «процесс завершился».
    char line[48];
    const int n = ::snprintf(line, sizeof(line), "debug_break=%d\n", code);
    if (n > 0) AppendCrashLine(line);
}

// Window rotation. Without a surface attached there is nothing to rotate, but
// again: the symbol has to resolve.
JNIEXPORT void setCurrentTransform(long native_window, int transform) {
    (void)native_window;
    (void)transform;
}

} // extern "C"

namespace {

// ── loading, carefully ──────────────────────────────────────────────

// Every exported name the core is known to have, as read from its dynamic
// symbol table. Used to verify a downloaded core is the real thing before a
// single call is made into it - a truncated or substituted file would
// otherwise fail at the first call, deep inside, with no useful message.
const char* kRequiredSymbols[] = {
    "javaInitialize",
    "deviceInitialize",
    "deviceLoadDescriptor",
    "deviceCloseEmulation",
    "deviceSignalEmulationClose",
    "graphicsInitialize",
    "graphicsInitializeRenderer",
    "graphicsRendererRunLoop",
    "graphicsRendererSetSize",
    "inputInitialize",
    "deviceGetGameFrameRate",
};

bool hasAll(void* handle, std::string& missing) {
    for (const char* name : kRequiredSymbols) {
        dlerror();
        void* sym = dlsym(handle, name);
        if (sym == nullptr) {
            missing = name;
            return false;
        }
    }
    return true;
}

} // namespace

extern "C" {

/**
 * Запомнить шаг, который сейчас выполняется.
 *
 * Пишется ДО вызова в ядро, а не после: если оно упадёт внутри, обработчик
 * сигнала возьмёт именно это имя, и отчёт скажет «шаг javaInitialize», а не
 * «неизвестно где». Kotlin может звать это сам, но надёжнее ставить метку
 * здесь: тогда её не забудут в новом вызове.
 */
JNIEXPORT void JNICALL
Java_org_yuzu_yuzu_1emu_utils_KenjiBridge_nativeSetStep(JNIEnv* env, jobject, jstring jstep) {
    if (jstep == nullptr) return;
    const char* step = env->GetStringUTFChars(jstep, nullptr);
    if (step == nullptr) return;
    NoteStep(step);
    env->ReleaseStringUTFChars(jstep, step);
}

/**
 * Включить отчёт о падении: файл, обработчики сигналов, обнуление старого.
 *
 * Старый отчёт стирается намеренно. Файл переживает смерть процесса, поэтому
 * без стирания вчерашнее падение выдавалось бы за сегодняшнее - и человек
 * чинил бы то, что уже починено.
 */
JNIEXPORT void JNICALL
Java_org_yuzu_yuzu_1emu_utils_KenjiBridge_nativeSetCrashLog(JNIEnv* env, jobject, jstring jpath) {
    if (jpath == nullptr) return;
    const char* path = env->GetStringUTFChars(jpath, nullptr);
    if (path == nullptr) return;
    ::strncpy(g_crashPath, path, sizeof(g_crashPath) - 1);
    g_crashPath[sizeof(g_crashPath) - 1] = '\0';
    env->ReleaseStringUTFChars(jpath, path);

    const int fd = ::open(g_crashPath, O_WRONLY | O_CREAT | O_TRUNC, 0600);
    if (fd >= 0) ::close(fd);
    InstallCrashHandlers();
    LOGI("crash report armed: %s", g_crashPath);
}

/**
 * Load the core and verify it before letting anything call into it.
 *
 * Returns an empty string on success, or a sentence fit to show a person.
 */
JNIEXPORT jstring JNICALL
Java_org_yuzu_yuzu_1emu_utils_KenjiBridge_nativeLoad(JNIEnv* env, jobject, jstring jpath) {
    NoteStep("dlopen");
    std::lock_guard<std::mutex> guard(g_lock);

    if (g_core != nullptr) {
        return env->NewStringUTF("");   // already up
    }

    const char* path = env->GetStringUTFChars(jpath, nullptr);
    if (path == nullptr) {
        return env->NewStringUTF("путь к ядру пуст");
    }

    // RTLD_NOW, not RTLD_LAZY. Lazy binding defers the failure to the first
    // call of a missing symbol, which surfaces as a crash somewhere unrelated;
    // resolving everything now turns the same problem into a message here.
    dlerror();
    void* handle = dlopen(path, RTLD_NOW | RTLD_LOCAL);
    std::string err = handle ? "" : (dlerror() ? dlerror() : "dlopen failed");
    env->ReleaseStringUTFChars(jpath, path);

    if (handle == nullptr) {
        // The two failures worth telling apart, because the fix differs.
        std::string msg;
        if (err.find("writable") != std::string::npos) {
            msg = "Android отклонил ядро: файл доступен для записи";
        } else if (err.find("32-bit") != std::string::npos ||
                   err.find("64-bit") != std::string::npos) {
            msg = "ядро собрано под другую архитектуру";
        } else {
            msg = "не удалось открыть ядро: " + err;
        }
        LOGE("%s", msg.c_str());
        g_lastError = msg;
        return env->NewStringUTF(msg.c_str());
    }

    std::string missing;
    if (!hasAll(handle, missing)) {
        // A file that opens but has the wrong contents. Closing it here means
        // no later call can reach a half-valid library.
        dlclose(handle);
        std::string msg = "ядро неполное: нет функции " + missing;
        LOGE("%s", msg.c_str());
        g_lastError = msg;
        return env->NewStringUTF(msg.c_str());
    }

    g_core = handle;
    LOGI("core loaded and verified");
    return env->NewStringUTF("");
}

/** How many of the known entry points the loaded core exposes. */
JNIEXPORT jint JNICALL
Java_org_yuzu_yuzu_1emu_utils_KenjiBridge_nativeSymbolCount(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> guard(g_lock);
    if (g_core == nullptr) return 0;
    int n = 0;
    for (const char* name : kRequiredSymbols) {
        if (dlsym(g_core, name) != nullptr) n++;
    }
    return n;
}

JNIEXPORT jboolean JNICALL
Java_org_yuzu_yuzu_1emu_utils_KenjiBridge_nativeIsLoaded(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> guard(g_lock);
    return g_core != nullptr ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_org_yuzu_yuzu_1emu_utils_KenjiBridge_nativeLastError(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> guard(g_lock);
    return env->NewStringUTF(g_lastError.c_str());
}

/**
 * Hand the core its JNI environment and data path.
 *
 * This is the first real call into the core, and the one most likely to fail:
 * it is where debug_break is reached and where a mismatched core aborts. It is
 * kept separate from loading so the interface can report "loaded, but would
 * not start" rather than one vague failure covering both.
 */
JNIEXPORT jstring JNICALL
Java_org_yuzu_yuzu_1emu_utils_KenjiBridge_nativeInitialize(JNIEnv* env, jobject,
                                                           jstring jdataPath) {
    NoteStep("javaInitialize");
    std::lock_guard<std::mutex> guard(g_lock);
    if (g_core == nullptr) {
        return env->NewStringUTF("ядро не загружено");
    }

    using JavaInitFn = bool (*)(void*, void*);
    auto fn = reinterpret_cast<JavaInitFn>(dlsym(g_core, "javaInitialize"));
    if (fn == nullptr) {
        return env->NewStringUTF("в ядре нет javaInitialize");
    }

    const char* dataPath = env->GetStringUTFChars(jdataPath, nullptr);
    if (dataPath == nullptr) {
        return env->NewStringUTF("путь к данным пуст");
    }

    // Official Kenji: javaInitialize(path, JNIEnv*). Passing JavaVM* here
    // made Interop.GetVirtualMachine dereference garbage and killed :kenji.
    g_inCore = 1;
    bool ok = fn(const_cast<char*>(dataPath), env);
    g_inCore = 0;

    env->ReleaseStringUTFChars(jdataPath, dataPath);

    if (!ok) {
        g_lastError = "ядро отказалось инициализироваться";
        LOGE("%s", g_lastError.c_str());
        return env->NewStringUTF(g_lastError.c_str());
    }
    LOGI("core initialised");
    return env->NewStringUTF("");
}

/** Release the core. Safe to call when nothing is loaded. */
JNIEXPORT void JNICALL
Java_org_yuzu_yuzu_1emu_utils_KenjiBridge_nativeUnload(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> guard(g_lock);
    if (g_core != nullptr) {
        dlclose(g_core);
        g_core = nullptr;
        LOGI("core unloaded");
    }
}


JNIEXPORT jstring JNICALL
Java_org_yuzu_yuzu_1emu_utils_KenjiBridge_nativeDeviceInit(
        JNIEnv* env, jobject, jint memoryManagerMode, jboolean useNce,
        jint memoryConfiguration, jint systemLanguage, jint regionCode,
        jint vSyncMode, jboolean enableDockedMode, jboolean enablePtc,
        jboolean enableLowPowerPtc, jboolean enableJitCacheEviction,
        jboolean enableInternetAccess, jboolean enableFsIntegrityChecks,
        jint fsGlobalAccessLogMode, jstring jtimeZone,
        jboolean ignoreMissingServices) {
    std::lock_guard<std::mutex> guard(g_lock);
    NoteStep("deviceInitialize");
    if (g_core == nullptr) return env->NewStringUTF("ядро не загружено");
    using Fn = bool (*)(int, bool, int, int, int, int,
                        bool, bool, bool, bool, bool, bool,
                        int, const char*, bool);
    auto fn = reinterpret_cast<Fn>(dlsym(g_core, "deviceInitialize"));
    if (fn == nullptr) return env->NewStringUTF("нет deviceInitialize");
    const char* tz = jtimeZone ? env->GetStringUTFChars(jtimeZone, nullptr) : "UTC";
    g_inCore = 1;
    bool ok = fn(memoryManagerMode, useNce, memoryConfiguration, systemLanguage,
                 regionCode, vSyncMode, enableDockedMode, enablePtc,
                 enableLowPowerPtc, enableJitCacheEviction, enableInternetAccess,
                 enableFsIntegrityChecks, fsGlobalAccessLogMode,
                 tz ? tz : "UTC", ignoreMissingServices);
    g_inCore = 0;
    if (jtimeZone && tz) env->ReleaseStringUTFChars(jtimeZone, tz);
    if (!ok) {
        g_lastError = "deviceInitialize отказал";
        return env->NewStringUTF(g_lastError.c_str());
    }
    return env->NewStringUTF("");
}

JNIEXPORT jstring JNICALL
Java_org_yuzu_yuzu_1emu_utils_KenjiBridge_nativeGraphicsInit(
        JNIEnv* env, jobject, jfloat resScale, jfloat maxAnisotropy,
        jboolean fastGpuTime, jboolean fast2DCopy, jboolean enableMacroJit,
        jboolean enableMacroHLE, jboolean enableShaderCache,
        jboolean enableTextureRecompression, jint backendThreading) {
    std::lock_guard<std::mutex> guard(g_lock);
    NoteStep("graphicsInitialize");
    if (g_core == nullptr) return env->NewStringUTF("ядро не загружено");
    using Fn = bool (*)(float, float, bool, bool, bool, bool, bool, bool, int);
    auto fn = reinterpret_cast<Fn>(dlsym(g_core, "graphicsInitialize"));
    if (fn == nullptr) return env->NewStringUTF("нет graphicsInitialize");
    g_inCore = 1;
    bool ok = fn(resScale, maxAnisotropy, fastGpuTime, fast2DCopy, enableMacroJit,
                 enableMacroHLE, enableShaderCache, enableTextureRecompression,
                 backendThreading);
    g_inCore = 0;
    if (!ok) {
        g_lastError = "graphicsInitialize отказал";
        return env->NewStringUTF(g_lastError.c_str());
    }
    return env->NewStringUTF("");
}

JNIEXPORT jstring JNICALL
Java_org_yuzu_yuzu_1emu_utils_KenjiBridge_nativeAttachSurface(JNIEnv* env, jobject, jobject surface,
                                                             jint width, jint height) {
    std::lock_guard<std::mutex> guard(g_lock);
    NoteStep("attachSurface");
    if (g_core == nullptr) return env->NewStringUTF("ядро не загружено");
    if (auto infn = reinterpret_cast<void (*)(int, int)>(dlsym(g_core, "inputInitialize"))) {
        g_inCore = 1;
        infn(width > 0 ? width : 1280, height > 0 ? height : 720);
        g_inCore = 0;
    }
    using RendFn = bool (*)(const char**, int, long);
    auto rfn = reinterpret_cast<RendFn>(dlsym(g_core, "graphicsInitializeRenderer"));
    if (rfn == nullptr) return env->NewStringUTF("нет graphicsInitializeRenderer");
    const char* exts[2] = { "VK_KHR_surface", "VK_KHR_android_surface" };
    g_inCore = 1;
    bool ok = rfn(exts, 2, 0);
    g_inCore = 0;
    if (!ok) {
        g_lastError = "graphicsInitializeRenderer отказал";
        return env->NewStringUTF(g_lastError.c_str());
    }
    if (auto sfn = reinterpret_cast<void (*)(int, int)>(dlsym(g_core, "graphicsRendererSetSize"))) {
        int w = width, h = height;
        if (surface != nullptr) {
            ANativeWindow* win = ANativeWindow_fromSurface(env, surface);
            if (win) {
                if (w <= 0) w = ANativeWindow_getWidth(win);
                if (h <= 0) h = ANativeWindow_getHeight(win);
                ANativeWindow_release(win);
            }
        }
        g_inCore = 1;
        sfn(w > 0 ? w : 1280, h > 0 ? h : 720);
        g_inCore = 0;
    }
    (void)surface;
    return env->NewStringUTF("");
}

JNIEXPORT jstring JNICALL
Java_org_yuzu_yuzu_1emu_utils_KenjiBridge_nativeLoadGame(JNIEnv* env, jobject, jint fd, jint type) {
    NoteStep("deviceLoadDescriptor");
    std::lock_guard<std::mutex> guard(g_lock);
    if (g_core == nullptr) return env->NewStringUTF("ядро не загружено");
    if (fd < 0) return env->NewStringUTF("нет дескриптора файла");
    using LoadFn = bool (*)(int, int, int);
    auto fn = reinterpret_cast<LoadFn>(dlsym(g_core, "deviceLoadDescriptor"));
    if (fn == nullptr) return env->NewStringUTF("нет deviceLoadDescriptor");
    g_inCore = 1;
    bool ok = fn(fd, type, -1);
    g_inCore = 0;
    if (!ok) {
        g_lastError = "ядро не открыло игру";
        return env->NewStringUTF(g_lastError.c_str());
    }
    return env->NewStringUTF("");
}

JNIEXPORT jstring JNICALL
Java_org_yuzu_yuzu_1emu_utils_KenjiBridge_nativeRunLoop(JNIEnv* env, jobject) {
    NoteStep("graphicsRendererRunLoop");
    using RunFn = void (*)();
    void* core = nullptr;
    {
        std::lock_guard<std::mutex> guard(g_lock);
        core = g_core;
    }
    if (core == nullptr) return env->NewStringUTF("ядро не загружено");
    auto fn = reinterpret_cast<RunFn>(dlsym(core, "graphicsRendererRunLoop"));
    if (fn == nullptr) return env->NewStringUTF("нет graphicsRendererRunLoop");
    g_inCore = 1;
    fn();
    g_inCore = 0;
    return env->NewStringUTF("");
}

JNIEXPORT void JNICALL
Java_org_yuzu_yuzu_1emu_utils_KenjiBridge_nativePlayStop(JNIEnv*, jobject) {
    void* core = nullptr;
    {
        std::lock_guard<std::mutex> guard(g_lock);
        core = g_core;
    }
    if (core == nullptr) return;
    using SigFn = void (*)();
    if (auto s = reinterpret_cast<SigFn>(dlsym(core, "deviceSignalEmulationClose"))) {
        g_inCore = 1;
        s();
        g_inCore = 0;
    }
    if (auto c = reinterpret_cast<SigFn>(dlsym(core, "deviceCloseEmulation"))) {
        g_inCore = 1;
        c();
        g_inCore = 0;
    }
}


JNIEXPORT jlong JNICALL
Java_org_yuzu_yuzu_1emu_utils_KenjiBridge_nativeWindowOf(JNIEnv* env, jobject, jobject surface) {
    if (surface == nullptr) return -1;
    ANativeWindow* win = ANativeWindow_fromSurface(env, surface);
    if (win == nullptr) return -1;
    return reinterpret_cast<jlong>(win);
}

} // extern "C"

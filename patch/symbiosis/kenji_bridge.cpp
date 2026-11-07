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
#include <cstdarg>
#include <cstring>
#include <unwind.h>
#include <link.h>
#include <ucontext.h>

#include <cerrno>
#include <cstdlib>
#include <pthread.h>
#include <sched.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/statfs.h>
#include <sys/syscall.h>
#include <sys/system_properties.h>

#include "common/symbiosis/got_patch.h"
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

/** Одна строка отчёта на кадр: `pc=<библиотека>+0x…`. */
void WriteFrame(int fd, uintptr_t pc) {
    if (pc == 0) return;
    Dl_info info{};
    char line[320];
    int n;
    if (::dladdr(reinterpret_cast<void*>(pc - 1), &info) != 0 && info.dli_fname != nullptr) {
        const char* slash = ::strrchr(info.dli_fname, '/');
        n = ::snprintf(line, sizeof(line), "pc=%s+0x%lx\n", slash ? slash + 1 : info.dli_fname,
                       static_cast<unsigned long>(pc - reinterpret_cast<uintptr_t>(info.dli_fbase)));
    } else {
        n = ::snprintf(line, sizeof(line), "pc=0x%lx\n", static_cast<unsigned long>(pc));
    }
    if (n > 0) ::write(fd, line, static_cast<size_t>(n));
}

#if defined(__aarch64__)
/**
 * Кадры упавшего потока — по цепочке frame pointer из ucontext.
 *
 * ЗАЧЕМ НЕ _Unwind_Backtrace
 *   Первый отчёт с устройства показал ровно это: `libsymbiosis_kenji.so+0x2b968
 *   → [vdso] → libc.so+0x530f4`. Первый кадр — сам обработчик сигнала, дальше
 *   мусор: _Unwind_Backtrace, запущенный из обработчика, не умеет пройти через
 *   сигнальный кадр и останавливается на нём. То есть обработчик «сработал», а
 *   виновника не назвал.
 *
 *   ucontext, который система передаёт в обработчик, содержит РЕГИСТРЫ
 *   упавшего потока: pc и цепочку x29 (frame pointer). По ней можно пройти
 *   вверх по стеку без libunwind, и первый же кадр будет настоящим адресом
 *   падения.
 */
/**
 * Похоже ли значение на адрес возврата в загруженный код.
 *
 * Проверяется через dladdr: он отвечает только для адресов внутри
 * отображённых объектов, поэтому случайные числа и указатели на данные
 * отсеиваются без ручных границ сегментов.
 */
bool LooksLikeCode(uintptr_t value, const Dl_info& self) {
    if (value < 0x1000 || (value & 0x3) != 0) return false;
    Dl_info info{};
    if (::dladdr(reinterpret_cast<void*>(value), &info) == 0 || info.dli_fbase == nullptr) {
        return false;
    }
    // Только свои библиотеки и libc: адреса из чужих объектов на этом стеке -
    // почти всегда мусор от прежних вызовов.
    if (self.dli_fname != nullptr && info.dli_fname != nullptr) {
        const char* a = ::strrchr(self.dli_fname, '/');
        const char* b = ::strrchr(info.dli_fname, '/');
        a = a ? a + 1 : self.dli_fname;
        b = b ? b + 1 : info.dli_fname;
        if (::strcmp(a, b) == 0) return true;
        if (::strcmp(b, "libc.so") == 0) return true;
        if (::strcmp(b, "libkenji.so") == 0) return true;
    }
    return false;
}

/**
 * Второй проход: слова стека, похожие на адреса возврата.
 *
 * Помечаются как `pc=`, чтобы их читал тот же разбор в Kotlin: это такие же
 * кадры, просто полученные иначе. Повторы выбрасываются — иначе один и тот же
 * вызов займёт весь отчёт.
 */
void WriteStackScan(int fd, ucontext_t* uc) {
    const uintptr_t sp = uc->uc_mcontext.sp;
    if (sp == 0) return;

    Dl_info self{};
    ::dladdr(reinterpret_cast<void*>(uc->uc_mcontext.pc), &self);

    constexpr std::size_t kWindow = 2048;   // байт вверх по стеку
    constexpr int kMax = 12;
    uintptr_t last = 0;
    int written = 0;
    char header[32];
    const int hn = ::snprintf(header, sizeof(header), "stack scan:\n");
    if (hn > 0) ::write(fd, header, static_cast<size_t>(hn));

    // Чтение чужой памяти после падения может само дать SIGSEGV. Окно берём
    // небольшое, а каждый шаг проверяем на выравнивание: так вероятность
    // попасть в неотображённую страницу мала, а пользы от прохода больше,
    // чем от аккуратности, которая ничего не находит.
    for (std::size_t off = 0; off < kWindow && written < kMax; off += 8) {
        const auto value = *reinterpret_cast<const uintptr_t*>(sp + off);
        if (value == last) continue;
        if (!LooksLikeCode(value, self)) continue;
        last = value;
        WriteFrame(fd, value);
        ++written;
    }
}

void WriteCrashFrames(int fd, void* uctx) {
    auto* uc = static_cast<ucontext_t*>(uctx);
    if (uc == nullptr) return;

    const uintptr_t pc = uc->uc_mcontext.pc;
    uintptr_t frame = uc->uc_mcontext.regs[29];      // x29: frame pointer
    const uintptr_t link = uc->uc_mcontext.regs[30]; // x30: адрес возврата

    // Регистры аргументов. По ним видно, с чем именно ядро не справилось:
    // указатель на строку, дескриптор, номер ошибки. Пишутся отдельным
    // префиксом, чтобы разбор кадров в Kotlin их не принимал за кадры.
    char regs[160];
    const int rn = ::snprintf(
        regs, sizeof(regs), "regs x0=%lx x1=%lx x2=%lx x3=%lx x29=%lx\n",
        static_cast<unsigned long>(uc->uc_mcontext.regs[0]),
        static_cast<unsigned long>(uc->uc_mcontext.regs[1]),
        static_cast<unsigned long>(uc->uc_mcontext.regs[2]),
        static_cast<unsigned long>(uc->uc_mcontext.regs[3]),
        static_cast<unsigned long>(uc->uc_mcontext.regs[29]));
    if (rn > 0) ::write(fd, regs, static_cast<size_t>(rn));

    WriteFrame(fd, pc);
    WriteFrame(fd, link);

    // Каждый кадр пишется сразу: если цепочка приведёт в нечитаемую память,
    // в файле останутся все кадры до неё - частичный отчёт лучше пустого.
    for (int i = 0; i < 14 && frame != 0; ++i) {
        if ((frame & 0x7) != 0 || frame < 0x1000) break;
        const auto* words = reinterpret_cast<const uintptr_t*>(frame);
        const uintptr_t next = words[0];
        const uintptr_t ret = words[1];
        WriteFrame(fd, ret);
        if (next <= frame) break;   // цепочка должна расти вверх по стеку
        frame = next;
    }

    // ── и вдобавок проход по стеку ───────────────────────────────────
    //
    // ПОЧЕМУ ОДНОЙ ЦЕПОЧКИ НЕ ХВАТИЛО. Отчёт с устройства (17:10) дал три
    // кадра: libc +0x530f4, libc +0x530c8 и ядро +0x173c214. Первые два -
    // abort() и raise() в libc, то есть механизм; третий - возврат после
    // вызова abort из крошечной функции в глубине ядра, и она молчит: ничего
    // не печатает и не передаёт. Виновника она не называет, а цепочка x29 на
    // ней и обрывается — libc свои кадры frame pointer не ведёт.
    //
    // Поэтому ниже независимый проход: слова на стеке, похожие на адреса
    // возврата в загруженный код. Он шумный, но именно он показывает, ОТКУДА
    // пришли, а не только КУДА пришли.
    WriteStackScan(fd, uc);
}
#else
/**
 * Хост-сборка (проверка на x86_64): обычный обратный проход.
 *
 * Ветка существует, чтобы обработчик можно было проверить настоящим SIGSEGV в
 * дочернем процессе на хосте, а не только глазами на телефоне.
 */
struct UnwindState {
    int fd;
    int depth;
};

_Unwind_Reason_Code UnwindCallback(_Unwind_Context* ctx, void* arg) {
    auto* state = static_cast<UnwindState*>(arg);
    WriteFrame(state->fd, _Unwind_GetIP(ctx));
    return ++state->depth < 12 ? _URC_NO_REASON : _URC_END_OF_STACK;
}

void WriteCrashFrames(int fd, void* uctx) {
    (void)uctx;
    UnwindState state{fd, 0};
    _Unwind_Backtrace(UnwindCallback, &state);
}
#endif

void CrashHandler(int sig, siginfo_t* info, void* uctx) {
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
        WriteCrashFrames(fd, uctx);
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

/**
 * Забрать себе stdout и stderr процесса.
 *
 * ЗАЧЕМ ЭТО ВАЖНЕЕ САМОГО ОТЧЁТА О СИГНАЛЕ. .NET-рантайм ядра печатает
 * необработанное исключение в stderr — «Unhandled exception. System.Xxx: …» —
 * и только потом вызывает abort(). В отчёте о сигнале этой строки нет, а из
 * logcat приложение её прочитать не может: буфер `crash` закрыт для обычных
 * процессов, а в общем строки тонут за секунды (это и было видно в отчётах с
 * устройства: адреса есть, текста нет).
 *
 * Поэтому stderr перенаправляется в файл ДО первого вызова в ядро. Процесс
 * умирает — файл остаётся, и в нём ровно то, что рантайм успел сказать.
 */
void ArmStdioCapture() {
    if (g_crashPath[0] == '\0') return;
    char path[512];
    const char* slash = ::strrchr(g_crashPath, '/');
    if (slash == nullptr) return;
    const std::size_t dir_len = static_cast<std::size_t>(slash - g_crashPath) + 1;
    const char* name = "kenji-stdout.txt";
    if (dir_len + ::strlen(name) + 1 >= sizeof(path)) return;
    ::memcpy(path, g_crashPath, dir_len);
    ::strcpy(path + dir_len, name);

    const int out = ::open(path, O_WRONLY | O_CREAT | O_TRUNC, 0600);
    if (out < 0) return;
    ::dup2(out, STDOUT_FILENO);
    ::dup2(out, STDERR_FILENO);
    if (out > STDERR_FILENO) ::close(out);

    // Процесс может умереть в любой момент, поэтому без буферизации: строка
    // должна попасть в файл сразу, а не при выходе, которого не будет.
    ::setvbuf(stdout, nullptr, _IONBF, 0);
    ::setvbuf(stderr, nullptr, _IONBF, 0);
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

// ── журнал JNI ──────────────────────────────────────────────────────
//
// ЗАЧЕМ. Ядру передаётся JNIEnv, и дальше оно разговаривает с Java само:
// ищет классы и методы, вызывает их. Когда оно умирает сразу после
// «core loaded and verified», вопрос не в том, что оно ответило, а в том,
// ЧТО ОНО СПРОСИЛО и что ему ответили. Обычный виновник — FindClass,
// вернувший null: класса нет в приложении или он называется иначе.
//
// ЧТО ЗДЕСЬ ОСТАЛОСЬ. Раньше здесь подменялась вся таблица функций JNI: ядру
// отдавался свой JNIEnv с копией таблицы. От этого пришлось отказаться — ART
// получает не таблицу, а указатель на JNIEnv и по нему находит поток, читая
// поля сразу за указателем на таблицу. Поддельная структура таких полей не
// имеет, и первый же вызов через ART кончался SIGSEGV в libart (кадры
// libart.so+0x309ee8 → javaInitialize+1292 пришли с устройства 04.11 14:02).
//
// Теперь ядру уходит настоящий JNIEnv, а журнал заполняет проверка
// (PrepareJni): те же FindClass и GetStaticMethodID, но выполненные нами до
// вызова ядра. Формат строк прежний, поэтому отчёт читает его как раньше.
namespace {

JavaVM* g_tapVm = nullptr;
int g_tapLines = 0;
constexpr int kTapMaxLines = 800;

char g_jniLogPath[512] = {0};

/** Дописать строку в файл перехвата. Без std::string: зовётся из JNI. */
void TapLog(const char* fn, const char* a, const char* b) {
    if (g_jniLogPath[0] == '\0' || g_tapLines >= kTapMaxLines) return;
    ++g_tapLines;
    char line[384];
    int n;
    if (a != nullptr && b != nullptr) {
        n = ::snprintf(line, sizeof(line), "%s(%s, %s)\n", fn, a, b);
    } else if (a != nullptr) {
        n = ::snprintf(line, sizeof(line), "%s(%s)\n", fn, a);
    } else {
        n = ::snprintf(line, sizeof(line), "%s\n", fn);
    }
    if (n <= 0) return;
    const int fd = ::open(g_jniLogPath, O_WRONLY | O_CREAT | O_APPEND, 0600);
    if (fd < 0) return;
    ::write(fd, line, static_cast<size_t>(n));
    ::close(fd);
}

// ── проверка JNI вместо подмены таблицы ─────────────────────────────
//
// ЧТО БЫЛО И ПОЧЕМУ ПРИШЛОСЬ ОТКАЗАТЬСЯ
//   Раньше ядру отдавался СВОЙ JNIEnv — структура, первое слово которой
//   указывало на копию таблицы функций ART с обёртками для журнала. Так
//   нельзя: ART получает не таблицу, а сам указатель на JNIEnv и через него
//   находит поток — `Thread::ForEnv(reinterpret_cast<JNIEnvExt*>(env))` читает
//   поля, лежащие сразу за указателем на таблицу. У поддельной структуры этих
//   полей нет, поэтому первый же вызов через ART (GetJavaVM внутри
//   Interop.Initialize) брал мусор за указатель на поток:
//
//     libart.so+0x309ee8 → libart.so+0x28c608 → … → javaInitialize+1292
//
//   Настоящий JNIEnv принадлежит ART, и подделывать его нельзя.
//
// ЧТО ВМЕСТО
//   Ядру отдаётся настоящий JNIEnv, а журнал заполняется проверкой: находим
//   класс, который ядро ищет по имени, и все методы, которые оно кэширует.
//   Если чего-то нет, причина называется до вызова ядра, а не после падения
//   внутри ART.

/** Класс, который ядро ищет через FindClass (LibKenjinx/Android/Interop.cs). */
constexpr const char* kCoreJavaClass = "org/kenjinx/android/KenjinxNative";

/** Методы, которые ядро кэширует при инициализации: имя и подпись. */
struct CoreJavaMethod {
    const char* name;
    const char* signature;
};

constexpr CoreJavaMethod kCoreJavaMethods[] = {
    {"test", "()V"},
    {"updateUiHandler", "(JJJIIIIJJ)V"},
    {"frameEnded", "()V"},
    {"updateProgress", "(JF)V"},
    {"getSurfacePtr", "()J"},
    {"getWindowHandle", "()J"},
};

/** Если в Java ждёт исключение — записать это в журнал и снять его. */
void DescribeAndClear(JNIEnv* env, const char* what) {
    if (env == nullptr || !env->ExceptionCheck()) return;
    TapLog("ИСКЛЮЧЕНИЕ", what, nullptr);
    env->ExceptionDescribe();
    env->ExceptionClear();
}

/**
 * Проверить, что ядро найдёт в Java всё, что ищет, и записать ответы.
 *
 * Возвращает настоящий JNIEnv: подменять его нельзя (см. выше).
 */
JNIEnv* PrepareJni(JNIEnv* real) {
    if (real == nullptr) return real;

    if (g_tapVm == nullptr) {
        const jint got = real->GetJavaVM(&g_tapVm);
        TapLog("GetJavaVM", got == JNI_OK ? "ok" : "FAIL", nullptr);
    }

    jclass cls = real->FindClass(kCoreJavaClass);
    if (cls == nullptr) {
        DescribeAndClear(real, "FindClass");
        TapLog("FindClass НЕ НАЙДЕН", kCoreJavaClass, nullptr);
        return real;
    }
    TapLog("FindClass", kCoreJavaClass, "ok");

    for (const auto& method : kCoreJavaMethods) {
        const jmethodID id = real->GetStaticMethodID(cls, method.name, method.signature);
        if (id == nullptr) {
            DescribeAndClear(real, "GetStaticMethodID");
            TapLog("GetStaticMethodID НЕ НАЙДЕН", method.name, method.signature);
            continue;
        }
        TapLog("GetStaticMethodID", method.name, method.signature);
    }

    real->DeleteLocalRef(cls);
    return real;
}
/** Путь к файлу перехвата: рядом с отчётом о падении. */
void TapSetLogPath(const char* crashPath) {
    if (crashPath == nullptr) return;
    const char* slash = ::strrchr(crashPath, '/');
    if (slash == nullptr) return;
    const std::size_t dir_len = static_cast<std::size_t>(slash - crashPath) + 1;
    const char* name = "kenji-jni.txt";
    if (dir_len + ::strlen(name) + 1 >= sizeof(g_jniLogPath)) return;
    ::memcpy(g_jniLogPath, crashPath, dir_len);
    ::strcpy(g_jniLogPath + dir_len, name);
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


// ── перехват abort() ────────────────────────────────────────────────
//
// ЗАЧЕМ. Ядро останавливается через abort(), и остановка эта тихая: функция,
// которая её требует, ничего не печатает. Обработчик сигнала видит уже libc —
// в отчёте с устройства регистры были x1=0x60bc (номер потока), x2=6 (сигнал),
// то есть аргументы tgkill, а не аргументы вызывающего. Вызывающий кадр так не
// получить.
//
//   Свой abort() получает управление ДО libc, на нормальном стеке. В этот
// момент адрес возврата в регистре x30 — и есть тот, кто потребовал
// остановку. Его и записываем, вместе с цепочкой кадров, которую здесь можно
// пройти надёжно: стек ещё цел, мы не в обработчике сигнала.
//
// КАК ПОПАСТЬ В ЧУЖУЮ ТАБЛИЦУ СИМВОЛОВ. libkenji.so ищет abort при загрузке.
// Чтобы он нашёл наш, наша библиотека должна быть в глобальной области
// видимости символов к этому моменту. Своя загрузка (System.loadLibrary) этого
// не даёт, поэтому библиотека переоткрывается с RTLD_GLOBAL|RTLD_NOLOAD —
// эта пара не загружает её заново, а только поднимает уже загруженную в
// глобальную область. Если система отказала, всё остаётся как было: ядро
// получит обычный abort и тихий отчёт, но ничего не сломается.
//
// ПОЧЕМУ ЭТО НЕ ОПАСНО. Наш abort ничего не решает и не пытается «спасти»
// процесс: он пишет строку и вызывает настоящий abort через dlsym(RTLD_NEXT).
// Поведение остаётся ровно тем же, добавляется только запись.
namespace {

using AbortFn = void (*)();
AbortFn g_realAbort = nullptr;
int g_abortHits = 0;

/** Настоящий abort из libc: первый после нас в порядке поиска. */
AbortFn RealAbort() {
    if (g_realAbort == nullptr) {
        dlerror();
        g_realAbort = reinterpret_cast<AbortFn>(dlsym(RTLD_NEXT, "abort"));
        if (g_realAbort == nullptr) {
            g_realAbort = reinterpret_cast<AbortFn>(dlsym(RTLD_DEFAULT, "abort"));
        }
    }
    return g_realAbort;
}

/** Записать вызывающего и цепочку кадров в файл отчёта. */
void NoteAbortCaller(void* return_address, void** frame_pointer) {
    const int fd = OpenCrashFile();
    if (fd < 0) return;

    // Вызывающий — с именем библиотеки и смещением, а не голым адресом:
    // абсолютный адрес после ASLR сам по себе ничего не значит, а
    // `libkenji.so+0x…` можно сверить с файлом на компьютере.
    char line[200];
    Dl_info info{};
    if (::dladdr(return_address, &info) != 0 && info.dli_fname != nullptr) {
        const char* slash = ::strrchr(info.dli_fname, '/');
        const int n = ::snprintf(
            line, sizeof(line), "abort-caller=%s+0x%lx\n", slash ? slash + 1 : info.dli_fname,
            static_cast<unsigned long>(reinterpret_cast<uintptr_t>(return_address) -
                                       reinterpret_cast<uintptr_t>(info.dli_fbase)));
        if (n > 0) ::write(fd, line, static_cast<size_t>(n));
    } else {
        const int n = ::snprintf(line, sizeof(line), "abort-caller=0x%lx\n",
                                 static_cast<unsigned long>(
                                     reinterpret_cast<uintptr_t>(return_address)));
        if (n > 0) ::write(fd, line, static_cast<size_t>(n));
    }

    // Цепочка x29 от нашего кадра: стек цел, каждый кадр настоящий.
    auto frame = reinterpret_cast<uintptr_t*>(frame_pointer);
    for (int i = 0; i < 12 && frame != nullptr; ++i) {
        const uintptr_t next = frame[0];
        const uintptr_t ret = frame[1];
        if (ret != 0) {
            const int wn = ::snprintf(line, sizeof(line), "abort-pc=%p\n",
                                      reinterpret_cast<void*>(ret));
            if (wn > 0) ::write(fd, line, static_cast<size_t>(wn));
        }
        if (next <= reinterpret_cast<uintptr_t>(frame)) break;
        frame = reinterpret_cast<uintptr_t*>(next);
    }
    ::close(fd);
}

} // namespace

// Экспортируется: имя обязано быть ровно `abort`, иначе поиск символа не
// найдёт его. Видимость по умолчанию (не скрыта) — это важно.
//
// БЕЗ `noexcept`. В bionic <stdlib.h> для C++ abort объявлен с nothrow, но
// разбор спецификации исключений в этом объявлении и в нашем должен совпадать
// буквально: сборка 41504168958 упала на
//   exception specification in declaration does not match previous declaration.
// Убрать `noexcept` — единственный правленый способ: Noreturn и так говорит
// всё, что нужно вызывающему коду.
extern "C" __attribute__((visibility("default"), noinline, noreturn)) void abort() {
    // Считаем вызовы, чтобы отличить «наш abort никто не позвал» от «позвали
    // один раз и это оно».
    if (g_abortHits < 100) {
        ++g_abortHits;
    }
    NoteAbortCaller(__builtin_return_address(0),
                    reinterpret_cast<void**>(__builtin_frame_address(0)));

    if (AbortFn real = RealAbort()) {
        real();
    }
    // Настоящего abort нет — значит мы в очень раннем окружении. Не
    // возвращаемся: возврат из abort оставил бы процесс в неизвестном
    // состоянии и упал бы где-то ещё.
    ::_exit(134);   // 128 + SIGABRT
}

/** Адрес обёртки abort — для проверки, что она видна глобально. */
AbortFn SymbiosisAbortWrapper();

AbortFn SymbiosisAbortWrapper() {
    return reinterpret_cast<AbortFn>(reinterpret_cast<void*>(&::abort));
}

/**
 * Поднять нашу библиотеку в глобальную область символов.
 *
 * Вызывается один раз, до загрузки ядра. Если не удалось — возвращает false,
 * и это нормальный исход: перехват просто не состоится.
 */
bool PromoteToGlobalScope() {
    // Имя берём из самого загрузчика: dladdr по адресу своей же функции.
    Dl_info self{};
    if (::dladdr(reinterpret_cast<void*>(&PromoteToGlobalScope), &self) == 0 ||
        self.dli_fname == nullptr) {
        return false;
    }
    void* handle = ::dlopen(self.dli_fname, RTLD_NOW | RTLD_GLOBAL | RTLD_NOLOAD);
    if (handle == nullptr) {
        return false;
    }
    // Проверяем результат, а не факт вызова: символ виден только если
    // библиотека действительно попала в глобальную область. Сравниваем с
    // адресом обёртки, объявленной ниже, а не с именем: у abort есть ещё и
    // std-объявление, и сравнение по имени компилятор понимает по-разному.
    return ::dlsym(RTLD_DEFAULT, "abort") == reinterpret_cast<void*>(SymbiosisAbortWrapper());
}

/**
 * Подменить обработчик инициализации .NET-ядра — и не дать ему уронить процесс.
 *
 * ЧТО ВИДНО ПО ФАЙЛУ ЯДРА
 *   Функция по адресу 0x170611c делает ровно это:
 *     ldr  x8, [0x4091000 + 0x290]   ; указатель на инициализацию
 *     cbz  x8, пропустить
 *     blr  x8                        ; вызвать
 *     cbz  w0, продолжить            ; вернул 0 — всё хорошо
 *     … bl 0x173c204                 ; вернул НЕ 0 → фатальная ошибка → abort
 *
 *   То есть ядро само зовёт свою инициализацию, и любой ненулевой ответ
 *   означает остановку. Именно это и происходит на телефоне на шаге
 *   javaInitialize: сообщения нет, потому что его и не печатают.
 *
 * ЧТО ДЕЛАЕМ
 *   Оборачиваем обработчик: зовём настоящий, записываем его ответ в отчёт
 *   строкой `init-callback=…` и возвращаем 0, чтобы ядро продолжило работу.
 *   Если настоящий вернул ошибку, это видно в отчёте; процесс при этом живёт,
 *   и следующий сбой случится там, где действительно чего-то не хватает, —
 *   а не в общей точке «что-то не так».
 */
using InitCallbackFn = int (*)(void*, void*, void*);

InitCallbackFn g_realInitCallback = nullptr;
int g_initCallbackResult = -999;
int g_initCallbackCalls = 0;

int GuardedInitCallback(void* a, void* b, void* c) {
    ++g_initCallbackCalls;
    // Смещение обработчика в файле ядра: по нему видно, какая именно функция
    // отказала, — адрес в памяти после ASLR ничего не значит.
    if (g_crashPath[0] != '\0' && g_realInitCallback != nullptr) {
        Dl_info info{};
        if (::dladdr(reinterpret_cast<void*>(g_realInitCallback), &info) != 0 &&
            info.dli_fbase != nullptr && info.dli_fname != nullptr) {
            const char* slash = ::strrchr(info.dli_fname, '/');
            char line[220];
            const int n = ::snprintf(
                line, sizeof(line), "init-callback-fn=%s+0x%lx\n", slash ? slash + 1 : info.dli_fname,
                static_cast<unsigned long>(reinterpret_cast<uintptr_t>(g_realInitCallback) -
                                           reinterpret_cast<uintptr_t>(info.dli_fbase)));
            if (n > 0) AppendCrashLine(line);
        }
    }
    const int result = g_realInitCallback != nullptr ? g_realInitCallback(a, b, c) : -1;
    g_initCallbackResult = result;
    LOGI("init-callback: original=%d calls=%d", result, g_initCallbackCalls);
    if (g_crashPath[0] != '\0') {
        char line[96];
        const int n = ::snprintf(line, sizeof(line), "init-callback=%d calls=%d\n", result,
                                 g_initCallbackCalls);
        if (n > 0) AppendCrashLine(line);
    }
    // Ноль — «инициализация прошла»: ядро продолжает работу. Это не подмена
    // результата ради красивой картинки: ответ записан в отчёт, и если
    // инициализация действительно не удалась, следующий отчёт назовёт место.
    return 0;
}

// ── что ядро просит у загрузчика ────────────────────────────────────
//
// ПОЧЕМУ ЭТО НУЖНО. Инициализация ядра вернула -1, и текста об ошибке нет:
// .NET-рантайм внутри NativeAOT молчит. Но все внешние функции он берёт через
// dlopen+dlsym (это видно по импортам самого файла), а значит на вопрос «чего
// не хватило» можно ответить точно: записать, что он просил и что получил.
// Ненайденный символ — это и есть причина отказа, названная по имени.

using DlopenFn = void* (*)(const char*, int);
using DlsymFn = void* (*)(void*, const char*);

std::atomic<int> g_dlCalls{0};
std::atomic<int> g_dlFailures{0};

void NoteDlRequest(const char* kind, const char* name, bool ok) {
    const int n = ++g_dlCalls;
    if (!ok) {
        ++g_dlFailures;
    }
    // Пишем только то, что важно: отказы всегда, успехи — первые полторы сотни,
    // иначе файл отчёта превращается в список всех символов ядра.
    if (!ok || n <= 150) {
        if (g_crashPath[0] != '\0') {
            char line[256];
            const int written = ::snprintf(line, sizeof(line), "dlreq %s %s %s\n", kind,
                                           name != nullptr ? name : "?", ok ? "ok" : "NULL");
            if (written > 0) AppendCrashLine(line);
        }
    }
}

void* RealDlopen(const char* name, int flags) {
    using Fn = DlopenFn;
    static Fn real = reinterpret_cast<Fn>(::dlsym(RTLD_DEFAULT, "dlopen"));
    return real != nullptr ? real(name, flags) : nullptr;
}

void* RealDlsym(void* handle, const char* name) {
    static DlsymFn real = reinterpret_cast<DlsymFn>(::dlsym(RTLD_DEFAULT, "dlsym"));
    return real != nullptr ? real(handle, name) : nullptr;
}

void* SymbiosisDlopen(const char* name, int flags) {
    void* handle = RealDlopen(name, flags);
    NoteDlRequest("dlopen", name, handle != nullptr);
    return handle;
}

void* SymbiosisDlsym(void* handle, const char* name) {
    void* symbol = RealDlsym(handle, name);
    NoteDlRequest("dlsym", name, symbol != nullptr);
    return symbol;
}

/** Подменить dlopen/dlsym в ядре — чтобы увидеть, чего ему не хватило. */
void ArmLoaderTap(void* coreHandle) {
    if (coreHandle == nullptr) return;
    void* anchor = ::dlsym(coreHandle, "javaInitialize");
    if (anchor == nullptr) return;

    const auto open_result =
        Symbiosis::PatchImport(anchor, "dlopen", reinterpret_cast<void*>(&SymbiosisDlopen));
    const auto sym_result =
        Symbiosis::PatchImport(anchor, "dlsym", reinterpret_cast<void*>(&SymbiosisDlsym));

    LOGI("loader tap: dlopen %s, dlsym %s", open_result.ok ? "ok" : open_result.detail.c_str(),
         sym_result.ok ? "ok" : sym_result.detail.c_str());
    if (g_crashPath[0] != '\0') {
        char line[256];
        const int n = ::snprintf(line, sizeof(line), "loader tap: dlopen %s, dlsym %s\n",
                                 open_result.ok ? "ok" : open_result.detail.c_str(),
                                 sym_result.ok ? "ok" : sym_result.detail.c_str());
        if (n > 0) AppendCrashLine(line);
    }
}

/**
 * Найти слот обработчика инициализации в загруженном ядре и обернуть его.
 *
 * Адрес слота — виртуальный адрес из файла ядра плюс база загрузки. Берётся он
 * из разбора этого же файла (0x4091000 + 0x290), потому что символа у него нет.
 */
void ArmInitGuard(void* coreHandle) {
    if (coreHandle == nullptr) return;
    void* anchor = ::dlsym(coreHandle, "javaInitialize");
    if (anchor == nullptr) return;

    Dl_info info{};
    if (::dladdr(anchor, &info) == 0 || info.dli_fbase == nullptr) return;

    const auto slot = reinterpret_cast<uintptr_t>(info.dli_fbase) + 0x4091000 + 0x290;
    const long page_size = ::sysconf(_SC_PAGESIZE);
    if (page_size <= 0) return;
    const auto page = slot & ~static_cast<uintptr_t>(page_size - 1);
    if (::mprotect(reinterpret_cast<void*>(page), static_cast<size_t>(page_size),
                   PROT_READ | PROT_WRITE) != 0) {
        LOGI("init guard: mprotect failed");
        return;
    }

    auto* cell = reinterpret_cast<InitCallbackFn*>(slot);
    if (*cell == nullptr) {
        LOGI("init guard: слот пуст, обёртка не нужна");
        return;
    }
    g_realInitCallback = *cell;
    *cell = &GuardedInitCallback;

    LOGI("init guard: обёрнута инициализация %p", reinterpret_cast<void*>(g_realInitCallback));
    if (g_crashPath[0] != '\0') {
        AppendCrashLine("init guard: обёрнута инициализация ядра\n");
    }
}

/**
 * Подменить `abort` уже в загруженном ядре — правкой его таблицы переходов.
 *
 * ПОЧЕМУ ПОДНЯТИЯ В ГЛОБАЛЬНУЮ ОБЛАСТЬ ОКАЗАЛОСЬ МАЛО
 *   Отчёт с устройства (21:04) пришёл без единой строки `abort-caller=`,
 *   хотя сборка с перехватчиком уже стояла. Значит ядро привязалось к
 *   настоящему `abort` в libc: разрешение символов произошло раньше, чем наша
 *   библиотека стала видна глобально, либо разрешение шло в своей области.
 *   Порядок загрузки тут подводит, а слот в таблице переходов — нет: это адрес
 *   в памяти уже загруженной библиотеки, и его можно переписать после факта.
 *
 * Что пишется в отчёт: `got patch: abort — подменено слотов: N` либо причина,
 * по которой не вышло. Без этой строки следующий отчёт снова нельзя будет
 * отличить от предыдущего.
 */
void ArmAbortTrap(void* coreHandle) {
    if (coreHandle == nullptr) return;

    // Любой символ ядра годится как точка отсчёта: по нему находится база
    // библиотеки. javaInitialize есть всегда — её наличие уже проверено выше.
    void* anchor = ::dlsym(coreHandle, "javaInitialize");
    // javaInitialize тут есть всегда: её наличие проверено до загрузки
    // (kRequiredSymbols). Если всё же нет — PatchImport вернёт причину, и она
    // попадёт в отчёт, а не превратится в пустую строку.

    const auto result =
        Symbiosis::PatchImport(anchor, "abort", reinterpret_cast<void*>(&::abort));

    LOGI("got patch abort: %s (%s)", result.ok ? "ok" : "failed", result.detail.c_str());
    if (g_crashPath[0] != '\0') {
        char line[256];
        const int n = ::snprintf(line, sizeof(line), "got patch: abort — %s\n",
                                 result.detail.c_str());
        if (n > 0) AppendCrashLine(line);
    }
}

// ── окружение рантайма: что именно проверяет ядро перед стартом ─────
//
// ЧТО ВИДНО ПО ФАЙЛУ ЯДРА
//   Функция 0x173db04 — первая же проверка инициализации рантайма
//   (0x1704320 → 0x173c2d8 → она). Её порядок такой:
//
//     page = sysconf(40)                     ; размер страницы, иначе 4096
//     cpus = sysconf(96)                     ; число процессоров
//     sdk  = atoi(__system_property_get("ro.build.version.sdk"))
//     syscall(283, 0, 0)                     ; membarrier, только запрос
//     p    = mmap(NULL, page, RW, PRIVATE|ANON, -1, 0)
//     if (mlock(p, page) != 0) return 0;     ; ← НЕНУЛЕВОЙ ОТВЕТ = ОТКАЗ
//     if (pthread_mutex_init(&lock, NULL)) return 0;
//
//   Любой нулевой ответ отсюда — это `init-callback=-1` в нашем отчёте:
//   ядро возвращает отказ, ни слова не написав. Дальше managed-куча не
//   заводится, и первая же аллокация падает по адресу 0 (0x174a09c).
//
// ЧТО ДЕЛАЕМ
//   1. Повторяем эти же вызовы сами и записываем ответы в отчёт строкой
//      `pal:` — так причина называется словами, а не адресом.
//   2. `mlock` в ядре подменяем: блокировка страницы в памяти — просьба
//      nice-to-have, и отказ на неё не должен означать «ядро не работает».
//      Настоящий ответ при этом пишется в отчёт, чтобы подмена не была
//      молчаливой.

using MlockFn = int (*)(const void*, size_t);
MlockFn g_realMlock = nullptr;
int g_mlockCalls = 0;
int g_mlockFailures = 0;
int g_mlockLastError = 0;

int SymbiosisMlock(const void* addr, size_t len) {
    ++g_mlockCalls;
    const int real = g_realMlock != nullptr ? g_realMlock(addr, len) : 0;
    if (real == 0) return 0;

    const int saved = errno;
    ++g_mlockFailures;
    g_mlockLastError = saved;
    LOGI("mlock: настоящий отказал (errno=%d), сообщаю успех", saved);
    if (g_crashPath[0] != '\0') {
        char line[160];
        const int n = ::snprintf(line, sizeof(line),
                                 "mlock: настоящий отказал errno=%d — сообщено «заперто»\n", saved);
        if (n > 0) AppendCrashLine(line);
    }
    errno = 0;
    return 0;
}

// ── настройки кучи .NET: 256 ГиБ адресного пространства ─────────────
//
// ЧТО ПОКАЗАЛ ОТЧЁТ С УСТРОЙСТВА (04.11 12:49)
//   Последняя строка перехвата вызовов:
//
//     → mmap(274877911040) = FAIL errno=12
//
//   274877911040 = 256 ГиБ + одна страница. Это не наша проверка и не
//   выделение под данные: столько .NET-GC резервирует себе под «регионы»
//   (region range) сразу при инициализации. По умолчанию на 64-битной
//   системе он берёт 256 ГБ виртуального адресного пространства, а на ARM64
//   под Android его всего 39 бит, то есть 512 ГиБ. Половину этого объёма
//   занять одним куском после ART и библиотек уже не удаётся — mmap отвечает
//   ENOMEM.
//
//   Отсюда вся цепочка: исключение внутри Initialize → `catch` → печать в
//   stdout → `init-callback=-1`. Дальше рантайм остаётся неинициализированным,
//   и первая же аллокация падает по нулевому адресу (кадры 0x174a09c →
//   0x1a70140).
//
// ЧТО ДЕЛАЕМ
//   Говорим GC, сколько адресного пространства резервировать: переменная
//   `DOTNET_GCRegionRange` (есть в этом файле ядра — строка присутствует).
//   16 ГиБ — это вчетверо больше кучи, которую ядро реально занимает на
//   телефоне с 8 ГБ памяти, и в тридцать раз меньше свободного места.
//
//   ВАЖНО: NativeAOT читает эту переменную как голое шестнадцатеричное
//   число. Форма `0x400000000` молча игнорируется — резерв остаётся 256 ГиБ.

/*
 * Подгрузить OpenSSL для криптографии .NET.
 *
 * ЧТО БЫЛО. Инициализация ядра прошла (init-callback=0), но через сорок
 * миллисекунд рабочий поток .NET напечатал «No usable version of libssl was
 * found» и процесс остановился через abort(). Криптография .NET ищет libssl
 * по списку имён (3, 1.1, 1.0.2, 1.0.0, 10 и просто libssl.so) и, не найдя
 * ни одного, убивает процесс — это её штатное поведение, а не сбой ядра.
 *
 * ЧТО ДЕЛАЕМ. Библиотеки теперь лежат в APK рядом с мостом (их кладёт
 * сборочный скрипт). Загружаем их сами и открыто: если загрузка не удастся,
 * в отчёте будет строка с причиной, а не «ядро молча упало». Сначала по
 * абсолютному пути рядом с собой — он не зависит от того, что система
 * считает путём поиска; потом по имени, на случай если библиотека пришла
 * из другого места.
 */
void PreloadOpenSsl() {
    std::string dir;
    Dl_info info{};
    if (::dladdr(reinterpret_cast<void*>(&PreloadOpenSsl), &info) != 0 && info.dli_fname != nullptr) {
        const std::string path(info.dli_fname);
        const auto slash = path.find_last_of('/');
        if (slash != std::string::npos) dir = path.substr(0, slash + 1);
    }

    // libcrypto первой: libssl зависит от неё по имени. Дальше — ICU: без
    // него .NET не может ни привести регистр, ни сравнить строки по правилам
    // языка, а пробы версий (u_strlen_50 … u_strlen_58) в отчёте — это его
    // попытки найти хоть одну подходящую.
    const char* names[] = {"libcrypto.so", "libssl.so", "libicuuc.so", "libicui18n.so"};
    for (const char* name : names) {
        void* handle = nullptr;
        std::string used;
        if (!dir.empty()) {
            const std::string absolute = dir + name;
            handle = ::dlopen(absolute.c_str(), RTLD_NOW | RTLD_GLOBAL);
            if (handle != nullptr) used = absolute;
        }
        if (handle == nullptr) {
            handle = ::dlopen(name, RTLD_NOW | RTLD_GLOBAL);
            if (handle != nullptr) used = name;
        }

        LOGI("ssl: %s %s", name, handle != nullptr ? "ok" : "не найдена");
        if (g_crashPath[0] != '\0') {
            char line[420];
            int n;
            if (handle != nullptr) {
                n = ::snprintf(line, sizeof(line), "ssl: %s=ok (%s)\n", name, used.c_str());
            } else {
                const char* why = ::dlerror();
                n = ::snprintf(line, sizeof(line),
                               "ssl: %s=HET (%s) — криптография .NET остановит ядро\n", name,
                               why != nullptr ? why : "dlopen не дал причины");
            }
            if (n > 0) AppendCrashLine(line);
        }
    }
}

void ConfigureCoreRuntimeEnv() {
    // Куча .NET резервирует виртуальное пространство заранее; на 39-битной
    // разметке 256 ГиБ не помещаются. 0x400000000 = 16 ГиБ.
    const char* knob = "DOTNET_GCRegionRange";
    const char* fallback = "400000000";

    std::string value = fallback;
    std::string source = "наш выбор 16 ГиБ";

    const char* given = ::getenv(knob);
    if (given != nullptr && given[0] != '\0') {
        std::string text(given);
        const bool prefixed = text.size() > 2 && text[0] == '0' && (text[1] == 'x' || text[1] == 'X');
        if (prefixed) {
            text = text.substr(2);
            source = "задано с приставкой 0x — убрана, NativeAOT понимает только голый hex";
        } else {
            source = "задано вручную";
        }
        value = text;
    }

    ::setenv(knob, value.c_str(), 1);
    LOGI("gc: %s=%s (%s)", knob, value.c_str(), source.c_str());

    // Криптография .NET ищет libssl сама и убивает процесс, если не нашла.
    PreloadOpenSsl();

    if (g_crashPath[0] != '\0') {
        char line[320];
        const int n = ::snprintf(line, sizeof(line),
                                 "gc: %s=%s (%s) — иначе GC резервирует 256 ГиБ и не влезает "
                                 "в 39-битное адресное пространство\n",
                                 knob, value.c_str(), source.c_str());
        if (n > 0) AppendCrashLine(line);
    }
}

/**
 * Повторить проверки окружения, которые делает ядро, и записать ответы.
 *
 * Значения берутся те же, что в коде ядра (40 и 96, а не макросы): макрос
 * в чужом заголовке может означать другое число, а нам нужно ровно то, что
 * спросит ядро.
 */
void PalPreflight() {
    if (g_crashPath[0] == '\0') return;

    char line[400];
    int n;

    // 1. Числа, которые ядро спрашивает первым делом. Номера — те же, что в
    //    его коде (40, 96, 98), а не наши макросы: важно ответить на тот же
    //    вопрос, а не на похожий. -1 означает «система не ответила», и в
    //    ядре это сразу отказ.
    const long pageByName = ::sysconf(40);      // _SC_PAGE_SIZE
    const long pageMacro = ::sysconf(_SC_PAGESIZE);
    const long cpus = ::sysconf(96);            // _SC_NPROCESSORS_CONF
    const long physPages = ::sysconf(98);       // _SC_PHYS_PAGES
    n = ::snprintf(line, sizeof(line),
                   "pal: sysconf 40=%ld pagesize=%ld 96=%ld 98=%ld\n",
                   pageByName, pageMacro, cpus, physPages);
    if (n > 0) AppendCrashLine(line);

    const long size = pageByName > 0 ? pageByName : (pageMacro > 0 ? pageMacro : 4096);

    // 2. Версия системы и membarrier: ядро при sdk >= 29 сначала пробует
    //    membarrier и только при отказе заводит страницу через mmap.
    char sdkRaw[128];
    sdkRaw[0] = '\0';
    ::__system_property_get("ro.build.version.sdk", sdkRaw);
    errno = 0;
    const long query = ::syscall(283, 0, 0);
    const int queryErr = query < 0 ? errno : 0;
    long registered = -1;
    int registerErr = 0;
    if (query >= 0 && (query & 8) != 0) {
        errno = 0;
        registered = ::syscall(283, 0x10, 0);
        registerErr = registered < 0 ? errno : 0;
    }
    n = ::snprintf(line, sizeof(line),
                   "pal: sdk=%s membarrier query=%ld errno=%d register=%ld errno=%d\n",
                   sdkRaw, query, queryErr, registered, registerErr);
    if (n > 0) AppendCrashLine(line);

    // 3. Страница: mmap, mlock, мьютекс — ровно в том порядке и с теми же
    //    аргументами, что у ядра. Здесь и рождается отказ без текста.
    errno = 0;
    void* zero = ::mmap(nullptr, static_cast<size_t>(size), PROT_READ | PROT_WRITE,
                        MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (zero == MAP_FAILED) {
        n = ::snprintf(line, sizeof(line), "pal: mmap(%ld)=FAIL errno=%d\n", size, errno);
        if (n > 0) AppendCrashLine(line);
    } else {
        n = ::snprintf(line, sizeof(line), "pal: mmap(%ld)=ok\n", size);
        if (n > 0) AppendCrashLine(line);

        errno = 0;
        const int locked = ::mlock(zero, static_cast<size_t>(size));
        if (locked == 0) {
            n = ::snprintf(line, sizeof(line), "pal: mlock(%ld)=ok\n", size);
        } else {
            n = ::snprintf(line, sizeof(line), "pal: mlock(%ld)=FAIL errno=%d\n", size, errno);
        }
        if (n > 0) AppendCrashLine(line);
        ::munlock(zero, static_cast<size_t>(size));
        ::munmap(zero, static_cast<size_t>(size));
    }

    pthread_mutex_t probe{};
    const int mutexAt = ::pthread_mutex_init(&probe, nullptr);
    if (mutexAt == 0) ::pthread_mutex_destroy(&probe);
    n = ::snprintf(line, sizeof(line), "pal: pthread_mutex_init=%d\n", mutexAt);
    if (n > 0) AppendCrashLine(line);

    // 4. Дальше ядро читает маску процессоров и общий объём памяти в
    //    страницах. Это тоже может отказать — молча, как и всё остальное.
    cpu_set_t mask;
    CPU_ZERO(&mask);
    errno = 0;
    const int affinity = ::sched_getaffinity(::getpid(), sizeof(mask), &mask);
    const long cpusOn = affinity == 0 ? ::sysconf(97) : -1;
    n = ::snprintf(line, sizeof(line), "pal: sched_getaffinity=%d errno=%d onln=%ld\n",
                   affinity, affinity == 0 ? 0 : errno, cpusOn);
    if (n > 0) AppendCrashLine(line);

    struct statfs fs {};
    errno = 0;
    const int fsResult = ::statfs("/sys/fs/cgroup", &fs);
    n = ::snprintf(line, sizeof(line), "pal: statfs(/sys/fs/cgroup)=%d errno=%d\n",
                   fsResult, fsResult == 0 ? 0 : errno);
    if (n > 0) AppendCrashLine(line);
}

// ── журнал вызовов окружения ────────────────────────────────────────
//
// ЧЕГО НЕ ХВАТАЕТ ОТЧЁТУ. Проверки окружения внутри ядра идут цепочкой, и
// любая неудача превращается в один и тот же ответ `-1`. Мы уже повторяем
// их сами (PalPreflight), но повтор — это наш вызов, а не вызов ядра: он
// может удаться там, где у ядра не вышло, и наоборот. Поэтому настоящие
// вызовы ядра тоже записываются — с аргументами и ответами.

std::atomic<int> g_envTraceBudget{80};

// Самое крупное резервирование адресного пространства, которое запросило
// ядро. Нужно, чтобы отчёт отвечал на вопрос «подействовала ли настройка
// кучи» фактом, а не надеждой: 256 ГиБ здесь означает, что переменная не
// прочитана, а 16 ГиБ — что прочитана.
std::atomic<unsigned long long> g_largestReserve{0};

void TraceEnv(const char* format, ...) {
    if (g_crashPath[0] == '\0') return;
    if (g_envTraceBudget.fetch_sub(1) <= 0) return;

    char line[320];
    va_list args;
    va_start(args, format);
    const int n = ::vsnprintf(line, sizeof(line), format, args);
    va_end(args);
    if (n > 0) AppendCrashLine(line);
}

using SigactionFn = int (*)(int, const struct sigaction*, struct sigaction*);
using MmapFn = void* (*)(void*, size_t, int, int, int, off_t);
using SysconfFn = long (*)(int);
using MutexInitFn = int (*)(pthread_mutex_t*, const pthread_mutexattr_t*);

SigactionFn g_realSigaction = nullptr;
MmapFn g_realMmap = nullptr;
SysconfFn g_realSysconf = nullptr;
MutexInitFn g_realMutexInit = nullptr;

int SymbiosisSigaction(int signal, const struct sigaction* act, struct sigaction* old) {
    const int result = g_realSigaction != nullptr ? g_realSigaction(signal, act, old) : -1;
    TraceEnv("call: sigaction(%d) = %d\n", signal, result);
    return result;
}

/** Размер в байтах так, как его читает человек: 274877911040 → «256.0 ГиБ». */
std::string HumanSize(size_t bytes) {
    char text[64];
    const double gib = static_cast<double>(bytes) / (1024.0 * 1024.0 * 1024.0);
    if (gib >= 1.0) {
        ::snprintf(text, sizeof(text), "%.1f ГиБ", gib);
    } else {
        ::snprintf(text, sizeof(text), "%.1f МиБ", static_cast<double>(bytes) / (1024.0 * 1024.0));
    }
    return text;
}

void* SymbiosisMmap(void* addr, size_t length, int prot, int flags, int fd, off_t offset) {
    void* result = g_realMmap != nullptr ? g_realMmap(addr, length, prot, flags, fd, offset)
                                         : MAP_FAILED;
    const std::string human = HumanSize(length);
    if (length > g_largestReserve.load(std::memory_order_relaxed)) {
        g_largestReserve.store(length, std::memory_order_relaxed);
    }
    if (result == MAP_FAILED) {
        const int saved = errno;
        TraceEnv("call: mmap(%zu = %s) = FAIL errno=%d\n", length, human.c_str(), saved);
        // Запрос больше ГиБ — это резерв, а не данные. Если он не прошёл по
        // нехватке адресного пространства, причина называется здесь, а не
        // выясняется по кадрам через два круга.
        if (length >= static_cast<size_t>(1024) * 1024 * 1024 && saved == ENOMEM) {
            TraceEnv("call: ядру не хватило адресного пространства под резерв %s "
                     "(на ARM64 под Android его 39 бит = 512 ГиБ)\n",
                     human.c_str());
        }
    } else {
        TraceEnv("call: mmap(%zu = %s) = ok\n", length, human.c_str());
    }
    return result;
}

long SymbiosisSysconf(int name) {
    const long result = g_realSysconf != nullptr ? g_realSysconf(name) : -1;
    // Спрашивают десятки разных чисел; пишем только те, что в проверке.
    if (name == 40 || name == 96 || name == 97 || name == 98) {
        TraceEnv("call: sysconf(%d) = %ld\n", name, result);
    }
    return result;
}

int SymbiosisMutexInit(pthread_mutex_t* mutex, const pthread_mutexattr_t* attr) {
    const int result = g_realMutexInit != nullptr ? g_realMutexInit(mutex, attr) : -1;
    TraceEnv("call: pthread_mutex_init = %d\n", result);
    return result;
}

/**
 * Поставить перехваты журнала на таблицу переходов ядра.
 *
 * Подменяются только те функции, чьи ответы нужны в отчёте; всё остальное
 * ядро получает как есть. Порядок важен: настоящие адреса берутся до
 * подмены, иначе `dlsym` вернёт наши же обёртки.
 */
void ArmEnvTrace(void* coreHandle) {
    if (coreHandle == nullptr) return;
    void* anchor = ::dlsym(coreHandle, "javaInitialize");
    if (anchor == nullptr) return;

    g_realSigaction = reinterpret_cast<SigactionFn>(::dlsym(RTLD_DEFAULT, "sigaction"));
    g_realMmap = reinterpret_cast<MmapFn>(::dlsym(RTLD_DEFAULT, "mmap"));
    g_realSysconf = reinterpret_cast<SysconfFn>(::dlsym(RTLD_DEFAULT, "sysconf"));
    g_realMutexInit = reinterpret_cast<MutexInitFn>(::dlsym(RTLD_DEFAULT, "pthread_mutex_init"));

    int armed = 0;
    struct Trap { const char* name; void* hook; bool ready; } traps[] = {
        {"sigaction", reinterpret_cast<void*>(&SymbiosisSigaction), g_realSigaction != nullptr},
        {"mmap", reinterpret_cast<void*>(&SymbiosisMmap), g_realMmap != nullptr},
        {"sysconf", reinterpret_cast<void*>(&SymbiosisSysconf), g_realSysconf != nullptr},
        {"pthread_mutex_init", reinterpret_cast<void*>(&SymbiosisMutexInit),
         g_realMutexInit != nullptr},
    };

    for (auto& trap : traps) {
        if (!trap.ready) {
            if (g_crashPath[0] != '\0') {
                char line[200];
                const int n = ::snprintf(line, sizeof(line),
                                         "got patch: %s не поставлен — нет настоящего адреса\n",
                                         trap.name);
                if (n > 0) AppendCrashLine(line);
            }
            continue;
        }
        const auto result = Symbiosis::PatchImport(anchor, trap.name, trap.hook);
        if (result.ok) ++armed;
        LOGI("got patch %s: %s (%s)", trap.name, result.ok ? "ok" : "failed",
             result.detail.c_str());
        if (g_crashPath[0] != '\0') {
            char line[256];
            const int n = ::snprintf(line, sizeof(line), "got patch: %s — %s\n", trap.name,
                                     result.detail.c_str());
            if (n > 0) AppendCrashLine(line);
        }
    }

    if (g_crashPath[0] != '\0') {
        char line[120];
        const int n = ::snprintf(line, sizeof(line), "got patch: журнал вызовов — узлов %d из 4\n",
                                 armed);
        if (n > 0) AppendCrashLine(line);
    }
}

/**
 * Подменить `mlock` в таблице переходов ядра.
 *
 * Подменяется только этот вызов: он единственный из проверок окружения,
 * отказ которого ничего не значит. `mmap` и `pthread_mutex_init` подменять
 * нельзя — они делают работу, без которой рантайм не заработает.
 */
void ArmMlockTrap(void* coreHandle) {
    if (coreHandle == nullptr) return;

    // Настоящий адрес нужен до подмены: после неё `dlsym` вернёт уже обёртку.
    g_realMlock = reinterpret_cast<MlockFn>(::dlsym(RTLD_DEFAULT, "mlock"));
    void* anchor = ::dlsym(coreHandle, "javaInitialize");
    if (anchor == nullptr) return;

    const auto result =
        Symbiosis::PatchImport(anchor, "mlock", reinterpret_cast<void*>(&SymbiosisMlock));

    LOGI("got patch mlock: %s (%s)", result.ok ? "ok" : "failed", result.detail.c_str());
    if (g_crashPath[0] != '\0') {
        char line[256];
        const int n = ::snprintf(line, sizeof(line), "got patch: mlock — %s\n",
                                 result.detail.c_str());
        if (n > 0) AppendCrashLine(line);
    }
}

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
    ArmStdioCapture();
    TapSetLogPath(g_crashPath);
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

    // Перехват abort() должен быть виден ДО загрузки ядра: символы чужой
    // библиотеки ищутся в глобальной области в момент её открытия, и позже
    // этого уже не исправить.
    const bool promoted = PromoteToGlobalScope();
    LOGI("abort interposer: %s", promoted ? "active" : "not active");
    if (g_crashPath[0] != '\0') {
        char line[96];
        const int n = ::snprintf(line, sizeof(line), "abort interposer: %s\n",
                                 promoted ? "активен" : "не активен");
        if (n > 0) AppendCrashLine(line);
    }

    // Настройки кучи — до dlopen, а не после: NativeAOT заводит рантайм в
    // инициализаторе модуля, то есть при открытии библиотеки. Если GC
    // инициализируется там, значение, выставленное после, он уже не прочтёт.
    ConfigureCoreRuntimeEnv();

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
    ArmAbortTrap(handle);
    ArmLoaderTap(handle);
    ArmMlockTrap(handle);
    ArmEnvTrace(handle);
    ArmInitGuard(handle);
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
    // Перехват ставится ровно здесь: до этого вызова ядро JNIEnv не получало,
    // после — получает только от нас.
    JNIEnv* passedEnv = PrepareJni(env);

    // Что именно мы передали ядру. Если оно отказывается стартовать, первое,
    // что нужно исключить, — путь: отсутствующий каталог для ядра выглядит как
    // «нет данных», и оно молча возвращает отказ.
    if (g_crashPath[0] != '\0') {
        struct stat st {};
        const bool exists = ::stat(dataPath, &st) == 0;
        const bool is_dir = exists && S_ISDIR(st.st_mode);
        char line[420];
        const int n = ::snprintf(line, sizeof(line), "init-args: path=%s exists=%d dir=%d env=%p\n",
                                 dataPath, exists ? 1 : 0, is_dir ? 1 : 0,
                                 reinterpret_cast<void*>(passedEnv));
        if (n > 0) AppendCrashLine(line);
    }

    // Проверки окружения, которые ядро сделает первым делом: их ответы и
    // есть причина отказа, если он случится. Пишем до вызова — после него
    // процесс может уже не отвечать.
    PalPreflight();

    g_inCore = 1;
    bool ok = fn(const_cast<char*>(dataPath), passedEnv);
    g_inCore = 0;

    // Итог по резерву кучи: подействовала настройка или нет.
    if (g_crashPath[0] != '\0') {
        const unsigned long long reserved = g_largestReserve.load();
        char line[320];
        int n;
        if (reserved == 0) {
            n = ::snprintf(line, sizeof(line),
                           "gc: куча резерва не запрашивала — настройка не понадобилась\n");
        } else if (reserved >= (64ULL << 30)) {
            n = ::snprintf(line, sizeof(line),
                           "gc: ядро всё равно просило %s — настройка не прочитана\n",
                           HumanSize(static_cast<size_t>(reserved)).c_str());
        } else {
            n = ::snprintf(line, sizeof(line), "gc: резерв кучи %s — настройка прочитана\n",
                           HumanSize(static_cast<size_t>(reserved)).c_str());
        }
        if (n > 0) AppendCrashLine(line);
    }

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


/**
 * Версия установленной прошивки — словами самого ядра.
 *
 * ЗАЧЕМ. «Игра не открылась» и «прошивки нет» до сих пор выглядели
 * одинаково: ядро в обоих случаях молчит, а решения у них разные. Прошивка
 * лежит в собственном каталоге второго ядра (`system`), а не в каталоге
 * Eden, поэтому её отсутствие — обычное состояние, а не поломка.
 *
 * Пустая строка — прошивки нет. Строку выделяет ядро своим аллокатором,
 * освобождать её отсюда не будем: вызов один за запуск, а чужой free()
 * на .NET-куче дороже утечки в несколько байт.
 */
JNIEXPORT jstring JNICALL
Java_org_yuzu_yuzu_1emu_utils_KenjiBridge_nativeFirmwareVersion(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> guard(g_lock);
    if (g_core == nullptr) return env->NewStringUTF("");
    using Fn = const char* (*)();
    auto fn = reinterpret_cast<Fn>(dlsym(g_core, "deviceGetInstalledFirmwareVersion"));
    if (fn == nullptr) return env->NewStringUTF("");
    g_inCore = 1;
    const char* version = fn();
    g_inCore = 0;
    const std::string text = (version != nullptr) ? version : "";
    if (g_crashPath[0] != '\0') {
        char line[240];
        const int n = ::snprintf(line, sizeof(line), "fw: %s\n",
                                 text.empty() ? "прошивка не установлена" : text.c_str());
        if (n > 0) AppendCrashLine(line);
    }
    return env->NewStringUTF(text.c_str());
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
    // Ввод здесь больше не инициализируется.
    //
    // ПОЧЕМУ. `InitializeInput` в ядре первым делом берёт
    // `SwitchDevice.EmulationContext` и передаёт его в менеджеры касаний и
    // геймпадов. До `deviceInitialize` контекста нет — вызов падал
    // NullReferenceException в .NET TP Worker, а необработанное исключение
    // в [UnmanagedCallersOnly] заканчивается abort(): отчёт показал ровно
    // это, SIGABRT на шаге attachSurface. В официальном приложении ввод
    // поднимается в GameHost.start, то есть уже после загрузки игры.
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

/**
 * Кто из пользователей открыт в ядре — словами самого ядра.
 *
 * ЗАЧЕМ. Игры обращаются к сервису учётной записи сразу после запуска. Если у
 * ядра нет ни профиля, ни открытого пользователя, эти вызовы кончаются
 * исключением в гостевом потоке, и игра встаёт на чёрном экране. Строка в
 * отчёте отделяет «нет профиля» от «профиль есть, но не открыт».
 */
JNIEXPORT jstring JNICALL
Java_org_yuzu_yuzu_1emu_utils_KenjiBridge_nativeOpenedUser(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> guard(g_lock);
    if (g_core == nullptr) return env->NewStringUTF("");
    using Fn = const char* (*)();
    auto fn = reinterpret_cast<Fn>(dlsym(g_core, "userGetOpenedUser"));
    if (fn == nullptr) return env->NewStringUTF("");
    g_inCore = 1;
    const char* user = fn();
    g_inCore = 0;
    const std::string text = (user != nullptr) ? user : "";
    if (g_crashPath[0] != '\0') {
        char line[240];
        const int n = ::snprintf(line, sizeof(line), "user: %s\n",
                                 text.empty() ? "пользователь не открыт" : text.c_str());
        if (n > 0) AppendCrashLine(line);
    }
    return env->NewStringUTF(text.c_str());
}

/**
 * Пинок рендереру после того, как поверхность появилась.
 *
 * ПОЧЕМУ ЭТО ОТДЕЛЬНЫЕ ВЫЗОВЫ, А НЕ «САМО ЗАРАБОТАЕТ». Официальное приложение
 * после появления поверхности делает ровно четыре вещи: отдаёт ядру окно,
 * сообщает поворот экрана, включает растяжение на весь экран и пересоздаёт
 * цепочку буферов. Без последнего Vulkan может рисовать в старую цепочку,
 * которой уже нет: цикл идёт, звук есть, кадров на экране нет — ровно то, что
 * видит человек.
 *
 * Вызовы безобидны, если ядро их не знает: dlsym вернёт nullptr, а имя
 * пропавшего символа попадёт в отчёт — молча пропускать нельзя, иначе
 * «пинок сделан» ничем не отличается от «пинок не сделан».
 *
 * ВОЗВРАЩАЕТ строку, а не void. JNI связывает функции по имени и не сверяет
 * тип: `void` в C++ при объявлении `: String` в Kotlin даёт `null` в регистре
 * возврата, и Kotlin падает с NullPointerException на следующей же строке —
 * внутри нашего кода, а не на месте ошибки. Ровно это стоило сборки от
 * 04.11 22:12:49: игра загрузилась, все пять вызовов прошли, а процесс умер
 * уже после них. Типы сверяет tools/check_jni_names.py.
 */
JNIEXPORT jstring JNICALL
Java_org_yuzu_yuzu_1emu_utils_KenjiBridge_nativeRendererKick(JNIEnv* env, jobject, jint width,
                                                              jint height, jint rotation) {
    std::lock_guard<std::mutex> guard(g_lock);
    NoteStep("rendererKick");
    if (g_core == nullptr) return env->NewStringUTF("ядро не загружено");

    std::string missing;
    auto note = [&missing](const char* name) {
        if (!missing.empty()) missing += ", ";
        missing += name;
    };

    // 1. Поворот поверхности так, как это делает официальный Kenji.
    if (auto fn = reinterpret_cast<void (*)(int)>(dlsym(g_core, "deviceSetSurfaceRotation"))) {
        g_inCore = 1;
        fn(static_cast<int>(rotation));
        g_inCore = 0;
    } else {
        note("deviceSetSurfaceRotation");
    }
    // 2. Размер отрисовки.
    if (auto fn = reinterpret_cast<void (*)(int, int)>(dlsym(g_core, "graphicsRendererSetSize"))) {
        g_inCore = 1;
        fn(width > 0 ? width : 1280, height > 0 ? height : 720);
        g_inCore = 0;
    } else {
        note("graphicsRendererSetSize");
    }
    // 3. Растяжение на весь экран — на телефоне это то, что человек ждёт.
    if (auto fn = reinterpret_cast<void (*)(bool)>(dlsym(g_core, "graphicsSetFullscreenStretch"))) {
        g_inCore = 1;
        fn(true);
        g_inCore = 0;
    } else {
        note("graphicsSetFullscreenStretch");
    }
    // 4. Пересоздать цепочку буферов на актуальной поверхности.
    if (auto fn = reinterpret_cast<void (*)()>(dlsym(g_core, "deviceRecreateSwapchain"))) {
        g_inCore = 1;
        fn();
        g_inCore = 0;
    } else {
        note("deviceRecreateSwapchain");
    }
    // 5. Явно разрешить показ: SetPresentEnabled(false) в ядре гасит вывод, и
    //    после него PresentAllowed остаётся ложным до явного true.
    if (auto fn = reinterpret_cast<void (*)(bool)>(dlsym(g_core, "graphicsSetPresentEnabled"))) {
        g_inCore = 1;
        fn(true);
        g_inCore = 0;
    } else {
        note("graphicsSetPresentEnabled");
    }

    if (missing.empty()) return env->NewStringUTF("");
    return env->NewStringUTF(("ядро не знает: " + missing).c_str());
}

/**
 * Ввод после deviceInitialize и до загрузки игры. Контекст нужен менеджерам
 * ввода; родственное ядро создаёт его в deviceInitialize. Порядок нужно
 * проверить с бинарником на устройстве.
 */
JNIEXPORT jstring JNICALL
Java_org_yuzu_yuzu_1emu_utils_KenjiBridge_nativeInputInit(JNIEnv* env, jobject, jint width,
                                                          jint height) {
    std::lock_guard<std::mutex> guard(g_lock);
    NoteStep("inputInitialize");
    if (g_core == nullptr) return env->NewStringUTF("ядро не загружено");
    using Fn = void (*)(int, int);
    auto fn = reinterpret_cast<Fn>(dlsym(g_core, "inputInitialize"));
    if (fn == nullptr) return env->NewStringUTF("нет inputInitialize");
    g_inCore = 1;
    fn(width > 0 ? width : 1280, height > 0 ? height : 720);
    g_inCore = 0;
    return env->NewStringUTF("");
}

// Виртуальный контроллер и обновление ввода — отдельные API ядра.
JNIEXPORT jint JNICALL
Java_org_yuzu_yuzu_1emu_utils_KenjiBridge_nativeConnectPad(JNIEnv*, jobject, jint index) {
    if (g_core == nullptr) return -1;
    using Fn = int (*)(int);
    auto fn = reinterpret_cast<Fn>(dlsym(g_core, "inputConnectGamepad"));
    if (fn == nullptr) return -1;
    return static_cast<jint>(fn(static_cast<int>(index)));
}

JNIEXPORT jboolean JNICALL
Java_org_yuzu_yuzu_1emu_utils_KenjiBridge_nativeUpdateInput(JNIEnv*, jobject) {
    if (g_core == nullptr) return JNI_FALSE;
    using Fn = void (*)();
    auto fn = reinterpret_cast<Fn>(dlsym(g_core, "inputUpdate"));
    if (fn == nullptr) return JNI_FALSE;
    fn();
    return JNI_TRUE;
}

// Called only while the Kotlin input event gate holds the core alive.
JNIEXPORT jboolean JNICALL
Java_org_yuzu_yuzu_1emu_utils_KenjiBridge_nativeInputEventsAvailable(JNIEnv*, jobject) {
    if (!g_core) return JNI_FALSE;
    const char* names[] = {"inputSetButtonPressed", "inputSetButtonReleased", "inputSetStickAxis",
                           "inputSetTouchPoint", "inputReleaseTouchPoint", "inputSetClientSize"};
    for (const auto* name : names) if (!dlsym(g_core, name)) return JNI_FALSE;
    return JNI_TRUE;
}
JNIEXPORT void JNICALL
Java_org_yuzu_yuzu_1emu_utils_KenjiBridge_nativeInputButton(JNIEnv*, jobject, jint button, jint id,
                                                            jboolean pressed) {
    if (!g_core) return;
    using Fn = void (*)(int, int);
    const char* name = pressed == JNI_TRUE ? "inputSetButtonPressed" : "inputSetButtonReleased";
    if (auto fn = reinterpret_cast<Fn>(dlsym(g_core, name))) fn(button, id);
}
JNIEXPORT void JNICALL
Java_org_yuzu_yuzu_1emu_utils_KenjiBridge_nativeInputStick(JNIEnv*, jobject, jint stick, jfloat x,
                                                           jfloat y, jint id) {
    if (!g_core) return;
    using Fn = void (*)(int, float, float, int);
    if (auto fn = reinterpret_cast<Fn>(dlsym(g_core, "inputSetStickAxis"))) fn(stick, x, y, id);
}
JNIEXPORT void JNICALL
Java_org_yuzu_yuzu_1emu_utils_KenjiBridge_nativeInputTouch(JNIEnv*, jobject, jint x, jint y,
                                                           jboolean pressed) {
    if (!g_core) return;
    if (pressed == JNI_TRUE) {
        using Fn = void (*)(int, int);
        if (auto fn = reinterpret_cast<Fn>(dlsym(g_core, "inputSetTouchPoint"))) fn(x, y);
    } else {
        using Fn = void (*)();
        if (auto fn = reinterpret_cast<Fn>(dlsym(g_core, "inputReleaseTouchPoint"))) fn();
    }
}
JNIEXPORT void JNICALL
Java_org_yuzu_yuzu_1emu_utils_KenjiBridge_nativeInputSize(JNIEnv*, jobject, jint width, jint height) {
    if (!g_core) return;
    using Fn = void (*)(int, int);
    if (auto fn = reinterpret_cast<Fn>(dlsym(g_core, "inputSetClientSize"))) fn(width, height);
}

/**
 * Отдать ядру дескриптор окна Android.
 *
 * Официальное приложение зовёт это при каждом появлении поверхности: по
 * этому числу ядро применяет поворот и размер. Без него ядро берёт окно из
 * своего JNI-колбэка `getWindowHandle`, но явный вызов убирает разницу между
 * «окно ещё не спросили» и «окно сменилось».
 */
JNIEXPORT void JNICALL
Java_org_yuzu_yuzu_1emu_utils_KenjiBridge_nativeSetWindowHandle(JNIEnv*, jobject, jlong handle) {
    std::lock_guard<std::mutex> guard(g_lock);
    if (g_core == nullptr) return;
    using Fn = void (*)(long);
    if (auto fn = reinterpret_cast<Fn>(dlsym(g_core, "deviceSetWindowHandle"))) {
        g_inCore = 1;
        fn(static_cast<long>(handle));
        g_inCore = 0;
    }
}

/**
 * Включить собственный журнал графики ядра.
 *
 * ЗАЧЕМ. Когда кадров нет, снаружи видно только наш счётчик. Что именно
 * делает Vulkan — не знает никто, кроме самого ядра, а его журнал по
 * умолчанию про графику молчит. `loggingEnabledGraphicsLog` включает в нём
 * отдельный графический лог: выбор устройства, создание поверхности и
 * цепочки буферов, ошибки показа. Это единственный способ назвать причину, не
 * угадывая по адресам.
 *
 * Подпись сверена не по догадке: в официальном `KenjinxNative` это
 * `loggingEnabledGraphicsLog(enabled: Boolean)`, то есть `void(bool)`.
 */
// Related Android host exposes loggingSetEnabled(int logLevel, bool enabled).
// LogLevel.Stub is ordinal 1 in that source; the binary's export is verified.
JNIEXPORT jboolean JNICALL
Java_org_yuzu_yuzu_1emu_utils_KenjiBridge_nativeEnableHidStubLog(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> guard(g_lock);
    if (g_core == nullptr) return JNI_FALSE;
    using Fn = void (*)(int, bool);
    auto fn = reinterpret_cast<Fn>(dlsym(g_core, "loggingSetEnabled"));
    if (fn == nullptr) return JNI_FALSE;
    fn(1, true);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_org_yuzu_yuzu_1emu_utils_KenjiBridge_nativeGraphicsLog(JNIEnv*, jobject, jboolean on) {
    if (g_core == nullptr) return;
    using Fn = void (*)(bool);
    if (auto fn = reinterpret_cast<Fn>(dlsym(g_core, "loggingEnabledGraphicsLog"))) {
        g_inCore = 1;
        fn(on == JNI_TRUE);
        g_inCore = 0;
    }
}

/**
 * Сколько кадров в секунду считает САМО ядро (`deviceGetGameFrameRate`).
 *
 * ЗАЧЕМ. Наш счётчик растёт от колбэка `frameEnded()`; если имя колбэка в
 * ядре другое, он молчит даже когда картинка идёт. Счётчик ядра не зависит от
 * колбэка вовсе — это его собственный FPS. Два числа рядом отвечают на
 * вопрос, который иначе не решается: кадров нет на самом деле, или их не
 * видит только наш счётчик.
 */
JNIEXPORT jdouble JNICALL
Java_org_yuzu_yuzu_1emu_utils_KenjiBridge_nativeGameFrameRate(JNIEnv*, jobject) {
    if (g_core == nullptr) return 0.0;
    using Fn = double (*)();
    if (auto fn = reinterpret_cast<Fn>(dlsym(g_core, "deviceGetGameFrameRate"))) {
        g_inCore = 1;
        const double fps = fn();
        g_inCore = 0;
        return fps;
    }
    return 0.0;
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

// Stop and close are deliberately separate. Closing the device before the
// run-loop thread returns can destroy resources it is still using.
JNIEXPORT jboolean JNICALL
Java_org_yuzu_yuzu_1emu_utils_KenjiBridge_nativeSignalPlayStop(JNIEnv*, jobject) {
    void* core = nullptr;
    {
        std::lock_guard<std::mutex> guard(g_lock);
        core = g_core;
    }
    if (core == nullptr) return JNI_FALSE;
    using Fn = void (*)();
    auto fn = reinterpret_cast<Fn>(dlsym(core, "deviceSignalEmulationClose"));
    if (fn == nullptr) return JNI_FALSE;
    g_inCore = 1;
    fn();
    g_inCore = 0;
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_org_yuzu_yuzu_1emu_utils_KenjiBridge_nativeClosePlay(JNIEnv*, jobject) {
    void* core = nullptr;
    {
        std::lock_guard<std::mutex> guard(g_lock);
        core = g_core;
    }
    if (core == nullptr) return JNI_FALSE;
    using Fn = void (*)();
    auto fn = reinterpret_cast<Fn>(dlsym(core, "deviceCloseEmulation"));
    if (fn == nullptr) return JNI_FALSE;
    g_inCore = 1;
    fn();
    g_inCore = 0;
    return JNI_TRUE;
}


JNIEXPORT jlong JNICALL
Java_org_yuzu_yuzu_1emu_utils_KenjiBridge_nativeWindowOf(JNIEnv* env, jobject, jobject surface) {
    if (surface == nullptr) return -1;
    ANativeWindow* win = ANativeWindow_fromSurface(env, surface);
    if (win == nullptr) return -1;
    return reinterpret_cast<jlong>(win);
}

// Pairs the reference acquired by nativeWindowOf. The player calls this only
// after device close and all known native callers have stopped.
JNIEXPORT void JNICALL
Java_org_yuzu_yuzu_1emu_utils_KenjiBridge_nativeReleaseWindow(JNIEnv*, jobject, jlong handle) {
    if (handle > 0) ANativeWindow_release(reinterpret_cast<ANativeWindow*>(handle));
}

} // extern "C"

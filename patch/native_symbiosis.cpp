// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * @file native_symbiosis.cpp
 * @brief JNI bridge for the Symbiosis layer: diagnostics, self-test and the
 *        per-device tuning profile catalogue.
 */

#include <atomic>
#include <csignal>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include <dlfcn.h>
#include <fcntl.h>
#include <unistd.h>

#if defined(__aarch64__)
#include <ucontext.h>
#else
#include <unwind.h>
#endif

#include <jni.h>

#include "common/android/android_common.h"
#include "common/fs/path_util.h"
#include "common/logging.h"
#include "common/settings.h"
#include "common/symbiosis/abi_shim.h"
#include "common/symbiosis/device_profiles.h"
#include "common/symbiosis/driver_broker.h"
#include "common/symbiosis/memory_governor.h"
#include "common/symbiosis/selftest.h"
#include "common/symbiosis/auto_modes.h"
#include "common/symbiosis/firmware_vault.h"
#include "common/symbiosis/game_catalogue.h"
#include "common/symbiosis/rom_tools.h"
#include "common/symbiosis/settings_audit.h"
#include "common/symbiosis/frame_metrics.h"
#include "common/symbiosis/settings_guide.h"
#include "common/symbiosis/save_guard.h"
#include "common/symbiosis/mali_tuning.h"
#include "common/symbiosis/launcher_profiles.h"
#include "common/symbiosis/symbiosis_log.h"
#include "common/symbiosis/thermal_monitor.h"

#include "core/core.h"
#include "core/file_sys/fs_filesystem.h"
#include "core/crypto/key_manager.h"
#include "core/file_sys/content_archive.h"
#include "core/file_sys/registered_cache.h"
#include "core/file_sys/submission_package.h"
#include "core/loader/loader.h"
#include "frontend_common/content_manager.h"
#include "native.h"

namespace {

/// Encodes profiles for Kotlin as newline-separated records with a field
/// separator that cannot appear in the payload. Far simpler and cheaper than
/// building JNI objects, and keeps the Kotlin side dependency-free.
constexpr char kFieldSep = '\x1f';  // unit separator
constexpr char kRecordSep = '\x1e'; // record separator

std::string EncodeProfile(const Symbiosis::Profile& profile) {
    std::string out;
    out += profile.id;
    out += kFieldSep;
    out += profile.display_name;
    out += kFieldSep;
    out += profile.summary;
    out += kFieldSep;
    out += profile.expected_effect;
    out += kFieldSep;
    out += profile.works_on_stock_driver ? "1" : "0";
    out += kFieldSep;
    out += std::to_string(profile.tweaks.size());
    for (const auto& tweak : profile.tweaks) {
        out += kFieldSep;
        out += tweak.key;
        out += kFieldSep;
        out += tweak.value;
        out += kFieldSep;
        out += tweak.reason;
    }
    return out;
}

Symbiosis::GpuFamily CurrentFamily() {
    if (auto* primary = Symbiosis::GetDriverBroker().Primary()) {
        return primary->family;
    }
    return Symbiosis::GpuFamily::Unknown;
}

Symbiosis::DriverOrigin CurrentOrigin() {
    if (auto* primary = Symbiosis::GetDriverBroker().Primary()) {
        return primary->origin;
    }
    return Symbiosis::DriverOrigin::System;
}

// ── Отчёт о падении ОСНОВНОГО процесса ────────────────────────────────
//
// ЗАЧЕМ. Второе ядро живёт в `:kenji` и у него свой отчёт — его пишет мост.
// А падение самой эмуляции (libyuzu-android) до сих пор не оставляло ничего,
// кроме «Eden Symbiosis Debug остановлено» в шторке. Именно так выглядело
// падение при работе с панелью настроек: человек видел только факт.
//
// Обработчик сигнала пишет шапку и кадры стека из ucontext — по ним видно,
// какая библиотека виновата. Файл читается при СЛЕДУЮЩЕМ запуске и
// показывается диалогом: причина нужна сразу, а не в переписке.
//
// Закрываем предыдущий анонимный namespace и открываем свой: вставлять эти
// функции в чужой — значит менять его видимость и порядок объявлений.
} // Anonymous namespace

namespace {

char g_mainCrashPath[512] = {0};
std::atomic<int> g_mainInHandler{0};

/**
 * Что делало приложение в момент падения.
 *
 * Без метки в отчёте видно «libyuzu-android.so+0x…» и ничего больше. С меткой
 * понятно, что это была панель настроек, а не, скажем, старт игры. Метка
 * меняется на границах опасных действий — это одна запись строки, дешевле
 * любого лога.
 */
char g_mainStep[96] = "приложение";

void MainWriteFrame(int fd, uintptr_t pc) {
    if (pc == 0) return;
    Dl_info info{};
    char line[320];
    int n;
    if (::dladdr(reinterpret_cast<void*>(pc - 1), &info) != 0 && info.dli_fname != nullptr) {
        const char* slash = ::strrchr(info.dli_fname, '/');
        n = ::snprintf(line, sizeof(line), "pc=%s+0x%lx\n", slash ? slash + 1 : info.dli_fname,
                       static_cast<unsigned long>(pc -
                                                  reinterpret_cast<uintptr_t>(info.dli_fbase)));
    } else {
        n = ::snprintf(line, sizeof(line), "pc=0x%lx\n", static_cast<unsigned long>(pc));
    }
    if (n > 0) ::write(fd, line, static_cast<size_t>(n));
}

#if defined(__aarch64__)
void MainWriteFrames(int fd, void* uctx) {
    auto* uc = static_cast<ucontext_t*>(uctx);
    if (uc == nullptr) return;
    const uintptr_t pc = uc->uc_mcontext.pc;
    uintptr_t frame = uc->uc_mcontext.regs[29];
    const uintptr_t link = uc->uc_mcontext.regs[30];
    MainWriteFrame(fd, pc);
    MainWriteFrame(fd, link);
    for (int i = 0; i < 14 && frame != 0; ++i) {
        if ((frame & 0x7) != 0 || frame < 0x1000) break;
        const auto* words = reinterpret_cast<const uintptr_t*>(frame);
        const uintptr_t next = words[0];
        const uintptr_t ret = words[1];
        MainWriteFrame(fd, ret);
        if (next <= frame) break;
        frame = next;
    }
}
#else
struct MainUnwindState {
    int fd;
    int depth;
};

_Unwind_Reason_Code MainUnwindCallback(_Unwind_Context* ctx, void* arg) {
    auto* state = static_cast<MainUnwindState*>(arg);
    MainWriteFrame(state->fd, _Unwind_GetIP(ctx));
    return ++state->depth < 12 ? _URC_NO_REASON : _URC_END_OF_STACK;
}

void MainWriteFrames(int fd, void* uctx) {
    (void)uctx;
    MainUnwindState state{fd, 0};
    _Unwind_Backtrace(MainUnwindCallback, &state);
}
#endif

const char* MainSignalName(int sig) {
    switch (sig) {
    case SIGSEGV: return "SIGSEGV";
    case SIGABRT: return "SIGABRT";
    case SIGBUS: return "SIGBUS";
    case SIGILL: return "SIGILL";
    case SIGFPE: return "SIGFPE";
    default: return "SIG";
    }
}

void MainCrashHandler(int sig, siginfo_t* info, void* uctx) {
    if (g_mainInHandler.exchange(1) != 0) {
        ::signal(sig, SIG_DFL);
        ::raise(sig);
        return;
    }
    const int fd = ::open(g_mainCrashPath, O_WRONLY | O_CREAT | O_APPEND, 0600);
    if (fd >= 0) {
        char head[320];
        const int n = ::snprintf(head, sizeof(head),
                                 "crash signal=%d name=%s addr=%p code=%d step=%s\n", sig,
                                 MainSignalName(sig), info ? info->si_addr : nullptr,
                                 info ? info->si_code : 0, g_mainStep);
        if (n > 0) ::write(fd, head, static_cast<size_t>(n));
        MainWriteFrames(fd, uctx);
        ::close(fd);
    }
    ::signal(sig, SIG_DFL);
    ::raise(sig);
}

} // namespace

extern "C" {

/**
 * Папка, в которую ядро пишет свой журнал.
 *
 * Корень данных настраивается, поэтому «Android/data/<пакет>/files/log» —
 * только значение по умолчанию. Спрашиваем путь у самого ядра: журнал ищется
 * там, где его файловый backend действительно создаёт, и после переноса
 * данных в общую папку экран продолжает работать.
 */
/**
 * Включить отчёт о падении для основного процесса.
 *
 * Вызывается при входе в игру: с этого момента сигнал (SIGSEGV, SIGABRT и
 * прочие) оставляет в файле шапку и кадры стека. Файл создаётся сразу —
 * «есть, но пуст» значит «падения не было», и это тоже нужный ответ.
 */
JNIEXPORT void JNICALL
Java_org_yuzu_yuzu_1emu_utils_NativeSymbiosis_armMainCrashLog(JNIEnv* env, jobject,
                                                              jstring jpath) {
    const std::string path = Common::Android::GetJString(env, jpath);
    if (path.empty()) return;
    ::strncpy(g_mainCrashPath, path.c_str(), sizeof(g_mainCrashPath) - 1);
    g_mainCrashPath[sizeof(g_mainCrashPath) - 1] = '\0';

    const int fd = ::open(g_mainCrashPath, O_WRONLY | O_CREAT | O_TRUNC, 0600);
    if (fd >= 0) ::close(fd);

    // Альтернативный стек не ставим: обработчик пишет в файл, а на нём же
    // может не хватить места, если падение случилось внутри записи журнала.
    struct sigaction action {};
    action.sa_sigaction = MainCrashHandler;
    action.sa_flags = SA_SIGINFO | SA_ONSTACK;
    ::sigemptyset(&action.sa_mask);
    // Массив, а не список в фигурных скобках: последний требует
    // <initializer_list>, и на голом TU это вылезает ошибкой сборки.
    const int handled[] = {SIGSEGV, SIGABRT, SIGBUS, SIGILL, SIGFPE};
    for (const int sig : handled) {
        ::sigaction(sig, &action, nullptr);
    }
    LOG_INFO(Frontend, "[Symbiosis] main-process crash report armed: {}", g_mainCrashPath);
}

/**
 * Отметить, что делает приложение: строка попадёт в отчёт о падении.
 *
 * Не потокобезопасно осознанно: это метка для человека, а не данные, и
 * стоимость атомарной записи на каждый пункт меню не нужна. Худшее, что
 * может случиться, — в отчёте окажется соседняя по времени метка.
 */
JNIEXPORT void JNICALL
Java_org_yuzu_yuzu_1emu_utils_NativeSymbiosis_setMainStep(JNIEnv* env, jobject, jstring jstep) {
    const std::string step = Common::Android::GetJString(env, jstep);
    if (step.empty()) return;
    ::strncpy(g_mainStep, step.c_str(), sizeof(g_mainStep) - 1);
    g_mainStep[sizeof(g_mainStep) - 1] = '\0';
}

JNIEXPORT jstring JNICALL
Java_org_yuzu_yuzu_1emu_utils_NativeSymbiosis_logDirectory(JNIEnv* env, jobject) {
    const auto& dir = Common::FS::GetEdenPath(Common::FS::EdenPath::LogDir);
    return Common::Android::ToJString(env, dir.string());
}

JNIEXPORT jstring JNICALL
Java_org_yuzu_yuzu_1emu_utils_NativeSymbiosis_getDriverTopology(JNIEnv* env, jobject) {
    return Common::Android::ToJString(env,
                                      Symbiosis::GetDriverBroker().DescribeTopology());
}

JNIEXPORT jstring JNICALL
Java_org_yuzu_yuzu_1emu_utils_NativeSymbiosis_getMemoryState(JNIEnv* env, jobject) {
    auto& governor = Symbiosis::GetMemoryGovernor();
    governor.Initialise();
    return Common::Android::ToJString(env, governor.DescribeState());
}

JNIEXPORT jstring JNICALL
Java_org_yuzu_yuzu_1emu_utils_NativeSymbiosis_getMemoryJson(JNIEnv* env, jobject) {
    auto& governor = Symbiosis::GetMemoryGovernor();
    governor.Initialise();
    return Common::Android::ToJString(env, governor.DescribeJson());
}

JNIEXPORT jboolean JNICALL
Java_org_yuzu_yuzu_1emu_utils_NativeSymbiosis_previousSessionCrashed(JNIEnv*, jobject) {
    return Symbiosis::CrashAnalyst::PreviousSessionCrashed() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_org_yuzu_yuzu_1emu_utils_NativeSymbiosis_getShimReport(JNIEnv* env, jobject) {
    return Common::Android::ToJString(env, Symbiosis::GetAbiShim().DescribeResolutions());
}

JNIEXPORT jstring JNICALL
Java_org_yuzu_yuzu_1emu_utils_NativeSymbiosis_runSelfTest(JNIEnv* env, jobject) {
    const auto report = Symbiosis::RunSelfTest();
    return Common::Android::ToJString(env, report.ToText());
}

/// Returns "passed|warned|failed" so the UI can colour the summary chip.
JNIEXPORT jstring JNICALL
Java_org_yuzu_yuzu_1emu_utils_NativeSymbiosis_runSelfTestCounts(JNIEnv* env, jobject) {
    const auto report = Symbiosis::RunSelfTest();
    const std::string counts = std::to_string(report.passed) + "|" +
                               std::to_string(report.warned) + "|" +
                               std::to_string(report.failed);
    return Common::Android::ToJString(env, counts);
}

/// Detected GPU family as a display string.
JNIEXPORT jstring JNICALL
Java_org_yuzu_yuzu_1emu_utils_NativeSymbiosis_getDetectedGpu(JNIEnv* env, jobject) {
    std::string out = Symbiosis::ToString(CurrentFamily());
    out += " / ";
    out += Symbiosis::ToString(CurrentOrigin());
    return Common::Android::ToJString(env, out);
}

/// All profiles matching the detected hardware, encoded for Kotlin.
JNIEXPORT jstring JNICALL
Java_org_yuzu_yuzu_1emu_utils_NativeSymbiosis_getProfilesForDevice(JNIEnv* env, jobject) {
    const auto profiles =
        Symbiosis::GetProfileEngine().ProfilesFor(CurrentFamily(), CurrentOrigin());
    std::string out;
    for (std::size_t i = 0; i < profiles.size(); ++i) {
        if (i > 0) {
            out += kRecordSep;
        }
        out += EncodeProfile(profiles[i]);
    }
    return Common::Android::ToJString(env, out);
}

/// Every profile in the catalogue, so the user can pick one for other hardware.
JNIEXPORT jstring JNICALL
Java_org_yuzu_yuzu_1emu_utils_NativeSymbiosis_getAllProfiles(JNIEnv* env, jobject) {
    const auto& profiles = Symbiosis::GetProfileEngine().All();
    std::string out;
    for (std::size_t i = 0; i < profiles.size(); ++i) {
        if (i > 0) {
            out += kRecordSep;
        }
        out += EncodeProfile(profiles[i]);
    }
    return Common::Android::ToJString(env, out);
}

/// Human-readable explanation of a single profile.
JNIEXPORT jstring JNICALL
Java_org_yuzu_yuzu_1emu_utils_NativeSymbiosis_describeProfile(JNIEnv* env, jobject,
                                                              jstring j_id) {
    const std::string id = Common::Android::GetJString(env, j_id);
    auto& engine = Symbiosis::GetProfileEngine();
    for (const auto& profile : engine.All()) {
        if (profile.id == id) {
            return Common::Android::ToJString(env, engine.Describe(profile));
        }
    }
    return Common::Android::ToJString(env, "");
}

/// Applies a profile's tweaks to the live settings.
/// Returns the number of settings that were actually changed.
JNIEXPORT jint JNICALL
Java_org_yuzu_yuzu_1emu_utils_NativeSymbiosis_applyProfile(JNIEnv* env, jobject, jstring j_id) {
    const std::string id = Common::Android::GetJString(env, j_id);

    const Symbiosis::Profile* target = nullptr;
    for (const auto& profile : Symbiosis::GetProfileEngine().All()) {
        if (profile.id == id) {
            target = &profile;
            break;
        }
    }
    if (target == nullptr) {
        LOG_WARNING(Frontend, "[Symbiosis] applyProfile: unknown id '{}'", id);
        return 0;
    }

    int applied = 0;
    for (const auto& tweak : target->tweaks) {
        // Walk the settings linkage and set by name. Unknown keys are skipped
        // rather than treated as an error: the catalogue is shared across
        // versions and a key may not exist in every build.
        bool found = false;
        for (const auto& [category, settings] : Settings::values.linkage.by_category) {
            for (auto* setting : settings) {
                if (setting->GetLabel() != tweak.key) {
                    continue;
                }
                found = true;
                try {
                    // LoadString is the string-based setter every BasicSetting
                    // implements; it parses into the setting's real type.
                    setting->LoadString(tweak.value);
                    applied++;
                } catch (const std::exception& e) {
                    LOG_WARNING(Frontend, "[Symbiosis] could not set {}={}: {}", tweak.key,
                                tweak.value, e.what());
                }
                break;
            }
            if (found) {
                break;
            }
        }
        if (!found) {
            LOG_DEBUG(Frontend, "[Symbiosis] setting '{}' not present in this build", tweak.key);
        }
    }

    LOG_INFO(Frontend, "[Symbiosis] applied profile '{}': {} of {} setting(s)", id, applied,
             target->tweaks.size());
    return applied;
}

/**
 * Снимок кадров и звука для экрана «Ровность кадров».
 *
 * Возвращает текст «ключ=значение», который разбирает FrameMetricsText на
 * Kotlin: так формат нельзя разобрать «почти» — либо ключ есть, либо нет.
 */
JNIEXPORT jstring JNICALL
Java_org_yuzu_yuzu_1emu_utils_NativeSymbiosis_frameMetrics(JNIEnv* env, jobject) {
    const std::string snapshot = Symbiosis::FrameMetrics::Snapshot();
    return env->NewStringUTF(snapshot.c_str());
}

/** Начать новый замер: забыть кадры, провалы звука и сборки шейдеров. */
JNIEXPORT void JNICALL
Java_org_yuzu_yuzu_1emu_utils_NativeSymbiosis_resetFrameMetrics(JNIEnv*, jobject) {
    Symbiosis::FrameMetrics::Reset();
}

/**
 * Explains, in one sentence, why a specific ROM will not open.
 *
 * "check keys and firmware" is what the UI said before, and it was wrong
 * whenever the cause was something else - which, for an update NSP, it always
 * is. Kotlin sees only a bare false from GameMetadata::getIsValid, so every
 * cause looked identical.
 *
 * Eden already knows the answer precisely: the loader returns a ResultStatus
 * from a list of about seventy specific failures, and GetResultStatusString
 * turns it into English. Rather than re-deriving that badly, this asks for the
 * status and translates the handful that a user can actually act on, falling
 * back to the loader's own words for the rest.
 *
 * Verified against the official v0.2.1 build: an NSP holding only an update
 * yields ErrorNSPMissingProgramNCA (nsp.cpp:100), which is not a key problem
 * and cannot be fixed by reinstalling prod.keys.
 */
JNIEXPORT jstring JNICALL
Java_org_yuzu_yuzu_1emu_utils_NativeSymbiosis_diagnoseRom(JNIEnv* env, jobject,
                                                          jstring j_path) {
    const std::string path = Common::Android::GetJString(env, j_path);
    auto& system = EmulationSession::GetInstance().System();

    // Всё тело под try.
    //
    // Эта функция намеренно проходит путь загрузки игры, чтобы назвать
    // точную причину отказа - а значит наследует и способ упасть: на
    // повреждённом контейнере разбор бросает C++-исключение, которое
    // через JNI не проходит и убивает процесс. Зовут её из строки
    // состояния на главном экране, то есть при обычном просмотре списка.
    try {
    // Base keys first: without them nothing NCA-backed parses, and every later
    // message would be a red herring.
    if (Core::Crypto::KeyManager::Instance().BaseDeriveNecessary()) {
        return Common::Android::ToJString(
            env, "no_keys|prod.keys отсутствует или не подходит к этой прошивке");
    }

    const auto file = Core::GetGameFileFromPath(system.GetFilesystem(), path);
    if (!file) {
        return Common::Android::ToJString(
            env, "unreadable|файл не открывается — нет доступа или он удалён");
    }
    if (file->GetSize() == 0) {
        return Common::Android::ToJString(env, "empty|файл пустой (0 байт)");
    }

    auto loader = Loader::GetLoader(system, file, 0, 0);
    if (!loader) {
        return Common::Android::ToJString(
            env,
            "unknown_format|формат не распознан — файл повреждён или скачан не полностью");
    }

    // ReadProgramId walks the same path a launch does, without starting one,
    // so its status is the real reason rather than an approximation.
    u64 program_id = 0;
    const auto status = loader->ReadProgramId(program_id);
    if (status == Loader::ResultStatus::Success) {
        // А вот здесь прошлая проверка расходилась с настоящей.
        //
        // getIsValid (game_metadata.cpp:239) требует больше, чем ReadProgramId:
        // для NSP и XCI он дополнительно зовёт IsBootableGameContainer, а тот
        // ищет внутри NCA с ролью Application/Program. Файл с целым
        // оглавлением, но без такого NCA проходил первую проверку, падал на
        // второй — и в шторке оставалось «ядро не принимает этот файл» без
        // причины. Ровно это и случилось с распакованным NSP на устройстве.
        const auto file_type = loader->GetFileType();
        if (Loader::IsContainerType(file_type) &&
            !Loader::IsBootableGameContainer(file, file_type)) {
            std::string why;
            if (Symbiosis::RomTools::DetectFormat(path) == Symbiosis::DumpFormat::Nsp) {
                why = Symbiosis::RomTools::DescribeContainer(path);
            } else {
                why = "внутри нет NCA с программой игры: вероятно, это обновление "
                      "или DLC, а не сама игра";
            }
            if (why.empty()) {
                why = "оглавление целое, но программу игры ядро внутри не нашло";
            }
            return Common::Android::ToJString(env, "not_bootable|" + why);
        }
        return Common::Android::ToJString(env, "ok|файл читается");
    }

    std::string message;
    switch (status) {
    case Loader::ResultStatus::ErrorNSPMissingProgramNCA:
    case Loader::ResultStatus::ErrorXCIMissingProgramNCA:
        message =
            "update_only|внутри нет самой игры — это обновление или DLC. "
            "Его нужно установить (Инструменты → Установить), а запускать "
            "базовую версию [v0]";
        break;
    case Loader::ResultStatus::ErrorMissingProductionKeyFile:
        message = "no_keys|нет prod.keys — положи его в папку keys";
        break;
    case Loader::ResultStatus::ErrorMissingTitlekey:
    case Loader::ResultStatus::ErrorIncorrectTitlekeyOrTitlekek:
    case Loader::ResultStatus::ErrorMissingTitlekek:
        message =
            "no_title_key|нет ключа именно для этой игры — нужен title key "
            "из полного дампа ключей";
        break;
    case Loader::ResultStatus::ErrorMissingHeaderKey:
    case Loader::ResultStatus::ErrorIncorrectHeaderKey:
        message = "bad_keys|header_key неверный — prod.keys от другой прошивки";
        break;
    case Loader::ResultStatus::ErrorMissingKeyAreaKey:
    case Loader::ResultStatus::ErrorIncorrectKeyAreaKey:
        message = "bad_keys|ключи не подходят к этому файлу — нужен полный prod.keys";
        break;
    case Loader::ResultStatus::ErrorBadNCAHeader:
    case Loader::ResultStatus::ErrorBadPFSHeader:
    case Loader::ResultStatus::ErrorIncorrectPFSFileSize:
    case Loader::ResultStatus::ErrorBadXCIHeader:
        message = "corrupt|файл повреждён или скачан не полностью";
        break;
    case Loader::ResultStatus::ErrorNoPackedUpdate:
    case Loader::ResultStatus::ErrorMissingBKTRBaseRomFS:
        message =
            "update_only|это патч без базовой игры — сначала установи "
            "базовую версию, потом обновление";
        break;
    default:
        // The loader's own wording, so a rare failure is still named rather
        // than flattened into "unknown".
        message = "other|" + Loader::GetResultStatusString(status);
        break;
    }
    return Common::Android::ToJString(env, message);
    } catch (const std::exception& e) {
        return Common::Android::ToJString(
            env, std::string{"corrupt|файл не читается: "} + e.what());
    } catch (...) {
        return Common::Android::ToJString(env, "corrupt|файл не читается");
    }
}

/// Live thermal state as "state|tempC|gpuClockPercent|summary|advice".
JNIEXPORT jstring JNICALL
Java_org_yuzu_yuzu_1emu_utils_NativeSymbiosis_getThermalState(JNIEnv* env, jobject) {
    const auto reading = Symbiosis::GetThermalMonitor().Sample();
    std::string out = Symbiosis::ToString(reading.state);
    out += '|';
    out += std::to_string(reading.max_temp_millic > 0 ? reading.max_temp_millic / 1000 : -1);
    out += '|';
    out += std::to_string(reading.gpu_clock_percent);
    out += '|';
    out += reading.summary;
    out += '|';
    out += reading.advice;
    return Common::Android::ToJString(env, out);
}


// --- Launchers -----------------------------------------------------------

/// All launcher skins encoded as records:
/// key | name | description | perfNote | accentARGB | bgARGB | cardRadius |
/// gridColumns | wideCards | virtualW | virtualH | colorLevels | totalColors
JNIEXPORT jstring JNICALL
Java_org_yuzu_yuzu_1emu_utils_NativeSymbiosis_getLaunchers(JNIEnv* env, jobject) {
    std::string out;
    const auto& all = Symbiosis::GetLauncherCatalogue().All();
    for (std::size_t i = 0; i < all.size(); ++i) {
        if (i > 0) {
            out += kRecordSep;
        }
        const auto& p = all[i];
        out += p.key;
        out += kFieldSep;
        out += p.display_name;
        out += kFieldSep;
        out += p.description;
        out += kFieldSep;
        out += p.performance_note;
        out += kFieldSep;
        out += std::to_string(p.skin.accent_argb);
        out += kFieldSep;
        out += std::to_string(p.skin.background_argb);
        out += kFieldSep;
        out += std::to_string(p.skin.card_radius_dp);
        out += kFieldSep;
        out += std::to_string(p.skin.grid_columns);
        out += kFieldSep;
        out += p.skin.wide_cards ? "1" : "0";
        out += kFieldSep;
        out += std::to_string(static_cast<int>(p.retro.virtual_width));
        out += kFieldSep;
        out += std::to_string(static_cast<int>(p.retro.virtual_height));
        out += kFieldSep;
        out += std::to_string(static_cast<int>(p.retro.color_levels));
        out += kFieldSep;
        out += std::to_string(p.retro.ColorCount());
    }
    return Common::Android::ToJString(env, out);
}

/// Index of the active launcher in the catalogue.
JNIEXPORT jint JNICALL
Java_org_yuzu_yuzu_1emu_utils_NativeSymbiosis_getActiveLauncher(JNIEnv*, jobject) {
    return static_cast<jint>(Settings::values.symbiosis_launcher.GetValue());
}

/// Selects a launcher by catalogue index and applies the settings it implies.
JNIEXPORT jint JNICALL
Java_org_yuzu_yuzu_1emu_utils_NativeSymbiosis_setActiveLauncher(JNIEnv*, jobject, jint index) {
    const auto& all = Symbiosis::GetLauncherCatalogue().All();
    if (index < 0 || static_cast<std::size_t>(index) >= all.size()) {
        return 0;
    }
    Settings::values.symbiosis_launcher.SetValue(static_cast<u32>(index));

    const auto& profile = all[static_cast<std::size_t>(index)];
    int applied = 0;
    for (const auto& [key, value] : profile.settings) {
        for (const auto& [category, settings] : Settings::values.linkage.by_category) {
            bool done = false;
            for (auto* setting : settings) {
                if (setting->GetLabel() != key) {
                    continue;
                }
                try {
                    setting->LoadString(value);
                    applied++;
                } catch (const std::exception& e) {
                    Symbiosis::LogWarning(Symbiosis::LogArea::Profile,
                                          std::string{"could not apply "} + key + ": " + e.what());
                }
                done = true;
                break;
            }
            if (done) {
                break;
            }
        }
    }

    Symbiosis::LogInfo(Symbiosis::LogArea::Profile,
                       "launcher set to '" + profile.display_name + "' (" +
                           std::to_string(applied) + " setting(s) applied)");
    return applied;
}

// --- Logs ----------------------------------------------------------------

/// @param area      Area index, ignored when @p allAreas is true.
/// @param allAreas  Include every area.
/// @param minLevel  0 debug, 1 info, 2 warning, 3 error.
JNIEXPORT jstring JNICALL
Java_org_yuzu_yuzu_1emu_utils_NativeSymbiosis_getLogDump(JNIEnv* env, jobject, jint area,
                                                         jboolean allAreas, jint minLevel) {
    const auto area_enum = static_cast<Symbiosis::LogArea>(
        area >= 0 && area < static_cast<jint>(Symbiosis::LogArea::COUNT) ? area : 0);
    const auto level_enum = static_cast<Symbiosis::LogLevel>(
        minLevel >= 0 && minLevel <= 3 ? minLevel : 0);
    return Common::Android::ToJString(
        env, Symbiosis::GetLog().Dump(area_enum, allAreas == JNI_TRUE, level_enum));
}

JNIEXPORT void JNICALL
Java_org_yuzu_yuzu_1emu_utils_NativeSymbiosis_clearLog(JNIEnv*, jobject) {
    Symbiosis::GetLog().Clear();
}

JNIEXPORT jint JNICALL
Java_org_yuzu_yuzu_1emu_utils_NativeSymbiosis_getLogCount(JNIEnv*, jobject) {
    return static_cast<jint>(Symbiosis::GetLog().Count());
}

/// Warning+error counts per area, joined with '|'.
JNIEXPORT jstring JNICALL
Java_org_yuzu_yuzu_1emu_utils_NativeSymbiosis_getLogProblemCounts(JNIEnv* env, jobject) {
    const auto counts = Symbiosis::GetLog().ProblemCounts();
    std::string out;
    for (std::size_t i = 0; i < counts.size(); ++i) {
        if (i > 0) {
            out += '|';
        }
        out += std::to_string(counts[i]);
    }
    return Common::Android::ToJString(env, out);
}

// --- Thermal policy ------------------------------------------------------

/// "shouldWarn|overCeiling|restMinutes|title|body" for the current reading and
/// the user's configured thresholds.
JNIEXPORT jstring JNICALL
Java_org_yuzu_yuzu_1emu_utils_NativeSymbiosis_getThermalAdvice(JNIEnv* env, jobject) {
    const auto reading = Symbiosis::GetThermalMonitor().Sample();
    const auto verdict = Symbiosis::EvaluateThermalPolicy(
        reading, Settings::values.symbiosis_temp_ceiling.GetValue(),
        Settings::values.symbiosis_temp_warning.GetValue());

    std::string out;
    out += verdict.should_warn ? "1" : "0";
    out += '|';
    out += verdict.over_ceiling ? "1" : "0";
    out += '|';
    out += std::to_string(verdict.suggested_rest_minutes);
    out += '|';
    out += verdict.title;
    out += '|';
    out += verdict.body;
    return Common::Android::ToJString(env, out);
}


// --- Crash guard ---------------------------------------------------------

/// True when the layer disabled itself because the previous run crashed.
JNIEXPORT jboolean JNICALL
Java_org_yuzu_yuzu_1emu_utils_NativeSymbiosis_isSafeMode(JNIEnv*, jobject) {
    return Symbiosis::CrashGuard::PreviousRunCrashed() ? JNI_TRUE : JNI_FALSE;
}

/// Clears the crash marker so the layer runs again next time.
JNIEXPORT void JNICALL
Java_org_yuzu_yuzu_1emu_utils_NativeSymbiosis_clearSafeMode(JNIEnv*, jobject) {
    Symbiosis::CrashGuard::Reset();
    Symbiosis::LogInfo(Symbiosis::LogArea::General, "safe mode cleared by user");
}


// --- Auto modes ----------------------------------------------------------

/// Modes for this hardware:
/// key | name | summary | detail | tempCeiling | tweakCount, then key/value/reason triples
JNIEXPORT jstring JNICALL
Java_org_yuzu_yuzu_1emu_utils_NativeSymbiosis_getAutoModes(JNIEnv* env, jobject) {
    Symbiosis::GpuFamily family = Symbiosis::GpuFamily::Unknown;
    Symbiosis::DriverOrigin origin = Symbiosis::DriverOrigin::System;
    if (auto* primary = Symbiosis::GetDriverBroker().Primary()) {
        family = primary->family;
        origin = primary->origin;
    }

    const auto modes = Symbiosis::GetAutoModeEngine().AllFor(family, origin);
    std::string out;
    for (std::size_t i = 0; i < modes.size(); ++i) {
        if (i > 0) {
            out += kRecordSep;
        }
        const auto& m = modes[i];
        out += m.key;
        out += kFieldSep;
        out += m.display_name;
        out += kFieldSep;
        out += m.summary;
        out += kFieldSep;
        out += m.detail;
        out += kFieldSep;
        out += std::to_string(m.temp_ceiling);
        out += kFieldSep;
        out += std::to_string(static_cast<u32>(m.mode));
        out += kFieldSep;
        out += std::to_string(m.tweaks.size());
        for (const auto& t : m.tweaks) {
            out += kFieldSep;
            out += t.key;
            out += kFieldSep;
            out += t.value;
            out += kFieldSep;
            out += t.reason;
        }
    }
    return Common::Android::ToJString(env, out);
}

/// Applies a mode by enum value. Returns the number of settings changed.
JNIEXPORT jint JNICALL
Java_org_yuzu_yuzu_1emu_utils_NativeSymbiosis_applyAutoMode(JNIEnv*, jobject, jint mode) {
    if (mode < 0 || mode >= static_cast<jint>(Symbiosis::AutoMode::COUNT)) {
        return 0;
    }
    Symbiosis::GpuFamily family = Symbiosis::GpuFamily::Unknown;
    Symbiosis::DriverOrigin origin = Symbiosis::DriverOrigin::System;
    if (auto* primary = Symbiosis::GetDriverBroker().Primary()) {
        family = primary->family;
        origin = primary->origin;
    }
    return static_cast<jint>(Symbiosis::GetAutoModeEngine().Apply(
        static_cast<Symbiosis::AutoMode>(mode), family, origin));
}

JNIEXPORT jint JNICALL
Java_org_yuzu_yuzu_1emu_utils_NativeSymbiosis_getCurrentAutoMode(JNIEnv*, jobject) {
    return static_cast<jint>(Symbiosis::AutoModeEngine::Current());
}


// --- Mali tuning ---------------------------------------------------------

/// Human-readable Mali report, or empty when this is not a Mali GPU.
JNIEXPORT jstring JNICALL
Java_org_yuzu_yuzu_1emu_utils_NativeSymbiosis_getMaliReport(JNIEnv* env, jobject) {
    const auto traits = Symbiosis::MaliTuning::Current();
    if (traits.generation == Symbiosis::MaliGeneration::NotMali) {
        return Common::Android::ToJString(env, "");
    }
    const auto advice = Symbiosis::MaliTuning::Advise(traits);
    return Common::Android::ToJString(env, Symbiosis::MaliTuning::Describe(traits, advice));
}

/// Driver suggestions: name | description | url | isSystem | verdict
JNIEXPORT jstring JNICALL
Java_org_yuzu_yuzu_1emu_utils_NativeSymbiosis_getDriverSuggestions(JNIEnv* env, jobject) {
    const auto suggestions = Symbiosis::SuggestDrivers(Symbiosis::MaliTuning::Current());
    std::string out;
    for (std::size_t i = 0; i < suggestions.size(); ++i) {
        if (i > 0) {
            out += kRecordSep;
        }
        const auto& d = suggestions[i];
        out += d.name;
        out += kFieldSep;
        out += d.description;
        out += kFieldSep;
        out += d.url;
        out += kFieldSep;
        out += d.is_system ? "1" : "0";
        out += kFieldSep;
        out += d.verdict;
    }
    return Common::Android::ToJString(env, out);
}

JNIEXPORT jstring JNICALL
Java_org_yuzu_yuzu_1emu_utils_NativeSymbiosis_getFirmwareAdvice(JNIEnv* env, jobject) {
    return Common::Android::ToJString(env,
                                      Symbiosis::FirmwareAdvice(Symbiosis::MaliTuning::Current()));
}

// --- Catalogue -----------------------------------------------------------

/// Homebrew: name | author | description | url | license | isTest | usesGpu
JNIEXPORT jstring JNICALL
Java_org_yuzu_yuzu_1emu_utils_NativeSymbiosis_getHomebrew(JNIEnv* env, jobject) {
    const auto& list = Symbiosis::GetGameCatalogue().Homebrew();
    std::string out;
    for (std::size_t i = 0; i < list.size(); ++i) {
        if (i > 0) {
            out += kRecordSep;
        }
        const auto& h = list[i];
        out += h.name;
        out += kFieldSep;
        out += h.author;
        out += kFieldSep;
        out += h.description;
        out += kFieldSep;
        out += h.url;
        out += kFieldSep;
        out += h.license;
        out += kFieldSep;
        out += h.is_test_tool ? "1" : "0";
        out += kFieldSep;
        out += h.exercises_gpu ? "1" : "0";
    }
    return Common::Android::ToJString(env, out);
}

/// Compatibility: title | rating | recommendedMode | note | memoryHeavy
JNIEXPORT jstring JNICALL
Java_org_yuzu_yuzu_1emu_utils_NativeSymbiosis_getCompatList(JNIEnv* env, jobject) {
    const auto& list = Symbiosis::GetGameCatalogue().Compatibility();
    std::string out;
    for (std::size_t i = 0; i < list.size(); ++i) {
        if (i > 0) {
            out += kRecordSep;
        }
        const auto& c = list[i];
        out += c.title;
        out += kFieldSep;
        out += Symbiosis::ToString(c.rating);
        out += kFieldSep;
        out += std::to_string(c.recommended_mode);
        out += kFieldSep;
        out += c.note;
        out += kFieldSep;
        out += c.memory_heavy ? "1" : "0";
    }
    return Common::Android::ToJString(env, out);
}

/// Advice for one title, or empty when it is not in the database.
JNIEXPORT jstring JNICALL
Java_org_yuzu_yuzu_1emu_utils_NativeSymbiosis_lookupCompat(JNIEnv* env, jobject,
                                                           jstring j_title) {
    const std::string title = Common::Android::GetJString(env, j_title);
    const auto* entry = Symbiosis::GetGameCatalogue().Lookup(title);
    if (entry == nullptr) {
        return Common::Android::ToJString(env, "");
    }
    std::string out = entry->title;
    out += kFieldSep;
    out += Symbiosis::ToString(entry->rating);
    out += kFieldSep;
    out += std::to_string(entry->recommended_mode);
    out += kFieldSep;
    out += entry->note;
    out += kFieldSep;
    out += entry->memory_heavy ? "1" : "0";
    return Common::Android::ToJString(env, out);
}


// --- Utilities: firmware vault -------------------------------------------

JNIEXPORT jstring JNICALL
Java_org_yuzu_yuzu_1emu_utils_NativeSymbiosis_analyseFirmware(JNIEnv* env, jobject,
                                                              jstring j_dir) {
    const std::string dir = Common::Android::GetJString(env, j_dir);
    const auto analysis = Symbiosis::FirmwareVault::Analyse(dir);
    // total|essential|font|applet|language|entries|prunable
    std::string out = std::to_string(analysis.total_bytes);
    out += '|' + std::to_string(analysis.essential_bytes);
    out += '|' + std::to_string(analysis.font_bytes);
    out += '|' + std::to_string(analysis.applet_bytes);
    out += '|' + std::to_string(analysis.language_bytes);
    out += '|' + std::to_string(analysis.entry_count);
    out += '|' + std::to_string(analysis.prunable_bytes);
    return Common::Android::ToJString(env, out);
}

JNIEXPORT jlong JNICALL
Java_org_yuzu_yuzu_1emu_utils_NativeSymbiosis_pruneFirmware(JNIEnv* env, jobject, jstring j_dir,
                                                            jboolean keep_applets,
                                                            jboolean keep_fonts,
                                                            jboolean keep_languages) {
    const std::string dir = Common::Android::GetJString(env, j_dir);
    const auto analysis = Symbiosis::FirmwareVault::Analyse(dir);
    Symbiosis::PruneOptions options{};
    options.keep_applets = keep_applets == JNI_TRUE;
    options.keep_extra_fonts = keep_fonts == JNI_TRUE;
    options.keep_extra_languages = keep_languages == JNI_TRUE;
    return static_cast<jlong>(Symbiosis::FirmwareVault::Prune(dir, analysis, options));
}

JNIEXPORT jlong JNICALL
Java_org_yuzu_yuzu_1emu_utils_NativeSymbiosis_estimateFirmware(JNIEnv* env, jobject,
                                                               jstring j_dir,
                                                               jboolean keep_applets,
                                                               jboolean keep_fonts,
                                                               jboolean keep_languages) {
    const std::string dir = Common::Android::GetJString(env, j_dir);
    const auto analysis = Symbiosis::FirmwareVault::Analyse(dir);
    Symbiosis::PruneOptions options{};
    options.keep_applets = keep_applets == JNI_TRUE;
    options.keep_extra_fonts = keep_fonts == JNI_TRUE;
    options.keep_extra_languages = keep_languages == JNI_TRUE;
    return static_cast<jlong>(Symbiosis::FirmwareVault::EstimatePrunedSize(analysis, options));
}

JNIEXPORT jstring JNICALL
Java_org_yuzu_yuzu_1emu_utils_NativeSymbiosis_getCompressionNote(JNIEnv* env, jobject) {
    return Common::Android::ToJString(env, Symbiosis::FirmwareVault::CompressionNote());
}

// --- Utilities: ROM tools ------------------------------------------------

/// filename|format|health|size|validEnd|reclaimable|summary|advice
JNIEXPORT jstring JNICALL
Java_org_yuzu_yuzu_1emu_utils_NativeSymbiosis_inspectDump(JNIEnv* env, jobject, jstring j_path) {
    const std::string path = Common::Android::GetJString(env, j_path);
    const auto r = Symbiosis::RomTools::Inspect(path);
    std::string out = r.filename;
    out += kFieldSep;
    out += Symbiosis::ToString(r.format);
    out += kFieldSep;
    out += Symbiosis::ToString(r.health);
    out += kFieldSep;
    out += std::to_string(r.file_size);
    out += kFieldSep;
    out += std::to_string(r.valid_data_end);
    out += kFieldSep;
    out += std::to_string(r.reclaimable);
    out += kFieldSep;
    out += r.summary;
    out += kFieldSep;
    out += r.advice;
    return Common::Android::ToJString(env, out);
}

/// Returns bytes reclaimed, or a negative code with the message in the log.
JNIEXPORT jlong JNICALL
Java_org_yuzu_yuzu_1emu_utils_NativeSymbiosis_trimXci(JNIEnv* env, jobject, jstring j_path) {
    const std::string path = Common::Android::GetJString(env, j_path);
    std::string error;
    const u64 freed = Symbiosis::RomTools::TrimXci(path, error);
    if (freed == 0 && !error.empty()) {
        Symbiosis::LogWarning(Symbiosis::LogArea::General, "trim: " + error);
    }
    return static_cast<jlong>(freed);
}

JNIEXPORT jstring JNICALL
Java_org_yuzu_yuzu_1emu_utils_NativeSymbiosis_convertXciToNsp(JNIEnv* env, jobject,
                                                              jstring j_src, jstring j_dst) {
    const std::string src = Common::Android::GetJString(env, j_src);
    const std::string dst = Common::Android::GetJString(env, j_dst);
    std::string error;
    const u64 written = Symbiosis::RomTools::XciToNsp(src, dst, error);
    // "bytes|error" so the UI can show the reason rather than a bare failure.
    return Common::Android::ToJString(env, std::to_string(written) + "|" + error);
}

JNIEXPORT jstring JNICALL
Java_org_yuzu_yuzu_1emu_utils_NativeSymbiosis_getRomCompressionNote(JNIEnv* env, jobject) {
    return Common::Android::ToJString(env, Symbiosis::RomTools::CompressionNote());
}

/**
 * Куда распаковке класть временные части.
 *
 * Путь приходит из Kotlin: temp_directory_path() на Android не работает (см.
 * RomTools::SetWorkDir), а значит без этого вызова распаковка NSZ/XCZ
 * заканчивалась ничем.
 */
JNIEXPORT void JNICALL
Java_org_yuzu_yuzu_1emu_utils_NativeSymbiosis_setRomWorkDir(JNIEnv* env, jobject, jstring j_dir) {
    Symbiosis::RomTools::SetWorkDir(j_dir ? Common::Android::GetJString(env, j_dir) : std::string{});
}

/// "bytes|error"
JNIEXPORT jstring JNICALL
Java_org_yuzu_yuzu_1emu_utils_NativeSymbiosis_decompressDump(JNIEnv* env, jobject,
                                                             jstring j_src, jstring j_dst) {
    const std::string src = Common::Android::GetJString(env, j_src);
    const std::string dst = Common::Android::GetJString(env, j_dst);
    std::string error;
    const u64 written = Symbiosis::RomTools::Decompress(src, dst, error);
    return Common::Android::ToJString(env, std::to_string(written) + "|" + error);
}

// --- Utilities: save vault -----------------------------------------------

JNIEXPORT void JNICALL
Java_org_yuzu_yuzu_1emu_utils_NativeSymbiosis_configureVault(JNIEnv* env, jobject, jstring j_dir,
                                                             jint keep) {
    Symbiosis::SaveVault::Configure(Common::Android::GetJString(env, j_dir),
                                    static_cast<u32>(keep < 1 ? 1 : keep));
}

JNIEXPORT jlong JNICALL
Java_org_yuzu_yuzu_1emu_utils_NativeSymbiosis_backupSaves(JNIEnv* env, jobject, jstring j_save_dir,
                                                          jstring j_title, jstring j_label) {
    return static_cast<jlong>(Symbiosis::SaveVault::Backup(
        Common::Android::GetJString(env, j_save_dir),
        Common::Android::GetJString(env, j_title),
        Common::Android::GetJString(env, j_label)));
}

/// path|titleId|label|size|timestamp|files
JNIEXPORT jstring JNICALL
Java_org_yuzu_yuzu_1emu_utils_NativeSymbiosis_listBackups(JNIEnv* env, jobject) {
    const auto list = Symbiosis::SaveVault::List();
    std::string out;
    for (std::size_t i = 0; i < list.size(); ++i) {
        if (i > 0) {
            out += kRecordSep;
        }
        const auto& b = list[i];
        out += b.path;
        out += kFieldSep;
        out += b.title_id;
        out += kFieldSep;
        out += b.label;
        out += kFieldSep;
        out += std::to_string(b.size_bytes);
        out += kFieldSep;
        out += std::to_string(b.timestamp);
        out += kFieldSep;
        out += std::to_string(b.file_count);
    }
    return Common::Android::ToJString(env, out);
}

JNIEXPORT jstring JNICALL
Java_org_yuzu_yuzu_1emu_utils_NativeSymbiosis_restoreBackup(JNIEnv* env, jobject, jstring j_path,
                                                            jstring j_save_dir) {
    Symbiosis::SaveBackup backup{};
    backup.path = Common::Android::GetJString(env, j_path);
    std::string error;
    const bool ok = Symbiosis::SaveVault::Restore(
        backup, Common::Android::GetJString(env, j_save_dir), error);
    return Common::Android::ToJString(env, ok ? "" : error);
}

JNIEXPORT jstring JNICALL
Java_org_yuzu_yuzu_1emu_utils_NativeSymbiosis_getVaultStatus(JNIEnv* env, jobject) {
    return Common::Android::ToJString(env, Symbiosis::SaveVault::Describe());
}

// --- Utilities: crash analyst --------------------------------------------

JNIEXPORT jstring JNICALL
Java_org_yuzu_yuzu_1emu_utils_NativeSymbiosis_analyseCrash(JNIEnv* env, jobject) {
    const auto findings = Symbiosis::CrashAnalyst::Analyse();
    return Common::Android::ToJString(env, Symbiosis::CrashAnalyst::Describe(findings));
}

JNIEXPORT jint JNICALL
Java_org_yuzu_yuzu_1emu_utils_NativeSymbiosis_crashFindingCount(JNIEnv*, jobject) {
    return static_cast<jint>(Symbiosis::CrashAnalyst::Analyse().size());
}


// --- Settings guide ------------------------------------------------------

/// key | title | section | risk | what | cost | advice
JNIEXPORT jstring JNICALL
Java_org_yuzu_yuzu_1emu_utils_NativeSymbiosis_getGuideEntries(JNIEnv* env, jobject,
                                                              jint section, jboolean all) {
    const auto& guide = Symbiosis::GetSettingsGuide();
    std::vector<Symbiosis::GuideEntry> list;
    if (all == JNI_TRUE || section < 0 ||
        section >= static_cast<jint>(Symbiosis::GuideSection::COUNT)) {
        list = guide.All();
    } else {
        list = guide.ForSection(static_cast<Symbiosis::GuideSection>(section));
    }

    std::string out;
    for (std::size_t i = 0; i < list.size(); ++i) {
        if (i > 0) {
            out += kRecordSep;
        }
        const auto& e = list[i];
        out += e.key;
        out += kFieldSep;
        out += e.title;
        out += kFieldSep;
        out += Symbiosis::ToString(e.section);
        out += kFieldSep;
        out += Symbiosis::ToString(e.risk);
        out += kFieldSep;
        out += e.what;
        out += kFieldSep;
        out += e.cost;
        out += kFieldSep;
        out += e.advice;
    }
    return Common::Android::ToJString(env, out);
}

/// Guide entry for one settings key, or empty.
JNIEXPORT jstring JNICALL
Java_org_yuzu_yuzu_1emu_utils_NativeSymbiosis_getGuideForKey(JNIEnv* env, jobject,
                                                             jstring j_key) {
    const std::string key = Common::Android::GetJString(env, j_key);
    const auto* e = Symbiosis::GetSettingsGuide().ForKey(key);
    if (e == nullptr) {
        return Common::Android::ToJString(env, "");
    }
    std::string out = e->key;
    out += kFieldSep;
    out += e->title;
    out += kFieldSep;
    out += Symbiosis::ToString(e->section);
    out += kFieldSep;
    out += Symbiosis::ToString(e->risk);
    out += kFieldSep;
    out += e->what;
    out += kFieldSep;
    out += e->cost;
    out += kFieldSep;
    out += e->advice;
    return Common::Android::ToJString(env, out);
}


// --- Settings audit ------------------------------------------------------

/// Full launch report: which settings took effect and which did not.
JNIEXPORT jstring JNICALL
Java_org_yuzu_yuzu_1emu_utils_NativeSymbiosis_getSettingsAudit(JNIEnv* env, jobject) {
    return Common::Android::ToJString(env, Symbiosis::SettingsAudit::Describe());
}

/// Structured audit for the list UI: key, requested, effective, verdict,
/// remedy, reason, evidence, suggested value.
JNIEXPORT jstring JNICALL
Java_org_yuzu_yuzu_1emu_utils_NativeSymbiosis_getAuditEntries(JNIEnv* env, jobject) {
    const auto entries = Symbiosis::SettingsAudit::Run();
    std::string out;
    for (const auto& e : entries) {
        if (!out.empty()) {
            out += kRecordSep;
        }
        out += e.key;
        out += kFieldSep;
        out += e.requested;
        out += kFieldSep;
        out += e.effective;
        out += kFieldSep;
        out += std::to_string(static_cast<int>(e.verdict));
        out += kFieldSep;
        out += std::to_string(static_cast<int>(e.remedy));
        out += kFieldSep;
        out += e.reason;
        out += kFieldSep;
        out += e.evidence;
        out += kFieldSep;
        out += e.suggested_value;
    }
    return Common::Android::ToJString(env, out);
}

/// Counts by verdict: applied, substituted, ignored, unsupported, fixable.
JNIEXPORT jstring JNICALL
Java_org_yuzu_yuzu_1emu_utils_NativeSymbiosis_getAuditSummary(JNIEnv* env, jobject) {
    const auto s = Symbiosis::SettingsAudit::Summarise();
    std::string out = std::to_string(s.applied);
    out += kFieldSep;
    out += std::to_string(s.substituted);
    out += kFieldSep;
    out += std::to_string(s.ignored);
    out += kFieldSep;
    out += std::to_string(s.unsupported);
    out += kFieldSep;
    out += std::to_string(s.fixable);
    return Common::Android::ToJString(env, out);
}

/// True once a game has run and the GPU device has reported its facts.
JNIEXPORT jboolean JNICALL
Java_org_yuzu_yuzu_1emu_utils_NativeSymbiosis_auditHasData(JNIEnv*, jobject) {
    return Symbiosis::SettingsAudit::HasData() ? JNI_TRUE : JNI_FALSE;
}

/// Applies every safe correction. Returns the number of settings changed.
JNIEXPORT jint JNICALL
Java_org_yuzu_yuzu_1emu_utils_NativeSymbiosis_auditAutoFix(JNIEnv*, jobject) {
    return static_cast<jint>(Symbiosis::SettingsAudit::AutoFix());
}

/// What AutoFix would change, without changing it.
JNIEXPORT jstring JNICALL
Java_org_yuzu_yuzu_1emu_utils_NativeSymbiosis_previewAuditAutoFix(JNIEnv* env, jobject) {
    return Common::Android::ToJString(env, Symbiosis::SettingsAudit::PreviewAutoFix());
}

} // extern "C"

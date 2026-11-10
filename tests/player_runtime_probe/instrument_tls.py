#!/usr/bin/env python3
"""Log candidate OpenSSL provenance in a disposable debug build only."""
from pathlib import Path
import sys

path = Path(sys.argv[1])
s = path.read_text()
needle = '        LOGI("ssl: %s %s", name, handle != nullptr ? "ok" : "не найдена");\n'
extra = '''        // CI-only observation: this does not prove .NET used the library.
        if (handle && ::strcmp(name, "libcrypto.so") == 0) {
            using Version = unsigned long (*)();
            auto version = reinterpret_cast<Version>(::dlsym(handle, "OpenSSL_version_num"));
            Dl_info origin{};
            if (version && ::dladdr(reinterpret_cast<void*>(version), &origin) && origin.dli_fname) {
                LOGI("CI_ONLY_TLS_OPENSSL_VERSION=0x%lx origin=%s", version(), origin.dli_fname);
            } else {
                LOGE("CI_ONLY_TLS_OPENSSL_VERSION_UNAVAILABLE");
            }
        }
'''
if s.count(needle) != 1:
    raise SystemExit('unexpected preload layout; refusing instrumentation')
s = s.replace(needle, needle + extra, 1)
needle = '    PreloadOpenSsl();\n'
extra = '''    // CI-only real TLS exercise in this player process. The probe library,
    // its throwaway key and cert exist only in the disposable debug build.
    {
        Dl_info origin{};
        if (::dladdr(reinterpret_cast<void*>(&PreloadOpenSsl), &origin) && origin.dli_fname && g_crashPath[0]) {
            std::string libraryPath(origin.dli_fname), certPath(g_crashPath);
            const auto libSlash = libraryPath.find_last_of('/');
            const std::string marker = "/Android/data/";
            const auto begin = certPath.find(marker);
            const auto end = begin == std::string::npos ? std::string::npos : certPath.find('/', begin + marker.size());
            if (libSlash != std::string::npos && end != std::string::npos) {
                const std::string dir = libraryPath.substr(0, libSlash);
                // Only the CI Android user 0 is tested. Files are copied by
                // run-as, so the app owns them and scoped storage is avoided.
                certPath = "/data/user/0/" + certPath.substr(begin + marker.size(), end - begin - marker.size()) + "/files";
                void* probe = ::dlopen((dir + "/libtls_in_process_probe.so").c_str(), RTLD_NOW | RTLD_LOCAL);
                using Fn = int (*)(const char*, const char*);
                auto fn = probe ? reinterpret_cast<Fn>(::dlsym(probe, "ci_tls_in_process")) : nullptr;
                if (!fn || fn(dir.c_str(), certPath.c_str()) != 0)
                    LOGE("CI_ONLY_TLS_IN_PROCESS_FAILED");
            } else LOGE("CI_ONLY_TLS_IN_PROCESS_PATH_FAILED");
        } else LOGE("CI_ONLY_TLS_IN_PROCESS_PATH_FAILED");
    }
'''
if s.count(needle) != 1:
    raise SystemExit('unexpected runtime setup; refusing instrumentation')
path.write_text(s.replace(needle, needle + extra, 1))

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
path.write_text(s.replace(needle, needle + extra, 1))

#!/usr/bin/env python3
"""Inject only into disposable player CI build: never into a released APK."""
from pathlib import Path
import sys
p = Path(sys.argv[1])
s = p.read_text()
needle = '    PreloadKenjiFfmpeg();\n'
extra = r'''    // CI-only same-process dynamic-loading and H.264 decode exercise.
    {
        Dl_info origin{};
        if (::dladdr(reinterpret_cast<void*>(&PreloadKenjiFfmpeg), &origin) && origin.dli_fname) {
            std::string path(origin.dli_fname);
            const auto slash = path.find_last_of('/');
            if (slash != std::string::npos) {
                void* probe = ::dlopen((path.substr(0, slash + 1) + "libffmpeg_ci_probe.so").c_str(), RTLD_NOW | RTLD_LOCAL);
                using Fn = int (*)();
                auto test = probe ? reinterpret_cast<Fn>(::dlsym(probe, "ci_ffmpeg_in_process")) : nullptr;
                const int rc = test ? test() : -1;
                LOGI("CI_FFMPEG_PROBE_RESULT=%d", rc);
            } else LOGE("CI_FFMPEG_PROBE_PATH_FAILED");
        } else LOGE("CI_FFMPEG_PROBE_PATH_FAILED");
    }
'''
if s.count(needle) != 1:
    raise SystemExit('unexpected preload layout')
p.write_text(s.replace(needle, needle + extra, 1))

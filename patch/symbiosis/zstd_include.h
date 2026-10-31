// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

#pragma once

/**
 * @file zstd_include.h
 * @brief One place that decides whether zstd is available, and why.
 *
 * Eden already ships zstd: the root CMakeLists pulls it with
 * `AddJsonPackage(zstd)` and `src/common/CMakeLists.txt` links `zstd::zstd`
 * into common, next to the emulator's own zstd_compression.cpp. So on Android
 * the header is there and nothing has to be vendored.
 *
 * A second, vendored copy of zstd was tried and rejected: two definitions of
 * the same ZSTD_* symbols in one library is a link failure waiting for the
 * next person to touch CMake.
 *
 * Host tests (plain g++, no Eden include paths) get zstd from the system
 * package instead: tools/run_tests.sh checks that libzstd is installed and
 * passes -DSYMBIOSIS_HOST_ZSTD -lzstd. When it is missing, the NSZ test fails
 * loudly rather than skipping — a silent skip here is exactly how "the
 * converter returns zero bytes" reached a phone.
 */

#if defined(__ANDROID__) || defined(SYMBIOSIS_HOST_ZSTD)
#define SYMBIOSIS_HAS_ZSTD 1
#include <zstd.h>
#endif

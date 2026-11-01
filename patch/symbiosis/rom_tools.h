// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * @file rom_tools.h
 * @brief Dump inspection and conversion helpers.
 *
 * Two problems this addresses, both of which cost people hours:
 *
 *  1. **XCI wastes space.** A cartridge dump is padded to the physical card
 *     size, so a 3.2 GB game can occupy 4 GB or 8 GB on disk. The useful data
 *     ends at `valid_data_end` in the gamecard header; everything past it is
 *     filler. Converting to NSP, or simply trimming, reclaims that space with
 *     no loss.
 *
 *  2. **Broken dumps look like emulator bugs.** A truncated download, a dump
 *     that stopped early, or a file missing the keys it needs will fail at
 *     load time with an error that points at the emulator rather than the
 *     file. Checking the header first turns "it crashed" into "this file is
 *     1.2 GB short".
 *
 * Everything here operates on files the user already has. Nothing is
 * downloaded, and no encryption is bypassed: the tools read headers and copy
 * bytes, exactly as the emulator itself does when loading.
 */

#pragma once

#include <string>
#include <vector>

#include "common/common_types.h"

namespace Symbiosis {

enum class DumpFormat : u32 {
    Unknown = 0,
    Xci,   ///< Cartridge dump.
    Nsp,   ///< Submission package (PFS0).
    Nca,   ///< Bare content archive.
    Nro,   ///< Homebrew.
    Nsz,   ///< Compressed NSP (community format).
    Xcz,   ///< Compressed XCI (community format).
    Ncz,   ///< Compressed NCA (community format).
};

const char* ToString(DumpFormat format);

enum class DumpHealth : u32 {
    Unknown = 0,
    Good,        ///< Header consistent, size matches expectations.
    Trimmed,     ///< Padding already removed. Fine, and smaller.
    Truncated,   ///< File is shorter than its header says. Will not load.
    Padded,      ///< Contains reclaimable filler.
    Corrupt,     ///< Header does not parse.
};

const char* ToString(DumpHealth health);

/// What inspection found out about a file.
struct DumpReport {
    std::string path;
    std::string filename;
    DumpFormat format{DumpFormat::Unknown};
    DumpHealth health{DumpHealth::Unknown};
    u64 file_size{0};
    /// Offset at which real content ends, from the header. 0 when unknown.
    u64 valid_data_end{0};
    /// Bytes that could be reclaimed by trimming or converting.
    u64 reclaimable{0};
    /// Plain-language verdict.
    std::string summary;
    /// What to do next, empty when nothing is wrong.
    std::string advice;
};

/**
 * @brief Inspects and converts game dumps.
 */
class RomTools {
public:
    /// Identifies a file by its magic bytes. Cheap: reads a few hundred bytes.
    [[nodiscard]] static DumpFormat DetectFormat(const std::string& path);

    /**
     * @brief Full health check.
     *
     * Reads only headers, so it is fast even on multi-gigabyte files and safe
     * to run on an entire library.
     */
    [[nodiscard]] static DumpReport Inspect(const std::string& path);

    /**
     * @brief Removes trailing padding from an XCI in place.
     *
     * Only ever shortens the file, and only to the boundary the header itself
     * declares. Refuses to act when the header is unreadable or the file is
     * already trimmed.
     *
     * @return Bytes reclaimed, 0 when nothing was done.
     */
    static u64 TrimXci(const std::string& path, std::string& error_out);

    /**
     * @brief Extracts the secure partition of an XCI into an NSP.
     *
     * This is the space-saving conversion: the resulting NSP holds the same
     * content archives without the cartridge padding or the update and normal
     * partitions.
     *
     * The source file is never modified.
     *
     * @return Size of the written NSP, 0 on failure.
     */
    static u64 XciToNsp(const std::string& source, const std::string& destination,
                        std::string& error_out);

    /// Formats a report for display.
    [[nodiscard]] static std::string Describe(const DumpReport& report);

    /**
     * @brief Decompresses NSZ/XCZ/NCZ into NSP/XCI-as-NSP/NCA.
     *
     * Writes a new file. The source is never modified. On failure the
     * destination is deleted so a half-written dump cannot be launched.
     *
     * @return Bytes written, 0 on failure (reason in error_out).
     */
    static u64 Decompress(const std::string& source, const std::string& destination,
                          std::string& error_out);

    /// Note explaining why NSZ compression is not offered as an encoder.
    /**
     * Куда складывать временные части распаковки.
     *
     * Почему это настройка, а не temp_directory_path(). На Android переменная
     * TMPDIR в процессе приложения не выставлена, а /tmp не существует. Тогда
     * std::filesystem::temp_directory_path() бросает исключение - и распаковка
     * NSZ/XCZ на телефоне заканчивалась ничем ещё до чтения файла. Путь
     * приходит из Kotlin (кэш приложения), а если его не задали, остаётся
     * прежнее поведение и запасной вариант рядом с результатом.
     */
    static void SetWorkDir(const std::string& path);

    [[nodiscard]] static std::string CompressionNote();
};

} // namespace Symbiosis

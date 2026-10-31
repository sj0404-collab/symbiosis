// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * @file ncz.h
 * @brief Turns an .ncz back into an .nca, the way the PC tools do it.
 *
 * The old implementation was a guess at the format and it was wrong from the
 * first byte after the header, which is why the Converter answered "0 bytes"
 * on real downloads. This follows the reference layout used by NSZ and by SAK
 * on PC (dezem/SAK), which is where the user pointed:
 *
 *   NCA header          0x4000 bytes, stored as is
 *   "NCZSECTN"          8 bytes
 *   section count       u64
 *   section table       count * 0x40 bytes:
 *                         u64 offset        offset in the decompressed NCA
 *                         u64 size
 *                         u64 crypto type   1 none, 2 XTS, 3 CTR, 4 BKTR
 *                         u64 padding
 *                         u8[16] key
 *                         u8[16] counter
 *   then either
 *     "NCZBLOCK"        u8 version, u8 type, u8 unused, u8 block size
 *                       exponent, u32 block count, u64 decompressed size,
 *                       u32[count] compressed sizes, then the blocks;
 *                       a block whose stored size is smaller than the block
 *                       size is zstd, otherwise it is stored raw
 *   or
 *     one plain zstd stream covering every section in order ("solid")
 *
 * Sections with crypto type 3 or 4 hold the *decrypted* body and have to be
 * encrypted again with AES-128-CTR (key and counter come from the table, no
 * keys.txt needed) or the result is a broken NCA that Eden refuses to load.
 */

#pragma once

#include <filesystem>
#include <fstream>
#include <string>

#include "common/common_types.h"

namespace Symbiosis::Ncz {

/// True when the file has an NCA header followed by "NCZSECTN".
bool LooksCompressed(std::ifstream& in, u64 start);

/// Decompressed size of the NCA, or 0 when the header cannot be read.
u64 DecompressedSize(std::ifstream& in, u64 start, u64 available);

/**
 * Decompress the NCZ that occupies [start, start + available) to @p dest.
 *
 * Writes the whole NCA: the 0x4000 header, then every section with its
 * encryption restored. Returns false and fills @p error with something a user
 * can act on; a partially written @p dest is removed.
 */
bool Decompress(std::ifstream& in, u64 start, u64 available,
                const std::filesystem::path& dest, std::string& error);

} // namespace Symbiosis::Ncz

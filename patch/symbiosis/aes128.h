// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * @file aes128.h
 * @brief AES-128 in CTR mode, exactly as NSZ/NCZ needs it.
 *
 * Why this exists at all: an NCZ stores the decrypted body of an NCA and
 * records, per section, the key and the counter needed to put the encryption
 * back. Without that step the "decompressed" file is a valid zstd stream but
 * a broken NCA, and Eden refuses to load it — one of the ways the old
 * converter produced a file that looked fine and did not work.
 *
 * Why not the emulator's own AES: the emulator's lives in core/crypto, and
 * common (where RomTools sits) cannot depend on core — that is a link cycle.
 * Vendoring nothing here: this is ~150 lines with no dependencies, and it is
 * checked against the FIPS-197 test vectors in tests/t_nsz.cpp, so a mistake
 * in it fails the build rather than the phone.
 *
 * No keys from keys/prod.keys are needed or used: NCZ carries the section key
 * and counter inside the file itself.
 */

#pragma once

#include <cstddef>
#include <array>

#include "common/common_types.h"

namespace Symbiosis::Aes {

/// AES-128 block encryption, one 16-byte block. Exposed for the test vectors.
void EncryptBlock(const std::array<u8, 16>& key, const u8 in[16], u8 out[16]);

/**
 * AES-128-CTR keystream generator for one NCZ section.
 *
 * The counter block is built the way the reference implementation (nacp/nsz,
 * and SAK behind it) builds it: the first 8 bytes are the section's counter
 * prefix, the last 8 are the big-endian block index, where the block index is
 * the absolute byte offset in the decompressed NCA divided by 16.
 */
class Ctr {
public:
    Ctr(const u8 key[16], const u8 counter[16]);

    /// XOR @p data with the keystream that belongs at absolute offset @p offset.
    void Xor(u64 offset, u8* data, std::size_t size);

private:
    std::array<u8, 16> key_{};
    std::array<u8, 8> prefix_{};
};

} // namespace Symbiosis::Aes

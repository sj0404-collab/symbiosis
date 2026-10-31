// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later
//
// NCZ → NCA. The layout is described in ncz.h; this file is the plumbing:
// parse the section table, pick block or solid decoding, and put the AES
// encryption of each section back while writing.

#include "common/symbiosis/ncz.h"

#include <algorithm>
#include <array>
#include <cstring>
#include <memory>
#include <vector>

#include "common/symbiosis/aes128.h"
#include "common/symbiosis/symbiosis_log.h"
#include "common/symbiosis/zstd_include.h"

namespace Symbiosis::Ncz {
namespace {

constexpr u64 kNcaHeaderSize = 0x4000;
constexpr u64 kMaxOutBytes = 34ULL * 1024ULL * 1024ULL * 1024ULL;
constexpr u32 kMaxSections = 64;
constexpr u32 kMaxBlocks = 8'000'000;
constexpr u32 kMinBlockExponent = 14;
// 2^26 = 64 MiB. Real files use 2^14..2^22; the format allows more, but a
// hostile header asking for a 4 GiB block would be a one-line way to kill the
// app on a phone.
constexpr u32 kMaxBlockExponent = 26;
constexpr u64 kIoChunk = 1ULL << 20;
constexpr char kSectnMagic[] = "NCZSECTN";
constexpr char kBlockMagic[] = "NCZBLOCK";

u64 ReadU64(const u8* p) {
    u64 v = 0;
    for (int i = 7; i >= 0; --i) {
        v = (v << 8) | p[i];
    }
    return v;
}

u32 ReadU32(const u8* p) {
    return static_cast<u32>(p[0]) | (static_cast<u32>(p[1]) << 8) |
           (static_cast<u32>(p[2]) << 16) | (static_cast<u32>(p[3]) << 24);
}

bool ReadAt(std::ifstream& in, u64 offset, void* out, std::size_t size) {
    in.clear();
    in.seekg(static_cast<std::streamoff>(offset), std::ios::beg);
    if (!in) {
        return false;
    }
    in.read(static_cast<char*>(out), static_cast<std::streamsize>(size));
    return static_cast<std::size_t>(in.gcount()) == size;
}

struct Section {
    u64 offset{0};
    u64 size{0};
    u64 crypto_type{0};
    std::array<u8, 16> key{};
    std::array<u8, 16> counter{};

    /// Types 3 and 4 are CTR and BKTR: both are AES-128-CTR over the section
    /// with the key and counter stored right here. XTS (2) is not produced by
    /// the compressors, because an XTS body is not what they store.
    bool Encrypted() const {
        return crypto_type == 3 || crypto_type == 4;
    }
};

struct Layout {
    std::array<u8, kNcaHeaderSize> header{};
    std::vector<Section> sections;
    u64 stream_offset{0};  ///< file offset of the first compressed byte
    u64 stream_origin{0};  ///< output offset the first compressed byte belongs to
    u64 stream_length{0};  ///< decompressed bytes the stream must yield
    u64 out_end{0};        ///< end offset of the NCA
    bool block_mode{false};
    u32 block_exponent{0};
    u32 block_count{0};
    std::vector<u32> block_sizes;
};

/// Everything up to the compressed payload, validated.
bool ParseLayout(std::ifstream& in, u64 start, u64 available, Layout& layout, std::string& error) {
    if (available < kNcaHeaderSize + 16) {
        error = "File is too short to be a compressed NCA.";
        return false;
    }
    if (!ReadAt(in, start, layout.header.data(), layout.header.size())) {
        error = "Could not read the NCA header.";
        return false;
    }
    char magic[8]{};
    if (!ReadAt(in, start + kNcaHeaderSize, magic, sizeof(magic)) ||
        std::memcmp(magic, kSectnMagic, sizeof(kSectnMagic) - 1) != 0) {
        error = "This file has no NCZSECTN section table, so it is not compressed.";
        return false;
    }
    u8 count_bytes[8]{};
    if (!ReadAt(in, start + kNcaHeaderSize + 8, count_bytes, sizeof(count_bytes))) {
        error = "The section table is unreadable.";
        return false;
    }
    const u64 count = ReadU64(count_bytes);
    if (count == 0 || count > kMaxSections) {
        error = "The section table lists " + std::to_string(count) + " sections, which is not plausible.";
        return false;
    }
    const u64 table_offset = start + kNcaHeaderSize + 16;
    layout.sections.resize(static_cast<std::size_t>(count));
    u64 sum = 0;
    u64 out_end = kNcaHeaderSize;
    for (u64 i = 0; i < count; ++i) {
        u8 entry[0x40]{};
        if (!ReadAt(in, table_offset + i * 0x40, entry, sizeof(entry))) {
            error = "The section table ends early.";
            return false;
        }
        Section& s = layout.sections[static_cast<std::size_t>(i)];
        s.offset = ReadU64(entry);
        s.size = ReadU64(entry + 8);
        s.crypto_type = ReadU64(entry + 16);
        std::memcpy(s.key.data(), entry + 32, 16);
        std::memcpy(s.counter.data(), entry + 48, 16);
        if (s.size == 0 || s.size > kMaxOutBytes || s.offset > kMaxOutBytes - s.size) {
            error = "Section " + std::to_string(i) + " has an implausible size.";
            return false;
        }
        if (i > 0 && s.offset < layout.sections[static_cast<std::size_t>(i - 1)].offset) {
            error = "The section table is out of order, so the file is damaged.";
            return false;
        }
        sum += s.size;
        out_end = std::max(out_end, s.offset + s.size);
    }
    if (sum > kMaxOutBytes - kNcaHeaderSize) {
        error = "The decompressed size from the section table is implausible.";
        return false;
    }
    layout.stream_offset = table_offset + count * 0x40;
    if (layout.stream_offset >= start + available) {
        error = "The compressed payload is missing: the file stops after the section table.";
        return false;
    }
    // The stream starts at the first section; when that section begins before
    // the header ends, its first bytes are the header we already wrote.
    layout.stream_origin = std::min<u64>(layout.sections.front().offset, kNcaHeaderSize);
    layout.out_end = out_end;
    layout.stream_length = out_end - layout.stream_origin;
    if (layout.out_end > kMaxOutBytes) {
        error = "The decompressed size is implausible.";
        return false;
    }

    char block_magic[8]{};
    if (!ReadAt(in, layout.stream_offset, block_magic, sizeof(block_magic))) {
        error = "The compressed payload is unreadable.";
        return false;
    }
    if (std::memcmp(block_magic, kBlockMagic, sizeof(kBlockMagic) - 1) != 0) {
        return true; // solid zstd stream
    }

    layout.block_mode = true;
    u8 meta[16]{};
    if (!ReadAt(in, layout.stream_offset + 8, meta, sizeof(meta))) {
        error = "The NCZBLOCK header is truncated.";
        return false;
    }
    layout.block_exponent = meta[3];
    layout.block_count = ReadU32(meta + 4);
    const u64 declared = ReadU64(meta + 8);
    if (layout.block_count == 0 || layout.block_count > kMaxBlocks) {
        error = "NCZBLOCK claims " + std::to_string(layout.block_count) + " blocks, which is not plausible.";
        return false;
    }
    if (layout.block_exponent < kMinBlockExponent || layout.block_exponent > kMaxBlockExponent) {
        error = "NCZBLOCK declares a block size of 2^" + std::to_string(layout.block_exponent) +
                " bytes, which the format does not allow.";
        return false;
    }
    if (declared != layout.stream_length) {
        error = "NCZBLOCK promises " + std::to_string(declared) + " bytes but the section table "
                "adds up to " + std::to_string(layout.stream_length) + ".";
        return false;
    }
    layout.block_sizes.resize(layout.block_count);
    if (!ReadAt(in, layout.stream_offset + 24, layout.block_sizes.data(),
                layout.block_sizes.size() * sizeof(u32))) {
        error = "The NCZBLOCK size table is truncated.";
        return false;
    }
    layout.stream_offset += 24 + static_cast<u64>(layout.block_count) * 4;
    return true;
}

/// Source of decompressed bytes, in output order.
class Pump {
public:
    Pump(u64 total) : remaining_{total} {}
    virtual ~Pump() = default;

    /// Fill @p want bytes unless the stream ends first.
    virtual bool Next(u8* buf, std::size_t want, std::size_t& got, std::string& error) = 0;

    u64 remaining() const {
        return remaining_;
    }

protected:
    void Take(std::size_t got) {
        remaining_ -= std::min<u64>(remaining_, got);
    }
    u64 remaining_{0};
};

class BlockPump final : public Pump {
public:
    BlockPump(std::ifstream& in, const Layout& layout)
        : Pump{layout.stream_length}, in_{in}, sizes_{layout.block_sizes},
          block_size_{1ULL << layout.block_exponent}, data_offset_{layout.stream_offset},
          cursor_{layout.stream_offset} {}

    bool Next(u8* buf, std::size_t want, std::size_t& got, std::string& error) override {
        got = 0;
        want = static_cast<std::size_t>(std::min<u64>(want, remaining_));
        while (got < want) {
            if (block_pos_ >= block_.size() && !LoadNextBlock(error)) {
                return false;
            }
            const std::size_t take =
                std::min(want - got, static_cast<std::size_t>(block_.size() - block_pos_));
            std::memcpy(buf + got, block_.data() + block_pos_, take);
            block_pos_ += take;
            got += take;
        }
        Take(got);
        return true;
    }

private:
    bool LoadNextBlock(std::string& error) {
        if (index_ >= sizes_.size()) {
            error = "The last NCZBLOCK was reached before the NCA was complete.";
            return false;
        }
        const u32 stored = sizes_[index_];
        const u64 left = remaining_;
        const u64 expected = std::min<u64>(block_size_, left);
        if (stored == 0 || stored > expected) {
            error = "Block " + std::to_string(index_) + " is stored as " + std::to_string(stored) +
                    " bytes, but it should hold " + std::to_string(expected) + ".";
            return false;
        }
        std::vector<u8> raw(stored);
        if (!ReadAt(in_, cursor_, raw.data(), raw.size())) {
            error = "Could not read block " + std::to_string(index_) + ": the file is truncated.";
            return false;
        }
        cursor_ += stored;
        if (stored == expected) {
            // Stored uncompressed: the block would not have been smaller.
            block_.assign(raw.begin(), raw.end());
        } else {
#ifdef SYMBIOSIS_HAS_ZSTD
            block_.resize(static_cast<std::size_t>(expected));
            const std::size_t got =
                ZSTD_decompress(block_.data(), block_.size(), raw.data(), raw.size());
            if (ZSTD_isError(got)) {
                error = "Block " + std::to_string(index_) + " is damaged: " +
                        ZSTD_getErrorName(got);
                return false;
            }
            if (got != expected) {
                error = "Block " + std::to_string(index_) + " unpacked to " +
                        std::to_string(got) + " bytes instead of " + std::to_string(expected) + ".";
                return false;
            }
#else
            error = "This build has no zstd decoder, so compressed blocks cannot be read.";
            return false;
#endif
        }
        block_pos_ = 0;
        ++index_;
        return true;
    }

    std::ifstream& in_;
    std::vector<u32> sizes_;
    u64 block_size_;
    u64 data_offset_;
    u64 cursor_{0};
    std::size_t index_{0};
    std::vector<u8> block_;
    std::size_t block_pos_{0};
};

class SolidPump final : public Pump {
public:
    SolidPump(std::ifstream& in, const Layout& layout, u64 file_end)
        : Pump{layout.stream_length}, in_{in}, file_end_{file_end} {
#ifdef SYMBIOSIS_HAS_ZSTD
        stream_ = ZSTD_createDStream();
        if (stream_ != nullptr) {
            ZSTD_initDStream(stream_);
        }
#endif
        in_.clear();
        in_.seekg(static_cast<std::streamoff>(layout.stream_offset), std::ios::beg);
        file_pos_ = layout.stream_offset;
    }

    ~SolidPump() override {
#ifdef SYMBIOSIS_HAS_ZSTD
        if (stream_ != nullptr) {
            ZSTD_freeDStream(stream_);
        }
#endif
    }

    bool Next(u8* buf, std::size_t want, std::size_t& got, std::string& error) override {
#ifdef SYMBIOSIS_HAS_ZSTD
        got = 0;
        want = static_cast<std::size_t>(std::min<u64>(want, remaining_));
        while (got < want) {
            if (input_pos_ >= input_.size()) {
                if (!Refill()) {
                    if (got == 0) {
                        error = "The zstd stream ends before the NCA is complete.";
                        return false;
                    }
                    break;
                }
            }
            ZSTD_inBuffer in_buf{input_.data(), input_.size(), input_pos_};
            ZSTD_outBuffer out_buf{buf + got, want - got, 0};
            const std::size_t ret = ZSTD_decompressStream(stream_, &out_buf, &in_buf);
            input_pos_ = in_buf.pos;
            got += out_buf.pos;
            if (ZSTD_isError(ret)) {
                error = std::string{"The zstd stream is damaged: "} + ZSTD_getErrorName(ret);
                return false;
            }
            if (ret == 0 && out_buf.pos == 0) {
                if (got == 0) {
                    error = "The zstd stream ends before the NCA is complete.";
                    return false;
                }
                break;
            }
            if (out_buf.pos == 0 && input_pos_ >= input_.size() && file_pos_ >= file_end_) {
                error = "The zstd stream is truncated: the file ends in the middle of a block.";
                return false;
            }
        }
        Take(got);
        return true;
#else
        (void)buf;
        (void)want;
        (void)got;
        error = "This build has no zstd decoder, so the solid stream cannot be read.";
        return false;
#endif
    }

private:
    bool Refill() {
        if (file_pos_ >= file_end_) {
            return false;
        }
        const u64 left = file_end_ - file_pos_;
        const std::size_t take = static_cast<std::size_t>(std::min<u64>(kIoChunk, left));
        input_.resize(take);
        in_.read(reinterpret_cast<char*>(input_.data()), static_cast<std::streamsize>(take));
        input_.resize(static_cast<std::size_t>(in_.gcount()));
        file_pos_ += input_.size();
        input_pos_ = 0;
        return !input_.empty();
    }

    std::ifstream& in_;
    u64 file_end_;
    u64 file_pos_{0};
    std::vector<u8> input_;
    std::size_t input_pos_{0};
#ifdef SYMBIOSIS_HAS_ZSTD
    ZSTD_DStream* stream_{nullptr};
#endif
};

struct Region {
    u64 offset{0};
    u64 size{0};
    bool encrypted{false};
    std::array<u8, 16> key{};
    std::array<u8, 16> counter{};
};

/// Output written from the stream, with the section encryption restored.
bool CopyRegions(Pump& pump, std::ofstream& out, const Layout& layout, std::string& error) {
    std::vector<u8> buf(static_cast<std::size_t>(std::min<u64>(kIoChunk, 1ULL << 20)));

    // Bytes the stream holds for the part already written as the header.
    u64 skip = kNcaHeaderSize - std::min<u64>(layout.stream_origin, kNcaHeaderSize);
    while (skip > 0) {
        const std::size_t want = static_cast<std::size_t>(std::min<u64>(skip, buf.size()));
        std::size_t got = 0;
        if (!pump.Next(buf.data(), want, got, error)) {
            return false;
        }
        if (got == 0) {
            error = "The compressed stream ended before the NCA header did.";
            return false;
        }
        skip -= got;
    }

    std::vector<Region> regions;
    u64 cursor = kNcaHeaderSize;
    for (const Section& s : layout.sections) {
        const u64 begin = std::max<u64>(s.offset, kNcaHeaderSize);
        const u64 end = s.offset + s.size;
        if (end <= begin) {
            continue;
        }
        if (begin > cursor) {
            regions.push_back(Region{cursor, begin - cursor, false, {}, {}});
            cursor = begin;
        }
        Region r;
        r.offset = begin;
        r.size = end - begin;
        r.encrypted = s.Encrypted();
        r.key = s.key;
        r.counter = s.counter;
        regions.push_back(r);
        cursor = end;
    }

    for (const Region& region : regions) {
        std::unique_ptr<Aes::Ctr> ctr;
        if (region.encrypted) {
            ctr = std::make_unique<Aes::Ctr>(region.key.data(), region.counter.data());
        }
        u64 done = 0;
        while (done < region.size) {
            const std::size_t want = static_cast<std::size_t>(
                std::min<u64>(region.size - done, buf.size()));
            std::size_t got = 0;
            if (!pump.Next(buf.data(), want, got, error)) {
                return false;
            }
            if (got == 0) {
                error = "The compressed stream ended " + std::to_string(region.size - done) +
                        " bytes before the section table said it would.";
                return false;
            }
            if (ctr != nullptr) {
                ctr->Xor(region.offset + done, buf.data(), got);
            }
            out.write(reinterpret_cast<const char*>(buf.data()), static_cast<std::streamsize>(got));
            if (!out) {
                error = "Could not write the decompressed NCA: storage may be full.";
                return false;
            }
            done += got;
        }
    }

    if (pump.remaining() != 0) {
        error = "The compressed stream still holds " + std::to_string(pump.remaining()) +
                " bytes after the last section.";
        return false;
    }
    return true;
}

} // namespace

bool LooksCompressed(std::ifstream& in, u64 start) {
    char magic[8]{};
    return ReadAt(in, start + kNcaHeaderSize, magic, sizeof(magic)) &&
           std::memcmp(magic, kSectnMagic, sizeof(kSectnMagic) - 1) == 0;
}

u64 DecompressedSize(std::ifstream& in, u64 start, u64 available) {
    Layout layout;
    std::string error;
    if (!ParseLayout(in, start, available, layout, error)) {
        return 0;
    }
    return layout.out_end;
}

bool Decompress(std::ifstream& in, u64 start, u64 available,
                const std::filesystem::path& dest, std::string& error) {
    Layout layout;
    if (!ParseLayout(in, start, available, layout, error)) {
        return false;
    }
    std::ofstream out{dest, std::ios::binary | std::ios::trunc};
    if (!out) {
        error = "Could not create the output file.";
        return false;
    }
    out.write(reinterpret_cast<const char*>(layout.header.data()),
              static_cast<std::streamsize>(layout.header.size()));
    if (!out) {
        error = "Could not write the NCA header.";
        return false;
    }

    std::unique_ptr<Pump> pump;
    if (layout.block_mode) {
        pump = std::make_unique<BlockPump>(in, layout);
    } else {
        pump = std::make_unique<SolidPump>(in, layout, start + available);
    }
    if (!CopyRegions(*pump, out, layout, error)) {
        out.close();
        std::error_code ec;
        std::filesystem::remove(dest, ec);
        return false;
    }
    out.flush();
    if (!out) {
        error = "Could not finish writing the NCA: storage may be full.";
        std::error_code ec;
        std::filesystem::remove(dest, ec);
        return false;
    }
    LogInfo(LogArea::General,
            "NCZ unpacked: " + std::to_string(layout.out_end / (1024 * 1024)) + " MiB, " +
                std::to_string(layout.sections.size()) + " section(s), " +
                (layout.block_mode ? "blocks" : "solid"));
    return true;
}

} // namespace Symbiosis::Ncz

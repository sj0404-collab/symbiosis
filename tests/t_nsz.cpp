// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Распаковка NSZ / XCZ / NCZ: настоящие файлы, настоящий zstd, настоящий AES.
//
// ЧТО ЛОВИТ ЭТОТ ТЕСТ
//   Раньше конвертер на любом скачанном NSZ отвечал «ноль результата»:
//   zstd-декодера в сборке не было, а разбор формата не совпадал с тем, как
//   файлы пишут PC-версии. Ни то, ни другое не видно «на глаз» в APK — файл
//   просто не появляется. Здесь оба случая падают до сборки.
//
// ПОЧЕМУ ФАЙЛЫ ЛЕЖАТ В tests/data
//   Их собирает make_fixtures.py по эталонной раскладке (nsz / SAK), отдельно
//   от этого кода. Синтетическими они быть не перестают — данных внутри
//   немного, но структура, шифрование и сжатие настоящие.
//
// ЧТО ПРОВЕРЯЕТСЯ
//   1. AES-128 против векторов NIST (иначе AES-CTR может «работать», но
//      расшифровывать не тем ключом).
//   2. solid-режим: один zstd-поток, секция BKTR.
//   3. блоковый режим внутри NSZ: сжимаемая секция, несжимаемая секция,
//      дырка между секциями, обычный .nca рядом.
//   4. XCZ: корневой HFS0 по 0xF000 и раздел secure.
//   5. Обрезанный файл: внятная ошибка и ноль байт, а не мусор на выходе.

#include <cstdio>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <string>
#include <vector>

#include "common/symbiosis/aes128.h"
#include "common/symbiosis/rom_tools.h"

using namespace Symbiosis;
namespace fs = std::filesystem;
using Bytes = std::vector<unsigned char>;

namespace {

int g_checks = 0;
int g_failures = 0;

void Check(bool ok, const std::string& what) {
    ++g_checks;
    if (!ok) {
        ++g_failures;
        std::printf("  FAIL: %s\n", what.c_str());
    }
}

Bytes ReadFile(const fs::path& path) {
    std::ifstream f(path, std::ios::binary);
    return Bytes((std::istreambuf_iterator<char>(f)), std::istreambuf_iterator<char>());
}

void WriteFile(const fs::path& path, const Bytes& data, std::size_t limit = SIZE_MAX) {
    std::ofstream f(path, std::ios::binary | std::ios::trunc);
    const std::size_t n = std::min(limit, data.size());
    f.write(reinterpret_cast<const char*>(data.data()), static_cast<std::streamsize>(n));
}

std::uint32_t Get32(const Bytes& b, std::size_t at) {
    std::uint32_t v = 0;
    for (int i = 0; i < 4; ++i) v |= static_cast<std::uint32_t>(b[at + i]) << (i * 8);
    return v;
}

std::uint64_t Get64(const Bytes& b, std::size_t at) {
    std::uint64_t v = 0;
    for (int i = 0; i < 8; ++i) v |= static_cast<std::uint64_t>(b[at + i]) << (i * 8);
    return v;
}

/// Сравнение байт в байт с адресом первого расхождения: «не совпало» без
/// позиции бесполезно, когда разбираешь чужой формат.
void CheckSame(const Bytes& got, const Bytes& want, const std::string& what) {
    if (got.size() != want.size()) {
        Check(false, what + ": размер " + std::to_string(got.size()) + " вместо " +
                         std::to_string(want.size()));
        return;
    }
    for (std::size_t i = 0; i < got.size(); ++i) {
        if (got[i] != want[i]) {
            char msg[256];
            std::snprintf(msg, sizeof(msg),
                          "%s: расхождение с байта %zu (получено %02x, ожидалось %02x)",
                          what.c_str(), i, got[i], want[i]);
            Check(false, msg);
            return;
        }
    }
    Check(true, what);
}

/// Достать файл из NSP по имени. Пустой результат — файла нет.
Bytes Pfs0Entry(const Bytes& nsp, const std::string& name) {
    if (nsp.size() < 0x10 || Get32(nsp, 0) != 0x30534650) {
        return {};
    }
    const std::uint32_t count = Get32(nsp, 4);
    const std::uint32_t strings = Get32(nsp, 8);
    const std::size_t names_at = 0x10 + 0x18 * count;
    const std::size_t data_at = names_at + strings;
    const std::string table(reinterpret_cast<const char*>(nsp.data() + names_at), strings);
    for (std::uint32_t i = 0; i < count; ++i) {
        const std::size_t e = 0x10 + 0x18 * i;
        const std::uint32_t name_off = Get32(nsp, e + 16);
        if (name_off >= strings) {
            continue;
        }
        if (table.compare(name_off, name.size(), name) != 0) {
            continue;
        }
        const std::uint64_t off = Get64(nsp, e);
        const std::uint64_t size = Get64(nsp, e + 8);
        if (data_at + off + size > nsp.size()) {
            return {};
        }
        return Bytes(nsp.begin() + static_cast<std::ptrdiff_t>(data_at + off),
                     nsp.begin() + static_cast<std::ptrdiff_t>(data_at + off + size));
    }
    return {};
}

void TestAes() {
    using Symbiosis::Aes::EncryptBlock;
    const auto enc = [](std::array<u8, 16> key, const char* in_hex, const char* want) {
        u8 in[16];
        for (int i = 0; i < 16; ++i) {
            unsigned v = 0;
            std::sscanf(in_hex + i * 2, "%2x", &v);
            in[i] = static_cast<u8>(v);
        }
        u8 out[16];
        EncryptBlock(key, in, out);
        char got[33];
        for (int i = 0; i < 16; ++i) std::snprintf(got + i * 2, 3, "%02x", out[i]);
        Check(std::strcmp(got, want) == 0, std::string{"AES-128: "} + want + " -> " + got);
    };
    // FIPS-197, приложение C.1.
    enc({0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e,
         0x0f},
        "00112233445566778899aabbccddeeff", "69c4e0d86a7b0430d8cdb78070b4c55a");
    // FIPS-197, приложение B.1.
    enc({0x2b, 0x7e, 0x15, 0x16, 0x28, 0xae, 0xd2, 0xa6, 0xab, 0xf7, 0x15, 0x88, 0x09, 0xcf, 0x4f,
         0x3c},
        "3243f6a8885a308d313198a2e0370734", "3925841d02dc09fbdc118597196a0b32");

    // CTR с нулевым ключом и нулевым счётчиком: сверено с openssl.
    u8 key[16] = {};
    u8 counter[16] = {};
    Bytes data(32, 0);
    Aes::Ctr{key, counter}.Xor(0, data.data(), data.size());
    char got[65];
    for (int i = 0; i < 32; ++i) std::snprintf(got + i * 2, 3, "%02x", data[i]);
    Check(std::strcmp(got, "66e94bd4ef8a2c3b884cfa59ca342b2e58e2fccefa7e3061367f1d57a4e7455a") == 0,
          std::string{"AES-CTR с нулевым ключом: "} + got);

    // Сдвиг: байт i при offset 5 обязан совпасть с байтом i+5 при offset 0.
    Bytes a(64, 0);
    Bytes b(64, 0);
    Aes::Ctr{key, counter}.Xor(0, a.data(), a.size());
    Aes::Ctr{key, counter}.Xor(5, b.data(), b.size());
    bool same = true;
    for (std::size_t i = 0; i + 5 < a.size() && i < b.size(); ++i) {
        if (b[i] != a[i + 5]) same = false;
    }
    Check(same, "AES-CTR: сдвиг внутри блока даёт то же значение");
}

} // namespace

int main() {
    std::setvbuf(stdout, nullptr, _IONBF, 0);

    const char* env = std::getenv("SYMBIOSIS_TEST_DATA");
    const fs::path data = env != nullptr ? fs::path{env} : fs::path{"tests/data"};
    if (!fs::exists(data / "block.nsz")) {
        std::printf("  файлы не найдены в %s — запускайте через tools/run_tests.sh\n",
                    data.c_str());
        return 1;
    }

    TestAes();

    const fs::path work = fs::temp_directory_path() / "symbiosis-nsz-test";
    std::error_code ec;
    fs::remove_all(work, ec);
    fs::create_directories(work);

    // 1. solid: один zstd-поток, секция шифруется обратно.
    {
        const fs::path src = data / "solid.ncz";
        const fs::path dst = work / "solid.nca";
        std::string error;
        const u64 written = RomTools::Decompress(src.string(), dst.string(), error);
        Check(written > 0, "solid.ncz распакован (" + error + ")");
        CheckSame(ReadFile(dst), ReadFile(data / "solid.nca"), "solid.ncz -> solid.nca");
    }

    // 2. NSZ: блоки, несжимаемая секция, дырка между секциями, обычный NCA.
    {
        const fs::path dst = work / "block.nsp";
        std::string error;
        const u64 written = RomTools::Decompress((data / "block.nsz").string(), dst.string(), error);
        Check(written > 0, "block.nsz распакован (" + error + ")");
        const Bytes nsp = ReadFile(dst);
        if (nsp.empty()) {
            Check(false, "block.nsz -> пустой NSP");
        } else {
            const std::string plain_name = "00" + std::string(30, 'a') + ".nca";
            const std::string packed_name = "00" + std::string(30, 'b') + ".nca";
            const Bytes plain = Pfs0Entry(nsp, plain_name);
            const Bytes packed = Pfs0Entry(nsp, packed_name);
            Check(!plain.empty(), "NSZ: обычный .nca внутри цел (" + plain_name + ")");
            Check(!packed.empty(), "NSZ: .ncz стал .nca (" + packed_name + ")");
            CheckSame(plain, ReadFile(data / "block_a.nca"), "NSZ: первый файл");
            CheckSame(packed, ReadFile(data / "block_b.nca"), "NSZ: второй файл после распаковки");
        }
    }

    // 3. XCZ: корневой HFS0 в 0xF000, раздел secure.
    {
        const fs::path dst = work / "game.nsp";
        std::string error;
        const u64 written = RomTools::Decompress((data / "game.xcz").string(), dst.string(), error);
        Check(written > 0, "game.xcz распакован (" + error + ")");
        const Bytes nsp = ReadFile(dst);
        if (nsp.empty()) {
            Check(false, "game.xcz -> пустой NSP");
        } else {
            const std::string plain_name = "01" + std::string(30, 'c') + ".nca";
            const std::string packed_name = "01" + std::string(30, 'd') + ".nca";
            CheckSame(Pfs0Entry(nsp, plain_name), ReadFile(data / "xcz_a.nca"), "XCZ: первый файл");
            CheckSame(Pfs0Entry(nsp, packed_name), ReadFile(data / "xcz_b.nca"),
                      "XCZ: второй файл после распаковки");
        }
    }

    // 4. Обрезанный файл: ноль байт, понятная ошибка, мусор не остаётся.
    {
        const Bytes full = ReadFile(data / "block.nsz");
        const fs::path broken = work / "broken.nsz";
        WriteFile(broken, full, full.size() / 2);
        const fs::path dst = work / "broken.nsp";
        std::string error;
        const u64 written = RomTools::Decompress(broken.string(), dst.string(), error);
        Check(written == 0, "обрезанный NSZ даёт ноль байт");
        Check(!error.empty(), "обрезанный NSZ объясняет причину");
        Check(!fs::exists(dst), "после ошибки не остаётся недописанный NSP");
        if (!error.empty()) {
            std::printf("  ожидаемое сообщение: %s\n", error.c_str());
        }
    }

    fs::remove_all(work, ec);
    if (g_failures > 0) {
        std::printf("NSZ TESTS FAILED (%d из %d)\n", g_failures, g_checks);
        return 1;
    }
    std::printf("NSZ TESTS PASSED (%d проверок)\n", g_checks);
    return 0;
}

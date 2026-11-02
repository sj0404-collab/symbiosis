#!/usr/bin/env python3
# SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
# SPDX-License-Identifier: GPL-3.0-or-later
"""Собирает тестовые NSZ / XCZ / NCZ по тому же формату, что и PC-версии.

Зачем отдельный генератор, а не «файл из интернета»
    Настоящая игра весит гигабайты, а тест должен бежать в CI за секунды.
    Поэтому файлы синтетические, но формат — не выдуманный: он повторяет
    раскладку из эталонной реализации (nsz / SAK), включая таблицу секций,
    AES-128-CTR и сжатие блоков настоящим zstd. Синтетики здесь ровно
    столько, сколько нужно, — данные внутри, а не структура.

    Важно: генератор пишет файлы независимо от C++ кода, который их потом
    читает. Если оба реализовать из одной головы, тест подтвердит только
    согласованность ошибки с самой собой.

Что получается (всё в этой папке):

    solid.ncz      solid-режим: один zstd-поток, секция cryptoType 4
    solid.nca      что должно получиться на выходе

    block.nsz      PFS0: обычный .nca + блоково сжатый .ncz с дыркой между
                   секциями и одной несжимаемой секцией
    block_a.nca    содержимое первого файла
    block_b.nca    содержимое второго файла после распаковки

    game.xcz       gamecard-образ: корневой HFS0 в 0xF000, раздел secure
    xcz_a.nca      содержимое первого файла secure
    xcz_b.nca      содержимое второго файла после распаковки

Запуск: python3 tests/data/make_fixtures.py
Требует: pip install zstandard pycryptodome
"""

from pathlib import Path
import struct

from Crypto.Cipher import AES
from Crypto.Util import Counter
import zstandard

HERE = Path(__file__).resolve().parent
NCA_HEADER = 0x4000


def nca_header(tag: int, size: int | None = None) -> bytes:
    """0x4000 байт с магией NCA3 — то, что копируется в выходной файл как есть.

    size — размер NCA, записанный по смещению 0x08. В настоящем заголовке он
    есть всегда, и именно он, а не таблица секций, говорит, какой длины файл
    должен получиться. Поэтому фикстуры с «коротким блоком» его заполняют.
    """
    header = bytearray(NCA_HEADER)
    header[0:4] = b"NCA3"
    for i in range(0x10, NCA_HEADER):
        header[i] = (i * 7 + tag) & 0xFF
    if size is not None:
        struct.pack_into("<Q", header, 0x08, size)
    return bytes(header)


def body(size: int, compressible: bool, seed: int) -> bytes:
    """Тело NCA. Часть — повторяющийся шаблон, часть — псевдошум."""
    if compressible:
        unit = (f"SYMBIOSIS-NSZ-{seed}-BLOCK ").encode()
        out = bytearray()
        while len(out) < size:
            out += unit
        return bytes(out[:size])
    state = seed * 2654435761 & 0xFFFFFFFF
    out = bytearray()
    for _ in range(size):
        state = (state * 1103515245 + 12345) & 0x7FFFFFFF
        out.append((state >> 16) & 0xFF)
    return bytes(out)


def aes_ctr(section: dict, plain: bytes, offset: int) -> bytes:
    """AES-128-CTR ровно в той схеме, которую ждёт формат NCZ."""
    ctr = Counter.new(64, prefix=section["counter"][:8], initial_value=(offset >> 4))
    return AES.new(section["key"], AES.MODE_CTR, counter=ctr).encrypt(plain)


def ncz(header: bytes, sections: list, payload: bytes, block_exponent: int | None,
        declared: int | None = None) -> bytes:
    """Собирает .ncz: заголовок, таблицу секций и сжатое тело.

    block_exponent=None — solid-режим: один zstd-поток на всё тело.
    """
    out = bytearray(header)
    out += b"NCZSECTN"
    out += struct.pack("<Q", len(sections))
    for s in sections:
        out += struct.pack("<QQQ", s["offset"], s["size"], s["crypto"])
        out += b"\x00" * 8
        out += s["key"]
        out += s["counter"]

    if block_exponent is None:
        out += zstandard.ZstdCompressor(level=19).compress(payload)
        return bytes(out)

    block_size = 1 << block_exponent
    blocks = [payload[i:i + block_size] for i in range(0, len(payload), block_size)]
    compressed = []
    for b in blocks:
        packed = zstandard.ZstdCompressor(level=19).compress(b)
        # Тот же выбор, что у компрессора: меньше блока — сжимаем, иначе пишем как есть.
        compressed.append(packed if len(packed) < len(b) else b)
    head = b"NCZBLOCK" + bytes([2, 1, 0, block_exponent])
    head += struct.pack("<I", len(blocks))
    head += struct.pack("<Q", len(payload) if declared is None else declared)
    head += b"".join(struct.pack("<I", len(c)) for c in compressed)
    out += head
    out += b"".join(compressed)
    return bytes(out)


def pfs0(files: list) -> bytes:
    """PFS0 без выравнивания: имена, затем данные."""
    names = b""
    offsets = []
    for name, data in files:
        offsets.append(len(names))
        names += name.encode() + b"\x00"
    out = bytearray()
    out += b"PFS0" + struct.pack("<II", len(files), len(names))
    out += b"\x00" * 4
    data_start = 0x10 + 0x18 * len(files) + len(names)
    cursor = 0
    for (name, data), name_off in zip(files, offsets):
        out += struct.pack("<QQI", cursor, len(data), name_off)
        out += b"\x00" * 4
        cursor += len(data)
    out += names
    for _, data in files:
        out += data
    assert len(out) == data_start + cursor
    return bytes(out)


def hfs0(files: list) -> bytes:
    """HFS0: записи по 0x40 байт, имена, затем данные."""
    names = b""
    name_offsets = []
    for name, data in files:
        name_offsets.append(len(names))
        names += name.encode() + b"\x00"
    out = bytearray()
    out += b"HFS0" + struct.pack("<II", len(files), len(names))
    out += b"\x00" * 4
    data_start = 0x10 + 0x40 * len(files) + len(names)
    cursor = 0
    for (name, data), name_off in zip(files, name_offsets):
        out += struct.pack("<QQI", cursor, len(data), name_off)
        out += b"\x00" * 0x2C  # запись HFS0 занимает ровно 0x40 байт
        cursor += len(data)
    out += names
    for _, data in files:
        out += data
    assert len(out) == data_start + cursor, (len(out), data_start + cursor)
    return bytes(out)


SECTION_KEY = bytes(range(0x10, 0x20))
SECTION_COUNTER = bytes(range(0x40, 0x50))


def make_solid() -> None:
    header = nca_header(1)
    plain = body(0x5000, compressible=True, seed=3)
    section = {
        "offset": NCA_HEADER,
        "size": len(plain),
        "crypto": 4,  # BKTR — распаковщик обрабатывает его как CTR
        "key": SECTION_KEY,
        "counter": SECTION_COUNTER,
    }
    (HERE / "solid.ncz").write_bytes(ncz(header, [section], plain, None))
    (HERE / "solid.nca").write_bytes(header + aes_ctr(section, plain, NCA_HEADER))


def make_block() -> None:
    header_a = nca_header(2)
    plain_a = b"NSZ-PLAIN-ENTRY" + body(0x3F0, compressible=False, seed=11)
    plain_a = plain_a[:0x400]
    plain_entry = header_a + plain_a

    header_b = nca_header(5)
    part1 = body(0x8000, compressible=True, seed=7)   # секция 1, сжимаемая
    gap = body(0x1000, compressible=True, seed=8)      # дырка между секциями
    part2 = body(0x3000, compressible=False, seed=13)  # секция 2, несжимаемая

    s1 = {
        "offset": NCA_HEADER,
        "size": len(part1),
        "crypto": 3,
        "key": SECTION_KEY,
        "counter": SECTION_COUNTER,
    }
    s2 = {
        "offset": NCA_HEADER + len(part1) + len(gap),
        "size": len(part2),
        "crypto": 1,  # без шифрования: байты пишутся как есть
        "key": b"\x00" * 16,
        "counter": b"\x00" * 16,
    }
    payload = part1 + gap + part2
    packed = ncz(header_b, [s1, s2], payload, block_exponent=14)

    expected_b = header_b + aes_ctr(s1, part1, NCA_HEADER) + gap + part2

    (HERE / "block.nsz").write_bytes(
        pfs0([("00" + "a" * 30 + ".nca", plain_entry), ("00" + "b" * 30 + ".ncz", packed)])
    )
    (HERE / "block_a.nca").write_bytes(plain_entry)
    (HERE / "block_b.nca").write_bytes(expected_b)


def make_short_block() -> None:
    """Последний блок короче своего места — и это НЕ порча файла.

    Ровно этот случай пришёл с телефона: «Block 306 unpacked to 409600 bytes
    instead of 1048576». zstd распаковал блок без единой жалобы; короче
    оказался сам блок, а таблица секций обещала больше, чем в файле лежит.
    Проверять надо не размер блока, а итог: совпал ли он с размером NCA из
    заголовка.
    """
    payload = body(0x100000 + 0x64000, compressible=True, seed=41)  # 1 МиБ + 400 КиБ
    nca_size = NCA_HEADER + len(payload)
    header = nca_header(43, size=nca_size)
    # Таблица секций обещает на 400 КиБ больше, чем реально сжато.
    section = {
        "offset": NCA_HEADER,
        "size": len(payload) + 0x64000,
        "crypto": 1,
        "key": b"\x00" * 16,
        "counter": b"\x00" * 16,
    }
    # Заявлено ровно столько же, сколько в таблице секций, а блоков хватает
    # на меньшее — как в файле с телефона.
    packed = ncz(header, [section], payload, block_exponent=20,
                 declared=len(payload) + 0x64000)
    (HERE / "short.ncz").write_bytes(packed)
    (HERE / "short.nca").write_bytes(header + payload)

    # Тот же файл, но заголовок NCA врёт про размер: тогда конец потока
    # ни с чем не совпадает и распаковка обязана отказаться.
    lying = nca_header(44, size=nca_size + 0x400000)
    (HERE / "short_bad.ncz").write_bytes(
        ncz(lying, [section], payload, block_exponent=20, declared=len(payload) + 0x64000)
    )


def make_xcz() -> None:
    header_a = nca_header(21)
    plain_a = body(0x600, compressible=False, seed=31)

    header_b = nca_header(22)
    part = body(0x4000, compressible=True, seed=33)
    s = {
        "offset": NCA_HEADER,
        "size": len(part),
        "crypto": 3,
        "key": SECTION_KEY,
        "counter": SECTION_COUNTER,
    }
    packed = ncz(header_b, [s], part, block_exponent=14)
    expected_b = header_b + aes_ctr(s, part, NCA_HEADER)

    secure = hfs0([("01" + "c" * 30 + ".nca", header_a + plain_a),
                   ("01" + "d" * 30 + ".ncz", packed)])

    # Корневой HFS0 обязан лежать по 0xF000 — это часть формата gamecard.
    root_offset = 0xF000
    empty_part = b""
    root_placeholder_size = len(hfs0([("update", empty_part), ("normal", empty_part),
                                      ("secure", secure), ("logo", empty_part)]))
    # Смещения в корне считаются от конца заголовка раздела, поэтому размер
    # разделов должен быть известен заранее. Собираем дважды.
    root = hfs0([("update", empty_part), ("normal", empty_part),
                 ("secure", secure), ("logo", empty_part)])
    assert len(root) == root_placeholder_size

    card = bytearray(root_offset)
    card[0x100:0x104] = b"HEAD"
    struct.pack_into("<I", card, 0x104, 2)          # secureOffset
    struct.pack_into("<I", card, 0x108, 1)          # backupOffset
    card[0x10C:0x110] = bytes([0, 0, 1, 0])         # titleKekIndex, size, версия, флаги
    struct.pack_into("<Q", card, 0x110, 0x0100000000000000)
    struct.pack_into("<I", card, 0x118, (root_offset + len(root) + 0x1FF) // 0x200)
    struct.pack_into("<Q", card, 0x130, root_offset // 0x200)
    struct.pack_into("<Q", card, 0x138, len(root))
    card += root
    card += secure

    (HERE / "game.xcz").write_bytes(bytes(card))
    (HERE / "xcz_a.nca").write_bytes(header_a + plain_a)
    (HERE / "xcz_b.nca").write_bytes(expected_b)


if __name__ == "__main__":
    make_solid()
    make_block()
    make_short_block()
    make_xcz()
    for f in sorted(HERE.iterdir()):
        if f.suffix in {".ncz", ".nsz", ".xcz", ".nca"}:
            print(f"{f.name:16} {f.stat().st_size:>8} байт")

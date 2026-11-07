#!/usr/bin/env python3
"""Print byte offset of the sole GPT system partition in an Android image.

This deliberately supports 512-byte sectors and verifies both GPT CRCs and
partition bounds. Reject ambiguous, corrupt, or unsupported layouts.
"""
import os
from pathlib import Path
import struct
import sys
import zlib

SECTOR = 512


def system_offset(path):
    size = os.path.getsize(path)
    with open(path, 'rb') as file:
        file.seek(SECTOR)
        sector = file.read(SECTOR)
        if len(sector) != SECTOR or sector[:8] != b'EFI PART':
            raise ValueError('not a 512-byte-sector GPT image')
        fields = struct.unpack_from('<8sIIIIQQQQ16sQIII', sector)
        _, revision, header_size, expected_crc, reserved, current, backup, first, last, _, table_lba, count, entry_size, table_crc = fields
        if revision != 0x10000 or not 92 <= header_size <= SECTOR or reserved or current != 1:
            raise ValueError('unsupported GPT header')
        header = bytearray(sector[:header_size])
        header[16:20] = bytes(4)
        if zlib.crc32(header) != expected_crc:
            raise ValueError('GPT header CRC mismatch')
        if backup * SECTOR >= size or first > last or last >= backup:
            raise ValueError('invalid GPT disk bounds')
        length = count * entry_size
        if not count or entry_size < 128 or entry_size % 8 or length > 4 * 1024 * 1024:
            raise ValueError('unsupported GPT partition table')
        if table_lba * SECTOR < 2 * SECTOR or table_lba * SECTOR + length > size:
            raise ValueError('GPT table outside image')
        file.seek(table_lba * SECTOR)
        entries = file.read(length)
        if len(entries) != length or zlib.crc32(entries) != table_crc:
            raise ValueError('GPT table CRC mismatch')
        matches = []
        for i in range(count):
            entry = entries[i * entry_size:(i + 1) * entry_size]
            if entry[:16] == bytes(16):
                continue
            start, end = struct.unpack_from('<QQ', entry, 32)
            if start < first or end > last or start > end or (end + 1) * SECTOR > size:
                raise ValueError('partition outside GPT usable range')
            try:
                name = entry[56:128].decode('utf-16le').split('\0', 1)[0]
            except UnicodeError as exc:
                raise ValueError('invalid GPT name') from exc
            if name == 'system':
                matches.append(start * SECTOR)
        if len(matches) != 1:
            raise ValueError('expected exactly one system partition')
        return matches[0]


if __name__ == '__main__':
    if len(sys.argv) != 2:
        raise SystemExit('usage: gpt_offset.py image')
    try:
        print(system_offset(Path(sys.argv[1])))
    except (ValueError, OSError, struct.error) as exc:
        raise SystemExit(f'GPT_ERROR {exc}')

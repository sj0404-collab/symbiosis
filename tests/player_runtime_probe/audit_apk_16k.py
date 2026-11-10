#!/usr/bin/env python3
"""Audit actual ARM64 APK entries; failure is required only for candidate TLS.

This measures ELF PT_LOAD and ZIP data placement, NOT execution on 16K Android.
Compressed entries are extracted at install, and have no direct-map alignment
requirement. No APK or replacement library is published by this script.
"""
import struct
import sys
import zipfile
from pathlib import Path


def elf_errors(data):
    if len(data) < 64 or data[:6] != b'\x7fELF\x02\x01':
        return ['not ELF64 little-endian']
    try:
        machine, = struct.unpack_from('<H', data, 18)
        phoff, = struct.unpack_from('<Q', data, 32)
        entsize, count = struct.unpack_from('<HH', data, 54)
        if machine != 183 or entsize < 56 or not count or phoff + entsize * count > len(data):
            return ['invalid ARM64 program header table']
        loads = []
        for i in range(count):
            kind, _, offset, addr, _, filesz, _, align = struct.unpack_from('<IIQQQQQQ', data, phoff + i * entsize)
            if kind == 1:
                loads.append((offset, addr, filesz, align))
        if not loads:
            return ['no PT_LOAD']
        return [f'offset={o:#x} vaddr={v:#x} align={a:#x}' for o, v, size, a in loads
                if o + size > len(data) or a < 16384 or a & (a - 1) or (v - o) % 16384]
    except struct.error:
        return ['truncated program header table']


def main(path):
    good, bad, compressed, zip_bad = 0, 0, 0, 0
    tls = {'libcrypto.so', 'libssl.so'}
    found = set()
    tls_bad = False
    with zipfile.ZipFile(path) as archive, open(path, 'rb') as raw:
        for entry in archive.infolist():
            if not entry.filename.startswith('lib/arm64-v8a/') or not entry.filename.endswith('.so'):
                continue
            name = Path(entry.filename).name
            errors = elf_errors(archive.read(entry))
            raw.seek(entry.header_offset)
            header = raw.read(30)
            if len(header) != 30 or header[:4] != b'PK\x03\x04':
                raise ValueError('invalid local ZIP header for ' + entry.filename)
            name_len, extra_len = struct.unpack_from('<HH', header, 26)
            offset = entry.header_offset + 30 + name_len + extra_len
            stored = entry.compress_type == zipfile.ZIP_STORED
            unaligned_zip = stored and offset % 16384 != 0
            if errors:
                bad += 1
                print('APK_ELF_16K_INCOMPATIBLE', name, '; '.join(errors))
            else:
                good += 1
            if not stored:
                compressed += 1
            if unaligned_zip:
                zip_bad += 1
                print('APK_UNCOMPRESSED_ENTRY_NOT_16K_ALIGNED', name, 'offset=', offset)
            if name in tls:
                found.add(name)
                tls_bad |= bool(errors) or unaligned_zip
                print('APK_TLS_ENTRY', name, 'elf16k=', not bool(errors),
                      'storage=', 'stored' if stored else 'compressed',
                      'zip_offset=', offset, 'zip16k=', offset % 16384 == 0)
    print(f'APK_16K_AUDIT arm64_elf_ok={good} arm64_elf_bad={bad} compressed={compressed} stored_zip_bad={zip_bad}')
    print('APK_16K_DEVICE_EXECUTION_NOT_TESTED')
    if found != tls or tls_bad:
        raise SystemExit('candidate TLS absent or incompatible with APK/ELF alignment')


if __name__ == '__main__':
    main(sys.argv[1])

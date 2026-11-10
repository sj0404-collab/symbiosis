#!/usr/bin/env python3
"""Block release builds while known Kenji integration evidence is missing.

Empty-body checks are deliberately supplemented by explicit blockers: merely
adding code to a callback must not silently turn an unfinished feature into a
release. Clearing each blocker requires an audited change and recorded evidence.
This still cannot prove the absence of other stubs or core compatibility.
It does not run or ship mock code.
"""
from pathlib import Path
import re
import struct
import sys

ROOT = Path(__file__).resolve().parents[1]

# Explicit release-review blockers. Do not remove merely because a callback
# gains a body or a mock test passes. The audited implementation and the
# relevant Android/core evidence must be recorded when each is removed.
UNRESOLVED_INTEGRATION = (
    'UI ядра: подключение к плееру не проверено на Android, остановка гостевых потоков и поздние callback не доказаны',
    'графические callback ядра: контракт и интеграция не проверены',
    'поставляемый OpenSSL 1.1.1l не получает публичные исправления безопасности; замена ещё не проверена',
)


def strip_comments(source: str) -> str:
    # The checked callbacks contain no string literals with comment delimiters.
    return re.sub(r'/\*.*?\*/|//[^\n]*', '', source, flags=re.S)


def check_sources(cpp: str, kotlin: str) -> list[str]:
    problems = []
    for name, source in (
        ('setRenderingThread', cpp),
        ('setCurrentTransform', cpp),
        ('updateUiHandler', kotlin),
    ):
        clean = strip_comments(source)
        match = re.search(r'\b' + name + r'\s*\([^)]*\)\s*\{([^{}]*)\}', clean, re.S)
        if match is None:
            # A nontrivial body can have nested blocks; accept its existence,
            # but don't claim this checks its semantics or completeness.
            if re.search(r'\b' + name + r'\s*\([^)]*\)\s*\{', clean, re.S) is None:
                problems.append(name + ': не удалось проверить функцию')
            continue
        body = match.group(1)
        body = re.sub(r'\(void\)\s*[A-Za-z_]\w*\s*;', '', body)
        if not body.strip():
            problems.append(name + ': функция не реализована')
    return problems


def check_tls_alignment(directory: Path) -> list[str]:
    """Fail closed on malformed or non-16K-compatible bundled ARM64 TLS ELF.

    ELF segment alignment alone is not an APK zip-alignment or device test.
    """
    problems = []
    for name in ('libcrypto.so', 'libssl.so'):
        try:
            data = (directory / name).read_bytes()
            if len(data) < 64 or data[:6] != b'\x7fELF\x02\x01':
                raise ValueError('не ELF64 little-endian')
            machine, = struct.unpack_from('<H', data, 18)
            phoff, = struct.unpack_from('<Q', data, 32)
            entsize, count = struct.unpack_from('<HH', data, 54)
            if machine != 183 or entsize < 56 or not count or phoff + entsize * count > len(data):
                raise ValueError('неверный ARM64 ELF или таблица сегментов')
            loads = []
            for index in range(count):
                kind, _, offset, vaddr, _, _, _, align = struct.unpack_from('<IIQQQQQQ', data, phoff + index * entsize)
                if kind == 1:
                    loads.append((offset, vaddr, align))
            if not loads:
                raise ValueError('нет PT_LOAD')
            if any(align < 16384 or align & (align - 1) or (vaddr - offset) % 16384 for offset, vaddr, align in loads):
                raise ValueError('PT_LOAD не совместим с выравниванием 16 КБ')
        except (OSError, ValueError, struct.error) as error:
            problems.append(f'{name}: {error}')
    return problems


def main() -> int:
    cpp = (ROOT / 'patch/symbiosis/kenji_bridge.cpp').read_text(encoding='utf-8')
    kotlin = (ROOT / 'patch/android/kenjinx/KenjinxNative.kt').read_text(encoding='utf-8')
    problems = check_sources(cpp, kotlin) + check_tls_alignment(ROOT / 'patch/libs/arm64-v8a') + list(UNRESOLVED_INTEGRATION)
    if problems:
        print('Выпуск APK остановлен: интеграция второго ядра не завершена.', file=sys.stderr)
        for problem in problems:
            print('• ' + problem, file=sys.stderr)
        return 1
    print('Известные пустые callback не найдены; это не доказательство работы ядра.')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())

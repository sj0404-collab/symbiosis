#!/usr/bin/env python3
"""Block release builds while known Kenji integration evidence is missing.

Empty-body checks are deliberately supplemented by explicit blockers: merely
adding code to a callback must not silently turn an unfinished feature into a
release. Clearing each blocker requires an audited change and recorded evidence.
This still cannot prove the absence of other stubs or core compatibility.
It does not run or ship mock code.
"""
from pathlib import Path
import hashlib
import re
import struct
import sys

ROOT = Path(__file__).resolve().parents[1]

# Explicit release-review blockers. Do not remove merely because a callback
# gains a body or a mock test passes. The audited implementation and the
# relevant Android/core evidence must be recorded when each is removed.
UNRESOLVED_INTEGRATION = (
    'UI ядра: базовый ввод проверен на Android; остановка гостевых потоков и поздние callback не доказаны',
    'графические callback ядра: контракт и интеграция не проверены',
    'OpenSSL 3.5.9: полная совместимость ядер и работа на реальном Android с страницами 16 КБ не доказаны',
    'SSL-служба обоих закреплённых ядер: импорт доверенного сертификата помечен как заглушка, гостевой TLS не прошёл',
    'служба учётной записи: Symbiosis падает на запросе без PID; оба закреплённых ядра помечают обработчик как Stubbed',
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


# Exact libraries extracted from the audited disposable Android runner build;
# updating either library requires a reviewed source provenance and new hashes.
EXPECTED_OPENSSL_SHA256 = {
    'libcrypto.so': '31dc1e131571302b5001893aeabd974b8a607eace72ab86ef61beba5a768cbf1',
    'libssl.so': '5e1b3583b7eb30d7792432f063d4fbbf0f4196fdd89e22e89bb8f8dac7507c9a',
}


def check_tls_provenance(directory: Path) -> list[str]:
    problems = []
    for name, expected in EXPECTED_OPENSSL_SHA256.items():
        try:
            actual = hashlib.sha256((directory / name).read_bytes()).hexdigest()
            if actual != expected:
                problems.append(f'{name}: SHA-256 не соответствует проверенной версии')
        except OSError:
            problems.append(f'{name}: библиотека недоступна для проверки SHA-256')
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
                kind, _, offset, vaddr, _, filesz, _, align = struct.unpack_from('<IIQQQQQQ', data, phoff + index * entsize)
                if kind == 1:
                    if offset + filesz > len(data):
                        raise ValueError('сегмент PT_LOAD выходит за пределы файла')
                    loads.append((offset, vaddr, align))
            if not loads:
                raise ValueError('нет PT_LOAD')
            if any(align < 16384 or align & (align - 1) or (vaddr - offset) % 16384 for offset, vaddr, align in loads):
                raise ValueError('PT_LOAD не совместим с выравниванием 16 КБ')
        except (OSError, ValueError, struct.error) as error:
            problems.append(f'{name}: {error}')
    return problems


def main(experimental: bool = False) -> int:
    cpp = (ROOT / 'patch/symbiosis/kenji_bridge.cpp').read_text(encoding='utf-8')
    kotlin = (ROOT / 'patch/android/kenjinx/KenjinxNative.kt').read_text(encoding='utf-8')
    libs = ROOT / 'patch/libs/arm64-v8a'
    # Experimental testing only waives the explicitly documented evidence
    # blockers. It must still reject missing/empty bridge callbacks and altered
    # or misaligned bundled TLS libraries. It is not a release certification.
    hard_problems = check_sources(cpp, kotlin) + check_tls_alignment(libs) + check_tls_provenance(libs)
    problems = hard_problems + ([] if experimental else list(UNRESOLVED_INTEGRATION))
    if problems:
        print('Сборка остановлена: обязательные проверки не пройдены.', file=sys.stderr)
        for problem in problems:
            print('• ' + problem, file=sys.stderr)
        return 1
    if experimental:
        print('ТОЛЬКО ТЕСТ: выпуск не подтверждён, известны ограничения:', file=sys.stderr)
        for problem in UNRESOLVED_INTEGRATION:
            print('• ' + problem, file=sys.stderr)
    else:
        print('Известные пустые callback не найдены; это не доказательство работы ядра.')
    return 0


if __name__ == '__main__':
    if len(sys.argv) == 1:
        raise SystemExit(main())
    if sys.argv[1:] == ['--experimental-test-only']:
        raise SystemExit(main(experimental=True))
    raise SystemExit('Неизвестный аргумент проверки выпуска')

#!/usr/bin/env python3
"""Block release builds while the known Kenji callbacks remain empty.

This is a narrow safety gate for observed gaps, not a proof that the app has
no other stubs or that either core works. It does not run or ship mock code.
"""
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]


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


def main() -> int:
    cpp = (ROOT / 'patch/symbiosis/kenji_bridge.cpp').read_text(encoding='utf-8')
    kotlin = (ROOT / 'patch/android/kenjinx/KenjinxNative.kt').read_text(encoding='utf-8')
    problems = check_sources(cpp, kotlin)
    if problems:
        print('Выпуск APK остановлен: известные функции второго ядра ещё не реализованы.', file=sys.stderr)
        for problem in problems:
            print('• ' + problem, file=sys.stderr)
        return 1
    print('Известные пустые callback не найдены; это не доказательство работы ядра.')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())

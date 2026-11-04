#!/usr/bin/env python3
"""Проверка скобок и комментариев в правленых Kotlin-файлах.

ЗАЧЕМ. Компилятор Kotlin замечает незакрытый блок комментария последним:
сборка идёт одиннадцать минут и падает на строке, которая к делу не
относится («Unresolved reference: isFile» в другом файле). Причина же —
одна лишняя последовательность `/*` внутри комментария: комментарии в
Kotlin вкладываются, и `Logs/*.log`, написанный в документации, открывает
вложенный комментарий, который никто не закрывает.

Проверка разбирает файл посимвольно, отличая строки и символьные литералы от
кода, поэтому `/*` внутри обычной строки за комментарий не считается.
"""
from __future__ import annotations

import sys
from pathlib import Path

OPEN = {"{": "}", "(": ")", "[": "]"}
CLOSE = {v: k for k, v in OPEN.items()}


def problems(path: Path) -> list[str]:
    text = path.read_text(encoding="utf-8")
    out: list[str] = []
    stack: list[tuple[str, int]] = []
    # Глубина блочного комментария: в Kotlin они вкладываются, поэтому счётчик,
    # а не признак «внутри комментария».
    depth = 0
    i = 0
    line = 1
    n = len(text)

    while i < n:
        ch = text[i]
        nxt = text[i + 1] if i + 1 < n else ""

        if ch == "\n":
            line += 1
            i += 1
            continue

        if depth > 0:
            if ch == "/" and nxt == "*":
                depth += 1
                i += 2
                continue
            if ch == "*" and nxt == "/":
                depth -= 1
                i += 2
                continue
            i += 1
            continue

        if ch == "/" and nxt == "/":
            j = text.find("\n", i)
            i = n if j < 0 else j
            continue

        if ch == "/" and nxt == "*":
            depth += 1
            i += 2
            continue

        # Сырая строка: внутри неё нет ни скобок кода, ни комментариев.
        if text.startswith('"""', i):
            close = text.find('"""', i + 3)
            if close < 0:
                out.append(f"{line}: незакрытая сырая строка")
                return out
            line += text.count("\n", i, close)
            i = close + 3
            continue

        if ch == '"':
            i += 1
            while i < n:
                if text[i] == "\\":
                    i += 2
                    continue
                if text[i] == '"':
                    i += 1
                    break
                if text[i] == "\n":
                    out.append(f"{line}: строка не закрыта до конца строки")
                    break
                i += 1
            continue

        if ch == "'":
            # Символьный литерал: 'x', '\\n', '\\u1234'. Скобка внутри него —
            # это символ, а не код, и учитывать её нельзя.
            j = i + 1
            if j < n and text[j] == "\\":
                j += 2
                while j < n and text[j] not in "'\n":
                    j += 1
            else:
                j += 1
            if j < n and text[j] == "'":
                i = j + 1
            else:
                # Не литерал — обычный символ, идём дальше.
                i += 1
            continue

        if ch in OPEN:
            stack.append((ch, line))
        elif ch in CLOSE:
            if not stack:
                out.append(f"{line}: лишняя «{ch}»")
            elif stack[-1][0] != CLOSE[ch]:
                kind, where = stack[-1]
                out.append(f"{line}: «{ch}» закрывает «{kind}» со строки {where}")
                stack.pop()
            else:
                stack.pop()
        i += 1

    if depth > 0:
        out.append("блок комментария не закрыт")
    for kind, where in stack:
        out.append(f"{where}: «{kind}» не закрыта")
    return out


def main() -> int:
    root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("patch")
    files = sorted(root.rglob("*.kt"))
    bad = 0
    for path in files:
        found = problems(path)
        if found:
            bad += 1
            print(f"{path}:")
            for item in found:
                print(f"  {item}")
    print(f"проверено {len(files)} файлов Kotlin, проблем: {bad}")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""Check that every hunk header in a .patch file agrees with its body.

WHY THIS EXISTS

    A unified-diff hunk starts with "@@ -a,b +c,d @@", and b/d are the number
    of old and new lines in the hunk. Git trusts those numbers. When a script
    inserts lines and forgets to bump the count, git stops reading when the
    count runs out and calls the next line "corrupt patch at line N".

    That N points at the END of the hunk, not at the mistake, so the message
    sends you to a file that is fine and hides the file that is not. Worse,
    the mistake is usually made while editing by hand or by a small python
    replacement, exactly where a miscount is easiest to make.

    This recomputes the counts and prints the header that should be there.

HOW A HUNK ENDS

    The patches in this repository put a blank line between a hunk and the
    next "diff --git". Git tolerates that blank, this script has to as well,
    otherwise every second hunk looks one line too long.

Usage: python3 tools/check_patch_hunks.py [patch files...]
Exit code 1 if any header disagrees.
"""

import pathlib
import re
import sys

HUNK = re.compile(r"^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@")


def check(path: pathlib.Path):
    lines = path.read_text(encoding="utf-8", errors="replace").split("\n")
    problems = []
    i = 0
    while i < len(lines):
        m = HUNK.match(lines[i])
        if not m:
            i += 1
            continue
        header_line = i + 1
        header = lines[i]
        i += 1
        old = new = 0
        while i < len(lines):
            line = lines[i]
            if HUNK.match(line) or line.startswith("diff --git"):
                break
            if line == "" and (i + 1 >= len(lines) or lines[i + 1].startswith("diff --git")):
                break  # spacer before the next file, not part of the hunk
            if line.startswith("\\"):
                pass  # "\ No newline at end of file"
            elif line.startswith("+"):
                new += 1
            elif line.startswith("-"):
                old += 1
            else:
                old += 1
                new += 1
            i += 1
        want_old = old if old != 1 else ""
        want_new = new if new != 1 else ""
        declared = (int(m.group(2) or 1), int(m.group(4) or 1))
        if declared != (old, new):
            fixed = f"@@ -{m.group(1)},{old} +{m.group(3)},{new} @@"
            problems.append((header_line, header, fixed))
    return problems


def main(argv):
    root = pathlib.Path(__file__).resolve().parent.parent
    targets = [pathlib.Path(a) for a in argv[1:]] or sorted(root.glob("patch/*.patch"))
    total = 0
    for path in targets:
        problems = check(path)
        for line_no, was, should in problems:
            print(f"{path}:{line_no}: header says {was!r}, body has {should!r}")
        total += len(problems)
        if not problems:
            print(f"ok   {path.name}: every hunk header matches its body")
    if total:
        print(f"\n{total} hunk header(s) to fix")
    return 1 if total else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))

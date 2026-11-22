"""Tiny Kotlin lexer helpers shared by the R4-6 i18n migration scripts.

Finds calls of the i18n lookup `text(` and splits their argument list while
respecting string literals (plain, raw, templates), comments and brackets.
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
# R5-1: the modules whose code calls the i18n lookup; the first owns the i18n package (C1, 0007:
# :ui-core, below the feature UI modules and the :ui shell).
SRCS = [ROOT / m / "src" for m in ("ui-core", "ui-reader", "ui-translation", "ui-sync", "ui", "composeApp")]
SRC = SRCS[0]
MAIN_SETS = ["commonMain", "jvmCommonMain", "androidMain", "desktopMain", "iosMain"]
I18N_DIR = "dev/shinsou/kmp/ui/i18n/"


def skip_string(src: str, i: int) -> int:
    """`i` points at the opening quote; returns index just past the literal."""
    if src.startswith('"""', i):
        j = src.index('"""', i + 3)
        j += 3
        while j < len(src) and src[j] == '"':
            j += 1
        return j
    j = i + 1
    while True:
        c = src[j]
        if c == "\\":
            j += 2
            continue
        if c == '"':
            return j + 1
        if c == "$" and src[j + 1] == "{":
            j = skip_brackets(src, j + 1)
            continue
        j += 1


def skip_char(src: str, i: int) -> int:
    j = i + 1
    if src[j] == "\\":
        j += 2
    else:
        j += 1
    while src[j] != "'":
        j += 1
    return j + 1


def skip_brackets(src: str, i: int) -> int:
    """`i` points at an opening ( [ or {; returns index just past its partner."""
    depth = 0
    j = i
    while j < len(src):
        c = src[j]
        if c == '"':
            j = skip_string(src, j)
            continue
        if c == "'":
            j = skip_char(src, j)
            continue
        if src.startswith("//", j):
            j = src.index("\n", j)
            continue
        if src.startswith("/*", j):
            j = src.index("*/", j) + 2
            continue
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
            if depth == 0:
                return j + 1
        j += 1
    raise ValueError("unbalanced")


def split_args(src: str, open_paren: int) -> tuple[list[tuple[int, int]], int]:
    """Returns ([(start, end) of each top-level argument], index past `)`)."""
    end = skip_brackets(src, open_paren)
    args = []
    depth = 0
    j = open_paren + 1
    start = j
    while j < end - 1:
        c = src[j]
        if c == '"':
            j = skip_string(src, j)
            continue
        if c == "'":
            j = skip_char(src, j)
            continue
        if src.startswith("//", j):
            j = src.index("\n", j)
            continue
        if src.startswith("/*", j):
            j = src.index("*/", j) + 2
            continue
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        elif c == "," and depth == 0:
            args.append((start, j))
            start = j + 1
        j += 1
    if src[start:end - 1].strip():
        args.append((start, end - 1))
    return args, end


def code_mask(src: str) -> list[bool]:
    """True for characters that are code (not inside comments or string literals).

    Code inside `${...}` string templates counts as code.
    """
    mask = [True] * len(src)

    def string_end(j: int) -> int:
        """Marks the literal starting at `j` as non-code except template bodies; returns its end."""
        raw = src.startswith('"""', j)
        k = j + (3 if raw else 1)
        while True:
            if raw and src.startswith('"""', k):
                k += 3
                while k < len(src) and src[k] == '"':
                    k += 1
                break
            c = src[k]
            if not raw and c == "\\":
                k += 2
                continue
            if not raw and c == '"':
                k += 1
                break
            if c == "$" and src[k + 1] == "{":
                for x in range(j, k + 2):
                    mask[x] = False
                inner_end = skip_brackets(src, k + 1)
                walk(k + 2, inner_end - 1)
                j = inner_end - 1
                k = inner_end
                continue
            k += 1
        for x in range(j, k):
            mask[x] = False
        return k

    def walk(start: int, stop: int) -> None:
        j = start
        while j < stop:
            c = src[j]
            if c == '"':
                j = string_end(j)
                continue
            if c == "'":
                j = skip_char(src, j)
                continue
            if src.startswith("//", j):
                k = src.find("\n", j)
                k = len(src) if k < 0 else k
                for x in range(j, k):
                    mask[x] = False
                j = k
                continue
            if src.startswith("/*", j):
                k = src.index("*/", j) + 2
                for x in range(j, k):
                    mask[x] = False
                j = k
                continue
            j += 1

    walk(0, len(src))
    return mask


SIMPLE_LITERAL = re.compile(r'^"((?:[^"\\$]|\\.|\$(?![A-Za-z_{]))*)"$', re.S)


def literal_value(arg: str) -> str | None:
    """The value of a single plain string literal without templates, else None."""
    m = SIMPLE_LITERAL.match(arg.strip())
    if not m:
        return None
    return unescape(m.group(1))


def unescape(body: str) -> str:
    out = []
    j = 0
    while j < len(body):
        c = body[j]
        if c == "\\":
            n = body[j + 1]
            if n == "u":
                out.append(chr(int(body[j + 2:j + 6], 16)))
                j += 6
                continue
            out.append({"n": "\n", "t": "\t", "r": "\r", "b": "\b", '"': '"', "'": "'", "\\": "\\", "$": "$"}[n])
            j += 2
            continue
        out.append(c)
        j += 1
    return "".join(out)


def escape(value: str) -> str:
    return (
        value.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n").replace("\t", "\\t")
        .replace("\r", "\\r").replace("$", "\\$")
    )


@dataclass
class Call:
    path: Path
    name_start: int  # index of `text`
    receiver: str  # e.g. "strings." or ""
    open_paren: int
    end: int
    args: list[tuple[int, int]]


TEXT_CALL = re.compile(r"(?<![A-Za-z0-9_])((?:[A-Za-z_][A-Za-z0-9_]*(?:\(\))?\??\.)?)text\(")


def find_text_calls(path: Path, src: str) -> list[Call]:
    mask = code_mask(src)
    calls = []
    for m in TEXT_CALL.finditer(src):
        name = m.start() + len(m.group(1))
        if not mask[name]:
            continue
        # `fun text(` / `.text(` declarations
        before = src[max(0, m.start() - 4):m.start()]
        if before.endswith("fun "):
            continue
        open_paren = m.end() - 1
        args, end = split_args(src, open_paren)
        calls.append(Call(path, name, m.group(1), open_paren, end, args))
    return calls


def main_files() -> list[Path]:
    files = []
    for src in SRCS:
        for s in MAIN_SETS:
            base = src / s / "kotlin"
            if base.exists():
                files.extend(sorted(base.rglob("*.kt")))
    return files

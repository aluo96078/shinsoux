#!/usr/bin/env python3
"""R4-6 (docs/architecture/0006): one-off migration from English-sentence lookups to typed keys.

Before: `strings.text("Some English sentence", args…)` looked the sentence up in per-language
`Map<String, String>` tables, so editing one English word silently dropped every translation.
After: `strings.text(StringKey.SOME_ENGLISH_SENTENCE, args…)`, with `enum class StringKey(val english)`
and one `Map<StringKey, String>` per language.

Inputs
  --dump  JSON snapshot of the old runtime, written by a temporary desktop test before the rewrite:
          for every language (en, zh-TW, zh-CN, ja, ko, fr, de, es, pt) its `translations` map, the
          values of its typed core fields (`core`) and the result of the old `text(key)` (`lookup`)
          for every string literal in the product sources. The new tables are derived from that
          snapshot, so a key renders exactly what the old lookup rendered in every language.

What it does (once: on a tree that already has `StringKey.kt` it stops before writing anything,
since the generated catalog is edited by hand from then on and regenerating it would drop keys)
  1. Scans product and test sources for i18n calls `text("literal", …)` and for `strKey("literal")`
     markers (placed by hand where a key travels through a variable: enum labels, `when` branches).
  2. Adds the messages that code outside the UI authors in English and the UI translates when it
     sees one (exception messages, platform notes): every literal outside a lookup that the old
     tables translated. They become `authoredMessageKey(message)`, an explicit closed map.
  3. Assigns each distinct English literal a readable identifier: its first words in upper snake
     case, `{n}` placeholders as `X`; collisions get `_2`, `_3`… in sorted-literal order.
  4. Writes `StringKey.kt`, `AuthoredMessages.kt` and one table file per language, then rewrites
     the call sites. Each file is re-read immediately before it is written; files outside the
     allowed set are never written (they are reported instead).
"""
from __future__ import annotations

import argparse
import json
import re
import sys
import unicodedata
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from kotlin_scan import (  # noqa: E402
    SRC, SRCS, code_mask, escape, find_text_calls, literal_value, skip_brackets, skip_string,
)

PKG_DIR = SRC / "commonMain" / "kotlin" / "dev" / "shinsou" / "kmp" / "ui" / "i18n"
ALL_SETS = ["commonMain", "jvmCommonMain", "androidMain", "desktopMain", "iosMain"]
TEST_SETS = ["commonTest", "desktopTest", "jvmCommonTest", "iosTest", "androidUnitTest"]
# `text(` functions that are not the i18n lookup.
NOT_I18N = ("plugin/sandbox/SandboxCbor.kt", "plugin/sandbox/SandboxProtocol.kt", "manga/MangaOcr.kt")
OWNED = ("/kmp/ui/", "/kmp/reader/", "/kmp/navigation/", "/kmp/architecture/")
# Product files outside the UI that call the lookup and cannot compile otherwise.
EXCEPTIONS = (
    "androidMain/kotlin/dev/shinsou/kmp/AndroidAppServices.kt",
    "androidMain/kotlin/dev/shinsou/kmp/AndroidBackgroundDownloads.kt",
    "desktopMain/kotlin/dev/shinsou/kmp/desktop/DesktopAppServices.kt",
    "desktopMain/kotlin/dev/shinsou/kmp/desktop/Main.kt",
    "iosMain/kotlin/dev/shinsou/kmp/IosDownloadBackgroundKeeper.kt",
)
LANGUAGES = [
    # (tag, file, table name, description)
    ("zh-TW", "TraditionalChineseStrings.kt", "TraditionalChineseTable", "Traditional Chinese (zh-TW)"),
    ("zh-CN", "SimplifiedChineseStrings.kt", "SimplifiedChineseTable", "Simplified Chinese (zh-CN)"),
    ("ja", "JapaneseStrings.kt", "JapaneseTable", "Japanese"),
    ("ko", "KoreanStrings.kt", "KoreanTable", "Korean"),
    ("fr", "FrenchStrings.kt", "FrenchTable", "French"),
    ("de", "GermanStrings.kt", "GermanTable", "German"),
    ("es", "SpanishStrings.kt", "SpanishTable", "Spanish"),
    ("pt", "PortugueseStrings.kt", "PortugueseTable", "Portuguese"),
]
OBSOLETE_FILES = ["SyncV3Strings.kt", "MangaTranslationStrings.kt", "AppUpdateStrings.kt"]
MARKER = re.compile(r'(?<![A-Za-z0-9_.])strKey\(')
# Typed fields of `ShinsouStrings` that stay as properties; each resolves through its key.
CORE_FIELD_KEYS = [
    "Library", "Updates", "History", "Browse", "More", "Sources", "Extensions", "Migration", "Downloads",
    "Statistics", "Settings", "Backup & restore", "About", "Search", "Search your library", "Refresh", "Filter",
    "Sort", "All", "Done", "Cancel", "Save", "Close", "Delete", "Remove", "Retry", "Share", "Select all",
    "selected", "Mark read", "Mark unread", "Move to category", "Continue reading", "Add to library",
    "In library", "My library", "Download", "Install", "Uninstall", "Chapters", "Reader settings",
    "Previous chapter", "Next chapter", "Page turn animation", "Animate transitions when changing pages",
    "Clear filters", "No matches", "Your library is empty", "No recent updates", "No reading history",
    "Create backup", "Restore backup",
]
PLACEHOLDER = re.compile(r"\{(\d+)\}")


def kotlin_files(sets: list[str]) -> list[Path]:
    out = []
    for src in SRCS:
        for s in sets:
            base = src / s / "kotlin"
            if base.exists():
                out.extend(sorted(base.rglob("*.kt")))
    return out


def rel(path: Path) -> str:
    """The path under its module's src/ (R5-1: ui/ or composeApp/)."""
    src = next(s for s in SRCS if path.is_relative_to(s))
    return path.relative_to(src).as_posix()


def writable(path: Path) -> bool:
    r = rel(path)
    return any(o in r for o in OWNED) or r in EXCEPTIONS


def is_i18n_file(path: Path) -> bool:
    return not rel(path).endswith(NOT_I18N)


def key_sites(path: Path, src: str) -> list[tuple[int, int, str]]:
    """(start, end, literal value) of every key literal: first arg of `text(` or a `strKey(` marker."""
    sites = []
    if is_i18n_file(path):
        for call in find_text_calls(path, src):
            if not call.args:
                continue
            a, b = call.args[0]
            raw = src[a:b]
            value = literal_value(raw)
            if value is None:
                continue
            lead = len(raw) - len(raw.lstrip())
            trail = len(raw.rstrip())
            sites.append((a + lead, a + trail, value))
    mask = code_mask(src)
    for m in MARKER.finditer(src):
        if not mask[m.start()]:
            continue
        end = skip_brackets(src, m.end() - 1)
        value = literal_value(src[m.end():end - 1])
        if value is None:
            raise SystemExit(f"{rel(path)}: strKey( needs one plain literal")
        sites.append((m.start(), end, value))
    return sites


def other_literals(path: Path, src: str, keyed: list[tuple[int, int, str]]) -> set[str]:
    """Plain string literals in code that are not themselves key literals."""
    mask = code_mask(src)
    spans = [(a, b) for a, b, _ in keyed]
    found = set()
    for i, c in enumerate(src):
        if c != '"' or mask[i] or (i > 0 and not mask[i - 1]) or src.startswith('"""', i):
            continue
        if any(a <= i < b for a, b in spans):
            continue
        end = skip_string(src, i)
        try:
            value = literal_value(src[i:end])
        except (KeyError, ValueError, IndexError):
            continue
        if value:
            found.add(value)
    return found


# Literals without ASCII words.
NAMED = {"简体中文": "SIMPLIFIED_CHINESE_NAME", "繁體中文": "TRADITIONAL_CHINESE_NAME"}


def identifier(literal: str) -> str:
    if literal in NAMED:
        return NAMED[literal]
    text = PLACEHOLDER.sub(" X ", literal)
    text = unicodedata.normalize("NFKD", text).encode("ascii", "ignore").decode()
    words = re.findall(r"[A-Za-z0-9]+", text)[:7]
    name = "_".join(w.upper() for w in words) or "KEY"
    if name[0].isdigit():
        name = "N" + name
    return name


def assign_identifiers(literals: list[str]) -> dict[str, str]:
    taken: dict[str, int] = {}
    out = {}
    for lit in sorted(literals):
        base = identifier(lit)
        n = taken.get(base, 0) + 1
        taken[base] = n
        out[lit] = base if n == 1 else f"{base}_{n}"
    return out


def placeholders(s: str) -> set[str]:
    return set(PLACEHOLDER.findall(s))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dump", required=True)
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument(
        "--resume", action="store_true",
        help="keep the generated StringKey.kt and only rewrite the call sites still left",
    )
    args = parser.parse_args()
    dump = json.loads(Path(args.dump).read_text())
    en = dump["en"]

    def old(lang: str, key: str) -> str:
        value = dump[lang]["lookup"].get(key)
        if value is None:
            raise SystemExit(f"no snapshot for {key!r}; re-run the dump with this literal")
        return value

    def explicit(lang: str, key: str) -> bool:
        d = dump[lang]
        if key in d["translations"]:
            return True
        core = d["core"].get(key)
        return core is not None and core != en["core"].get(key)

    product = [p for p in kotlin_files(ALL_SETS)]
    tests = [p for p in kotlin_files(TEST_SETS)]

    used: set[str] = set(CORE_FIELD_KEYS)
    sites: dict[Path, list[tuple[int, int, str]]] = {}
    others: set[str] = set()
    for path in product:
        src = path.read_text()
        found = key_sites(path, src)
        if found:
            sites[path] = found
            used.update(v for _, _, v in found)
        if PKG_DIR.as_posix() not in path.as_posix():
            others |= other_literals(path, src, found)

    languages = [lang for lang in dump if lang != "en"]

    def translated_anywhere(key: str) -> bool:
        return old("en", key) != key or any(explicit(lang, key) for lang in languages)

    authored = sorted(k for k in others if k in en["lookup"] and translated_anywhere(k))
    used |= set(authored)
    for k in sorted(used):
        old("en", k)

    if args.resume:
        ids = recover_ids(dump)
        rewrite_sites(product + tests, ids)
        return
    ids = assign_identifiers(sorted(used))

    # ---- report
    table_keys = set()
    for lang in dump.values():
        table_keys |= set(lang["translations"])
    dropped = sorted(table_keys - used)
    print(f"keys: {len(used)}  authored messages: {len(authored)}  dropped table entries: {len(dropped)}")
    for tag, *_ in LANGUAGES:
        missing = [k for k in used if not explicit(tag, k) and old(tag, k) == old("en", k)]
        print(f"  {tag}: {len(used) - len(missing)} explicit, {len(missing)} fall back to English")
    mismatches = []
    for tag, *_ in LANGUAGES:
        for k in used:
            if explicit(tag, k) and placeholders(old(tag, k)) != placeholders(old("en", k)):
                mismatches.append((tag, k, old(tag, k)))
    for m in mismatches:
        print("  placeholder mismatch:", m)
    Path("/tmp/i18n-migration-report.json").write_text(json.dumps({
        "ids": ids, "authored": authored, "dropped": dropped,
    }, ensure_ascii=False, indent=1))
    if args.dry_run:
        return
    # The migration ran already: the catalog is hand-maintained now and holds keys the scan of the
    # rewritten tree cannot rediscover. Writing it again would remove them and break the build.
    if (PKG_DIR / "StringKey.kt").exists():
        raise SystemExit(
            f"{PKG_DIR / 'StringKey.kt'} exists: the typed-key migration already ran; nothing is written. "
            "Edit StringKey.kt and the language tables by hand."
        )

    # ---- generated files
    header = "// GENERATED once by scripts/i18n/migrate_typed_keys.py (R4-6, docs/architecture/0006); edit by hand from now on.\n"
    lines = [
        "package dev.shinsou.kmp.ui.i18n", "", header.rstrip(), "",
        "/** Every user-facing UI string; [english] is the text shown when a language has no entry. */",
        '@Suppress("LargeClass") // one entry per string; splitting it would only scatter the keys',
        "enum class StringKey(val english: String) {",
    ]
    for lit, ident in sorted(ids.items(), key=lambda kv: kv[1]):
        lines.append(f'    {ident}("{escape(old("en", lit))}"),')
    lines += ["}", ""]
    write_generated(PKG_DIR / "StringKey.kt", "\n".join(lines))

    lines = [
        "package dev.shinsou.kmp.ui.i18n", "", header.rstrip(), "",
        "/**",
        " * Messages that code outside the UI authors in English (exception messages, platform notes) and",
        " * the UI translates when it shows one. Matching is exact; any other text is external and shown as is.",
        " * Moving each producer to a [StringKey] removes its line here.",
        " */",
        "private val authoredMessageKeys: Map<String, StringKey> = mapOf(",
    ]
    for lit in authored:
        lines.append(f'    "{escape(lit)}" to StringKey.{ids[lit]},')
    lines += [")", "", "internal fun authoredMessageKey(message: String): StringKey? = authoredMessageKeys[message]", ""]
    write_generated(PKG_DIR / "AuthoredMessages.kt", "\n".join(lines))

    for tag, file, table, description in LANGUAGES:
        lines = [
            "package dev.shinsou.kmp.ui.i18n", "", header.rstrip(), "",
            f"/** The {description} strings. A key without an entry shows [StringKey.english]. */",
            f"internal val {table}: Map<StringKey, String> = mapOf(",
        ]
        for lit, ident in sorted(ids.items(), key=lambda kv: kv[1]):
            if explicit(tag, lit) or old(tag, lit) != old("en", lit):
                lines.append(f'    StringKey.{ident} to "{escape(old(tag, lit))}",')
        lines += [")", ""]
        write_generated(PKG_DIR / file, "\n".join(lines))
    for name in OBSOLETE_FILES:
        p = PKG_DIR / name
        if p.exists():
            p.unlink()
            print("deleted", rel(p))

    rewrite_sites(product + tests, ids)


def rewrite_sites(paths: list[Path], ids: dict[str, str]) -> None:
    skipped = []
    for path in paths:
        if not path.exists():
            continue
        src = path.read_text()  # re-read right before writing
        found = key_sites(path, src)
        if not found:
            continue
        unknown = [v for _, _, v in found if v not in ids]
        if unknown:
            skipped.append((rel(path), sorted(set(unknown))))
        edits = [(a, b, f"StringKey.{ids[v]}") for a, b, v in found if v in ids]
        if not edits:
            continue
        if not writable(path):
            skipped.append((rel(path), ["NOT WRITABLE"]))
            continue
        for a, b, new in sorted(edits, reverse=True):
            src = src[:a] + new + src[b:]
        src = add_import(src)
        path.write_text(src)
        print(f"rewrote {len(edits):4d} in {rel(path)}")
    for s in skipped:
        print("left as is:", s)


def recover_ids(dump: dict) -> dict[str, str]:
    """literal -> identifier, read back from a StringKey.kt this script generated."""
    by_english: dict[str, list[str]] = {}
    for lit, english in dump["en"]["lookup"].items():
        by_english.setdefault(english, []).append(lit)
    ids = {}
    enum = (PKG_DIR / "StringKey.kt").read_text()
    for m in re.finditer(r'^    ([A-Z0-9_]+)\("((?:[^"\\]|\\.)*)"\),$', enum, re.M):
        ident, english = m.group(1), literal_value('"' + m.group(2) + '"')
        base = re.sub(r"_\d+$", "", ident)
        candidates = [lit for lit in by_english.get(english, []) if identifier(lit) in (ident, base)]
        if len(candidates) != 1:
            raise SystemExit(f"cannot recover the literal of {ident}: {candidates}")
        ids[candidates[0]] = ident
    if assign_identifiers(sorted(ids)) != ids:
        raise SystemExit("recovered identifiers differ from a fresh assignment")
    return ids


def write_generated(path: Path, content: str) -> None:
    path.write_text(content)
    print("wrote", rel(path))


def add_import(src: str) -> str:
    if re.search(r"^package dev\.shinsou\.kmp\.ui\.i18n\s*$", src, re.M):
        return src
    if re.search(r"^import dev\.shinsou\.kmp\.ui\.i18n\.(StringKey|\*)\s*$", src, re.M):
        return src
    imports = list(re.finditer(r"^import .*$", src, re.M))
    line = "import dev.shinsou.kmp.ui.i18n.StringKey"
    if imports:
        # Keep the block sorted where it already is.
        for m in imports:
            if m.group(0) > line:
                return src[:m.start()] + line + "\n" + src[m.start():]
        last = imports[-1]
        return src[:last.end()] + "\n" + line + src[last.end():]
    pkg = re.search(r"^package .*$", src, re.M)
    return src[:pkg.end()] + "\n\n" + line + src[pkg.end():]


if __name__ == "__main__":
    main()

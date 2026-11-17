#!/usr/bin/env python3
"""Builds the Simplified/Traditional Chinese conversion table the app bundles, from OpenCC's dictionaries.

Usage:
    python3 tools/chinese-script/build_dictionary.py

Downloads OpenCC's dictionaries (Apache-2.0, https://github.com/BYVoid/OpenCC) at a pinned commit and writes
  composeApp/src/commonMain/composeResources/files/chinese-script.txt   the conversion table
  composeApp/src/commonMain/composeResources/files/opencc-LICENSE.txt   OpenCC's license, shipped with it
  composeApp/src/commonMain/kotlin/dev/shinsou/kmp/translation/ChineseScriptCharacters.kt
                                                                        characters written only one way, for detection
The table follows OpenCC's s2twp (Simplified -> Taiwan, with Taiwan phrases) and tw2sp (Taiwan -> Simplified, with
mainland phrases). A phrase is left out when the rest of the table converts any text it
appears in the same way.
"""
import os, sys, urllib.request

COMMIT = "fae2a0a911c30764e669a7d8b0591cd8e852934a"
BASE = f"https://raw.githubusercontent.com/BYVoid/OpenCC/{COMMIT}/data/dictionary"
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
CACHE = os.path.join(ROOT, "build", "opencc", COMMIT)
TABLE = os.path.join(ROOT, "composeApp/src/commonMain/composeResources/files/chinese-script.txt")
LICENSE = os.path.join(ROOT, "composeApp/src/commonMain/composeResources/files/opencc-LICENSE.txt")
CHARACTERS = os.path.join(ROOT, "composeApp/src/commonMain/kotlin/dev/shinsou/kmp/translation/ChineseScriptCharacters.kt")


def load(name):
    path = os.path.join(CACHE, name + ".txt")
    if not os.path.exists(path):
        os.makedirs(CACHE, exist_ok=True)
        urllib.request.urlretrieve(f"{BASE}/{name}.txt", path + ".part")
        os.replace(path + ".part", path)
    entries = {}
    for line in open(path, encoding="utf-8"):
        if line.startswith("#") or "\t" not in line:
            continue
        key, values = line.rstrip("\n").split("\t", 1)
        entries.setdefault(key, values.split(" "))
    return entries


def reverse(entries):
    out = {}
    for key, values in entries.items():
        for value in values:
            out.setdefault(value, [key])
    return out


def merge(*dicts):
    """Earlier dictionaries win, as in OpenCC's short-circuit groups; only the first value of each entry is kept."""
    out = {}
    for d in dicts:
        for key, values in d.items():
            out.setdefault(key, values[0])
    return out


def convert(text, table, longest):
    out, i = [], 0
    while i < len(text):
        for n in range(min(longest, len(text) - i), 0, -1):
            hit = table.get(text[i:i + n])
            if hit is not None:
                out.append(hit); i += n; break
        else:
            out.append(text[i]); i += 1
    return "".join(out)


def prune(table):
    """Drops entries that change nothing, and phrases the rest of the table converts the same way in any text.

    The longest match reads text left to right. Where a dropped phrase used to match, the rest of the table reads its
    characters instead, so it is only dropped when they convert the same and none of the matches inside it can run
    past its end: 里面 stops 面包 (bread) from being matched inside 里面包含, which would turn into 裡麵包含. A phrase
    dropped early may be needed once later ones are gone, so the dropped ones are checked again against the final
    table until none is.
    """
    kept, prefixes = dict(table), {}

    def count(key, step):
        for n in range(1, len(key)):
            prefixes[key[:n]] = prefixes.get(key[:n], 0) + step

    for key in table:
        count(key, 1)

    def spares(key):
        """Whether text where [key] matched converts the same without it."""
        out, j = [], 0
        while j < len(key):
            # past the start, a longer match would run beyond the phrase into whatever follows it
            if j > 0 and prefixes.get(key[j:], 0) > 0:
                return False
            for n in range(len(key) - j, 0, -1):
                hit = kept.get(key[j:j + n])
                if hit is not None:
                    out.append(hit); j += n; break
            else:
                out.append(key[j]); j += 1
        return "".join(out) == table[key]

    def drop(key):
        del kept[key]
        count(key, -1)
        if spares(key):
            return True
        kept[key] = table[key]
        count(key, 1)
        return False

    dropped = [key for key in sorted(table, key=len, reverse=True) if (len(key) > 1 or key == table[key]) and drop(key)]
    while True:
        needed = [key for key in dropped if not spares(key)]
        if not needed:
            return kept
        for key in needed:
            kept[key] = table[key]
            count(key, 1)
        dropped = [key for key in dropped if key not in kept]


def main():
    st, ts = load("STCharacters"), load("TSCharacters")
    stages = {
        "s2t": merge(load("STPhrases"), st),
        "t2tw": merge(load("TWPhrases"), load("TWVariantsPhrases"), load("TWVariants")),
        "tw2t": merge(load("TWPhrasesRev"), load("TWVariantsRevPhrases"), reverse(load("TWVariants"))),
        "t2s": merge(load("TSPhrases"), ts),
    }
    os.makedirs(os.path.dirname(TABLE), exist_ok=True)
    with open(TABLE, "w", encoding="utf-8", newline="\n") as f:
        f.write(f"# Built by tools/chinese-script/build_dictionary.py from OpenCC {COMMIT}\n")
        f.write("# OpenCC dictionaries: Copyright (c) 2010-2020 Carbo Kuo and contributors, Apache License 2.0\n")
        for name, table in stages.items():
            kept = prune(table)
            print(name, len(table), "->", len(kept), file=sys.stderr)
            f.write(f"@{name}\n")
            for key in sorted(kept):
                f.write(f"{key}\t{kept[key]}\n")
    print("table", os.path.getsize(TABLE), "bytes", file=sys.stderr)
    urllib.request.urlretrieve(f"https://raw.githubusercontent.com/BYVoid/OpenCC/{COMMIT}/LICENSE", LICENSE)

    # characters written only one way: a Simplified form that is never a Traditional one, and the other way round
    traditional = {v for vs in st.values() for v in vs} | set(ts)
    simplified = {v for vs in ts.values() for v in vs} | set(st)
    only_simplified = sorted(k for k, vs in st.items() if k not in vs and k not in traditional and len(k) == 1 and ord(k) < 0x10000)
    only_traditional = sorted(k for k, vs in ts.items() if k not in vs and k not in simplified and len(k) == 1 and ord(k) < 0x10000)
    print("simplified-only", len(only_simplified), "traditional-only", len(only_traditional), file=sys.stderr)
    with open(CHARACTERS, "w", encoding="utf-8", newline="\n") as f:
        f.write("package dev.shinsou.kmp.translation\n\n")
        f.write(f"// Built by tools/chinese-script/build_dictionary.py from OpenCC {COMMIT} (Apache-2.0). Do not edit.\n\n")
        f.write("/** Characters written only in Simplified Chinese. */\n")
        f.write(f'internal const val SIMPLIFIED_ONLY = "{"".join(only_simplified)}"\n\n')
        f.write("/** Characters written only in Traditional Chinese. */\n")
        f.write(f'internal const val TRADITIONAL_ONLY = "{"".join(only_traditional)}"\n')


if __name__ == "__main__":
    main()

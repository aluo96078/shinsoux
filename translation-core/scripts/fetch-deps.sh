#!/usr/bin/env bash
# Fetches the pinned sources of the offline translation engine into third_party/ (not committed;
# ADR-0004 §4.5) and applies the patches in patches/. Re-running is a no-op.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p third_party

# Mozilla's Firefox translation engine (bergamot-translator with its Marian fork), MPL-2.0.
# Pinned to a commit: its submodules are pinned by that commit's tree.
TRANSLATIONS_URL=https://github.com/mozilla/translations.git
TRANSLATIONS_COMMIT=8eada038fd45bbf6fa58a412b22f80c79887fabc
SUBMODULES=(
  inference/3rd_party/ssplit-cpp
  inference/marian-fork/src/3rd_party/sentencepiece
  inference/marian-fork/src/3rd_party/ruy
  inference/marian-fork/src/3rd_party/simd_utils
  inference/marian-fork/src/3rd_party/intgemm
)
# The sentence splitter's regex library. Its build would download this at build time without
# checking it; it is fetched and verified here instead.
PCRE2_VERSION=10.39
PCRE2_SHA256=0781bd2536ef5279b1943471fdcdbd9961a2845e1d2c9ad849b9bd98ba1a9bd4

dir=third_party/translations
if [ ! -d "$dir/.git" ]; then
  git init --quiet "$dir"
  git -C "$dir" remote add origin "$TRANSLATIONS_URL"
  git -C "$dir" fetch --quiet --depth 1 origin "$TRANSLATIONS_COMMIT"
  git -C "$dir" -c advice.detachedHead=false checkout --quiet FETCH_HEAD
fi
head="$(git -C "$dir" rev-parse HEAD)"
if [ "$head" != "$TRANSLATIONS_COMMIT" ]; then
  echo "$dir is at $head; it is pinned to $TRANSLATIONS_COMMIT. Remove it to fetch it again." >&2
  exit 1
fi
git -C "$dir" submodule --quiet update --init --depth 1 --recursive "${SUBMODULES[@]}"
echo "translations $head"

pcre2="$dir/inference/3rd_party/ssplit-cpp/src/3rd-party/pcre2-$PCRE2_VERSION"
if [ ! -f "$pcre2/configure" ]; then
  archive="$(mktemp)"
  trap 'rm -f "$archive"' EXIT
  curl --fail --silent --show-error --location --output "$archive" \
    "https://github.com/PhilipHazel/pcre2/releases/download/pcre2-$PCRE2_VERSION/pcre2-$PCRE2_VERSION.tar.gz"
  if command -v sha256sum > /dev/null; then actual="$(sha256sum "$archive" | cut -d' ' -f1)"; else actual="$(shasum -a 256 "$archive" | cut -d' ' -f1)"; fi
  if [ "$actual" != "$PCRE2_SHA256" ]; then
    echo "pcre2-$PCRE2_VERSION.tar.gz has SHA-256 $actual; expected $PCRE2_SHA256." >&2
    exit 1
  fi
  mkdir -p "$(dirname "$pcre2")"
  tar -xzf "$archive" -C "$(dirname "$pcre2")"
fi
echo "pcre2 $PCRE2_VERSION"

apply_patch() { # directory patch
  local patch="$PWD/patches/$2"
  if git -C "$1" apply --reverse --check "$patch" 2>/dev/null; then
    echo "$2 already applied"
  else
    git -C "$1" apply "$patch"
    echo "applied $2"
  fi
}
apply_patch "$dir" translations-build-fixes.patch
apply_patch "$dir/inference/3rd_party/ssplit-cpp" ssplit-pcre2.patch
apply_patch "$dir/inference/marian-fork/src/3rd_party/sentencepiece" sentencepiece-constexpr.patch
apply_patch "$dir/inference/marian-fork/src/3rd_party/simd_utils" simd-utils-fenv.patch

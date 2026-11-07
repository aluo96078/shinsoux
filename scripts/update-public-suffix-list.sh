#!/usr/bin/env bash
# Refreshes composeApp/psl/public_suffix_list.dat from the Public Suffix List and prints the
# commit and SHA-256 to record in composeApp/psl/README.md. Review the diff before committing.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
target="$root/composeApp/psl/public_suffix_list.dat"
commit="$(git ls-remote https://github.com/publicsuffix/list.git refs/heads/main | cut -f1)"
tmp="$(mktemp)"
trap 'rm -f "$tmp"' EXIT
curl -sSfL "https://raw.githubusercontent.com/publicsuffix/list/$commit/public_suffix_list.dat" -o "$tmp"
rules="$(grep -c '^[^/[:space:]]' "$tmp" || true)"
if [ "$rules" -lt 5000 ]; then
  echo "Downloaded list has only $rules rules; refusing to replace it." >&2
  exit 1
fi
mv "$tmp" "$target"
trap - EXIT
echo "commit: $commit"
echo "sha256: $(shasum -a 256 "$target" | cut -d' ' -f1)"
echo "rules:  $rules"

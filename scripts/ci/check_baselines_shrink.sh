#!/usr/bin/env bash
# R2-1/R2-2/R4-6 (docs/architecture/0006): the detekt baselines, the layering allowlist and the
# i18n coverage lists record the findings that existed when the checks came in. They may only
# shrink: this fails when a change adds entries to any of them compared with the base it is
# merged into.
#
# The detekt baselines and the allowlist are compared by count: their entries name files and
# signatures, so moving code renames entries without adding a finding. The i18n lists hold keys,
# which do not move, so there every entry the base does not have is an addition.
set -euo pipefail

base="${1:?usage: check_baselines_shrink.sh <base ref>}"
# Without the base there is nothing to compare with, and that must not pass for "nothing grew".
if ! git rev-parse --verify --quiet "$base^{commit}" >/dev/null; then
  echo "::error::base $base is not a commit in this clone; the baselines cannot be compared" >&2
  exit 1
fi
status=0

entries() { # <ref or "WORKTREE"> <path> <grep pattern>: the matching lines, none when the file is missing
  if [[ "$1" == WORKTREE ]]; then
    [[ -f "$2" ]] && grep -- "$3" "$2" || true
  else
    git show "$1:$2" 2>/dev/null | grep -- "$3" || true
  fi
}

count() { entries "$@" | wc -l | tr -d ' '; }

check() { # <path> <grep pattern> [keys]
  local before after added
  before="$(count "$base" "$1" "$2")"
  after="$(count WORKTREE "$1" "$2")"
  echo "$1: $before -> $after"
  # A file the base does not have yet is being introduced, not grown.
  git cat-file -e "$base:$1" 2>/dev/null || return 0
  if (( after > before )); then
    echo "::error file=$1::$1 grew from $before to $after entries; fix the new findings instead"
    status=1
  elif [[ "${3:-}" == keys ]]; then
    added="$(comm -13 <(entries "$base" "$1" "$2" | sort -u) <(entries WORKTREE "$1" "$2" | sort -u))"
    if [[ -n "$added" ]]; then
      echo "::error file=$1::$1 has entries its base does not: $(echo "$added" | tr '\n' ' ')"
      status=1
    fi
  fi
}

for baseline in config/detekt/baseline-*.xml; do
  check "$baseline" "<ID>"
done
check config/architecture/dependency-allowlist.txt "|"
# One key per line; blank lines and "#" comments are not entries.
check config/i18n/zh-TW-missing.txt "^[^#[:space:]]" keys
check config/i18n/zh-TW-orphans.txt "^[^#[:space:]]" keys

exit "$status"

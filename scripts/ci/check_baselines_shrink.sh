#!/usr/bin/env bash
# R2-1/R2-2 (docs/architecture/0006): the detekt baselines and the layering allowlist record the
# findings that existed when the checks came in. They may only shrink: this fails when a change
# adds entries to any of them compared with the base it is merged into.
#
# They are compared by count: their entries name files and signatures, so moving code renames
# entries without adding a finding. The detekt baselines are counted per rule too, so one finding
# fixed does not make room for another of a different rule.
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

check() { # <path> <grep pattern>
  local before after
  before="$(count "$base" "$1" "$2")"
  after="$(count WORKTREE "$1" "$2")"
  echo "$1: $before -> $after"
  # A file the base does not have yet is being introduced, not grown.
  git cat-file -e "$base:$1" 2>/dev/null || return 0
  if (( after > before )); then
    echo "::error file=$1::$1 grew from $before to $after entries; fix the new findings instead"
    status=1
  fi
}

# R5-1: every Gradle module has its own detekt baselines, config/detekt/<module>/baseline-<kind>.xml
# (before R5-1 there was one set, config/detekt/baseline-<kind>.xml). They are added up per kind
# (the source set: main, androidMainSourceSet, ...), so code moving between modules moves entries
# without growing anything, while the kind as a whole still may only shrink.
baseline_files() { # <ref or "WORKTREE"> <kind>: the baseline files of that kind in every module
  if [[ "$1" == WORKTREE ]]; then
    find config/detekt -type f -name "baseline-$2.xml" | sort
  else
    git ls-tree -r --name-only "$1" -- config/detekt | grep -- "/baseline-$2\.xml$" || true
  fi
}

detekt_entries() { # <ref or "WORKTREE"> <kind>: the entries of every baseline of that kind
  local file
  for file in $(baseline_files "$1" "$2"); do entries "$1" "$file" "<ID>"; done
}

# The rule of a baseline entry: <ID>Rule:File.kt$signature</ID>.
rules() { detekt_entries "$1" "$2" | sed -E 's/.*<ID>([A-Za-z]+):.*/\1/' | sort | uniq -c | awk '{print $2 " " $1}'; }

check_kind() { # <kind>: no more entries in total, and none of any rule, than in the base
  local before after rule
  before="$(detekt_entries "$base" "$1" | wc -l | tr -d ' ')"
  after="$(detekt_entries WORKTREE "$1" | wc -l | tr -d ' ')"
  echo "detekt baselines $1: $before -> $after"
  # A kind the base does not have yet is being introduced, not grown.
  [[ -n "$(baseline_files "$base" "$1")" ]] || return 0
  if (( after > before )); then
    echo "::error::the detekt baselines of $1 grew from $before to $after entries; fix the new findings instead"
    status=1
  fi
  # By rule as well: fixing one finding must not make room for a finding of another kind.
  while read -r rule after; do
    before="$(rules "$base" "$1" | awk -v rule="$rule" '$1 == rule {print $2}')"
    if (( after > ${before:-0} )); then
      echo "::error::the detekt baselines of $1 have $after entries of $rule, up from ${before:-0}; fix the new findings instead"
      status=1
    fi
  done < <(rules WORKTREE "$1")
}

kinds="$( { baseline_files WORKTREE '*'; baseline_files "$base" '[^/]*'; } | sed -E 's#.*/baseline-(.*)\.xml$#\1#' | sort -u)"
for kind in $kinds; do
  check_kind "$kind"
done
check config/architecture/dependency-allowlist.txt "^[^#].*|"
check config/architecture/module-allowlist.txt "^[^#].*|"

exit "$status"

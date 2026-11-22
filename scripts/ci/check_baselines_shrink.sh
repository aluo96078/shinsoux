#!/usr/bin/env bash
# R2-1/R2-2 (docs/architecture/0006): the detekt baselines and the layering allowlist record the
# findings that existed when the checks came in. They may only shrink: this fails when a change
# adds entries to any of them compared with the base it is merged into.
#
# They are compared by count: their entries name files and signatures, so moving code renames
# entries without adding a finding. The detekt baselines are counted per rule too, so one finding
# fixed does not make room for another of a different rule.
#
# Detekt baseline layout (0007 §7 follow-up 6): a module's findings are in
# config/detekt/<module>/baseline-<kind>.xml, each finding once. detekt reads one file per task, so
# the typed desktop task reads common + main, the typed Android task common + debug +
# androidMainSourceSet, and the untyped source-set tasks androidMainSourceSet / iosMainSourceSet
# (build-logic DetektBaselines.kt and QualityPlugin). Before that layout main and debug listed the
# findings of the common sources twice. So the detekt baselines are counted as unique findings: the
# distinct `<ID>` entries of a module's files, added up over the modules (code moving between
# modules still moves entries without growing the total). The same count on the old layout of the
# base ref is what makes the de-duplication neither growth nor a way to hide a finding.
#
# They are counted per analysis target as well, each target being the files its task reads (the
# same formula on both layouts: before `common` existed, main and debug held it). So a finding
# fixed for one platform does not make room for a new one on another.
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

# R5-1: every Gradle module has its own detekt baselines (before R5-1 there was one set,
# config/detekt/baseline-<kind>.xml). A finding is one entry of one module: the module is the
# directory of the file, the finding its <ID> text. The kinds (common, main, debug, ...) only say
# which tasks read the file, so they are not told apart: a finding listed in two of a module's files
# (the old layout listed every common finding in main and debug) counts once.
baseline_files() { # <ref or "WORKTREE">: the detekt baseline files of every module
  if [[ "$1" == WORKTREE ]]; then
    find config/detekt -type f -name 'baseline*.xml' | sort
  else
    git ls-tree -r --name-only "$1" -- config/detekt | grep -E -- '/baseline[^/]*\.xml$' || true
  fi
}

findings() { # <ref or "WORKTREE"> [kind...]: "<module directory>|<ID entry>", one line per distinct
  # finding of the baseline files of those kinds (all kinds when none are given)
  local ref="$1" file kind
  shift
  for file in $(baseline_files "$ref"); do
    if (( $# > 0 )); then
      kind="$(basename "$file" .xml)"
      kind="${kind#baseline-}"
      [[ " $* " == *" $kind "* ]] || continue
    fi
    entries "$ref" "$file" "<ID>" | sed -E 's/^[[:space:]]+//' | sed "s#^#$(dirname "$file")|#"
  done | sort -u
}

# The analysis targets and the baseline kinds each one's task reads (build-logic DetektBaselines.kt).
TARGETS=(
  "desktop:common main"
  "android:common debug androidMainSourceSet"
  "androidMainSourceSet:androidMainSourceSet"
  "iosMainSourceSet:iosMainSourceSet"
)

# The rule of a finding: <ID>Rule:File.kt$signature</ID>.
rules() { sed -E 's/^[^|]*\|<ID>([A-Za-z]+):.*/\1/' | sort | uniq -c | awk '{print $2 " " $1}'; }

check_findings() { # <label> [kind...]: no more distinct findings, and none of any rule, than in the base
  local label="$1" before_list after_list before after rule
  shift
  before_list="$(findings "$base" "$@")"
  after_list="$(findings WORKTREE "$@")"
  before="$(printf '%s\n' "$before_list" | grep -c . || true)"
  after="$(printf '%s\n' "$after_list" | grep -c . || true)"
  echo "detekt baselines ($label): $before -> $after"
  # A base without baselines yet is being introduced, not grown.
  [[ -n "$(baseline_files "$base")" ]] || return 0
  if (( after > before )); then
    echo "::error::the detekt baselines ($label) grew from $before to $after findings; fix the new findings instead"
    status=1
  fi
  # By rule as well: fixing one finding must not make room for a finding of another kind.
  while read -r rule after; do
    before="$(printf '%s\n' "$before_list" | rules | awk -v rule="$rule" '$1 == rule {print $2}')"
    if (( after > ${before:-0} )); then
      echo "::error::the detekt baselines ($label) have $after findings of $rule, up from ${before:-0}; fix the new findings instead"
      status=1
    fi
  done < <(printf '%s\n' "$after_list" | rules)
}

check_findings "unique findings"
for target in "${TARGETS[@]}"; do
  # shellcheck disable=SC2086 # the kinds are words
  check_findings "${target%%:*}" ${target#*:}
done
check config/architecture/dependency-allowlist.txt "^[^#].*|"
check config/architecture/module-allowlist.txt "^[^#].*|"

exit "$status"

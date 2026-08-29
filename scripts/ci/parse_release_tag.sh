#!/usr/bin/env bash

set -euo pipefail

tag="${1:-${GITHUB_REF_NAME:-}}"
if [[ ! "$tag" =~ ^v(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]{0,2})(-beta\.([1-9][0-9]?))?$ ]]; then
  echo "Invalid release tag: expected vMAJOR.MINOR.PATCH or vMAJOR.MINOR.PATCH-beta.N, got $tag." >&2
  exit 1
fi

major="${BASH_REMATCH[1]}"
minor="${BASH_REMATCH[2]}"
patch="${BASH_REMATCH[3]}"
beta_number="${BASH_REMATCH[5]:-}"

if (( 10#$major > 81 || 10#$minor > 255 || 10#$patch > 654 )); then
  echo "Unsupported release version: major <= 81, minor <= 255, and patch <= 654 are required." >&2
  exit 1
fi
if (( 10#$major == 0 && 10#$minor == 0 && 10#$patch == 0 )); then
  echo "Unsupported release version: v0.0.0 and its prereleases are not publishable." >&2
  exit 1
fi

prerelease=false
stage=99
if [[ -n "$beta_number" ]]; then
  if (( 10#$beta_number > 98 )); then
    echo "Unsupported beta number: beta.N requires N between 1 and 98." >&2
    exit 1
  fi
  prerelease=true
  stage=$((10#$beta_number))
fi

package_version="$major.$minor.$patch"
windows_build=$((10#$patch * 100 + stage))
windows_package_version="$major.$minor.$windows_build"
version="${tag#v}"
version_code=$(((((10#$major * 256) + 10#$minor) * 1000 + 10#$patch) * 100 + stage))
if (( version_code <= 0 || version_code > 2100000000 )); then
  echo "Unsupported application version code: $version_code is outside the Android range." >&2
  exit 1
fi

outputs=(
  "tag=$tag"
  "version=$version"
  "package_version=$package_version"
  "windows_package_version=$windows_package_version"
  "version_code=$version_code"
  "prerelease=$prerelease"
)
printf '%s\n' "${outputs[@]}"
if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
  printf '%s\n' "${outputs[@]}" >> "$GITHUB_OUTPUT"
fi

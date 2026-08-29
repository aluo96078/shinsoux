#!/usr/bin/env bash

set -euo pipefail

if (( $# < 3 )); then
  echo "Usage: $0 PLATFORM PRERELEASE SECRET_NAME..." >&2
  exit 2
fi

platform="$1"
prerelease="$2"
shift 2
required_secrets=("$@")

if [[ "$prerelease" != true && "$prerelease" != false ]]; then
  echo "::error title=Invalid release metadata::prerelease must be true or false."
  exit 1
fi

missing=()
present_count=0
for secret_name in "${required_secrets[@]}"; do
  if [[ -n "${!secret_name:-}" ]]; then
    present_count=$((present_count + 1))
  else
    missing+=("$secret_name")
  fi
done

emit_availability() {
  if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
    printf 'available=%s\n' "$1" >> "$GITHUB_OUTPUT"
  else
    printf 'available=%s\n' "$1"
  fi
}

if (( present_count == ${#required_secrets[@]} )); then
  emit_availability true
  echo "$platform release signing configuration is available."
  exit 0
fi

emit_availability false
if (( present_count > 0 )); then
  echo "::error title=Incomplete $platform signing configuration::Missing: ${missing[*]}. Remove the partial configuration or provide every required secret."
  exit 1
fi

if [[ "$prerelease" == true ]]; then
  echo "::warning title=$platform omitted from beta::No $platform signing secrets are configured; this beta will not contain a $platform artifact."
  exit 0
fi

echo "::error title=Missing $platform signing configuration::A formal release requires: ${required_secrets[*]}."
exit 1

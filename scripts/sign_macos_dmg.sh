#!/usr/bin/env bash

set -euo pipefail

if (( $# != 4 )); then
  echo "Usage: $0 DMG APPLE_DEVELOPMENT_IDENTITY ENTITLEMENTS_PLIST KEYCHAIN_OR_EMPTY" >&2
  exit 2
fi

dmg_path="$1"
signing_identity="$2"
entitlements_plist="$3"
keychain_path="$4"

if [[ "$(/usr/bin/uname -s)" != "Darwin" ]]; then
  echo "Apple Development signing is only available on macOS." >&2
  exit 1
fi
if [[ ! -f "$dmg_path" ]]; then
  echo "macOS disk image does not exist: $dmg_path" >&2
  exit 1
fi

script_directory="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
app_signer="$script_directory/sign_macos_app.sh"
dmg_directory="$(cd "$(dirname "$dmg_path")" && pwd)"
working_directory="$(mktemp -d "$dmg_directory/.shinsou-sign-dmg.XXXXXX")"
writable_image="$working_directory/writable.dmg"
signed_image="$working_directory/signed.dmg"
mount_directory="$working_directory/mount"
verify_mount_directory="$working_directory/verify-mount"
active_mount=""

cleanup() {
  set +e
  if [[ -n "$active_mount" ]]; then
    /usr/bin/hdiutil detach "$active_mount" -force >/dev/null 2>&1
  fi
  if [[ "$working_directory" == "$dmg_directory"/.shinsou-sign-dmg.* ]]; then
    /bin/rm -rf -- "$working_directory"
  fi
}
trap cleanup EXIT
trap 'exit 1' HUP INT TERM

/usr/bin/hdiutil verify "$dmg_path"
/usr/bin/hdiutil convert "$dmg_path" -quiet -format UDRW -o "$writable_image"
/bin/mkdir -p "$mount_directory"
/usr/bin/hdiutil attach \
  "$writable_image" \
  -readwrite \
  -nobrowse \
  -mountpoint "$mount_directory" \
  >/dev/null
active_mount="$mount_directory"

shopt -s nullglob
applications=("$mount_directory"/*.app)
if (( ${#applications[@]} != 1 )); then
  echo "Expected exactly one application in the DMG, found ${#applications[@]}." >&2
  exit 1
fi

"$app_signer" \
  "${applications[0]}" \
  "$signing_identity" \
  "$entitlements_plist" \
  "$keychain_path"

/usr/bin/hdiutil detach "$mount_directory" >/dev/null
active_mount=""
/usr/bin/hdiutil convert \
  "$writable_image" \
  -quiet \
  -format UDZO \
  -imagekey zlib-level=9 \
  -o "$signed_image"
/usr/bin/hdiutil verify "$signed_image"

# Validate the final compressed image, not only the temporary writable copy, before replacing the
# Gradle output. The original DMG remains untouched if any conversion or signature check fails.
/bin/mkdir -p "$verify_mount_directory"
/usr/bin/hdiutil attach \
  "$signed_image" \
  -readonly \
  -nobrowse \
  -mountpoint "$verify_mount_directory" \
  >/dev/null
active_mount="$verify_mount_directory"
verified_applications=("$verify_mount_directory"/*.app)
if (( ${#verified_applications[@]} != 1 )); then
  echo "Expected exactly one application in the signed DMG, found ${#verified_applications[@]}." >&2
  exit 1
fi
/usr/bin/codesign --verify --deep --strict --verbose=2 "${verified_applications[0]}"
signature_details="$(/usr/bin/codesign -d --verbose=4 "${verified_applications[0]}" 2>&1)"
if ! /usr/bin/grep -Fqx "Authority=$signing_identity" <<<"$signature_details"; then
  echo "The final DMG application was not signed by the configured identity." >&2
  exit 1
fi
designated_requirement="$(/usr/bin/codesign -d --requirements - "${verified_applications[0]}" 2>&1)"
if /usr/bin/grep -Fq "cdhash" <<<"$designated_requirement"; then
  echo "The final DMG application has a build-specific cdhash requirement." >&2
  exit 1
fi
/usr/bin/hdiutil detach "$verify_mount_directory" >/dev/null
active_mount=""

/bin/mv -f "$signed_image" "$dmg_path"
printf '%s\n' "$signature_details" | /usr/bin/grep -E \
  '^(Identifier|Authority|TeamIdentifier|Signature)='
printf '%s\n' "$designated_requirement"

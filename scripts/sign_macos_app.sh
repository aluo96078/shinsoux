#!/usr/bin/env bash

set -euo pipefail

if (( $# != 4 )); then
  echo "Usage: $0 APP_BUNDLE APPLE_DEVELOPMENT_IDENTITY ENTITLEMENTS_PLIST KEYCHAIN_OR_EMPTY" >&2
  exit 2
fi

app_bundle="$1"
signing_identity="$2"
entitlements_plist="$3"
keychain_path="$4"

if [[ "$(/usr/bin/uname -s)" != "Darwin" ]]; then
  echo "Apple Development signing is only available on macOS." >&2
  exit 1
fi
if [[ ! -d "$app_bundle" || ! -f "$app_bundle/Contents/Info.plist" ]]; then
  echo "macOS application bundle does not exist: $app_bundle" >&2
  exit 1
fi
if [[ ! -f "$entitlements_plist" ]]; then
  echo "macOS application entitlements do not exist: $entitlements_plist" >&2
  exit 1
fi
if [[ "$signing_identity" != "Apple Development:"* ]]; then
  echo "Local post-build signing requires a full Apple Development certificate name." >&2
  exit 1
fi

identity_listing=""
codesign_arguments=(
  --force
  --deep
  --options runtime
  --timestamp=none
  --entitlements "$entitlements_plist"
)
if [[ -n "$keychain_path" ]]; then
  if [[ ! -f "$keychain_path" ]]; then
    echo "macOS signing keychain does not exist: $keychain_path" >&2
    exit 1
  fi
  identity_listing="$(/usr/bin/security find-identity -v -p codesigning "$keychain_path")"
  codesign_arguments+=(--keychain "$keychain_path")
else
  identity_listing="$(/usr/bin/security find-identity -v -p codesigning)"
fi
if ! /usr/bin/grep -Fq "\"$signing_identity\"" <<<"$identity_listing"; then
  echo "The configured Apple Development signing identity is unavailable: $signing_identity" >&2
  exit 1
fi

# jpackage already signs its app image ad-hoc with the hardened-runtime entitlements required by
# the bundled JVM. Replace only that unstable outer signature with the configured certificate and
# explicitly retain those entitlements. The nested runtime remains sealed by the outer signature.
codesign_arguments+=(--sign "$signing_identity" "$app_bundle")
/usr/bin/codesign "${codesign_arguments[@]}"

bundle_identifier="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "$app_bundle/Contents/Info.plist")"
if [[ -z "$bundle_identifier" ]]; then
  echo "The signed application has no CFBundleIdentifier." >&2
  exit 1
fi

/usr/bin/codesign --verify --deep --strict --verbose=2 "$app_bundle"
/usr/bin/codesign \
  --verify \
  --deep \
  --strict \
  --verbose=2 \
  -R="identifier \"$bundle_identifier\" and anchor apple generic" \
  "$app_bundle"

signature_details="$(/usr/bin/codesign -d --verbose=4 "$app_bundle" 2>&1)"
if ! /usr/bin/grep -Fqx "Authority=$signing_identity" <<<"$signature_details"; then
  echo "The application was not signed by the configured identity." >&2
  exit 1
fi
if /usr/bin/grep -Fq "Signature=adhoc" <<<"$signature_details"; then
  echo "The application still has an ad-hoc outer signature." >&2
  exit 1
fi

designated_requirement="$(/usr/bin/codesign -d --requirements - "$app_bundle" 2>&1)"
if /usr/bin/grep -Fq "cdhash" <<<"$designated_requirement"; then
  echo "The application still has a build-specific cdhash designated requirement." >&2
  exit 1
fi

printf '%s\n' "$signature_details" | /usr/bin/grep -E \
  '^(Identifier|Authority|TeamIdentifier|Signature)='
printf '%s\n' "$designated_requirement"

#!/usr/bin/env bash

set -euo pipefail

required_environment=(
  RELEASE_VERSION
  RELEASE_VERSION_CODE
  IOS_TEAM_ID
  IOS_DISTRIBUTION_CERTIFICATE_BASE64
  IOS_DISTRIBUTION_CERTIFICATE_PASSWORD
  IOS_APP_PROVISIONING_PROFILE_BASE64
  IOS_WIDGET_PROVISIONING_PROFILE_BASE64
  OUTPUT_DIR
)

for variable_name in "${required_environment[@]}"; do
  if [[ -z "${!variable_name:-}" ]]; then
    echo "::error title=Missing iOS release setting::$variable_name is required to build a signed IPA."
    exit 1
  fi
done

if [[ "$(uname -s)" != "Darwin" ]]; then
  echo "This script must run on macOS." >&2
  exit 1
fi

if [[ ! "$RELEASE_VERSION" =~ ^(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]{0,2})$ ]]; then
  echo "Invalid release version: $RELEASE_VERSION" >&2
  exit 1
fi
release_major="${BASH_REMATCH[1]}"
release_minor="${BASH_REMATCH[2]}"
release_patch="${BASH_REMATCH[3]}"
if (( 10#$release_major > 255 || 10#$release_minor > 255 || 10#$release_patch > 999 )); then
  echo "Release version components exceed the supported range: $RELEASE_VERSION" >&2
  exit 1
fi

if [[ ! "$RELEASE_VERSION_CODE" =~ ^[1-9][0-9]*$ ]]; then
  echo "Invalid release version code: $RELEASE_VERSION_CODE" >&2
  exit 1
fi
expected_version_code=$((10#$release_major * 1000000 + 10#$release_minor * 1000 + 10#$release_patch))
if [[ "$RELEASE_VERSION_CODE" != "$expected_version_code" ]]; then
  echo "Release version code is $RELEASE_VERSION_CODE, expected $expected_version_code." >&2
  exit 1
fi

script_directory="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repository_root="$(cd "$script_directory/../.." && pwd)"
temporary_parent="${RUNNER_TEMP:-${TMPDIR:-/tmp}}"
working_directory="$(mktemp -d "$temporary_parent/shinsou-ios-release.XXXXXX")"
keychain_path="$working_directory/shinsou-release.keychain-db"
keychain_password="$(openssl rand -hex 32)"
certificate_path="$working_directory/distribution.p12"
app_profile_path="$working_directory/app.mobileprovision"
widget_profile_path="$working_directory/widget.mobileprovision"
app_profile_plist="$working_directory/app-profile.plist"
widget_profile_plist="$working_directory/widget-profile.plist"
archive_path="$working_directory/Shinsou.xcarchive"
export_directory="$working_directory/export"
export_options="$working_directory/ExportOptions.plist"
inspection_directory="$working_directory/inspection"
installed_profile_paths=()
installed_profile_backups=()
original_keychains=()
keychain_search_list_changed=false

while IFS= read -r keychain_entry; do
  keychain_entry="${keychain_entry#"${keychain_entry%%[![:space:]]*}"}"
  keychain_entry="${keychain_entry#\"}"
  keychain_entry="${keychain_entry%\"}"
  [[ -n "$keychain_entry" ]] && original_keychains+=("$keychain_entry")
done < <(security list-keychains -d user)

if (( ${#original_keychains[@]} == 0 )); then
  default_keychain="$(security default-keychain -d user)"
  default_keychain="${default_keychain#"${default_keychain%%[![:space:]]*}"}"
  default_keychain="${default_keychain#\"}"
  default_keychain="${default_keychain%\"}"
  [[ -n "$default_keychain" ]] && original_keychains+=("$default_keychain")
fi

cleanup() {
  set +e
  if [[ "$keychain_search_list_changed" == true && ${#original_keychains[@]} -gt 0 ]]; then
    security list-keychains -d user -s "${original_keychains[@]}" >/dev/null 2>&1
  fi
  security delete-keychain "$keychain_path" >/dev/null 2>&1
  for (( profile_index = 0; profile_index < ${#installed_profile_paths[@]}; profile_index++ )); do
    installed_profile="${installed_profile_paths[$profile_index]}"
    profile_backup="${installed_profile_backups[$profile_index]}"
    if [[ -n "$profile_backup" ]]; then
      cp -p "$profile_backup" "$installed_profile"
    else
      rm -f -- "$installed_profile"
    fi
  done
  rm -rf -- "$working_directory"
}
trap cleanup EXIT

decode_secret() {
  local encoded_value="$1"
  local destination="$2"
  printf '%s' "$encoded_value" | openssl base64 -d -A -out "$destination"
  if [[ ! -s "$destination" ]]; then
    echo "Decoded signing asset is empty: $destination" >&2
    exit 1
  fi
  chmod 600 "$destination"
}

profile_value() {
  local profile_plist="$1"
  local key_path="$2"
  /usr/libexec/PlistBuddy -c "Print $key_path" "$profile_plist"
}

validate_profile() {
  local profile_plist="$1"
  local expected_bundle_identifier="$2"
  local label="$3"
  local expected_application_identifier
  local actual_team
  local actual_application_identifier
  local application_identifier_prefix
  local get_task_allow
  local beta_reports_active

  actual_team="$(profile_value "$profile_plist" ':TeamIdentifier:0')"
  application_identifier_prefix="$(profile_value "$profile_plist" ':ApplicationIdentifierPrefix:0')"
  expected_application_identifier="$application_identifier_prefix.$expected_bundle_identifier"
  actual_application_identifier="$(profile_value "$profile_plist" ':Entitlements:application-identifier')"

  if [[ "$actual_team" != "$IOS_TEAM_ID" ]]; then
    echo "$label profile belongs to team $actual_team, expected $IOS_TEAM_ID." >&2
    exit 1
  fi
  if [[ "$actual_application_identifier" != "$expected_application_identifier" ]]; then
    echo "$label profile is for $actual_application_identifier, expected $expected_application_identifier." >&2
    exit 1
  fi
  if /usr/libexec/PlistBuddy -c 'Print :ProvisionedDevices' "$profile_plist" >/dev/null 2>&1; then
    echo "$label profile contains registered devices; an App Store Connect profile is required." >&2
    exit 1
  fi
  if /usr/libexec/PlistBuddy -c 'Print :ProvisionsAllDevices' "$profile_plist" >/dev/null 2>&1; then
    echo "$label profile is an enterprise profile; an App Store Connect profile is required." >&2
    exit 1
  fi
  get_task_allow="$(profile_value "$profile_plist" ':Entitlements:get-task-allow' 2>/dev/null || true)"
  if [[ "$get_task_allow" == "true" || "$get_task_allow" == "YES" ]]; then
    echo "$label profile enables get-task-allow; an App Store distribution profile is required." >&2
    exit 1
  fi
  beta_reports_active="$(profile_value "$profile_plist" ':Entitlements:beta-reports-active' 2>/dev/null || true)"
  if [[ "$beta_reports_active" != "true" && "$beta_reports_active" != "YES" ]]; then
    echo "$label profile is missing beta-reports-active; an App Store Connect profile is required." >&2
    exit 1
  fi
}

install_profile() {
  local source_profile="$1"
  local profile_uuid="$2"
  local legacy_directory="$HOME/Library/MobileDevice/Provisioning Profiles"
  local modern_directory="$HOME/Library/Developer/Xcode/UserData/Provisioning Profiles"

  mkdir -p "$legacy_directory" "$modern_directory"

  for profile_directory in "$legacy_directory" "$modern_directory"; do
    local destination="$profile_directory/$profile_uuid.mobileprovision"
    local backup=""
    if [[ -f "$destination" ]]; then
      mkdir -p "$working_directory/profile-backups"
      backup="$working_directory/profile-backups/${#installed_profile_paths[@]}.mobileprovision"
      cp -p "$destination" "$backup"
    fi
    cp "$source_profile" "$destination"
    chmod 600 "$destination"
    installed_profile_paths+=("$destination")
    installed_profile_backups+=("$backup")
  done
}

decode_secret "$IOS_DISTRIBUTION_CERTIFICATE_BASE64" "$certificate_path"
decode_secret "$IOS_APP_PROVISIONING_PROFILE_BASE64" "$app_profile_path"
decode_secret "$IOS_WIDGET_PROVISIONING_PROFILE_BASE64" "$widget_profile_path"

security cms -D -i "$app_profile_path" > "$app_profile_plist"
security cms -D -i "$widget_profile_path" > "$widget_profile_plist"

validate_profile "$app_profile_plist" "dev.aluo.shinsoux" "App"
validate_profile "$widget_profile_plist" "dev.aluo.shinsoux.widget" "Widget"

if ! profile_value "$app_profile_plist" ':Entitlements:com.apple.security.application-groups' |
  grep -Fq 'group.dev.aluo.shinsoux'; then
  echo "App profile does not include group.dev.aluo.shinsoux." >&2
  exit 1
fi
if ! profile_value "$app_profile_plist" ':Entitlements:com.apple.developer.icloud-container-identifiers' |
  grep -Fq 'iCloud.dev.aluo.shinsoux'; then
  echo "App profile does not include iCloud.dev.aluo.shinsoux." >&2
  exit 1
fi
if ! profile_value "$app_profile_plist" ':Entitlements:com.apple.developer.ubiquity-container-identifiers' |
  grep -Fq 'iCloud.dev.aluo.shinsoux'; then
  echo "App profile does not include the required ubiquity container." >&2
  exit 1
fi
if ! profile_value "$app_profile_plist" ':Entitlements:com.apple.developer.icloud-services' |
  grep -Fq 'CloudDocuments'; then
  echo "App profile does not enable the CloudDocuments iCloud service." >&2
  exit 1
fi
if ! profile_value "$widget_profile_plist" ':Entitlements:com.apple.security.application-groups' |
  grep -Fq 'group.dev.aluo.shinsoux'; then
  echo "Widget profile does not include group.dev.aluo.shinsoux." >&2
  exit 1
fi

app_profile_uuid="$(profile_value "$app_profile_plist" ':UUID')"
widget_profile_uuid="$(profile_value "$widget_profile_plist" ':UUID')"

install_profile "$app_profile_path" "$app_profile_uuid"
install_profile "$widget_profile_path" "$widget_profile_uuid"

security create-keychain -p "$keychain_password" "$keychain_path"
security set-keychain-settings -lut 21600 "$keychain_path"
security unlock-keychain -p "$keychain_password" "$keychain_path"
security import "$certificate_path" \
  -k "$keychain_path" \
  -P "$IOS_DISTRIBUTION_CERTIFICATE_PASSWORD" \
  -T /usr/bin/codesign \
  -T /usr/bin/security
security set-key-partition-list \
  -S apple-tool:,apple:,codesign: \
  -s \
  -k "$keychain_password" \
  "$keychain_path"
if (( ${#original_keychains[@]} > 0 )); then
  security list-keychains -d user -s "$keychain_path" "${original_keychains[@]}"
else
  security list-keychains -d user -s "$keychain_path"
fi
keychain_search_list_changed=true

if ! security find-identity -v -p codesigning "$keychain_path" |
  grep -Eq 'Apple Distribution|iPhone Distribution'; then
  echo "The imported certificate does not contain an Apple Distribution signing identity." >&2
  exit 1
fi

xcodebuild \
  -project "$repository_root/iosApp/Shinsou.xcodeproj" \
  -scheme Shinsou \
  -configuration Release \
  -sdk iphoneos \
  -destination 'generic/platform=iOS' \
  -archivePath "$archive_path" \
  MARKETING_VERSION="$RELEASE_VERSION" \
  CURRENT_PROJECT_VERSION="$RELEASE_VERSION_CODE" \
  DEVELOPMENT_TEAM="$IOS_TEAM_ID" \
  CODE_SIGN_STYLE=Manual \
  CODE_SIGN_IDENTITY='Apple Distribution' \
  SHINSOU_APP_PROVISIONING_PROFILE="$app_profile_uuid" \
  SHINSOU_WIDGET_PROVISIONING_PROFILE="$widget_profile_uuid" \
  OTHER_CODE_SIGN_FLAGS="--keychain $keychain_path" \
  archive

plutil -create xml1 "$export_options"
/usr/libexec/PlistBuddy -c 'Add :method string app-store-connect' "$export_options"
/usr/libexec/PlistBuddy -c 'Add :destination string export' "$export_options"
/usr/libexec/PlistBuddy -c 'Add :signingStyle string manual' "$export_options"
/usr/libexec/PlistBuddy -c "Add :teamID string $IOS_TEAM_ID" "$export_options"
/usr/libexec/PlistBuddy -c 'Add :signingCertificate string Apple Distribution' "$export_options"
/usr/libexec/PlistBuddy -c 'Add :manageAppVersionAndBuildNumber bool false' "$export_options"
/usr/libexec/PlistBuddy -c 'Add :provisioningProfiles dict' "$export_options"
/usr/libexec/PlistBuddy \
  -c "Add :provisioningProfiles:dev.aluo.shinsoux string $app_profile_uuid" \
  "$export_options"
/usr/libexec/PlistBuddy \
  -c "Add :provisioningProfiles:dev.aluo.shinsoux.widget string $widget_profile_uuid" \
  "$export_options"

mkdir -p "$export_directory"
xcodebuild \
  -exportArchive \
  -archivePath "$archive_path" \
  -exportPath "$export_directory" \
  -exportOptionsPlist "$export_options"

ipa_candidates=("$export_directory"/*.ipa)
if [[ ${#ipa_candidates[@]} -ne 1 || ! -f "${ipa_candidates[0]}" ]]; then
  echo "Expected exactly one exported IPA in $export_directory." >&2
  exit 1
fi

mkdir -p "$inspection_directory"
unzip -q "${ipa_candidates[0]}" -d "$inspection_directory"
app_candidates=("$inspection_directory"/Payload/*.app)
if [[ ${#app_candidates[@]} -ne 1 || ! -d "${app_candidates[0]}" ]]; then
  echo "Exported IPA does not contain exactly one application bundle." >&2
  exit 1
fi

codesign --verify --deep --strict "${app_candidates[0]}"
actual_bundle_identifier="$(profile_value "${app_candidates[0]}/Info.plist" ':CFBundleIdentifier')"
actual_version="$(profile_value "${app_candidates[0]}/Info.plist" ':CFBundleShortVersionString')"
actual_build="$(profile_value "${app_candidates[0]}/Info.plist" ':CFBundleVersion')"
if [[ "$actual_bundle_identifier" != "dev.aluo.shinsoux" ]]; then
  echo "IPA bundle identifier is $actual_bundle_identifier, expected dev.aluo.shinsoux." >&2
  exit 1
fi
if [[ "$actual_version" != "$RELEASE_VERSION" || "$actual_build" != "$RELEASE_VERSION_CODE" ]]; then
  echo "IPA version is $actual_version ($actual_build), expected $RELEASE_VERSION ($RELEASE_VERSION_CODE)." >&2
  exit 1
fi

widget_candidates=("${app_candidates[0]}"/PlugIns/*.appex)
if [[ ${#widget_candidates[@]} -ne 1 || ! -d "${widget_candidates[0]}" ]]; then
  echo "Exported IPA does not contain exactly one Widget extension." >&2
  exit 1
fi
codesign --verify --strict "${widget_candidates[0]}"
widget_bundle_identifier="$(profile_value "${widget_candidates[0]}/Info.plist" ':CFBundleIdentifier')"
widget_version="$(profile_value "${widget_candidates[0]}/Info.plist" ':CFBundleShortVersionString')"
widget_build="$(profile_value "${widget_candidates[0]}/Info.plist" ':CFBundleVersion')"
if [[ "$widget_bundle_identifier" != "dev.aluo.shinsoux.widget" ]]; then
  echo "Widget bundle identifier is $widget_bundle_identifier, expected dev.aluo.shinsoux.widget." >&2
  exit 1
fi
if [[ "$widget_version" != "$RELEASE_VERSION" || "$widget_build" != "$RELEASE_VERSION_CODE" ]]; then
  echo "Widget version is $widget_version ($widget_build), expected $RELEASE_VERSION ($RELEASE_VERSION_CODE)." >&2
  exit 1
fi

embedded_app_profile="$inspection_directory/embedded-app-profile.plist"
embedded_widget_profile="$inspection_directory/embedded-widget-profile.plist"
security cms -D -i "${app_candidates[0]}/embedded.mobileprovision" > "$embedded_app_profile"
security cms -D -i "${widget_candidates[0]}/embedded.mobileprovision" > "$embedded_widget_profile"
if [[ "$(profile_value "$embedded_app_profile" ':UUID')" != "$app_profile_uuid" ]]; then
  echo "IPA was not signed with the configured App provisioning profile." >&2
  exit 1
fi
if [[ "$(profile_value "$embedded_widget_profile" ':UUID')" != "$widget_profile_uuid" ]]; then
  echo "Widget was not signed with the configured provisioning profile." >&2
  exit 1
fi

mkdir -p "$OUTPUT_DIR"
output_path="$OUTPUT_DIR/Shinsou-X-$RELEASE_VERSION-ios.ipa"
cp "${ipa_candidates[0]}" "$output_path"
shasum -a 256 "$output_path"

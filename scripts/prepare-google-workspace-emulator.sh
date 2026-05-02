#!/usr/bin/env bash
set -euo pipefail

ADB="${ADB:-}"
if [[ -z "$ADB" ]]; then
  if [[ -n "${ANDROID_HOME:-}" ]]; then
    ADB="$ANDROID_HOME/platform-tools/adb"
  elif [[ -n "${ANDROID_SDK_ROOT:-}" ]]; then
    ADB="$ANDROID_SDK_ROOT/platform-tools/adb"
  else
    ADB="adb"
  fi
fi

TMP_DIR="${DROIDLM_EMULATOR_SETUP_TMP:-/tmp/droidlm-emulator-setup}"
UI_XML="$TMP_DIR/window.xml"
APK_DIR="${DROIDLM_GOOGLE_APK_DIR:-/tmp/droidlm-google-apks}"
SKIP_INSTALL="${DROIDLM_SKIP_WORKSPACE_INSTALL:-false}"
REQUIRE_ACCOUNT="${DROIDLM_REQUIRE_GOOGLE_ACCOUNT:-false}"
TEST_EMAIL="${DROIDLM_TEST_GOOGLE_EMAIL:-}"
TEST_PASSWORD="${DROIDLM_TEST_GOOGLE_PASSWORD:-}"

mkdir -p "$TMP_DIR"

log() {
  printf '%s\n' "$*"
}

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

adb_cmd() {
  "$ADB" "$@"
}

adb_shell() {
  "$ADB" shell "$@"
}

require_device() {
  adb_cmd get-state >/dev/null 2>&1 || fail "No adb device is connected. Start the emulator and rerun this task."
}

package_installed() {
  local package_name="$1"
  local output
  output="$(adb_shell pm path "$package_name" 2>/dev/null || true)"
  [[ "$output" == package:* ]]
}

resolve_launcher() {
  local package_name="$1"
  adb_shell cmd package resolve-activity --brief "$package_name" 2>/dev/null || true
}

install_apk_if_missing() {
  local label="$1"
  local package_name="$2"
  local env_var="$3"
  local short_name="$4"

  if package_installed "$package_name"; then
    log "$label: already installed"
    return 0
  fi

  if [[ "$SKIP_INSTALL" == "true" ]]; then
    log "$label: missing; install skipped"
    return 1
  fi

  local env_path="${!env_var:-}"
  local candidates=()
  if [[ -n "$env_path" ]]; then
    candidates+=("$env_path")
  fi
  candidates+=("$APK_DIR/$short_name.apk")
  candidates+=("/tmp/google-$short_name.apk")

  local apk_path=""
  local candidate
  for candidate in "${candidates[@]}"; do
    if [[ -f "$candidate" ]]; then
      apk_path="$candidate"
      break
    fi
  done

  if [[ -z "$apk_path" ]]; then
    log "$label: missing; no APK found"
    log "  Provide $env_var, or place $short_name.apk in $APK_DIR, or place google-$short_name.apk in /tmp."
    return 1
  fi

  log "$label: installing from $apk_path"
  adb_cmd install -r "$apk_path" >/dev/null

  if package_installed "$package_name"; then
    log "$label: installed"
    return 0
  fi

  log "$label: install command completed, but package $package_name is still missing"
  return 1
}

install_workspace_apps() {
  local missing=0
  install_apk_if_missing "Google Drive" "com.google.android.apps.docs" "DROIDLM_DRIVE_APK" "drive" || missing=1
  install_apk_if_missing "Gmail" "com.google.android.gm" "DROIDLM_GMAIL_APK" "gmail" || missing=1
  install_apk_if_missing "Google Docs" "com.google.android.apps.docs.editors.docs" "DROIDLM_DOCS_APK" "docs" || missing=1
  install_apk_if_missing "Google Sheets" "com.google.android.apps.docs.editors.sheets" "DROIDLM_SHEETS_APK" "sheets" || missing=1
  package_installed "com.android.vending" || log "Google Play Store: missing; use a Google Play emulator image if Play installs are needed"
  return "$missing"
}

account_dump() {
  adb_shell dumpsys account 2>/dev/null || true
}

any_google_account_present() {
  local dump
  dump="$(account_dump)"
  [[ "$dump" == *"type=com.google"* ]]
}

email_account_present() {
  local email="$1"
  local dump
  dump="$(account_dump)"
  [[ "$dump" == *"Account {name=$email, type=com.google}"* ]]
}

dump_ui() {
  adb_shell uiautomator dump /sdcard/window.xml >/dev/null
  adb_cmd pull /sdcard/window.xml "$UI_XML" >/dev/null
}

ui_query() {
  local mode="$1"
  local value="$2"
  python3 - "$UI_XML" "$mode" "$value" <<'PY'
import re
import sys
import xml.etree.ElementTree as ET

xml_path, mode, value = sys.argv[1:4]
try:
    root = ET.parse(xml_path).getroot()
except Exception:
    sys.exit(1)

for node in root.iter("node"):
    text = node.attrib.get("text", "")
    desc = node.attrib.get("content-desc", "")
    resource_id = node.attrib.get("resource-id", "")
    bounds = node.attrib.get("bounds", "")
    if bounds == "[0,0][0,0]":
        continue
    matched = False
    if mode == "id":
        matched = resource_id == value
    elif mode == "text":
        matched = text == value
    elif mode == "text_contains":
        matched = value in text
    elif mode == "desc_contains":
        matched = value in desc
    elif mode == "any_contains":
        matched = value in text or value in desc or value in resource_id
    else:
        sys.exit(2)
    if not matched:
        continue
    match = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds)
    if not match:
        continue
    left, top, right, bottom = map(int, match.groups())
    print(f"{(left + right) // 2} {(top + bottom) // 2}")
    sys.exit(0)

sys.exit(1)
PY
}

ui_switch_checked() {
  local resource_id="$1"
  python3 - "$UI_XML" "$resource_id" <<'PY'
import sys
import xml.etree.ElementTree as ET

xml_path, resource_id = sys.argv[1:3]
try:
    root = ET.parse(xml_path).getroot()
except Exception:
    sys.exit(1)

for node in root.iter("node"):
    if node.attrib.get("resource-id") == resource_id:
        print(node.attrib.get("checked", "false"))
        sys.exit(0)

sys.exit(1)
PY
}

ui_contains() {
  local value="$1"
  ui_query any_contains "$value" >/dev/null 2>&1
}

tap_query() {
  local mode="$1"
  local value="$2"
  local coords
  coords="$(ui_query "$mode" "$value" 2>/dev/null || true)"
  if [[ -z "$coords" ]]; then
    return 1
  fi
  adb_shell input tap $coords
}

tap_id() {
  tap_query id "$1"
}

tap_text() {
  tap_query text "$1"
}

type_safe_text() {
  local value="$1"
  if [[ ! "$value" =~ ^[A-Za-z0-9._@+-]+$ ]]; then
    fail "Only letters, numbers, dots, underscores, at signs, plus, and dashes are supported for automated input. Sign in manually for more complex credentials."
  fi
  adb_shell input text "$value"
}

launch_drive() {
  adb_shell am force-stop com.google.android.apps.docs >/dev/null 2>&1 || true
  adb_shell monkey -p com.google.android.apps.docs -c android.intent.category.LAUNCHER 1 >/dev/null
}

turn_off_google_backup_if_needed() {
  local switch_id="com.google.android.gms:id/sud_items_switch"
  local checked
  checked="$(ui_switch_checked "$switch_id" 2>/dev/null || true)"
  if [[ "$checked" == "true" ]]; then
    log "Google services: disabling basic device backup before accepting"
    tap_id "$switch_id" || true
    sleep 1
    dump_ui
  fi
}

sign_in_google_account() {
  local email="$1"
  local password="$2"

  if email_account_present "$email"; then
    log "Google account: already configured"
    return 0
  fi

  [[ -n "$email" ]] || fail "DROIDLM_TEST_GOOGLE_EMAIL is required for sign-in automation."
  [[ -n "$password" ]] || fail "DROIDLM_TEST_GOOGLE_PASSWORD is required for sign-in automation."

  log "Google account: starting visible sign-in flow"
  launch_drive
  sleep 5

  local i
  for i in $(seq 1 90); do
    if email_account_present "$email"; then
      log "Google account: configured"
      return 0
    fi

    dump_ui || true

    if ui_contains "find your Google Account"; then
      fail "Google rejected the account email. Check DROIDLM_TEST_GOOGLE_EMAIL."
    fi
    if ui_contains "Wrong password" || ui_contains "Enter a valid password"; then
      fail "Google rejected the password. Check DROIDLM_TEST_GOOGLE_PASSWORD."
    fi
    if ui_contains "Verify it's you" || ui_contains "2-Step Verification" || ui_contains "Enter a code"; then
      fail "Google requires an interactive verification challenge. Finish it manually, then rerun verification."
    fi

    if tap_id "identifierId"; then
      sleep 1
      type_safe_text "$email"
      sleep 1
      tap_id "identifierNext" || tap_text "Next" || true
      sleep 6
      continue
    fi

    if tap_id "password"; then
      sleep 1
      type_safe_text "$password"
      sleep 1
      tap_id "passwordNext" || tap_text "Next" || true
      sleep 8
      continue
    fi

    if tap_id "signinconsentNext" || tap_text "I agree"; then
      sleep 8
      continue
    fi

    if ui_contains "Account Recovery Options" || ui_contains "Make sure you can always sign in"; then
      log "Google account: skipping recovery option changes"
      tap_text "Cancel" || tap_text "Skip" || true
      sleep 6
      continue
    fi

    if ui_contains "home and work addresses"; then
      log "Google account: skipping address personalization"
      tap_text "Skip" || true
      sleep 6
      continue
    fi

    if ui_contains "Google services" || ui_contains "Backup & storage"; then
      turn_off_google_backup_if_needed
      tap_text "ACCEPT" || tap_text "Accept" || true
      sleep 8
      continue
    fi

    if ui_contains "Signed in as" || ui_contains "$email"; then
      sleep 2
      continue
    fi

    sleep 2
  done

  fail "Timed out while waiting for Google account setup to complete. Finish the visible UI manually, then rerun verification."
}

verify_workspace() {
  local missing=0
  local package_name
  for package_name in \
    com.google.android.apps.docs \
    com.google.android.gm \
    com.google.android.apps.docs.editors.docs \
    com.google.android.apps.docs.editors.sheets; do
    if ! package_installed "$package_name"; then
      log "$package_name: missing"
      missing=1
      continue
    fi
    local launcher
    launcher="$(resolve_launcher "$package_name")"
    if [[ "$launcher" == *"No activity found"* || -z "$launcher" ]]; then
      log "$package_name: installed, but no launcher resolved"
    else
      log "$package_name: installed"
    fi
  done

  if any_google_account_present; then
    log "Google account: configured"
  else
    log "Google account: not configured"
    if [[ "$REQUIRE_ACCOUNT" == "true" || -n "$TEST_EMAIL" || -n "$TEST_PASSWORD" ]]; then
      missing=1
    fi
  fi

  return "$missing"
}

main() {
  require_device

  local install_missing=0
  install_workspace_apps || install_missing=1

  if [[ -n "$TEST_EMAIL" || -n "$TEST_PASSWORD" ]]; then
    sign_in_google_account "$TEST_EMAIL" "$TEST_PASSWORD"
  fi

  local verify_missing=0
  verify_workspace || verify_missing=1

  if [[ "$install_missing" -ne 0 || "$verify_missing" -ne 0 ]]; then
    fail "Workspace emulator setup is incomplete. Add missing APKs or complete Google sign-in, then rerun."
  fi

  log "Workspace emulator setup complete."
}

main "$@"

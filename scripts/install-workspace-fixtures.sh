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

MODE="${1:-install}"
FIXTURE_ROOT="${DROIDLM_WORKSPACE_FIXTURE_ROOT:-test-fixtures/workspace}"
MANIFEST="$FIXTURE_ROOT/fixtures.json"
DEVICE_ROOT="${DROIDLM_WORKSPACE_DEVICE_ROOT:-/sdcard/Documents/DroidLMFixtures}"
TMP_DIR="${DROIDLM_EMULATOR_SETUP_TMP:-/tmp/droidlm-emulator-setup}"
UI_XML="$TMP_DIR/window.xml"

mkdir -p "$TMP_DIR"

log() {
  printf '%s\n' "$*"
}

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

adb_cmd() {
  "$ADB" "$@" </dev/null
}

adb_shell() {
  "$ADB" shell "$@" </dev/null
}

require_device() {
  adb_cmd get-state >/dev/null 2>&1 || fail "No adb device is connected. Start the emulator and rerun this task."
}

require_manifest() {
  [[ -f "$MANIFEST" ]] || fail "Missing fixture manifest: $MANIFEST"
}

manifest_rows() {
  python3 - "$MANIFEST" <<'PY'
import json
import sys
from pathlib import Path

manifest = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
for fixture in manifest.get("fixtures", []):
    print("\t".join([
        fixture["category"],
        fixture["file"],
        fixture["mime"],
    ]))
PY
}

package_installed() {
  local package_name="$1"
  local output
  output="$(adb_shell pm path "$package_name" 2>/dev/null || true)"
  [[ "$output" == package:* ]]
}

viewer_package_for() {
  local category="$1"
  local file_name="$2"
  local mime="$3"
  if [[ "$mime" == "application/pdf" ]]; then
    printf 'com.android.chrome\n'
  elif [[ "$category" == "docs" ]]; then
    printf 'com.google.android.apps.docs.editors.docs\n'
  elif [[ "$category" == "spreadsheets" ]]; then
    printf 'com.google.android.apps.docs.editors.sheets\n'
  elif [[ "$category" == "images" ]]; then
    printf 'com.google.android.apps.photos\n'
  else
    fail "No viewer mapping for $category/$file_name ($mime)"
  fi
}

start_args_for() {
  local category="$1"
  local file_name="$2"
  local mime="$3"
  local device_path="$4"
  if [[ "$mime" == "application/pdf" ]]; then
    printf '%s\n' -n com.android.chrome/com.google.android.apps.chrome.Main -a android.intent.action.VIEW -d "file://$device_path" -t "$mime"
  else
    local viewer
    viewer="$(viewer_package_for "$category" "$file_name" "$mime")"
    printf '%s\n' -a android.intent.action.VIEW -p "$viewer" -d "file://$device_path" -t "$mime"
  fi
}

install_fixtures() {
  require_manifest
  log "Installing Workspace fixtures to $DEVICE_ROOT"
  adb_shell mkdir -p "$DEVICE_ROOT/docs" "$DEVICE_ROOT/images" "$DEVICE_ROOT/spreadsheets"

  local category file_name mime local_path device_path
  while IFS=$'\t' read -r category file_name mime; do
    local_path="$FIXTURE_ROOT/$category/$file_name"
    device_path="$DEVICE_ROOT/$category/$file_name"
    [[ -f "$local_path" ]] || fail "Missing local fixture: $local_path"
    adb_cmd push "$local_path" "$device_path" >/dev/null
    adb_shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file://$device_path" >/dev/null 2>&1 || true
    log "$category/$file_name: installed"
  done < <(manifest_rows)
}

current_focus() {
  adb_shell dumpsys window | python3 -c 'import sys
for line in sys.stdin:
    if "mCurrentFocus" in line or "mFocusedApp" in line:
        print(line.strip())'
}

verify_file_present() {
  local device_path="$1"
  adb_shell test -f "$device_path" >/dev/null 2>&1 || fail "Missing device fixture: $device_path"
}

verify_opens() {
  local category="$1"
  local file_name="$2"
  local mime="$3"
  local device_path="$DEVICE_ROOT/$category/$file_name"
  local viewer
  viewer="$(viewer_package_for "$category" "$file_name" "$mime")"

  package_installed "$viewer" || fail "Required viewer package is missing for $file_name: $viewer"
  verify_file_present "$device_path"

  adb_shell am force-stop "$viewer" >/dev/null 2>&1 || true

  mapfile -t start_args < <(start_args_for "$category" "$file_name" "$mime" "$device_path")
  local output
  output="$(adb_shell am start -W "${start_args[@]}" 2>&1 || true)"
  if [[ "$output" != *"Status: ok"* ]]; then
    printf '%s\n' "$output" >&2
    fail "Could not open $category/$file_name"
  fi

  sleep 2
  local focus
  focus="$(current_focus)"
  if [[ "$focus" != *"$viewer"* && "$output" != *"Activity: $viewer"* ]]; then
    printf '%s\n' "$output" >&2
    printf '%s\n' "$focus" >&2
    fail "Viewer $viewer was not foreground after opening $category/$file_name"
  fi

  log "$category/$file_name: opened with $viewer"
  adb_shell input keyevent 3 >/dev/null 2>&1 || true
  adb_shell am force-stop "$viewer" >/dev/null 2>&1 || true
}

verify_fixtures() {
  require_manifest
  local category file_name mime
  while IFS=$'\t' read -r category file_name mime; do
    verify_opens "$category" "$file_name" "$mime"
  done < <(manifest_rows)
  log "All Workspace fixtures opened successfully."
}

main() {
  require_device
  case "$MODE" in
    install)
      install_fixtures
      ;;
    verify)
      verify_fixtures
      ;;
    install-and-verify)
      install_fixtures
      verify_fixtures
      ;;
    *)
      fail "Unknown mode: $MODE. Use install, verify, or install-and-verify."
      ;;
  esac
}

main "$@"

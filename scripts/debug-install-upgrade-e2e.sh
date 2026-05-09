#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_HOME_RESOLVED="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$ANDROID_HOME_RESOLVED" ]]; then
  printf 'ERROR: ANDROID_HOME or ANDROID_SDK_ROOT must point to the Android SDK.\n' >&2
  exit 1
fi

export ANDROID_HOME="$ANDROID_HOME_RESOLVED"
export ANDROID_SDK_ROOT="$ANDROID_HOME_RESOLVED"

ADB="${ADB:-$ANDROID_HOME/platform-tools/adb}"
GH="${GH:-gh}"
REPO="${DROIDLM_INSTALL_E2E_REPO:-Grappeggia/droidlm}"
APP_ID="${DROIDLM_INSTALL_E2E_APP_ID:-ai.droidlm.debug}"
TEST_APP_ID="${DROIDLM_INSTALL_E2E_TEST_APP_ID:-ai.droidlm.debug.test}"
RELEASE_LIMIT="${DROIDLM_INSTALL_E2E_RELEASE_LIMIT:-50}"
ARTIFACT_ROOT="${DROIDLM_INSTALL_E2E_ARTIFACT_DIR:-$REPO_ROOT/test-artifacts/install-upgrade-e2e}"
CACHE_DIR="${DROIDLM_INSTALL_E2E_CACHE_DIR:-$REPO_ROOT/build/install-upgrade-e2e}"
RUN_ID="$(date +%Y%m%d-%H%M%S)"
ARTIFACT_DIR="$ARTIFACT_ROOT/$RUN_ID"
TRANSCRIPT="$ARTIFACT_DIR/transcript.log"

mkdir -p "$ARTIFACT_DIR" "$CACHE_DIR"
exec > >(tee -a "$TRANSCRIPT") 2>&1

log() {
  printf '%s\n' "$*" >&2
}

fail() {
  log "ERROR: $*"
  log "Artifacts: $ARTIFACT_DIR"
  exit 1
}

require_executable() {
  [[ -x "$1" ]] || fail "$1 is missing or not executable"
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "$1 is missing from PATH"
}

adb_args=()
select_device() {
  require_executable "$ADB"
  if [[ -n "${ANDROID_SERIAL:-}" ]]; then
    adb_args=(-s "$ANDROID_SERIAL")
    return 0
  fi

  mapfile -t devices < <("$ADB" devices | python3 -c 'import sys
for line in sys.stdin:
    fields=line.strip().split()
    if len(fields) == 2 and fields[1] == "device":
        print(fields[0])')
  if [[ ${#devices[@]} -eq 1 ]]; then
    adb_args=(-s "${devices[0]}")
    export ANDROID_SERIAL="${devices[0]}"
    return 0
  fi

  "$ADB" devices || true
  fail "ANDROID_SERIAL is required when zero or multiple online devices are visible"
}

adb_device() {
  "$ADB" "${adb_args[@]}" "$@"
}

host_tool() {
  local tool_name="$1"
  local override_var="$2"
  local override_value="${!override_var:-}"
  if [[ -n "$override_value" && -x "$override_value" ]]; then
    printf '%s\n' "$override_value"
    return 0
  fi

  local build_tools_dir="$ANDROID_HOME/build-tools"
  if [[ -d "$build_tools_dir" ]]; then
    python3 - "$build_tools_dir" "$tool_name" <<'PY'
from pathlib import Path
import sys
root = Path(sys.argv[1])
tool = sys.argv[2]
candidates = sorted(root.glob(f"*/{tool}"), key=lambda p: p.parent.name)
if candidates:
    print(candidates[-1])
PY
  fi
}

release_tags() {
  require_command "$GH"
  "$GH" release list \
    --repo "$REPO" \
    --limit "$RELEASE_LIMIT" \
    --json tagName,isPrerelease,publishedAt \
    --jq '.[] | select(.isPrerelease and (.tagName | test("-debug\\.[0-9]+$"))) | .tagName'
}

resolve_release_tags() {
  latest_tag="${DROIDLM_INSTALL_E2E_LATEST_TAG:-}"
  baseline_tag="${DROIDLM_INSTALL_E2E_BASELINE_TAG:-}"
  local latest_apk_override="${DROIDLM_INSTALL_E2E_LATEST_APK:-}"

  if [[ -n "$latest_apk_override" && -n "${DROIDLM_INSTALL_E2E_BASELINE_APK:-}" ]]; then
    latest_tag="${latest_tag:-local-debug}"
    return 0
  fi

  if [[ -z "$latest_tag" || -z "$baseline_tag" ]]; then
    mapfile -t tags < <(release_tags)
    [[ ${#tags[@]} -ge 2 ]] || fail "Expected at least two debug prereleases in $REPO"
    if [[ -n "$latest_apk_override" ]]; then
      latest_tag="${latest_tag:-local-debug}"
      baseline_tag="${baseline_tag:-${tags[0]}}"
    else
      latest_tag="${latest_tag:-${tags[0]}}"
      baseline_tag="${baseline_tag:-${tags[1]}}"
    fi
  fi
}

download_release_apk() {
  local tag="$1"
  require_command "$GH"

  local tag_dir="$CACHE_DIR/$tag"
  mkdir -p "$tag_dir"

  mapfile -t assets < <("$GH" release view "$tag" \
    --repo "$REPO" \
    --json assets \
    --jq '.assets[] | select(.name | test("-debug\\.apk$|\\.apk$")) | .name')
  [[ ${#assets[@]} -gt 0 ]] || fail "No APK asset found for $tag in $REPO"

  local asset="${assets[0]}"
  local apk="$tag_dir/$asset"
  if [[ ! -f "$apk" ]]; then
    log "Downloading $tag asset $asset"
    "$GH" release download "$tag" --repo "$REPO" --pattern "$asset" --dir "$tag_dir" --clobber >&2
  else
    log "Using cached $tag asset $asset"
  fi

  [[ -s "$apk" ]] || fail "Downloaded APK is empty: $apk"
  printf '%s\n' "$apk"
}

resolve_apks() {
  resolve_release_tags

  latest_apk="${DROIDLM_INSTALL_E2E_LATEST_APK:-}"
  baseline_apk="${DROIDLM_INSTALL_E2E_BASELINE_APK:-}"

  if [[ -z "$latest_apk" ]]; then
    latest_apk="$(download_release_apk "$latest_tag")"
  fi
  if [[ -z "$baseline_apk" ]]; then
    baseline_apk="$(download_release_apk "$baseline_tag")"
  fi

  [[ -f "$latest_apk" ]] || fail "Latest APK does not exist: $latest_apk"
  [[ -f "$baseline_apk" ]] || fail "Baseline APK does not exist: $baseline_apk"
}

apk_sha256() {
  python3 - "$1" <<'PY'
from hashlib import sha256
from pathlib import Path
import sys
h = sha256()
with Path(sys.argv[1]).open('rb') as f:
    for chunk in iter(lambda: f.read(1024 * 1024), b''):
        h.update(chunk)
print(h.hexdigest())
PY
}

apk_abis() {
  python3 - "$1" <<'PY'
import sys
import zipfile
abis = []
with zipfile.ZipFile(sys.argv[1]) as apk:
    for name in apk.namelist():
        parts = name.split('/')
        if len(parts) >= 3 and parts[0] == 'lib' and parts[-1].endswith('.so'):
            abis.append(parts[1])
print(','.join(sorted(set(abis))) if abis else 'none')
PY
}

apk_package_name() {
  local apk="$1"
  local aapt
  aapt="$(host_tool aapt AAPT || true)"
  if [[ -z "$aapt" || ! -x "$aapt" ]]; then
    return 0
  fi

  "$aapt" dump badging "$apk" | python3 -c "import re
import sys
package_name = ''
for line in sys.stdin:
    if package_name:
        continue
    match = re.search(r\"package: name='([^']+)'\", line)
    if match:
        package_name = match.group(1)
if package_name:
    print(package_name)"
}

apk_version_code() {
  local apk="$1"
  local aapt
  aapt="$(host_tool aapt AAPT || true)"
  if [[ -z "$aapt" || ! -x "$aapt" ]]; then
    return 0
  fi

  "$aapt" dump badging "$apk" | python3 -c "import re
import sys
version_code = ''
for line in sys.stdin:
    if version_code:
        continue
    match = re.search(r\"versionCode='([0-9]+)'\", line)
    if match:
        version_code = match.group(1)
if version_code:
    print(version_code)"
}


describe_apk() {
  local label="$1"
  local apk="$2"
  local out="$ARTIFACT_DIR/${label}-apk.txt"
  local aapt apksigner
  aapt="$(host_tool aapt AAPT || true)"
  apksigner="$(host_tool apksigner APKSIGNER || true)"

  {
    printf 'label=%s\n' "$label"
    printf 'path=%s\n' "$apk"
    printf 'bytes=%s\n' "$(python3 -c 'import os,sys; print(os.path.getsize(sys.argv[1]))' "$apk")"
    printf 'sha256=%s\n' "$(apk_sha256 "$apk")"
    printf 'native-code=%s\n' "$(apk_abis "$apk")"
    if [[ -n "$aapt" && -x "$aapt" ]]; then
      "$aapt" dump badging "$apk" | python3 -c 'import sys
for line in sys.stdin:
    if line.startswith(("package:", "sdkVersion:", "targetSdkVersion:", "native-code:")):
        print(line.rstrip())'
    fi
    if [[ -n "$apksigner" && -x "$apksigner" ]]; then
      "$apksigner" verify --print-certs "$apk" | python3 -c 'import sys
for line in sys.stdin:
    if "certificate SHA-256 digest:" in line or line.startswith("Verified"):
        print(line.rstrip())'
    fi
  } | tee "$out"
}

describe_device() {
  local out="$ARTIFACT_DIR/device.txt"
  {
    printf 'serial=%s\n' "${ANDROID_SERIAL:-}"
    for prop in \
      ro.product.manufacturer \
      ro.product.model \
      ro.product.device \
      ro.build.version.release \
      ro.build.version.sdk \
      ro.product.cpu.abilist \
      ro.product.cpu.abi; do
      printf '%s=%s\n' "$prop" "$(adb_device shell getprop "$prop" | tr -d '\r')"
    done
    adb_device shell wm size | tr -d '\r'
    adb_device shell wm density | tr -d '\r'
    adb_device shell df -h /data | tr -d '\r'
  } | tee "$out"
}

collect_package_state() {
  local stage="$1"
  adb_device shell pm path "$APP_ID" >"$ARTIFACT_DIR/${stage}-pm-path.txt" 2>&1 || true
  adb_device shell dumpsys package "$APP_ID" >"$ARTIFACT_DIR/${stage}-dumpsys-package.txt" 2>&1 || true
}

collect_logcat() {
  local stage="$1"
  adb_device logcat -d -v time \
    PackageManager:* \
    PackageInstaller:* \
    PackageInstallerSession:* \
    installd:* \
    AndroidRuntime:* \
    '*:S' >"$ARTIFACT_DIR/${stage}-install-logcat.txt" 2>&1 || true
}

uninstall_app() {
  local package_name="$1"
  log "Uninstalling $package_name if present"
  adb_device uninstall "$package_name" || true
}

install_apk() {
  local stage="$1"
  local apk="$2"
  local out="$ARTIFACT_DIR/${stage}-adb-install.txt"
  log "Installing $stage: $apk"
  adb_device logcat -c || true

  set +e
  adb_device install -r "$apk" >"$out" 2>&1
  local status=$?
  set -e

  cat "$out"
  collect_package_state "$stage"
  if [[ $status -ne 0 ]]; then
    collect_logcat "$stage"
    return "$status"
  fi
  return 0
}

main() {
  select_device
  resolve_apks

  log "DroidLM debug install-upgrade E2E"
  log "Repository: $REPO"
  log "Baseline: ${baseline_tag:-local} -> $baseline_apk"
  log "Latest: ${latest_tag:-local} -> $latest_apk"
  log "Artifacts: $ARTIFACT_DIR"
  log ""

  describe_device
  log ""
  describe_apk baseline "$baseline_apk"
  log ""
  describe_apk latest "$latest_apk"
  local baseline_package latest_package baseline_version_code latest_version_code
  baseline_package="$(apk_package_name "$baseline_apk")"
  latest_package="$(apk_package_name "$latest_apk")"
  baseline_version_code="$(apk_version_code "$baseline_apk")"
  latest_version_code="$(apk_version_code "$latest_apk")"
  if [[ -n "$latest_package" && -z "${DROIDLM_INSTALL_E2E_APP_ID:-}" ]]; then
    APP_ID="$latest_package"
    TEST_APP_ID="${APP_ID}.test"
  fi
  if [[ -n "$baseline_package" && -n "$latest_package" && "$baseline_package" != "$latest_package" ]]; then
    fail "Baseline package $baseline_package differs from latest package $latest_package; choose a baseline debug APK with the same package for an upgrade test"
  fi
  if [[ -n "$baseline_version_code" && -n "$latest_version_code" && "$latest_version_code" -le "$baseline_version_code" ]]; then
    fail "Latest versionCode $latest_version_code must be greater than baseline versionCode $baseline_version_code for package-installer upgrades"
  fi
  log ""

  uninstall_app "$TEST_APP_ID"
  uninstall_app "$APP_ID"

  log "Clean-install check for latest debug APK"
  if ! install_apk clean-latest "$latest_apk"; then
    fail "Latest debug APK failed clean install on ${ANDROID_SERIAL:-target device}"
  fi

  uninstall_app "$APP_ID"

  log "Upgrade check from baseline debug APK to latest debug APK"
  if ! install_apk baseline "$baseline_apk"; then
    fail "Baseline debug APK failed install on ${ANDROID_SERIAL:-target device}"
  fi
  if ! install_apk upgrade-latest "$latest_apk"; then
    fail "Latest debug APK failed upgrade over baseline debug APK on ${ANDROID_SERIAL:-target device}"
  fi

  log "Install-upgrade E2E passed. Artifacts: $ARTIFACT_DIR"
}

main "$@"

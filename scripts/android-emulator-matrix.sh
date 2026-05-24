#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_HOME_RESOLVED="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$ANDROID_HOME_RESOLVED" ]]; then
  if command -v sdkmanager >/dev/null 2>&1; then
    ANDROID_HOME_RESOLVED="$(cd "$(dirname "$(dirname "$(command -v sdkmanager)")")/.." && pwd)"
  else
    printf 'ERROR: ANDROID_HOME or ANDROID_SDK_ROOT must point to the Android SDK.\n' >&2
    exit 1
  fi
fi
export ANDROID_HOME="$ANDROID_HOME_RESOLVED"
export ANDROID_SDK_ROOT="$ANDROID_HOME_RESOLVED"

SDKMANAGER="${SDKMANAGER:-$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager}"
AVDMANAGER="${AVDMANAGER:-$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager}"
EMULATOR="${EMULATOR:-$ANDROID_HOME/emulator/emulator}"
ADB="${ADB:-$ANDROID_HOME/platform-tools/adb}"
GRADLEW="${GRADLEW:-$REPO_ROOT/gradlew}"
ARTIFACT_DIR="$REPO_ROOT/test-artifacts/emulator-matrix"

# name|serialPort|grpcPort|device|systemImage|width|height|density|ramMb|cores|heapMb|label|tags
PROFILES=(
  "droidlm_api36_latest|5554|8554|pixel_7|system-images;android-36;google_apis;x86_64|1080|2400|420|6144|4|512|Latest flagship baseline|phone,core,voice,audio"
  "droidlm_api35_midrange|5556|8556|pixel_6|system-images;android-35;google_apis;x86_64|1080|2400|420|4096|2|384|Mainstream midrange phone|phone,core,voice,audio"
  "droidlm_api33_budget_720p|5558|8558|medium_phone|system-images;android-33;google_apis;x86_64|720|1600|320|2048|2|256|Budget 720p installed-base phone|phone,core,voice,audio,budget"
  "droidlm_api29_lenovo_tb8505f|5560|8560|medium_tablet|system-images;android-29;google_apis;x86_64|800|1280|213|2048|2|256|Lenovo TB-8505F Android 10 tablet|tablet,core,voice,legacy"
  "droidlm_api29_lenovo_stress|5568|8568|medium_tablet|system-images;android-29;google_apis;x86_64|800|1280|213|1536|1|192|Low-end Android 10 tablet stress profile|tablet,core,voice,legacy,budget,stress"
  "droidlm_api31_phone|5562|8562|medium_phone|system-images;android-31;google_apis;x86_64|1080|2340|420|3072|2|320|Android 12 permission and overlay baseline|phone,core,voice"
  "droidlm_api35_play_midrange|5564|8564|pixel_6|system-images;android-35;google_apis_playstore;x86_64|1080|2400|420|4096|2|384|Mainstream midrange phone with Google Play|phone,play,workspace"
  "droidlm_api35_play_tablet|5566|8566|medium_tablet|system-images;android-35;google_apis_playstore;x86_64|1600|2560|320|6144|4|512|Modern tablet with Google Play|tablet,play,workspace"
)

RELEASE_VOICE_PROFILES=(
  droidlm_api36_latest
  droidlm_api35_midrange
  droidlm_api33_budget_720p
  droidlm_api29_lenovo_tb8505f
  droidlm_api31_phone
)

RELEASE_LOG_UPLOAD_PROFILES=(
  droidlm_api36_latest
  droidlm_api35_midrange
  droidlm_api33_budget_720p
  droidlm_api29_lenovo_tb8505f
  droidlm_api31_phone
)

RELEASE_VOSK_PROFILES=(
  droidlm_api36_latest
  droidlm_api33_budget_720p
)

RELEASE_SUPPORT_LOG_PROFILES=(
  droidlm_api36_latest
)

RELEASE_CAPTURE_REGRESSION_PROFILES=(
  "droidlm_api29_lenovo_tb8505f|16"
  "droidlm_api33_budget_720p|8"
  "droidlm_api29_lenovo_stress|8"
)



RELEASE_ON_DEVICE_AUDIO_PROFILES=(
  droidlm_api36_latest
  droidlm_api33_budget_720p
)

RELEASE_INSTALL_UPGRADE_PROFILES=(
  droidlm_api35_midrange
  droidlm_api33_budget_720p
  droidlm_api29_lenovo_tb8505f
  droidlm_api31_phone
)



RELEASE_WORKSPACE_PROFILES=(
  droidlm_api35_midrange
  droidlm_api29_lenovo_tb8505f
)

log() {
  printf '%s\n' "$*"
}

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

require_tool() {
  [[ -x "$1" ]] || fail "$1 is missing or not executable"
}

require_android_tools() {
  require_tool "$SDKMANAGER"
  require_tool "$AVDMANAGER"
  require_tool "$EMULATOR"
  require_tool "$ADB"
  require_tool "$GRADLEW"
}

profile_field() {
  local profile="$1"
  local index="$2"
  awk -F'|' -v idx="$index" '{print $idx}' <<<"$profile"
}

find_profile() {
  local wanted="$1"
  local profile name
  for profile in "${PROFILES[@]}"; do
    name="$(profile_field "$profile" 1)"
    if [[ "$name" == "$wanted" ]]; then
      printf '%s\n' "$profile"
      return 0
    fi
  done
  return 1
}

for_profiles() {
  local wanted="$1"
  local profile
  if [[ "$wanted" == "all" ]]; then
    printf '%s\n' "${PROFILES[@]}"
    return 0
  fi
  find_profile "$wanted" || fail "Unknown emulator profile '$wanted'. Run '$0 list'."
}

avd_exists() {
  local name="$1"
  "$EMULATOR" -list-avds | grep -Fxq "$name"
}

image_installed() {
  local image="$1"
  "$SDKMANAGER" --list_installed | grep -Fq "${image}"
}

install_image_if_needed() {
  local image="$1"
  if image_installed "$image"; then
    log "System image already installed: $image"
    return 0
  fi
  log "Installing system image: $image"
  yes | "$SDKMANAGER" --install "$image"
}

write_avd_config() {
  local name="$1"
  local width="$2"
  local height="$3"
  local density="$4"
  local ram_mb="$5"
  local cores="$6"
  local heap_mb="$7"
  local avd_dir="$HOME/.android/avd/${name}.avd"
  local config_file="$avd_dir/config.ini"
  [[ -f "$config_file" ]] || fail "Missing AVD config: $config_file"
  python3 - "$config_file" "$width" "$height" "$density" "$ram_mb" "$cores" "$heap_mb" <<'PY'
from pathlib import Path
import sys
config_file = Path(sys.argv[1])
width, height, density, ram_mb, cores, heap_mb = sys.argv[2:]
updates = {
    "hw.lcd.width": width,
    "hw.lcd.height": height,
    "hw.lcd.density": density,
    "hw.ramSize": ram_mb,
    "hw.cpu.ncore": cores,
    "vm.heapSize": heap_mb,
    "hw.keyboard": "yes",
    "hw.gpu.enabled": "yes",
    "hw.gpu.mode": "auto",
    "hw.audioInput": "yes",
    "hw.audioOutput": "yes",
    "showDeviceFrame": "no",
    "disk.dataPartition.size": "8G",
    "fastboot.forceColdBoot": "yes",
}
lines = config_file.read_text().splitlines()
seen = set()
out = []
for line in lines:
    if "=" not in line:
        out.append(line)
        continue
    key = line.split("=", 1)[0]
    if key in updates:
        out.append(f"{key}={updates[key]}")
        seen.add(key)
    else:
        out.append(line)
for key, value in updates.items():
    if key not in seen:
        out.append(f"{key}={value}")
config_file.write_text("\n".join(out) + "\n")
PY
}

create_profile() {
  local profile="$1"
  local name port grpc_port device image width height density ram_mb cores heap_mb label
  name="$(profile_field "$profile" 1)"
  port="$(profile_field "$profile" 2)"
  grpc_port="$(profile_field "$profile" 3)"
  device="$(profile_field "$profile" 4)"
  image="$(profile_field "$profile" 5)"
  width="$(profile_field "$profile" 6)"
  height="$(profile_field "$profile" 7)"
  density="$(profile_field "$profile" 8)"
  ram_mb="$(profile_field "$profile" 9)"
  cores="$(profile_field "$profile" 10)"
  heap_mb="$(profile_field "$profile" 11)"
  label="$(profile_field "$profile" 12)"

  log "Preparing $name ($label)"
  install_image_if_needed "$image"
  if avd_exists "$name"; then
    log "AVD already exists: $name"
  else
    log "Creating AVD $name from $image with device profile $device"
    printf 'no\n' | "$AVDMANAGER" create avd --force --name "$name" --package "$image" --device "$device" >/dev/null
  fi
  write_avd_config "$name" "$width" "$height" "$density" "$ram_mb" "$cores" "$heap_mb"
  log "Configured $name: ${width}x${height}@${density}dpi, ${ram_mb}MB RAM, ${cores} cores, emulator-$port, gRPC $grpc_port"
}

serial_for_profile() {
  local profile="$1"
  printf 'emulator-%s\n' "$(profile_field "$profile" 2)"
}

boot_profile() {
  local profile="$1"
  local name port grpc_port width height density serial log_file
  name="$(profile_field "$profile" 1)"
  port="$(profile_field "$profile" 2)"
  grpc_port="$(profile_field "$profile" 3)"
  width="$(profile_field "$profile" 6)"
  height="$(profile_field "$profile" 7)"
  density="$(profile_field "$profile" 8)"
  serial="$(serial_for_profile "$profile")"
  log_file="$ARTIFACT_DIR/${name}-emulator.log"
  mkdir -p "$ARTIFACT_DIR"

  if "$ADB" devices | grep -Fq "$serial"; then
    log "$name is already visible as $serial"
  else
    log "Booting $name as $serial; log: $log_file"
    "$EMULATOR" -avd "$name" -port "$port" -grpc "$grpc_port" -no-window -no-snapshot -no-boot-anim -gpu swiftshader_indirect >"$log_file" 2>&1 &
  fi

  "$ADB" -s "$serial" wait-for-device
  local deadline=$((SECONDS + 240))
  while (( SECONDS < deadline )); do
    if [[ "$("$ADB" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; then
      "$ADB" -s "$serial" shell input keyevent 82 >/dev/null 2>&1 || true
      "$ADB" -s "$serial" shell wm size "${width}x${height}" >/dev/null 2>&1 || true
      "$ADB" -s "$serial" shell wm density "$density" >/dev/null 2>&1 || true
      "$ADB" -s "$serial" shell settings put global window_animation_scale 0 >/dev/null 2>&1 || true
      "$ADB" -s "$serial" shell settings put global transition_animation_scale 0 >/dev/null 2>&1 || true
      "$ADB" -s "$serial" shell settings put global animator_duration_scale 0 >/dev/null 2>&1 || true
      "$ADB" -s "$serial" shell svc power stayon true >/dev/null 2>&1 || true
      log "$name booted ($serial)"
      local post_boot_settle_seconds="${DROIDLM_E2E_POST_BOOT_SETTLE_SECONDS:-0}"
      if [[ "$post_boot_settle_seconds" =~ ^[0-9]+$ ]] && (( post_boot_settle_seconds > 0 )); then
        log "Settling $name for ${post_boot_settle_seconds}s before running tests"
        sleep "$post_boot_settle_seconds"
      fi
      return 0
    fi
    sleep 2
  done
  fail "Timed out waiting for $name to boot. See $log_file"
}

configure_device_state() {
  local serial="$1"
  local network_mode="${DROIDLM_E2E_NETWORK_MODE:-online}"
  local font_scale="${DROIDLM_E2E_FONT_SCALE:-}"

  case "$network_mode" in
    online)
      "$ADB" -s "$serial" shell svc wifi enable >/dev/null 2>&1 || true
      "$ADB" -s "$serial" shell svc data enable >/dev/null 2>&1 || true
      ;;
    offline)
      "$ADB" -s "$serial" shell svc wifi disable >/dev/null 2>&1 || true
      "$ADB" -s "$serial" shell svc data disable >/dev/null 2>&1 || true
      ;;
    *)
      fail "Unknown DROIDLM_E2E_NETWORK_MODE '$network_mode'. Expected online or offline."
      ;;
  esac

  if [[ -n "$font_scale" ]]; then
    "$ADB" -s "$serial" shell settings put system font_scale "$font_scale" >/dev/null 2>&1 || true
  fi
}

stop_profile() {
  local profile="$1"
  local name serial
  name="$(profile_field "$profile" 1)"
  serial="$(serial_for_profile "$profile")"
  local port
  port="$(profile_field "$profile" 2)"
  if "$ADB" devices | grep -Fq "$serial"; then
    log "Stopping $name ($serial)"
    "$ADB" -s "$serial" emu kill >/dev/null 2>&1 || true
  fi
  local deadline=$((SECONDS + 30))
  while (( SECONDS < deadline )); do
    if ! "$ADB" devices | grep -Fq "$serial"; then
      return 0
    fi
    sleep 1
  done
  fail "Timed out waiting for $name ($serial) to stop after kill"
}

is_transient_profile_failure_log() {
  local log_file="$1"
  [[ -f "$log_file" ]] || return 1
  grep -Fqi "Process crashed" "$log_file" ||
    grep -Fqi "device offline" "$log_file" ||
    grep -Fqi "failed to get feature set" "$log_file" ||
    (grep -Fqi "device '" "$log_file" && grep -Fqi "not found" "$log_file")
}

run_profile() {
  local profile="$1"
  shift
  local name serial grpc_port relay_port relay_url debug_log_upload_url
  name="$(profile_field "$profile" 1)"
  serial="$(serial_for_profile "$profile")"
  grpc_port="$(profile_field "$profile" 3)"
  relay_port="${DROIDLM_E2E_RELAY_PORT:-8787}"
  relay_url="${DROIDLM_E2E_RELAY_URL:-}"
  debug_log_upload_url="${DROIDLM_E2E_DEBUG_LOG_UPLOAD_URL:-}"
  create_profile "$profile"
  boot_profile "$profile"
  configure_device_state "$serial"
  if [[ -z "$relay_url" && "${DROIDLM_E2E_ENABLE_RELAY_REVERSE:-false}" == "true" ]]; then
    if "$ADB" -s "$serial" reverse "tcp:$relay_port" "tcp:$relay_port" >/dev/null 2>&1; then
      relay_url="http://127.0.0.1:$relay_port"
      log "Mapped $serial tcp:$relay_port to host; emulator relay URL: $relay_url"
      if [[ -z "$debug_log_upload_url" ]]; then debug_log_upload_url="$relay_url"; fi
    else
      log "Warning: could not configure adb reverse for $serial tcp:$relay_port"
    fi
  fi
  local stop_after_run="${DROIDLM_MATRIX_STOP_AFTER_RUN:-false}"
  local max_retries="${DROIDLM_E2E_PROFILE_RETRY_COUNT:-1}"
  if ! [[ "$max_retries" =~ ^[0-9]+$ ]]; then
    max_retries=1
  fi
  local retry_count=0
  local status=0
  local task_label="${1:-gradle}"
  task_label="${task_label//[^A-Za-z0-9._-]/_}"
  while :; do
    local gradle_log
    gradle_log="$(mktemp "/tmp/droidlm-${name}-${task_label}.XXXX.log")"
    log "Running Gradle on $name ($serial): $*"
    set +e
    (
      cd "$REPO_ROOT"
      ANDROID_SERIAL="$serial" DROIDLM_E2E_GRPC_PORT="$grpc_port" DROIDLM_E2E_RELAY_URL="$relay_url" DROIDLM_E2E_DEBUG_LOG_UPLOAD_URL="$debug_log_upload_url" "$GRADLEW" "$@" </dev/null 2>&1 | tee "$gradle_log"
    )
    status=$?
    set -e
    if (( status == 0 )); then
      rm -f "$gradle_log"
      break
    fi
    if (( retry_count >= max_retries )) || ! is_transient_profile_failure_log "$gradle_log"; then
      log "Gradle output saved to $gradle_log"
      break
    fi
    retry_count=$((retry_count + 1))
    log "Transient device failure on $name ($serial); rebooting and retrying Gradle task (retry $retry_count/$max_retries)"
    rm -f "$gradle_log"
    stop_profile "$profile"
    boot_profile "$profile"
    configure_device_state "$serial"
  done
  if [[ "$stop_after_run" == "true" ]]; then
    stop_profile "$profile"
  fi
  return "$status"
}

run_profile_names() {
  local -n profile_names_ref="$1"
  shift
  local profile_name
  for profile_name in "${profile_names_ref[@]}"; do
    run_profile "$(find_profile "$profile_name")" "$@"
  done
}

run_capture_regression_profile() {
  local entry="$1"
  local profile_name stress_threads profile completion_latency_ms
  profile_name="${entry%%|*}"
  stress_threads="${entry#*|}"
  profile="$(find_profile "$profile_name")"
  local previous_record_hold="${DROIDLM_E2E_CAPTURE_RECORD_HOLD_MS:-}"
  local previous_cpu_stress="${DROIDLM_E2E_CAPTURE_CPU_STRESS_THREADS:-}"
  local previous_timeout="${DROIDLM_E2E_MIC_INJECTION_TIMEOUT_MS:-}"
  local previous_completion_latency="${DROIDLM_E2E_CAPTURE_MAX_COMPLETION_LATENCY_MS:-}"
  DROIDLM_E2E_CAPTURE_RECORD_HOLD_MS=10000
  DROIDLM_E2E_CAPTURE_CPU_STRESS_THREADS="$stress_threads"
  DROIDLM_E2E_MIC_INJECTION_TIMEOUT_MS=120000
  completion_latency_ms="$previous_completion_latency"
  if [[ -z "$completion_latency_ms" ]]; then
    case "$profile_name" in
      droidlm_api29_lenovo_tb8505f) completion_latency_ms=12000 ;;
      droidlm_api29_lenovo_stress) completion_latency_ms=15000 ;;
    esac
  fi
  export DROIDLM_E2E_CAPTURE_RECORD_HOLD_MS DROIDLM_E2E_CAPTURE_CPU_STRESS_THREADS DROIDLM_E2E_MIC_INJECTION_TIMEOUT_MS
  if [[ -n "$completion_latency_ms" ]]; then
    export DROIDLM_E2E_CAPTURE_MAX_COMPLETION_LATENCY_MS="$completion_latency_ms"
  else
    unset DROIDLM_E2E_CAPTURE_MAX_COMPLETION_LATENCY_MS
  fi
  set +e
  run_profile "$profile" connectedHoverMicCaptureRegressionE2e
  local status=$?
  set -e
  restore_env_var DROIDLM_E2E_CAPTURE_RECORD_HOLD_MS "$previous_record_hold"
  restore_env_var DROIDLM_E2E_CAPTURE_CPU_STRESS_THREADS "$previous_cpu_stress"
  restore_env_var DROIDLM_E2E_MIC_INJECTION_TIMEOUT_MS "$previous_timeout"
  restore_env_var DROIDLM_E2E_CAPTURE_MAX_COMPLETION_LATENCY_MS "$previous_completion_latency"
  return "$status"
}

restore_env_var() {
  local name="$1"
  local value="$2"
  if [[ -n "$value" ]]; then
    export "$name=$value"
  else
    unset "$name"
  fi
}

run_release_capture_regression() {
  local entry
  for entry in "${RELEASE_CAPTURE_REGRESSION_PROFILES[@]}"; do
    run_capture_regression_profile "$entry"
  done
}

run_release_core() {
  log "Running release core matrix: voice, upload, offline speech, capture regression, support-log regression, on-device STT, and install/upgrade."
  run_profile_names RELEASE_VOICE_PROFILES connectedVoiceE2e
  run_profile_names RELEASE_LOG_UPLOAD_PROFILES connectedDebugLogUploadE2e

  local previous_network_mode="${DROIDLM_E2E_NETWORK_MODE:-online}"
  DROIDLM_E2E_NETWORK_MODE=offline
  run_profile_names RELEASE_VOSK_PROFILES connectedVoskOfflineE2e
  DROIDLM_E2E_NETWORK_MODE="$previous_network_mode"

  run_release_capture_regression
  run_profile_names RELEASE_SUPPORT_LOG_PROFILES connectedSupportLogMicRegressionE2e

  run_profile_names RELEASE_ON_DEVICE_AUDIO_PROFILES connectedOnDeviceAudioSourceE2e
  run_profile_names RELEASE_INSTALL_UPGRADE_PROFILES -Pdroidlm.debugIteration=999 connectedDebugInstallUpgradeE2e

}

run_release_workspace() {
  log "Running release workspace matrix on phone and tablet profiles with bundled Docs and Sheets stubs."
  run_profile_names RELEASE_WORKSPACE_PROFILES connectedWorkspaceFileOpsReleaseE2e
  run_profile_names RELEASE_WORKSPACE_PROFILES connectedDocsAgentLoopReleaseE2e
}

run_release_matrix() {
  local mode="${1:-full}"
  local previous_stop_after_run="${DROIDLM_MATRIX_STOP_AFTER_RUN:-false}"
  DROIDLM_MATRIX_STOP_AFTER_RUN=true

  case "$mode" in
    core)
      run_release_core
      ;;
    workspace)
      run_release_workspace
      ;;
    full)
      run_release_core
      run_release_workspace
      ;;
    *)
      DROIDLM_MATRIX_STOP_AFTER_RUN="$previous_stop_after_run"
      fail "Unknown release matrix mode '$mode'. Expected core, workspace, or full."
      ;;
  esac

  DROIDLM_MATRIX_STOP_AFTER_RUN="$previous_stop_after_run"
}

print_list() {
  printf '%-30s %-14s %-10s %-18s %-50s %-36s %s\n' "AVD" "serial" "gRPC" "device" "system image" "profile" "tags"
  local profile name port grpc_port device image label tags
  for profile in "${PROFILES[@]}"; do
    name="$(profile_field "$profile" 1)"
    port="$(profile_field "$profile" 2)"
    grpc_port="$(profile_field "$profile" 3)"
    device="$(profile_field "$profile" 4)"
    image="$(profile_field "$profile" 5)"
    label="$(profile_field "$profile" 12)"
    tags="$(profile_field "$profile" 13)"
    printf '%-30s %-14s %-10s %-18s %-50s %-36s %s\n' "$name" "emulator-$port" "$grpc_port" "$device" "$image" "$label" "$tags"
  done
}

usage() {
  cat <<'EOF'
Usage: scripts/android-emulator-matrix.sh <command> [profile] [gradle tasks...]

Commands:
  list                 Show the DroidLM emulator matrix.
  create [profile]     Create/configure all profiles or one named profile.
  boot <profile>       Boot one profile headlessly.
  stop [profile]       Stop all profiles or one named profile.
  run [profile] [tasks] Create, boot, and run Gradle tasks. Defaults to connectedVoiceE2e.
  release [mode]       Run the curated release matrix. Modes: core, workspace, full.
  doctor               Print SDK, AVD, and adb state.

Profiles:
  all
  droidlm_api36_latest
  droidlm_api35_midrange
  droidlm_api33_budget_720p
  droidlm_api29_lenovo_tb8505f
  droidlm_api29_lenovo_stress
  droidlm_api31_phone
  droidlm_api35_play_midrange
  droidlm_api35_play_tablet

Examples:
  scripts/android-emulator-matrix.sh create all
  scripts/android-emulator-matrix.sh run all connectedVoiceE2e
  scripts/android-emulator-matrix.sh run all connectedDebugLogUploadE2e
  scripts/android-emulator-matrix.sh run droidlm_api33_budget_720p connectedVoskOfflineE2e
  scripts/android-emulator-matrix.sh run droidlm_api29_lenovo_tb8505f connectedDebugInstallUpgradeE2e
  scripts/android-emulator-matrix.sh release full
EOF
}

main() {
  require_android_tools
  local command="${1:-}"
  if [[ -z "$command" || "$command" == "help" || "$command" == "--help" ]]; then
    usage
    return 0
  fi
  shift || true

  case "$command" in
    list)
      print_list
      ;;
    create)
      local target="${1:-all}"
      local profiles=()
      mapfile -t profiles < <(for_profiles "$target")
      local profile
      for profile in "${profiles[@]}"; do create_profile "$profile"; done
      ;;
    boot)
      local target="${1:-}"
      [[ -n "$target" && "$target" != "all" ]] || fail "boot requires one profile name"
      create_profile "$(find_profile "$target")"
      boot_profile "$(find_profile "$target")"
      ;;
    stop)
      local target="${1:-all}"
      local profiles=()
      mapfile -t profiles < <(for_profiles "$target")
      local profile
      for profile in "${profiles[@]}"; do stop_profile "$profile"; done
      ;;
    run)
      local target="${1:-all}"
      if [[ $# -gt 0 ]]; then shift; fi
      local tasks=("$@")
      if [[ ${#tasks[@]} -eq 0 ]]; then tasks=(connectedVoiceE2e); fi
      local profiles=()
      mapfile -t profiles < <(for_profiles "$target")
      local profile
      for profile in "${profiles[@]}"; do run_profile "$profile" "${tasks[@]}"; done
      ;;
    release)
      local mode="${1:-full}"
      run_release_matrix "$mode"
      ;;
    doctor)
      "$SDKMANAGER" --list_installed | grep -E 'platforms;android-|system-images;android-' || true
      printf '\nAVDs:\n'
      "$EMULATOR" -list-avds || true
      printf '\nADB devices:\n'
      "$ADB" devices || true
      ;;
    *)
      usage >&2
      return 2
      ;;
  esac
}

main "$@"

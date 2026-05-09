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

# name|serialPort|grpcPort|device|systemImage|width|height|density|ramMb|cores|heapMb|label
PROFILES=(
  "droidlm_api36_latest|5554|8554|pixel_7|system-images;android-36;google_apis;x86_64|1080|2400|420|6144|4|512|Latest flagship baseline"
  "droidlm_api35_midrange|5556|8556|pixel_6|system-images;android-35;google_apis;x86_64|1080|2400|420|4096|2|384|Mainstream midrange phone"
  "droidlm_api33_budget_720p|5558|8558|medium_phone|system-images;android-33;google_apis;x86_64|720|1600|320|2048|2|256|Budget 720p installed-base phone"
  "droidlm_api29_lenovo_tb8505f|5560|8560|medium_tablet|system-images;android-29;google_apis;x86_64|800|1280|213|2048|2|256|Lenovo TB-8505F Android 10 tablet"
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
      return 0
    fi
    sleep 2
  done
  fail "Timed out waiting for $name to boot. See $log_file"
}

stop_profile() {
  local profile="$1"
  local name serial
  name="$(profile_field "$profile" 1)"
  serial="$(serial_for_profile "$profile")"
  if "$ADB" devices | grep -Fq "$serial"; then
    log "Stopping $name ($serial)"
    "$ADB" -s "$serial" emu kill >/dev/null 2>&1 || true
  fi
}

run_profile() {
  local profile="$1"
  shift
  local name serial grpc_port
  name="$(profile_field "$profile" 1)"
  serial="$(serial_for_profile "$profile")"
  grpc_port="$(profile_field "$profile" 3)"
  create_profile "$profile"
  boot_profile "$profile"
  log "Running Gradle on $name ($serial): $*"
  (
    cd "$REPO_ROOT"
    ANDROID_SERIAL="$serial" DROIDLM_E2E_GRPC_PORT="$grpc_port" "$GRADLEW" "$@" </dev/null
  )
}

print_list() {
  printf '%-30s %-14s %-10s %-18s %-50s %s\n' "AVD" "serial" "gRPC" "device" "system image" "profile"
  local profile name port grpc_port device image label
  for profile in "${PROFILES[@]}"; do
    name="$(profile_field "$profile" 1)"
    port="$(profile_field "$profile" 2)"
    grpc_port="$(profile_field "$profile" 3)"
    device="$(profile_field "$profile" 4)"
    image="$(profile_field "$profile" 5)"
    label="$(profile_field "$profile" 12)"
    printf '%-30s %-14s %-10s %-18s %-50s %s\n' "$name" "emulator-$port" "$grpc_port" "$device" "$image" "$label"
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
  doctor               Print SDK, AVD, and adb state.

Profiles:
  all
  droidlm_api36_latest
  droidlm_api35_midrange
  droidlm_api33_budget_720p
  droidlm_api29_lenovo_tb8505f

Examples:
  scripts/android-emulator-matrix.sh create all
  scripts/android-emulator-matrix.sh run all connectedVoiceE2e
  scripts/android-emulator-matrix.sh run droidlm_api33_budget_720p connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=ai.droidlm.e2e.DroidLmVoskOfflineE2ETest
  scripts/android-emulator-matrix.sh run droidlm_api29_lenovo_tb8505f connectedDebugInstallUpgradeE2e
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

#!/usr/bin/env bash
set -euo pipefail

log() {
  printf '%s\n' "$*"
}

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "$1 is required in the container"
}

require_command adb
require_command avdmanager
require_command emulator
require_command pactl
require_command pulseaudio
require_command paplay
require_command ffmpeg

export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/android-sdk}}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
export XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-/tmp/droidlm-xdg-runtime}"
export PULSE_SERVER="${PULSE_SERVER:-unix:$XDG_RUNTIME_DIR/pulse/native}"
export DROIDLM_E2E_MIC_INJECTION_MODE="${DROIDLM_E2E_MIC_INJECTION_MODE:-grpc}"
export DROIDLM_E2E_PULSE_SERVER="${DROIDLM_E2E_PULSE_SERVER:-tcp:127.0.0.1:4713}"
export DROIDLM_E2E_PULSE_SINK="${DROIDLM_E2E_PULSE_SINK:-droidlm_e2e_mic}"
export DROIDLM_E2E_PULSE_SOURCE="${DROIDLM_E2E_PULSE_SOURCE:-droidlm_e2e_mic_source}"
export DROIDLM_E2E_AUDIO_DRIVER="${DROIDLM_E2E_AUDIO_DRIVER:-$([[ "$DROIDLM_E2E_MIC_INJECTION_MODE" == "pulse" ]] && printf pa || printf '')}"
if [[ -n "$DROIDLM_E2E_AUDIO_DRIVER" ]]; then
  export QEMU_AUDIO_DRV="$DROIDLM_E2E_AUDIO_DRIVER"
  export QEMU_AUDIO_IN_DRV="$DROIDLM_E2E_AUDIO_DRIVER"
  export QEMU_AUDIO_OUT_DRV="$DROIDLM_E2E_AUDIO_DRIVER"
else
  unset QEMU_AUDIO_DRV QEMU_AUDIO_IN_DRV QEMU_AUDIO_OUT_DRV
fi
if [[ "$DROIDLM_E2E_MIC_INJECTION_MODE" == "pulse" ]]; then
  export QEMU_PA_SERVER="$DROIDLM_E2E_PULSE_SERVER"
  export QEMU_PA_SOURCE="$DROIDLM_E2E_PULSE_SOURCE"
  export QEMU_PA_SINK="$DROIDLM_E2E_PULSE_SINK"
else
  unset QEMU_PA_SERVER QEMU_PA_SOURCE QEMU_PA_SINK
fi

mkdir -p "$XDG_RUNTIME_DIR/pulse"
chmod 700 "$XDG_RUNTIME_DIR"
cat >"$HOME/.asoundrc" <<'EOF'
pcm.!default {
  type pulse
}
ctl.!default {
  type pulse
}
EOF

if ! pulseaudio --check >/dev/null 2>&1; then
  log "Starting container-local PulseAudio at $PULSE_SERVER"
  pulseaudio --daemonize=yes --exit-idle-time=-1 --log-target=stderr
fi

for _ in $(seq 1 30); do
  if pactl info >/dev/null 2>&1; then
    break
  fi
  sleep 1
done
pactl info >/dev/null 2>&1 || fail "PulseAudio did not become ready at $PULSE_SERVER"

AVD_NAME="${DROIDLM_E2E_AVD:-droidlm_e2e}"
SYSTEM_IMAGE="${DROIDLM_E2E_SYSTEM_IMAGE:-system-images;android-34;google_apis;x86_64}"
export DROIDLM_E2E_AVD="$AVD_NAME"

if ! avdmanager list avd | grep -q "Name: $AVD_NAME"; then
  log "Creating AVD $AVD_NAME from $SYSTEM_IMAGE"
  printf 'no\n' | avdmanager create avd --force --name "$AVD_NAME" --package "$SYSTEM_IMAGE" --device "pixel_6" >/dev/null
fi

adb start-server >/dev/null
adb devices || true

if [[ "$#" -eq 0 ]]; then
  set -- ./gradlew connectedHoverMicAudioE2e
fi

log "Running in single-container virtual mic environment: $*"
exec "$@"

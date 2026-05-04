#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IMAGE_NAME="${DROIDLM_E2E_CONTAINER_IMAGE:-droidlm-virtual-mic-e2e}"
DOCKERFILE="$REPO_ROOT/e2e/virtual-mic-container/Dockerfile"
HOST_UID="$(id -u)"
HOST_GID="$(id -g)"
KVM_GID="$(stat -c '%g' /dev/kvm 2>/dev/null || true)"
GRADLE_CACHE_DIR="${DROIDLM_E2E_GRADLE_CACHE_DIR:-$REPO_ROOT/build/container-gradle-cache}"

if [[ ! -e /dev/kvm ]]; then
  printf 'ERROR: /dev/kvm is required for the Android Emulator container.\n' >&2
  exit 1
fi

if [[ ! -r /dev/kvm || ! -w /dev/kvm ]]; then
  printf 'ERROR: current user cannot read/write /dev/kvm. Add the user to the kvm group or adjust permissions.\n' >&2
  exit 1
fi

mkdir -p "$GRADLE_CACHE_DIR"
printf 'Building %s from %s\n' "$IMAGE_NAME" "$DOCKERFILE"
docker build \
  --build-arg "UID=$HOST_UID" \
  --build-arg "GID=$HOST_GID" \
  -t "$IMAGE_NAME" \
  -f "$DOCKERFILE" \
  "$REPO_ROOT"

DOCKER_ARGS=(
  --rm
  --device /dev/kvm
  --shm-size=2g
  --workdir /workspace
  --volume "$REPO_ROOT:/workspace"
  --volume "$GRADLE_CACHE_DIR:/home/droidlm/.gradle"
  --env DROIDLM_E2E_AVD="${DROIDLM_E2E_AVD:-droidlm_e2e}"
  --env DROIDLM_E2E_SYSTEM_IMAGE="${DROIDLM_E2E_SYSTEM_IMAGE:-system-images;android-34;google_apis;x86_64}"
)

if [[ -n "$KVM_GID" ]]; then
  DOCKER_ARGS+=(--group-add "$KVM_GID")
fi

if [[ -n "${OPENAI_API_KEY:-}" ]]; then
  DOCKER_ARGS+=(--env OPENAI_API_KEY)
fi

while IFS= read -r env_name; do
  DOCKER_ARGS+=(--env "$env_name")
done < <(compgen -e | awk '/^DROIDLM_E2E_/')

if [[ -f "$REPO_ROOT/.env.local" ]]; then
  DOCKER_ARGS+=(--env-file "$REPO_ROOT/.env.local")
fi

printf 'Starting single-container virtual mic E2E run\n'
docker run "${DOCKER_ARGS[@]}" "$IMAGE_NAME" "$@"

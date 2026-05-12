#!/usr/bin/env bash
set -euo pipefail

VERSION="${1:-}"
ITERATION="${2:-1}"
REMOTE="${DROIDLM_RELEASE_REMOTE:-origin}"
BRANCH="${DROIDLM_RELEASE_BRANCH:-main}"
SKIP_E2E="${DROIDLM_SKIP_E2E:-false}"
RELEASE_E2E_MODE="${DROIDLM_RELEASE_E2E_MODE:-full}"

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

usage() {
  printf 'Usage: %s <version> [debug-iteration]\n' "${0##*/}"
  printf 'Example: %s 0.1.12 1\n' "${0##*/}"
}

find_apkanalyzer() {
  if [[ -n "${APK_ANALYZER:-}" ]]; then
    printf '%s\n' "$APK_ANALYZER"
    return 0
  fi
  if command -v apkanalyzer >/dev/null 2>&1; then
    command -v apkanalyzer
    return 0
  fi
  local sdk_root candidate
  for sdk_root in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}"; do
    [[ -n "$sdk_root" ]] || continue
    candidate="$sdk_root/cmdline-tools/latest/bin/apkanalyzer"
    if [[ -x "$candidate" ]]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done
  return 1
}

base_version_code() {
  local raw
  raw="$(awk -F= '/^val baseVersionCode[[:space:]]*=/{gsub(/[[:space:]]/, "", $2); print $2; exit}' app/build.gradle.kts)"
  [[ "$raw" =~ ^[0-9]+$ ]] || fail "Could not read baseVersionCode from app/build.gradle.kts"
  printf '%s\n' "$raw"
}

verify_debug_apk_metadata() {
  local apk="$1"
  local expected_version_name="$2"
  local expected_version_code="$3"
  local analyzer actual_version_name actual_version_code
  analyzer="$(find_apkanalyzer)" || fail "apkanalyzer is required to verify the release APK metadata"
  [[ -x "$analyzer" ]] || fail "apkanalyzer is not executable: $analyzer"
  actual_version_name="$("$analyzer" manifest version-name "$apk" | tr -d '\r')"
  actual_version_code="$("$analyzer" manifest version-code "$apk" | tr -d '\r')"
  [[ "$actual_version_name" == "$expected_version_name" ]] || fail "Debug APK versionName mismatch: expected $expected_version_name, got $actual_version_name"
  [[ "$actual_version_code" == "$expected_version_code" ]] || fail "Debug APK versionCode mismatch: expected $expected_version_code, got $actual_version_code"
}

[[ -n "$VERSION" ]] || { usage; exit 2; }
[[ "$VERSION" != v* ]] || fail "Pass the bare version, for example 0.1.12, not v0.1.12"
[[ "$ITERATION" =~ ^[0-9]+$ ]] || fail "Debug iteration must be a number"

git diff --quiet || fail "Working tree has unstaged changes"
git diff --cached --quiet || fail "Index has staged changes"
CURRENT_BRANCH="$(git branch --show-current)"
[[ "$CURRENT_BRANCH" == "$BRANCH" ]] || fail "Expected branch $BRANCH, got $CURRENT_BRANCH"

git fetch "$REMOTE" "$BRANCH"
git diff --quiet "HEAD" "$REMOTE/$BRANCH" || fail "Local $BRANCH differs from $REMOTE/$BRANCH"

TAG="v${VERSION}-debug.${ITERATION}"
ASSET="DroidLM-${VERSION}-debug.${ITERATION}-debug.apk"
APK="app/build/outputs/apk/debug/app-debug.apk"
EXPECTED_VERSION_NAME="${VERSION}-debug.${ITERATION}"
EXPECTED_VERSION_CODE="$(( $(base_version_code) * 1000 + ITERATION ))"

git rev-parse -q --verify "refs/tags/$TAG" >/dev/null && fail "Tag already exists locally: $TAG"
git ls-remote --exit-code --tags "$REMOTE" "$TAG" >/dev/null 2>&1 && fail "Tag already exists on $REMOTE: $TAG"

GRADLE_DEBUG_FLAGS=(-Pdroidlm.debugIteration="$ITERATION")
./gradlew "${GRADLE_DEBUG_FLAGS[@]}" testDebugUnitTest assembleDebug
if [[ "$SKIP_E2E" != "true" ]]; then
  scripts/android-emulator-matrix.sh release "$RELEASE_E2E_MODE"
fi
# The E2E matrix may run Gradle without droidlm.debugIteration and overwrite app-debug.apk.
# Rebuild the final artifact after verification so the uploaded APK carries the release iteration.
./gradlew "${GRADLE_DEBUG_FLAGS[@]}" assembleDebug
VERIFY_COMMANDS="./gradlew -Pdroidlm.debugIteration=${ITERATION} testDebugUnitTest assembleDebug"
if [[ "$SKIP_E2E" != "true" ]]; then
  VERIFY_COMMANDS="$VERIFY_COMMANDS, scripts/android-emulator-matrix.sh release ${RELEASE_E2E_MODE}"
fi
VERIFY_COMMANDS="$VERIFY_COMMANDS, and ./gradlew -Pdroidlm.debugIteration=${ITERATION} assembleDebug"

[[ -f "$APK" ]] || fail "Debug APK not found: $APK"
verify_debug_apk_metadata "$APK" "$EXPECTED_VERSION_NAME" "$EXPECTED_VERSION_CODE"
cp "$APK" "/tmp/$ASSET"

gh release create "$TAG" "/tmp/$ASSET" \
  --target "$BRANCH" \
  --title "DroidLM ${TAG}" \
  --prerelease \
  --notes "Debug build for DroidLM ${VERSION}.\n\nChannel: debug\nTag: ${TAG}\nAsset: ${ASSET}\n\nVerified with ${VERIFY_COMMANDS}."

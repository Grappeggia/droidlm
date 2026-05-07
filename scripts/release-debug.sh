#!/usr/bin/env bash
set -euo pipefail

VERSION="${1:-}"
ITERATION="${2:-1}"
REMOTE="${DROIDLM_RELEASE_REMOTE:-origin}"
BRANCH="${DROIDLM_RELEASE_BRANCH:-main}"
SKIP_E2E="${DROIDLM_SKIP_E2E:-false}"

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

usage() {
  printf 'Usage: %s <version> [debug-iteration]\n' "${0##*/}"
  printf 'Example: %s 0.1.12 1\n' "${0##*/}"
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

git rev-parse -q --verify "refs/tags/$TAG" >/dev/null && fail "Tag already exists locally: $TAG"
git ls-remote --exit-code --tags "$REMOTE" "$TAG" >/dev/null 2>&1 && fail "Tag already exists on $REMOTE: $TAG"

GRADLE_DEBUG_FLAGS=(-Pdroidlm.debugIteration="$ITERATION")
./gradlew "${GRADLE_DEBUG_FLAGS[@]}" testDebugUnitTest assembleDebug
if [[ "$SKIP_E2E" != "true" ]]; then
  ./gradlew "${GRADLE_DEBUG_FLAGS[@]}" connectedVoiceE2e
fi
VERIFY_COMMANDS="./gradlew -Pdroidlm.debugIteration=${ITERATION} testDebugUnitTest assembleDebug"
if [[ "$SKIP_E2E" != "true" ]]; then
  VERIFY_COMMANDS="$VERIFY_COMMANDS and ./gradlew -Pdroidlm.debugIteration=${ITERATION} connectedVoiceE2e"
fi


[[ -f "$APK" ]] || fail "Debug APK not found: $APK"
cp "$APK" "/tmp/$ASSET"

gh release create "$TAG" "/tmp/$ASSET" \
  --target "$BRANCH" \
  --title "DroidLM ${TAG}" \
  --prerelease \
  --notes "Debug build for DroidLM ${VERSION}.\n\nChannel: debug\nTag: ${TAG}\nAsset: ${ASSET}\n\nVerified with ${VERIFY_COMMANDS}."

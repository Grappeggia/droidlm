#!/usr/bin/env bash
set -euo pipefail

VERSION="${1:-}"
REMOTE="${DROIDLM_RELEASE_REMOTE:-origin}"
BRANCH="${DROIDLM_RELEASE_BRANCH:-main}"
SKIP_E2E="${DROIDLM_SKIP_E2E:-false}"
ALLOW_UNSIGNED="${DROIDLM_ALLOW_UNSIGNED_RELEASE:-false}"
RELEASE_E2E_MODE="${DROIDLM_RELEASE_E2E_MODE:-full}"

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

usage() {
  printf 'Usage: %s <version>\n' "${0##*/}"
  printf 'Example: %s 0.1.12\n' "${0##*/}"
}

[[ -n "$VERSION" ]] || { usage; exit 2; }
[[ "$VERSION" != v* ]] || fail "Pass the bare version, for example 0.1.12, not v0.1.12"

if [[ "$ALLOW_UNSIGNED" != "true" ]]; then
  [[ -n "${DROIDLM_RELEASE_STORE_FILE:-}" ]] || fail "DROIDLM_RELEASE_STORE_FILE is required for prod releases"
  [[ -n "${DROIDLM_RELEASE_STORE_PASSWORD:-}" ]] || fail "DROIDLM_RELEASE_STORE_PASSWORD is required for prod releases"
  [[ -n "${DROIDLM_RELEASE_KEY_ALIAS:-}" ]] || fail "DROIDLM_RELEASE_KEY_ALIAS is required for prod releases"
  [[ -n "${DROIDLM_RELEASE_KEY_PASSWORD:-}" ]] || fail "DROIDLM_RELEASE_KEY_PASSWORD is required for prod releases"
fi

git diff --quiet || fail "Working tree has unstaged changes"
git diff --cached --quiet || fail "Index has staged changes"
CURRENT_BRANCH="$(git branch --show-current)"
[[ "$CURRENT_BRANCH" == "$BRANCH" ]] || fail "Expected branch $BRANCH, got $CURRENT_BRANCH"

git fetch "$REMOTE" "$BRANCH"
git diff --quiet "HEAD" "$REMOTE/$BRANCH" || fail "Local $BRANCH differs from $REMOTE/$BRANCH"

TAG="v${VERSION}"
ASSET="DroidLM-${VERSION}-release.apk"

git rev-parse -q --verify "refs/tags/$TAG" >/dev/null && fail "Tag already exists locally: $TAG"
git ls-remote --exit-code --tags "$REMOTE" "$TAG" >/dev/null 2>&1 && fail "Tag already exists on $REMOTE: $TAG"

./gradlew testDebugUnitTest testReleaseUnitTest assembleRelease
if [[ "$SKIP_E2E" != "true" ]]; then
  scripts/android-emulator-matrix.sh release "$RELEASE_E2E_MODE"
fi

mapfile -t RELEASE_APKS < <(python3 - <<'PY'
from pathlib import Path
for path in sorted(Path('app/build/outputs/apk/release').glob('*.apk')):
    print(path)
PY
)
[[ "${#RELEASE_APKS[@]}" -gt 0 ]] || fail "Release APK not found in app/build/outputs/apk/release"
APK="${RELEASE_APKS[0]}"
cp "$APK" "/tmp/$ASSET"

gh release create "$TAG" "/tmp/$ASSET" \
  --target "$BRANCH" \
  --title "DroidLM ${TAG}" \
  --latest \
  --notes "Production release for DroidLM ${VERSION}.\n\nChannel: prod\nTag: ${TAG}\nAsset: ${ASSET}\n\nVerified with ./gradlew testDebugUnitTest testReleaseUnitTest assembleRelease and scripts/android-emulator-matrix.sh release ${RELEASE_E2E_MODE}."

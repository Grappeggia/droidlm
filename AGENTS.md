# DroidLM Agent Instructions

- Work only in this repository and use `/tmp` for temporary artifacts.
- Do not embed OpenAI API keys, Mobilerun API keys, release keystore files, keystore passwords, or signing credentials in Android source, resources, Gradle files, BuildConfig, scripts, release notes, commits, or APKs.
- Keep SlopCode changes out of this repository; DroidLM release work should not modify `../slopcode`.

## Code Best Practices

- Keep the settings experience simple, intuitive, and uncluttered.
- Favor sensible defaults and contextual flows over exposing advanced planning or floating-control tuning in the main settings UI.

## Build Verification

- Build APKs locally before publishing.
- For debug/prerelease builds, run `./gradlew testDebugUnitTest assembleDebug` at minimum, and run emulator E2E with `./gradlew connectedVoiceE2e` when changing Android behavior.
- For prod/release builds, run `./gradlew testDebugUnitTest testReleaseUnitTest assembleRelease` at minimum, and run emulator E2E with `./gradlew connectedVoiceE2e` when changing Android behavior.
- Run CLI checks with `npm test` and `npm pack --dry-run` from `cli/` when CLI packaging or install/release instructions change.

## Release Channels

- Debug builds and prod builds must use separate tags and GitHub Releases.
- Debug releases are prereleases and use tags in the form `v<version>-debug.<iteration>`, for example `v0.1.12-debug.1`.
- Prod releases are stable releases and use tags in the form `v<version>`, for example `v0.1.12`.
- Debug release APK assets must be named `DroidLM-<version>-debug.<iteration>-debug.apk`.
- Prod release APK assets must be named `DroidLM-<version>-release.apk`.
- Debug builds use the `debug` Android build type and install as `ai.droidlm.debug` with a `-debug` version name suffix.
- Prod builds use the `release` Android build type and install as `ai.droidlm`.
- Prefer `scripts/release-debug.sh <version> [iteration]` for debug prereleases.
- Prefer `scripts/release-prod.sh <version>` for prod releases.
- Prod release signing is configured only from environment variables: `DROIDLM_RELEASE_STORE_FILE`, `DROIDLM_RELEASE_STORE_PASSWORD`, `DROIDLM_RELEASE_KEY_ALIAS`, and `DROIDLM_RELEASE_KEY_PASSWORD`.
- Do not publish unsigned prod builds unless the user explicitly requests it and `DROIDLM_ALLOW_UNSIGNED_RELEASE=true` is set.

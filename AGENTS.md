# DroidLM Agent Instructions

- Work only in this repository and use `/tmp` for temporary artifacts.
- Do not embed OpenAI API keys, Mobilerun API keys, release keystore files, keystore passwords, or signing credentials in Android source, resources, Gradle files, BuildConfig, scripts, release notes, commits, or APKs.
- Keep SlopCode changes out of this repository; DroidLM release work should not modify `../slopcode`.

## Issue Investigation

- When asked to fetch or investigate user-reported issues, treat uploaded debug log bundles as a main source alongside GitHub Issues and other explicit report channels.
- Use `gs://droidlm-debug-logs/debug-logs/` as the canonical debug-log bucket/prefix. Locate recent bundles with `gcloud storage ls --recursive --long gs://droidlm-debug-logs/debug-logs/**`, filter by UTC timestamps, and copy only relevant bundles to `/tmp` for inspection.
- Start each debug-log review with `issue-description.txt`, `diagnostics-health.json`, `timeline-index.json`, `speech/*.jsonl`, and `llm/*.json`; avoid opening raw audio unless the user explicitly asks for audio analysis.
- Debug logs can contain raw microphone audio, spoken text, screen context, and LLM traces. Summarize findings without exposing secrets, API keys, raw audio, or unnecessary user content.

## Debug Log and E2E Sanitization

- When turning a user report, debug log, transcript, screen context, or support-log bundle into code, tests, fixtures, release notes, or commits, sanitize it automatically before writing files.
- Do not commit raw microphone audio, speech transcripts, debug-log bundles, LLM traces, screenshots, user document titles, account identifiers, personal names, email addresses, auth tokens, cloud object URIs, local absolute paths, or customer/user content copied from reports.
- Replace report-derived labels with synthetic placeholders that preserve the bug shape, for example `Summary of Docs`, `Distractor Meeting Notes`, `Release lead`, `Mobile lead`, and `e2e@example.test`.
- Keep private audio or debug fixtures outside git. Load them only from local ignored paths or explicit environment variables such as `DROIDLM_PRIVATE_SUPPORT_LOG_PCM`; provide a sanitized synthetic fallback when tests need to run in CI or release verification.
- Before committing E2E tests or fixtures, search changed files for private markers including real names, emails, API keys, bearer tokens, `gs://` debug-log URIs, raw support-log filenames, and `app/src/androidTest/assets/*.pcm`.

## Code Best Practices

- Keep the settings experience simple, intuitive, and uncluttered.
- Favor sensible defaults and contextual flows over exposing advanced planning or floating-control tuning in the main settings UI.

## Architecture Guardrails

- Keep `DroidLMApp` as the composition root only. Add app-wide dependencies to `AppGraph`/`RealAppGraph`; do not add new public singleton-style fields or helper lookups on `DroidLMApp`.
- Android entry points must depend on typed dependency bundles or factories from `appGraph()`. Do not reintroduce `DroidLMApp.from(...)`, `(application as DroidLMApp)`, or broad app-object lookups in activities, services, receivers, or accessibility components.
- Service lifecycle state must live in app-scoped runtime stores such as `AccessibilityRuntime`, `OverlayRuntime`, and `ListeningRuntime`. Do not publish mutable lifecycle state from companion objects, `object`s, or static `Service` references.
- New execution behavior should go into focused collaborators under `execution/`; do not grow `DroidLmExecutor` with unrelated wiring, lifecycle, or UI-state responsibilities.

## Build Verification

- When the user says to `ship`, run the full shipping workflow: rebase/sync the current branch, check for other in-progress release or emulator jobs on this machine and wait or report if one is active, build, test, iterate until green, commit, push, and cut the requested release.
- For machine-level release monitoring, check for running `scripts/release-debug.sh`, `scripts/release-prod.sh`, `scripts/android-emulator-matrix.sh`, Gradle, and emulator/qemu processes before starting a release; avoid overlapping release jobs.
- Build APKs locally before publishing.
- For debug/prerelease builds, run `./gradlew testDebugUnitTest assembleDebug` at minimum. Release candidates must also pass `scripts/android-emulator-matrix.sh release full`.
- For prod/release builds, run `./gradlew testDebugUnitTest testReleaseUnitTest assembleRelease` at minimum. Stable releases must also pass `scripts/android-emulator-matrix.sh release full`.
- When asked to start emulators or run the device test matrix for release verification, use `scripts/android-emulator-matrix.sh release full`; it creates, boots, targets, and stops each required AVD profile.
- Run CLI checks with `npm test` and `npm pack --dry-run` from `cli/` when CLI packaging or install/release instructions change.

## Release Channels

- Debug builds and prod builds must use separate tags and GitHub Releases.
- Debug releases are prereleases and use tags in the form `v<version>-debug.<iteration>`, for example `v0.1.12-debug.1`.
- Prod releases are stable releases and use tags in the form `v<version>`, for example `v0.1.12`.
- Debug release APK assets must be named `DroidLM-<version>-debug.<iteration>-debug.apk`.
- Prod release APK assets must be named `DroidLM-<version>-release.apk`.
- Debug builds use the `debug` Android build type and install as `com.studionext54.droidlm.debug` with a `-debug` version name suffix.
- Prod builds use the `release` Android build type and install as `com.studionext54.droidlm`.
- Prefer `scripts/release-debug.sh <version> [iteration]` for debug prereleases.
- Prefer `scripts/release-prod.sh <version>` for prod releases.
- Prod release signing is configured only from environment variables: `DROIDLM_RELEASE_STORE_FILE`, `DROIDLM_RELEASE_STORE_PASSWORD`, `DROIDLM_RELEASE_KEY_ALIAS`, and `DROIDLM_RELEASE_KEY_PASSWORD`.
- Do not publish unsigned prod builds unless the user explicitly requests it and `DROIDLM_ALLOW_UNSIGNED_RELEASE=true` is set.

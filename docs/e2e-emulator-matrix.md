# DroidLM E2E Emulator Matrix

DroidLM uses emulator profiles to cover common Android device classes and targeted regressions: latest high-end phones, mainstream midrange phones, Play-enabled Workspace devices, older/budget 720p phones, Android 12 permission behavior, and Android 10 tablet install behavior.

## Device Coverage

| AVD | Represents | Android | Screen | Resources |
| --- | --- | --- | --- | --- |
| `droidlm_api36_latest` | Latest Samsung/Pixel/high-end Android behavior | API 36 | 1080x2400 @ 420 dpi | 6 GB RAM, 4 cores |
| `droidlm_api35_midrange` | Galaxy A-series, Redmi Note, Oppo/Vivo midrange | API 35 | 1080x2400 @ 420 dpi | 4 GB RAM, 2 cores |
| `droidlm_api33_budget_720p` | Redmi A, Oppo A, Vivo Y, Tecno/Infinix-style budget phones | API 33 | 720x1600 @ 320 dpi | 2 GB RAM, 2 cores |
| `droidlm_api29_lenovo_tb8505f` | Lenovo TB-8505F-style Android 10 tablet install behavior | API 29 | 800x1280 @ 213 dpi | 2 GB RAM, 2 cores |
| `droidlm_api31_phone` | Android 12-era permission and overlay behavior | API 31 | 1080x2340 @ 420 dpi | 3 GB RAM, 2 cores |
| `droidlm_api35_play_midrange` | Midrange phone with Google Play / Workspace installs | API 35 | 1080x2400 @ 420 dpi | 4 GB RAM, 2 cores |
| `droidlm_api35_play_tablet` | Modern tablet with Google Play / Workspace installs | API 35 | 1600x2560 @ 320 dpi | 6 GB RAM, 4 cores |

This matrix intentionally covers API spread, screen density/size, performance class, Google Play Workspace coverage, and an Android 10 tablet install path. It still does not emulate OEM skins such as HONOR MagicOS, Samsung One UI, MIUI/HyperOS, ColorOS, FuntouchOS, or Lenovo customizations.

## Prerequisites

- Android SDK with `sdkmanager`, `avdmanager`, `emulator`, and `adb` available under `ANDROID_HOME` or `ANDROID_SDK_ROOT`.
- KVM/hardware acceleration for practical E2E runtime.
- `ffmpeg` for audio conversion tasks.
- `OPENAI_API_KEY` in the environment or `.env.local` only when you want to override the bundled Open Google Drive audio sample; the core mic-injection tasks can now reuse the packaged WAV fixture.
- `gcloud` with Application Default Credentials only when running live GCS debug-log upload tests against a local upload endpoint: `gcloud auth application-default login`.

The script installs missing SDK system images automatically when possible:

```bash
scripts/android-emulator-matrix.sh create all
```

## Common Commands

List the profiles:

```bash
scripts/android-emulator-matrix.sh list
```

Create or update all AVDs:

```bash
scripts/android-emulator-matrix.sh create all
```

Run the standard voice E2E suite across all profiles:

```bash
scripts/android-emulator-matrix.sh run all connectedVoiceE2e
```

Run only the budget profile:

```bash
scripts/android-emulator-matrix.sh run droidlm_api33_budget_720p connectedVoiceE2e
```

Run the local debug APK install/upgrade regression check on the Android 10 Lenovo-style tablet profile:

```bash
scripts/android-emulator-matrix.sh run droidlm_api29_lenovo_tb8505f connectedDebugInstallUpgradeE2e
```

## Release Gates

Run the emulator-only release matrix that is now required by the release scripts:

```bash
scripts/android-emulator-matrix.sh release full
```

This curated matrix runs:

- `connectedVoiceE2e` across the full profile list, including the Play phone/tablet additions.
- `connectedDebugLogUploadE2e` across the full profile list.
- `connectedVoskOfflineE2e` on flagship + budget profiles with network disabled.
- `connectedSupportLogMicRegressionE2e` on the flagship profile where the May 10 mic regression is tracked.
- `connectedEmulatorMicProbeE2e` and `connectedHoverMicAudioE2e` on flagship, midrange, and budget phones.
- `connectedOnDeviceAudioSourceE2e` on flagship, budget, and Play midrange phones.
- `connectedDebugInstallUpgradeE2e` on midrange, budget, API 31, legacy tablet, and Play midrange profiles.
- `connectedActionKnownIssuesE2e` on flagship + API 31 profiles.
- `connectedWorkspaceFileOpsE2e` on the Play-enabled phone and tablet profiles.

If you only need the non-Workspace portion locally, run:

```bash
scripts/android-emulator-matrix.sh release core
```

And to rerun only the Workspace Play-profile checks:

```bash
scripts/android-emulator-matrix.sh release workspace
```

`release full` is emulator-only and does not require physical devices, but the Workspace portion still expects either Google Workspace APKs under `/tmp/droidlm-google-apks` (or the existing per-app env vars), or preinstalled Workspace apps on the Play-enabled AVDs.

Run the hidden debug-log upload E2E across all supported profiles. By default, the instrumentation test overrides the built-in endpoint with an in-device mock server and verifies the app uploads without any user-visible URL setting:

```bash
scripts/android-emulator-matrix.sh run all connectedDebugLogUploadE2e
```

Run the same E2E against a deployed HTTPS Cloud Function that writes to GCS:

```bash
DROIDLM_E2E_DEBUG_LOG_UPLOAD_URL=https://us-central1-droidlm-495821.cloudfunctions.net/droidlm-debug-log-upload \
scripts/android-emulator-matrix.sh run all connectedDebugLogUploadE2e
```

For a local host upload endpoint, map the emulator loopback to the host with `adb reverse`:

```bash
(cd server && DROIDLM_DEBUG_LOG_BUCKET=droidlm-debug-logs DROIDLM_DEBUG_LOG_PROJECT=droidlm-495821 uvicorn main:app --host 127.0.0.1 --port 8787)
DROIDLM_E2E_ENABLE_RELAY_REVERSE=true \
scripts/android-emulator-matrix.sh run droidlm_api36_latest connectedDebugLogUploadE2e
```


Run the Vosk/support-log instrumentation test on one profile:

```bash
scripts/android-emulator-matrix.sh run droidlm_api33_budget_720p connectedVoskOfflineE2e
```

Replay the May 10 support-log regression on the flagship profile:

```bash
scripts/android-emulator-matrix.sh run droidlm_api36_latest connectedSupportLogMicRegressionE2e
```

Run mic-injection E2E on the latest profile:

```bash
scripts/android-emulator-matrix.sh run droidlm_api36_latest connectedEmulatorMicProbeE2e connectedHoverMicAudioE2e
```

Stop all matrix emulators:

```bash
scripts/android-emulator-matrix.sh stop all
```

## Containerized Mic E2E

The containerized virtual-mic runner also accepts the device profile now:

```bash
DROIDLM_E2E_AVD=droidlm_api35_midrange \
DROIDLM_E2E_AVD_DEVICE=pixel_6 \
DROIDLM_E2E_SYSTEM_IMAGE='system-images;android-35;google_apis;x86_64' \
scripts/run-containerized-virtual-mic-e2e.sh ./gradlew connectedHoverMicAudioE2e
```

## Notes

- `scripts/android-emulator-matrix.sh run ...` exports `ANDROID_SERIAL` so Gradle and `adb` target the active emulator.
- `scripts/android-emulator-matrix.sh release ...` stops each profile after its task bundle finishes so the full release gate can run on one workstation without keeping every AVD alive at once.
- Optional state toggles are available through `DROIDLM_E2E_NETWORK_MODE=offline|online` and `DROIDLM_E2E_FONT_SCALE=<scale>` for targeted scenario reruns.
- gRPC mic ports are assigned per profile: `8554`, `8556`, `8558`, `8560`, `8562`, `8564`, and `8566`.
- E2E videos and emulator logs are written under `test-artifacts/`, which is gitignored.
- `connectedDebugInstallUpgradeE2e` builds the local debug APK and upgrades it over the latest GitHub debug prerelease; running `scripts/debug-install-upgrade-e2e.sh` directly defaults to the latest and previous GitHub debug prereleases. Override with `DROIDLM_INSTALL_E2E_LATEST_TAG`, `DROIDLM_INSTALL_E2E_BASELINE_TAG`, `DROIDLM_INSTALL_E2E_LATEST_APK`, or `DROIDLM_INSTALL_E2E_BASELINE_APK`.
- Private support-log PCM fixtures should stay local and are ignored via `app/src/androidTest/assets/droidlm-audio-*-vosk.pcm`.

# DroidLM E2E Emulator Matrix

DroidLM uses three Android emulator profiles to cover the most common Android device classes seen globally: latest high-end phones, mainstream midrange phones, and older/budget 720p phones.

## Device Coverage

| AVD | Represents | Android | Screen | Resources |
| --- | --- | --- | --- | --- |
| `droidlm_api36_latest` | Latest Samsung/Pixel/high-end Android behavior | API 36 | 1080x2400 @ 420 dpi | 6 GB RAM, 4 cores |
| `droidlm_api35_midrange` | Galaxy A-series, Redmi Note, Oppo/Vivo midrange | API 35 | 1080x2400 @ 420 dpi | 4 GB RAM, 2 cores |
| `droidlm_api33_budget_720p` | Redmi A, Oppo A, Vivo Y, Tecno/Infinix-style budget phones | API 33 | 720x1600 @ 320 dpi | 2 GB RAM, 2 cores |

This matrix intentionally covers API spread, screen density/size, and performance class. It does not emulate OEM skins such as HONOR MagicOS, Samsung One UI, MIUI/HyperOS, ColorOS, or FuntouchOS. Use at least one physical HONOR or Samsung device for OEM-specific speech, overlay, and permission behavior.

## Prerequisites

- Android SDK with `sdkmanager`, `avdmanager`, `emulator`, and `adb` available under `ANDROID_HOME` or `ANDROID_SDK_ROOT`.
- KVM/hardware acceleration for practical E2E runtime.
- `ffmpeg` for audio conversion tasks.
- `OPENAI_API_KEY` in the environment or `.env.local` only when running TTS-backed mic-injection tests such as `connectedHoverMicAudioE2e`; cached audio can avoid a fresh TTS call.

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

Run the standard voice E2E suite across all three profiles:

```bash
scripts/android-emulator-matrix.sh run all connectedVoiceE2e
```

Run only the budget profile:

```bash
scripts/android-emulator-matrix.sh run droidlm_api33_budget_720p connectedVoiceE2e
```

Run the Vosk/support-log instrumentation test on one profile:

```bash
scripts/android-emulator-matrix.sh run droidlm_api33_budget_720p connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=ai.droidlm.e2e.DroidLmVoskOfflineE2ETest
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
- gRPC mic ports are assigned per profile: `8554`, `8556`, and `8558`.
- E2E videos and emulator logs are written under `test-artifacts/`, which is gitignored.
- Private support-log PCM fixtures should stay local and are ignored via `app/src/androidTest/assets/droidlm-audio-*-vosk.pcm`.

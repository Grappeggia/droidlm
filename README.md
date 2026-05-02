# DroidLM

DroidLM is a ready-to-install Android APK that behaves like a local-first Android assistant on a user-owned phone or tablet. It preserves the Mobilerun/DroidRun Portal concept as an Android AccessibilityService/control layer and adds push-to-talk voice commands, relay-backed OpenAI transcription, local intent parsing, OCR, text editing helpers, safety gates, and action logs.

## Architecture

```text
Android app
  -> local wake phrase or push-to-talk
  -> short command recorder
  -> configurable relay /transcribe
  -> OpenAI transcription on the relay
  -> local rule-first intent parser
  -> safety classifier and confirmation gates
  -> PortalController Accessibility control layer
  -> Android UI actions such as launch app, tap, back/home, text insertion, screenshot, OCR
```

The OpenAI API key is never stored in Android source, resources, `BuildConfig`, `gradle.properties`, or the APK. All OpenAI calls go through the configurable backend relay in `server/`.

## Build APK

```bash
./gradlew assembleDebug
```

The debug APK is written under `app/build/outputs/apk/debug/`.

Install with:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Run The Relay

```bash
cd server
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env
# edit .env and set OPENAI_API_KEY
uvicorn main:app --host 0.0.0.0 --port 8787
```

Set DroidLM's relay URL to `http://<relay-host>:8787` in the app.

## First Setup

1. Open DroidLM.
2. Read the microphone and Accessibility disclosure.
3. Grant microphone permission.
4. Grant Android 13+ notification permission when prompted.
5. Tap **Open Accessibility Settings** and enable **DroidLM Device Control**.
6. Enter the relay base URL and tap **Save Relay URL**.
7. Tap **Test Relay**.
8. Use **Push to Talk** or the text command field.

Push-to-talk is always available, even when Porcupine wake-word setup is incomplete.

## Test Commands

- `DroidLM open my Drive app` launches `com.google.android.apps.docs`.
- `droid lm launch gmail` launches Gmail.
- `open google docs` launches Google Docs.
- `launch sheets` launches Google Sheets.
- `hey Droid L M go home` triggers global home.
- `DroidLM put the cursor after budget and type comma revised` inserts text in an accessible editable field.
- `DroidLM replace draft with final` replaces visible editable text.
- `DroidLM append new line signed comma Alex` appends a newline and signature.

## Emulator E2E Voice Invocation Test

The repository includes an emulator E2E suite for the command `DroidLM open the Google Drive App`.
It installs a test-only Drive-compatible target app with package `com.google.android.apps.docs`, uses the packaged spoken WAV sample at `app/src/androidTest/assets/droidlm_open_google_drive.wav`, posts that audio to a mock `/transcribe` relay, and verifies DroidLM launches the Drive package.

Run the voice E2E test with a booted emulator:

```bash
adb devices
./gradlew connectedVoiceE2e
```

Prepare and verify the real Google Workspace emulator environment separately:

```bash
DROIDLM_DOCS_APK=/tmp/google-docs.apk \
DROIDLM_SHEETS_APK=/tmp/google-sheets.apk \
DROIDLM_TEST_GOOGLE_EMAIL=e2e@example.test \
DROIDLM_TEST_GOOGLE_PASSWORD='<test-account-password>' \
./gradlew prepareWorkspaceEmulator
./gradlew verifyWorkspaceEmulator
```

`prepareWorkspaceEmulator` installs Drive, Gmail, Docs, and Sheets when APKs are available locally, opens a visible Google sign-in flow when credentials are supplied, skips recovery/address changes, disables basic device backup before accepting Google services, and pushes the sample files under `test-fixtures/workspace` to `/sdcard/Documents/DroidLMFixtures`. It does not store credentials in the repo or app.

`verifyWorkspaceEmulator` checks app availability and opens every fixture on-device through ADB intents. The fixture set includes public online document, image, and spreadsheet examples tracked in `test-fixtures/workspace/fixtures.json`.

APK lookup defaults are `DROIDLM_GOOGLE_APK_DIR` (`/tmp/droidlm-google-apks`) plus `/tmp/google-drive.apk`, `/tmp/google-gmail.apk`, `/tmp/google-docs.apk`, and `/tmp/google-sheets.apk`. Per-app overrides are `DROIDLM_DRIVE_APK`, `DROIDLM_GMAIL_APK`, `DROIDLM_DOCS_APK`, and `DROIDLM_SHEETS_APK`. If no APK is available, use `./gradlew openWorkspaceInstallPages` to open the install URL for the first missing app, finish installation in the emulator UI, then rerun `./gradlew verifyWorkspaceEmulator`.

If the emulator already has real Google Drive installed, skip the Drive stub install and run:

```bash
./gradlew :app:connectedDebugAndroidTest
```

The E2E test APK is built by `./gradlew :app:assembleDebugAndroidTest`, and the Drive stub APK is built by `./gradlew :driveStub:assembleDebug`.


## OCR And Text Editing

DroidLM first uses Accessibility text and selection APIs. It falls back to screenshot + ML Kit OCR for coordinate estimates only when structured text is unavailable. Cloud screenshot analysis is disabled by default and requires explicit settings plus confirmation where configured.

## Optional Porcupine Wake Word

The MVP includes a Porcupine engine placeholder and a settings field for a Picovoice AccessKey. To enable true local wake-word detection, add the Picovoice Porcupine Android SDK and wire `PorcupineWakeWordEngine` to a DroidLM `.ppn` model asset. Push-to-talk remains the required fallback.

## Optional Mobilerun Cloud Mode

Set execution mode to `MOBILERUN_CLOUD_TASK`, save a Mobilerun API key, and set the device ID. DroidLM calls the Mobilerun task stream from the app with the user-configured key stored via AndroidX Security.

## Safety And Privacy

- Accessibility automation is highly privileged and should be used only on user-owned devices.
- DroidLM does not implement stealth behavior, hidden microphone capture, credential theft, permission bypasses, Android security bypasses, or unauthorized remote control.
- A foreground notification is visible while listening/recording.
- Destructive, payment, credential, private-message, account, permission, install/uninstall, and other sensitive actions require confirmation.
- Max autonomous steps default to 12.
- Raw audio and screenshots are deleted by default unless debug retention is explicitly enabled.
- Cloud screenshot analysis can expose screen contents to the relay/OpenAI and is disabled by default.
- General-purpose Accessibility automation may not be suitable for Play Store distribution unless it qualifies as an accessibility tool.

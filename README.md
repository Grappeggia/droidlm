# DroidLM

DroidLM is a ready-to-install Android APK that behaves like a local-first Android assistant on a user-owned phone or tablet. It preserves the Mobilerun/DroidRun Portal concept as an Android AccessibilityService control layer and adds push-to-talk voice commands, offline and relay-backed transcription, local intent parsing, GPT planning, bounded agentic multi-step execution, OCR, text-editing helpers, safety gates, action logs, and debug-log exports.

DroidLM is built around a strict separation of concerns:

- The Android app observes the current device state and executes Android actions locally.
- The model proposes structured actions or tool calls, but the app validates safety, packages, node targets, budgets, and confirmations before execution.
- API keys are never embedded in Android source, resources, `BuildConfig`, Gradle files, scripts, release notes, commits, or APKs.

## Repository Layout

```text
.
|-- app/          Android application, accessibility portal, voice, OCR, planning, agent runtime
|-- driveStub/    Test-only Drive-compatible Android target used by emulator E2E tests
|-- server/       Optional FastAPI relay for OpenAI transcription, planning, and vision calls
|-- cli/          npm developer CLI for install, doctor, relay checks, and release APK installs
|-- scripts/      Release helpers for debug prereleases and production releases
|-- test-fixtures/ Google Workspace emulator fixture definitions and sample files
`-- README.md     Project architecture, setup, testing, and safety notes
```

## Architecture Overview

At runtime, DroidLM is an observation -> planning -> validation -> execution loop. Android state is collected locally, represented as typed Kotlin models, optionally sent to a planner, and then executed through the Accessibility portal only after deterministic app-side checks pass.

```text
User input
  |-- Push-to-talk / manual text / optional wake flow
  v
Speech and command ingestion
  |-- Vosk offline recognizer
  |-- Android SpeechRecognizer path
  |-- Optional relay transcription
  v
Intent and planning layer
  |-- Local IntentParser for deterministic commands
  |-- GPT plan preview for reviewed multi-step plans
  |-- Local LLM loop for one-action-at-a-time planning
  |-- Agent loop for bounded multi-tool turns
  |-- Optional Mobilerun Cloud task mode
  v
Safety and validation layer
  |-- SafetyClassifier risk categories
  |-- confirmation prompts
  |-- package installed/enabled/launchable checks
  |-- UI node and coordinate validation
  |-- conservative turn/tool/runtime budgets
  v
Execution layer
  |-- DroidLmExecutor orchestration
  |-- PortalController abstraction
  |-- AccessibilityPortalController implementation
  |-- TextEditingController and WorkspaceFileOperationController helpers
  v
Android effects
  |-- launch app, back/home, tap, scroll, wait, dialog response
  |-- insert/replace/select text
  |-- screenshot, OCR, Google Docs/Sheets/Drive helper actions
  v
Diagnostics
  |-- action logs
  |-- speech diagnostics
  |-- retained debug audio/screenshots/LLM traces when debug logging is enabled
```

### Execution Modes

DroidLM supports several execution modes, all mediated by the same local validation and portal execution layer.

- `LOCAL_RULE_FIRST`: default deterministic mode. `IntentParser` handles direct commands such as launching known apps, pressing home/back, and text-editing shortcuts. Commands that need advanced planning fail fast with setup guidance.
- `LOCAL_LLM_LOOP`: asks OpenAI for exactly one next action per loop iteration, executes it, observes again, and stops at `DONE`, failure, or the configured step cap.
- `AGENT_LOOP`: bounded agentic mode. The model may request multiple typed tool calls per turn, but DroidLM enforces turn, tool-call, mutating-call, runtime, package, node, coordinate, and repeated-failure limits.
- `MOBILERUN_CLOUD_TASK`: delegates the task to Mobilerun Cloud using a user-configured API key and device ID.

## Android App Deep Dive

### Application Wiring

`DroidLMApp` constructs the app-wide service graph:

- `SettingsRepository` for persistent settings and encrypted API-key storage.
- `ActionLogRepository`, `SpeechDiagnosticsLogger`, and `DebugLogStore` for observability.
- `RelayClient` and `OpenAiClient` for relay and direct OpenAI calls.
- `AccessibilityPortalController` as the concrete Android control backend.
- `AppInventoryRepository` for package inventory and launchability metadata.
- `DeviceContextAggregator` plus Google Workspace context providers.
- `MlKitOcrEngine`, `TextEditingController`, and `WorkspaceFileOperationController` for text and document workflows.
- `DroidLmExecutor` as the central orchestration engine.
- `CommandRecorder`, `VoskOfflineSpeechRecognizer`, and `SpeechRecognitionController` for voice input.

Architecture guardrails:

- `DroidLMApp` is the composition root only; app-wide wiring lives in `AppGraph`/`RealAppGraph`.
- Android entry points depend on typed dependency bundles or factories from `appGraph()`, not raw `DroidLMApp` lookups.
- Service lifecycle state is published through app-scoped runtime stores such as `AccessibilityRuntime`, `OverlayRuntime`, and `ListeningRuntime`, not companion-object `MutableStateFlow`s or static `Service` references.
- New execution behavior should be added as focused collaborators under `execution/` instead of expanding `DroidLmExecutor` with more unrelated responsibilities.

### UI Layer

`MainActivity` is a Jetpack Compose Material 3 UI. It provides:

- setup status for microphone, notification, accessibility, overlay, and speech recognition readiness;
- push-to-talk and manual command entry;
- a focused settings surface for transcription, wake word, confirmations, OCR, cloud vision, and debug logging;
- plan previews and confirmation cards;
- execution status, action logs, and debug-log export controls.

`DroidLmViewModel` bridges Compose state to repositories and the executor. It exposes flows for settings, speech recognition state, logs, pending plans, confirmations, and planner key setup prompts. The main settings UI intentionally stays simple and uncluttered, while advanced planning approvals remain contextual and overlay controls stay in setup/status flows instead of the core settings list.

### Settings and Secrets

`SettingsRepository` uses AndroidX DataStore Preferences for normal settings and AndroidX Security Crypto for secret storage. Configurable values include:

- relay URL;
- OpenAI model and device-stored OpenAI API key presence;
- transcription provider and offline-speech preference;
- execution mode;
- auto-accept-safe-plan toggle;
- max autonomous steps;
- max agent turns and max agent tool calls;
- Mobilerun device/model settings;
- OCR, cloud vision, debug logging, package allow/deny lists, and sensitive-app denylist.

The app never hard-codes API keys. Direct OpenAI planning requires the user to save a key on-device, while the optional relay can keep the OpenAI key server-side.

### Voice and Transcription

Voice input is split into capture, recognition, and planning handoff:

- `CommandRecorder` records short push-to-talk audio clips.
- `SpeechRecognitionController` coordinates provider selection, partials, push-to-talk lifecycle, diagnostics, and fallback behavior.
- `VoskOfflineSpeechRecognizer` uses the bundled `vosk-model-en-us-0.22-lgraph` model for offline English recognition and records read-loop diagnostics when debug logging is enabled.
- Android SpeechRecognizer integration is available for platform speech recognition paths.
- `RelayClient.transcribe` can post audio to the optional FastAPI relay `/transcribe` endpoint for OpenAI transcription.

Push-to-talk remains the required fallback even when optional wake-word setup is incomplete.

### Intent Parsing and Action Model

The command/action model lives under `app/src/main/kotlin/ai/droidlm/intent`.

- `DroidLmAction` is the sealed action hierarchy used internally across local parsing, LLM planning, plan previews, and agent tool execution.
- `IntentParser` maps common deterministic commands to actions without calling a model. It resolves known app aliases, asks for clarification for bare `open`, and routes missing/unlaunchable known apps toward app-store recovery.
- `RelayClient.parsePlanActionJson` parses planner JSON into `DroidLmAction`, including semantic UI actions such as `TAP_NODE`, `SCROLL`, `WAIT_FOR_UI`, `DIALOG_ACTION`, text-edit actions, Workspace helpers, screenshots, and app-store listing actions.
- `ActionUiFormatter` renders action labels for plan previews, confirmations, compact status, and logs.

The model never executes arbitrary code. It can only return supported JSON actions or agent tool calls that the app parses and validates.

### Planning and Agent Runtime

Planning has two model clients:

- `RelayClient` calls the optional backend relay for `/plan-action`, `/plan-preview`, `/planner/status`, `/transcribe`, and `/analyze-screenshot`.
- `OpenAiClient` calls OpenAI directly from the device when the user has saved a local OpenAI key. It retains LLM traces in debug logs when debug logging is enabled.

The bounded agent runtime lives under `app/src/main/kotlin/ai/droidlm/agent`:

- `AgentModels` defines `AgentBudgets`, `AgentDecision`, `AgentToolCall`, `AgentToolSpec`, and `AgentToolResult`.
- `AgentJsonParser` accepts the agent response envelope and also adapts legacy one-action JSON into a single tool call.
- `AgentToolRegistry` exposes typed tools, maps them to `DroidLmAction`, assigns risk categories, enforces per-tool limits, validates package launchability, validates node targets against the current UI state, validates coordinates against screen bounds, and marks tools that require a fresh observation.
- `DroidLmExecutor.runAgentLoop` owns turn execution, history, budgets, repeated-failure stops, confirmation prompts, safety checks, tool-call execution, fresh-observation breaks, and final stop reasons.

Default agent budgets are intentionally conservative:

- 4 agent turns;
- 8 total tool calls;
- 3 tool calls per turn;
- 2 mutating tool calls per turn;
- 2 consecutive failures;
- 75 second runtime cap.

Settings clamp agent turns to 1-8 and agent tool calls to 1-16.

### Device Context and Observation

`DeviceContextAggregator` combines the current `PortalState`, installed package inventory, active app metadata, and app-specific context providers.

The Google Workspace providers add structured app context:

- `GoogleDocsContextProvider` describes Docs modes, editability, selections, text windows, document actions, and visible document affordances.
- `GoogleSheetsContextProvider` describes active cells, visible grids, formula/edit modes, sheet text windows, and spreadsheet actions.
- `GoogleDriveContextProvider` describes Drive location, visible files, selected files, search context, and file actions.

`UiContextJson` converts portal state and context into planner-friendly JSON while preserving node IDs and semantic action metadata. Planners are instructed to prefer these structured node/action affordances over raw coordinates.

### Safety, Confirmation, and Recovery

`SafetyClassifier` detects risky categories such as payments, banking, credentials, account deletion, file deletion, private messages, security settings, app installs, permissions, and private-data sharing. It also mandates confirmation for screenshot analysis in sensitive contexts and app-store listing actions.

DroidLM validates and gates actions before execution:

- missing, disabled, or non-launchable packages are rejected before app launch;
- missing apps can trigger a confirmed Play Store listing flow;
- stale node IDs are rejected in agent mode;
- coordinates are rejected when outside known screen bounds;
- install/store, permission/credential, external-share, screenshot, and sensitive-edit tools require confirmation depending on policy;
- repeated failures stop agent execution instead of looping.

### Portal and Android Execution

`PortalController` is the app's control abstraction. `AccessibilityPortalController` implements it through Android accessibility APIs and Android intents.

Supported effects include:

- app launch and app-store listing launch;
- Settings, Home, Back, Recents, notifications, quick settings;
- tap, long press, swipe, scroll, tap text, tap node, focus node;
- wait for UI, dialog actions, menu actions, tab selection, toggles, sliders, expand/collapse, refresh;
- chooser/file/photo/share flows;
- screenshots and OCR handoff.

The execution layer logs every action start/result and keeps UI status updated so the user can cancel active automation.

### OCR and Text Editing

DroidLM prefers Accessibility text, focus, and selection APIs. OCR is a fallback or verification mechanism, not the first choice for editable fields.

- `MlKitOcrEngine` wraps Google ML Kit Text Recognition for on-device OCR.
- `TextCoordinateMapper` maps OCR elements back to screen coordinates when structured targets are unavailable.
- `TextEditingController` implements semantic text operations such as focus editable, selection, insert, replace selection, full-text replace, move cursor, anchor insertion, append, prepend, select all, and verification.
- `WorkspaceFileOperationController` handles local file-backed Google Workspace helper operations such as document text replacement, document notes, current-sheet cell updates, and spreadsheet row appends.

Cloud screenshot analysis is disabled by default and requires explicit settings plus confirmation because it may expose screen contents.

### Diagnostics and Debug Logs

DroidLM keeps normal action logs in memory and can retain deeper diagnostics only when debug logging is enabled. Debug exports may contain spoken text, transcripts, screenshots, audio, app inventory, UI/device state, and LLM traces, but never API keys.

Key diagnostic components:

- `ActionLogRepository`: user-visible action log entries.
- `SpeechDiagnosticsLogger`: speech-recognition, push-to-talk, planner, and executor event stream.
- `DebugLogStore`: retained audio, screenshots, text traces, and ZIP bundle creation.
- LLM trace retention in `OpenAiClient`: request/response metadata, assistant JSON, parsed actions, errors, and repair attempts.

## Server Relay Deep Dive

The optional relay in `server/` is a FastAPI service. It exists to keep the OpenAI API key server-side for deployments that do not want a device-stored key.

Endpoints:

- `GET /health`: health check.
- `GET /planner/status`: reports relay planner readiness and configured model.
- `POST /setup/openai-key`: saves an OpenAI key on the relay after setup-token verification.
- `DELETE /setup/openai-key`: removes the relay-stored key.
- `POST /transcribe`: accepts an audio multipart upload and returns transcript text.
- `POST /plan-preview`: asks OpenAI for a multi-step plan preview.
- `POST /plan-action`: asks OpenAI for exactly one action.
- `POST /analyze-screenshot`: sends screenshots for vision/OCR-style analysis.

Relay implementation libraries:

- FastAPI for HTTP routing and request handling.
- Uvicorn for local ASGI serving.
- OpenAI Python SDK for transcription, chat planning, and vision calls.
- Pydantic for request models and typed validation.
- python-multipart for audio/image uploads.
- python-dotenv for `.env` loading.

Run the relay locally:

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

## CLI Deep Dive

The npm CLI in `cli/` is a developer convenience wrapper. It does not enable Accessibility, grant Android permissions, execute Android actions remotely, or store OpenAI API keys.

Commands:

- `droidlm doctor`: checks Node.js, `adb`, connected Android devices, DroidLM install state, and relay health.
- `droidlm install`: installs a local APK, an APK URL, or a GitHub Release APK asset.
- `droidlm relay check`: calls relay `/health`.
- `droidlm version`: prints the CLI version.

The package is ESM, requires Node.js 18+, and is intentionally dependency-light.

```bash
cd cli
npm test
npm pack --dry-run
```

After publishing to npm:

```bash
npx droidlm doctor
npx droidlm install --apk app/build/outputs/apk/debug/app-debug.apk
npx droidlm relay check --url http://<relay-host>:8787
```

## Libraries and Frameworks

### Android and Kotlin

- Android Gradle Plugin + Kotlin Android plugin: application build, variants, signing, and Kotlin compilation.
- Kotlin JVM target 17: app and test code target Java 17 bytecode.
- Kotlin coroutines: asynchronous voice, networking, settings, planner, OCR, and executor flows.
- Jetpack Compose + Compose BOM: declarative UI.
- Material 3: app cards, buttons, text fields, switches, dialogs, and chips.
- AndroidX Activity Compose: Compose integration with `ComponentActivity`.
- AndroidX Lifecycle ViewModel Compose: state ownership and lifecycle-aware Compose integration.
- AndroidX Core KTX: Kotlin extensions for Android platform APIs.
- AndroidX DataStore Preferences: asynchronous typed settings persistence.
- AndroidX Security Crypto: encrypted local storage for user-supplied keys.
- OkHttp: direct HTTP client for relay and OpenAI calls.
- ML Kit Text Recognition: on-device OCR.
- Vosk Android: offline speech recognition with the bundled English model.
- Android AccessibilityService APIs: local device observation and UI automation.

### Testing

- JUnit 4: unit test runner.
- Robolectric: Android framework testing on the JVM.
- kotlinx-coroutines-test: coroutine scheduler and test utilities.
- MockWebServer: deterministic HTTP tests for relay/OpenAI clients.
- org.json test dependency: JSON parsing and assertions in local tests.
- AndroidX Test, JUnit extensions, test rules, and UiAutomator: instrumentation and emulator E2E tests.
- `driveStub`: a test-only Android module that mimics the Google Drive package name for deterministic launch tests.

## Build APK

```bash
./gradlew assembleDebug
```

The debug APK is written under `app/build/outputs/apk/debug/`.

Install with:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## First Setup

1. Open DroidLM.
2. Read the microphone and Accessibility disclosure.
3. Grant microphone permission.
4. Grant Android 13+ notification permission when prompted.
5. Tap **Open Accessibility Settings** and enable **DroidLM Device Control**.
6. Optional: configure a relay base URL and tap **Test Relay**.
7. Optional: save an OpenAI key on-device for direct GPT planning or agent mode.
8. Choose the execution mode in settings.
9. Use **Push to Talk** or the text command field.

Push-to-talk is always available, even when Porcupine wake-word setup is incomplete.

## Test Commands

- `DroidLM open my Drive app` launches `com.google.android.apps.docs`.
- `droid lm launch gmail` launches Gmail.
- `open google docs` launches Google Docs.
- `launch sheets` launches Google Sheets or offers app-store recovery when Sheets is missing.
- `hey Droid L M go home` triggers global home.
- `DroidLM put the cursor after budget and type comma revised` inserts text in an accessible editable field.
- `DroidLM replace draft with final` replaces visible editable text.
- `DroidLM append new line signed comma Alex` appends a newline and signature.

## Emulator E2E Voice Invocation Test

The repository includes an emulator E2E suite for the command `DroidLM open the Google Drive App`. It installs a test-only Drive-compatible target app with package `com.google.android.apps.docs`, uses the packaged spoken WAV sample at `app/src/androidTest/assets/droidlm_open_google_drive.wav`, posts that audio to a mock `/transcribe` relay, and verifies DroidLM launches the Drive package.

Run the voice E2E test with a booted emulator:

```bash
adb devices
./gradlew connectedVoiceE2e
```

Or run the full emulator matrix:

```bash
scripts/android-emulator-matrix.sh run all connectedVoiceE2e
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

## Optional Porcupine Wake Word

The MVP includes a Porcupine engine placeholder and a settings field for a Picovoice AccessKey. To enable true local wake-word detection, add the Picovoice Porcupine Android SDK and wire `PorcupineWakeWordEngine` to a DroidLM `.ppn` model asset. Push-to-talk remains the required fallback.

## Optional Mobilerun Cloud Mode

Set execution mode to `MOBILERUN_CLOUD_TASK`, save a Mobilerun API key, and set the device ID. DroidLM calls the Mobilerun task stream from the app with the user-configured key stored via AndroidX Security.

## Release Channels

Debug and production builds use separate package IDs and release channels:

- Debug build type installs as `com.droidlm.debug` and appends `-debug` or `-debug.<iteration>` to `versionName`.
- Release build type installs as `com.droidlm`.
- Debug releases use prerelease tags like `v0.1.26-debug.15` and assets like `DroidLM-0.1.26-debug.15-debug.apk`.
- Production releases use stable tags like `v0.1.26` and assets like `DroidLM-0.1.26-release.apk`.
- Production signing is read only from `DROIDLM_RELEASE_STORE_FILE`, `DROIDLM_RELEASE_STORE_PASSWORD`, `DROIDLM_RELEASE_KEY_ALIAS`, and `DROIDLM_RELEASE_KEY_PASSWORD`.
- `scripts/release-debug.sh` and `scripts/release-prod.sh` now require `scripts/android-emulator-matrix.sh release full` unless `DROIDLM_SKIP_E2E=true` is set.

## Safety and Privacy

- Accessibility automation is highly privileged and should be used only on user-owned devices.
- DroidLM does not implement stealth behavior, hidden microphone capture, credential theft, permission bypasses, Android security bypasses, or unauthorized remote control.
- A foreground notification is visible while listening/recording.
- Destructive, payment, credential, private-message, account, permission, install/uninstall, external-share, screenshot-analysis, and other sensitive actions require confirmation.
- Agent mode uses conservative turn, tool, failure, mutation, and runtime limits.
- Raw audio and screenshots are not retained unless debug logging is explicitly enabled.
- Debug ZIP exports may include spoken text, transcripts, screenshots, audio, app inventory, UI/device state, and LLM traces, but never API keys.
- Cloud screenshot analysis can expose screen contents to the relay/OpenAI and is disabled by default.
- General-purpose Accessibility automation may not be suitable for Play Store distribution unless it qualifies as an accessibility tool.

License: MIT. See `LICENSE`.

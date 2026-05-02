# DroidLM Agent Instructions

- Work only in this repository and use `/tmp` for temporary artifacts.
- Build APKs locally before publishing: run `./gradlew testDebugUnitTest assembleDebug` at minimum, and run emulator E2E with `./gradlew connectedVoiceE2e` when changing Android behavior.
- When asked to publish a build, upload the locally built APK from `app/build/outputs/apk/debug/app-debug.apk` to this repository's GitHub Releases for the requested version.
- Name release APK assets as `DroidLM-<version>-debug.apk` unless the user requests another signing/build variant.
- Do not embed OpenAI API keys or Mobilerun API keys in Android source, resources, Gradle files, BuildConfig, or APKs.
- Keep SlopCode changes out of this repository; DroidLM release work should not modify `../slopcode`.

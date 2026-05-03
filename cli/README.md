# DroidLM CLI

Developer CLI for installing DroidLM APKs and checking the local setup from npm.

## Usage

```bash
npx droidlm doctor
npx droidlm install --apk app/build/outputs/apk/debug/app-debug.apk
npx droidlm relay check --url http://127.0.0.1:8787
```

## Commands

- `droidlm doctor` checks Node.js, `adb`, connected Android devices, DroidLM install state, and relay health.
- `droidlm install` installs a local APK, downloads an APK URL, or downloads an APK from a GitHub Release.
- `droidlm relay check` calls the relay `/health` endpoint.
- `droidlm version` prints the CLI version.

## Install Sources

`droidlm install` resolves an APK in this order:

1. `--apk <path>` or `DROIDLM_APK_PATH`
2. `--apk-url <url>` or `DROIDLM_APK_URL`
3. A local Gradle build at `app/build/outputs/apk/...`
4. A GitHub Release asset from `--repo <owner/repo>` or `DROIDLM_GITHUB_REPO`

Checksum verification is supported with `--checksum <sha256>` or `DROIDLM_APK_SHA256`. GitHub Release checksum assets named `<apk>.sha256`, `SHA256SUMS`, `SHA256SUMS.txt`, or `checksums.txt` are detected automatically.

## Environment

- `ANDROID_HOME` or `ANDROID_SDK_ROOT`: Android SDK path used to find `adb`.
- `ANDROID_ADB`: explicit `adb` path.
- `ANDROID_SERIAL`: target device serial when multiple devices are connected.
- `DROIDLM_RELAY_URL`: relay base URL for `doctor` and `relay check`.
- `GITHUB_TOKEN`: optional token for GitHub release downloads.

## License

MIT. See `LICENSE`.

The npm package is a developer convenience wrapper. It does not enable Android Accessibility, grant permissions, or store OpenAI API keys.

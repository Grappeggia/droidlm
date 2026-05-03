#!/usr/bin/env node
import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import {
  accessSync,
  constants,
  createReadStream,
  createWriteStream,
  existsSync,
  mkdtempSync,
  readFileSync,
  statSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { basename, delimiter, dirname, join, resolve } from "node:path";
import { Readable } from "node:stream";
import { pipeline } from "node:stream/promises";

const APP_ID = "ai.droidlm";
const DEFAULT_REPO = "Grappeggia/droidlm";
const DEFAULT_RELAY_URL = "http://127.0.0.1:8787";
const USER_AGENT = `droidlm-cli/${loadVersion()}`;

async function main() {
  const tokens = process.argv.slice(2);
  const command = tokens[0];

  if (!command || isHelp(command)) {
    printHelp();
    return;
  }

  if (command === "version" || command === "--version" || command === "-v") {
    console.log(loadVersion());
    return;
  }

  if (command === "doctor") {
    const args = parseOptions(tokens.slice(1));
    if (args.help || args.h) {
      printDoctorHelp();
      return;
    }
    await runDoctor(args);
    return;
  }

  if (command === "install") {
    const args = parseOptions(tokens.slice(1));
    if (args.help || args.h) {
      printInstallHelp();
      return;
    }
    await installApp(args);
    return;
  }

  if (command === "relay") {
    const subcommand = tokens[1];
    const args = parseOptions(tokens.slice(2));
    if (!subcommand || isHelp(subcommand) || args.help || args.h) {
      printRelayHelp();
      return;
    }
    if (subcommand === "check") {
      await checkRelayCommand(args);
      return;
    }
    throw new Error(`Unknown relay command: ${subcommand}`);
  }

  throw new Error(`Unknown command: ${command}`);
}

function printHelp() {
  console.log(`DroidLM CLI ${loadVersion()}

Usage:
  droidlm <command> [options]

Commands:
  install       Install a DroidLM APK on a connected Android device
  doctor        Check adb, Android device state, app install state, and relay health
  relay check   Check the DroidLM relay /health endpoint
  version       Print the CLI version

Examples:
  droidlm doctor --relay-url http://127.0.0.1:8787
  droidlm install --apk app/build/outputs/apk/debug/app-debug.apk
  droidlm install --repo Grappeggia/droidlm --tag v0.1.0
  droidlm relay check --url http://127.0.0.1:8787`);
}

function printDoctorHelp() {
  console.log(`Usage:
  droidlm doctor [options]

Options:
  --adb <path>          Path to adb. Defaults to PATH or Android SDK platform-tools
  --device <serial>     Device serial to inspect when multiple devices are connected
  --relay-url <url>     Relay base URL. Defaults to DROIDLM_RELAY_URL or ${DEFAULT_RELAY_URL}`);
}

function printInstallHelp() {
  console.log(`Usage:
  droidlm install [options]

Options:
  --apk <path>          Install this local APK
  --apk-url <url>       Download and install an APK from this URL
  --checksum <sha256>   Verify the downloaded APK checksum
  --repo <owner/repo>   GitHub repo for release downloads. Defaults to ${DEFAULT_REPO}
  --tag <tag>           GitHub release tag. Defaults to latest release
  --asset <name>        Exact GitHub release asset name to install
  --adb <path>          Path to adb. Defaults to PATH or Android SDK platform-tools
  --device <serial>     Device serial to install onto
  --dry-run             Resolve checks without installing
  --skip-checksum       Do not verify checksum even if a checksum asset exists`);
}

function printRelayHelp() {
  console.log(`Usage:
  droidlm relay check [options]

Options:
  --url <url>           Relay base URL. Defaults to DROIDLM_RELAY_URL or ${DEFAULT_RELAY_URL}`);
}

function parseOptions(tokens) {
  const args = { _: [] };
  for (let i = 0; i < tokens.length; i += 1) {
    const token = tokens[i];
    if (token === "--") {
      args._.push(...tokens.slice(i + 1));
      break;
    }
    if (token.startsWith("--")) {
      const equals = token.indexOf("=");
      if (equals !== -1) {
        args[token.slice(2, equals)] = token.slice(equals + 1);
        continue;
      }
      const key = token.slice(2);
      const next = tokens[i + 1];
      if (next && !next.startsWith("-")) {
        args[key] = next;
        i += 1;
      } else {
        args[key] = true;
      }
      continue;
    }
    if (token === "-h") {
      args.h = true;
      continue;
    }
    if (token === "-v") {
      args.v = true;
      continue;
    }
    args._.push(token);
  }
  return args;
}

async function runDoctor(args) {
  let failures = 0;
  let warnings = 0;

  const fail = (label, detail) => {
    failures += 1;
    printCheck("fail", label, detail);
  };
  const warn = (label, detail) => {
    warnings += 1;
    printCheck("warn", label, detail);
  };
  const ok = (label, detail) => printCheck("ok", label, detail);

  ok("Node.js", process.version);

  const adb = findAdb(args);
  if (!adb) {
    fail("adb", "not found on PATH or under ANDROID_HOME/ANDROID_SDK_ROOT");
  } else {
    const version = run(adb, ["version"]);
    if (version.status === 0) {
      ok("adb", firstLine(version.stdout) || adb);
    } else {
      fail("adb", formatRunFailure(version));
    }
  }

  const sdkHome = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT;
  if (sdkHome) {
    ok("Android SDK", sdkHome);
  } else {
    warn("Android SDK", "ANDROID_HOME or ANDROID_SDK_ROOT is not set");
  }

  if (adb) {
    const devices = getAdbDevices(adb);
    if (!devices.ok) {
      fail("Android device", devices.detail);
    } else {
      const online = devices.devices.filter((device) => device.state === "device");
      const unavailable = devices.devices.filter((device) => device.state !== "device");
      if (online.length === 0) {
        fail("Android device", "no connected device is in the device state");
      } else {
        ok("Android device", online.map((device) => device.serial).join(", "));
      }
      for (const device of unavailable) {
        warn("Android device", `${device.serial} is ${device.state}`);
      }

      const serial = chooseDevice(args, online, false);
      if (serial) {
        const installed = run(adb, ["-s", serial, "shell", "pm", "path", APP_ID]);
        if (installed.status === 0 && installed.stdout.includes("package:")) {
          ok("DroidLM app", `installed as ${APP_ID}`);
        } else {
          warn("DroidLM app", `${APP_ID} is not installed on ${serial}`);
        }
      }
    }
  }

  const relayUrl = getRelayUrl(args);
  const relay = await checkRelay(relayUrl).catch((error) => ({ ok: false, detail: error.message }));
  if (relay.ok) {
    ok("Relay", `${normalizeBaseUrl(relayUrl)}/health responded ok`);
  } else {
    warn("Relay", `${normalizeBaseUrl(relayUrl)}/health failed: ${relay.detail}`);
  }

  const status = failures === 0 ? "passed" : "failed";
  const warningText = warnings === 1 ? "1 warning" : `${warnings} warnings`;
  console.log(`Doctor ${status}: ${failures} failures, ${warningText}`);
  if (failures > 0) {
    process.exitCode = 1;
  }
}

async function checkRelayCommand(args) {
  const relayUrl = getRelayUrl(args);
  const result = await checkRelay(relayUrl);
  if (!result.ok) {
    throw new Error(`${normalizeBaseUrl(relayUrl)}/health failed: ${result.detail}`);
  }
  console.log(`Relay ok: ${normalizeBaseUrl(relayUrl)}/health`);
}

async function installApp(args) {
  const adb = findAdb(args);
  if (!adb) {
    throw new Error("adb not found. Install Android platform-tools or pass --adb <path>.");
  }

  const devices = getAdbDevices(adb);
  if (!devices.ok) {
    throw new Error(`Unable to list Android devices: ${devices.detail}`);
  }
  const online = devices.devices.filter((device) => device.state === "device");
  const serial = chooseDevice(args, online, true);
  const apk = await resolveApk(args);

  console.log(`APK: ${apk.path}`);
  console.log(`Source: ${apk.source}`);
  console.log(`Device: ${serial}`);

  if (args["dry-run"]) {
    console.log("Dry run complete; no install performed.");
    return;
  }

  const installArgs = ["-s", serial, "install", "-r", apk.path];
  const result = spawnSync(adb, installArgs, { stdio: "inherit" });
  if (result.error) {
    throw result.error;
  }
  if (result.status !== 0) {
    throw new Error(`adb install failed with exit code ${result.status}`);
  }

  console.log(`Installed ${APP_ID} on ${serial}.`);
}

async function resolveApk(args) {
  const localPath = stringOption(args, "apk") || process.env.DROIDLM_APK_PATH;
  if (localPath) {
    return requireLocalApk(localPath, "local APK");
  }

  const apkUrl = stringOption(args, "apk-url") || process.env.DROIDLM_APK_URL;
  if (apkUrl) {
    return downloadApk(apkUrl, stringOption(args, "checksum") || process.env.DROIDLM_APK_SHA256, "APK URL", args);
  }

  const localBuild = findLocalBuildApk();
  if (localBuild) {
    return requireLocalApk(localBuild, "local Gradle build");
  }

  return downloadGitHubReleaseApk(args);
}

function requireLocalApk(path, source) {
  const resolved = resolve(path);
  if (!existsSync(resolved)) {
    throw new Error(`APK not found: ${resolved}`);
  }
  if (!statSync(resolved).isFile()) {
    throw new Error(`APK path is not a file: ${resolved}`);
  }
  if (!resolved.toLowerCase().endsWith(".apk")) {
    throw new Error(`APK path must end with .apk: ${resolved}`);
  }
  return { path: resolved, source };
}

function findLocalBuildApk() {
  const relativeCandidates = [
    "app/build/outputs/apk/release/app-release.apk",
    "app/build/outputs/apk/debug/app-debug.apk",
  ];
  let dir = process.cwd();
  const seen = new Set();
  for (let depth = 0; depth < 8; depth += 1) {
    if (seen.has(dir)) {
      break;
    }
    seen.add(dir);
    for (const relative of relativeCandidates) {
      const candidate = join(dir, relative);
      if (existsSync(candidate) && statSync(candidate).isFile()) {
        return candidate;
      }
    }
    const parent = dirname(dir);
    if (parent === dir) {
      break;
    }
    dir = parent;
  }
  return null;
}

async function downloadGitHubReleaseApk(args) {
  const repo = stringOption(args, "repo") || process.env.DROIDLM_GITHUB_REPO || DEFAULT_REPO;
  const tag = stringOption(args, "tag") || process.env.DROIDLM_GITHUB_TAG;
  const assetName = stringOption(args, "asset") || process.env.DROIDLM_GITHUB_ASSET;
  const release = await fetchGitHubRelease(repo, tag);
  const apkAsset = chooseApkAsset(release.assets || [], assetName);
  if (!apkAsset) {
    const available = (release.assets || []).map((asset) => asset.name).join(", ") || "none";
    throw new Error(`No APK asset found on ${repo} ${release.tag_name}. Available assets: ${available}`);
  }

  let checksum = stringOption(args, "checksum") || process.env.DROIDLM_APK_SHA256;
  if (!checksum && !args["skip-checksum"]) {
    checksum = await fetchChecksumForAsset(release.assets || [], apkAsset).catch(() => null);
  }

  return downloadApk(apkAsset.browser_download_url, checksum, `GitHub release ${repo}@${release.tag_name}`, args);
}

async function fetchGitHubRelease(repo, tag) {
  const endpoint = tag
    ? `https://api.github.com/repos/${repo}/releases/tags/${encodeURIComponent(tag)}`
    : `https://api.github.com/repos/${repo}/releases/latest`;
  const headers = {
    accept: "application/vnd.github+json",
    "user-agent": USER_AGENT,
  };
  if (process.env.GITHUB_TOKEN) {
    headers.authorization = `Bearer ${process.env.GITHUB_TOKEN}`;
  }

  const response = await fetchWithTimeout(endpoint, { headers }, 15000);
  if (response.status === 404) {
    throw new Error(`No GitHub release found at ${repo}${tag ? ` tag ${tag}` : " latest"}. Pass --apk or --apk-url to install a specific build.`);
  }
  if (!response.ok) {
    throw new Error(`GitHub release lookup failed (${response.status}): ${await response.text()}`);
  }
  return response.json();
}

function chooseApkAsset(assets, assetName) {
  if (assetName) {
    return assets.find((asset) => asset.name === assetName) || null;
  }
  const apkAssets = assets.filter((asset) => asset.name.toLowerCase().endsWith(".apk"));
  apkAssets.sort((left, right) => assetScore(left.name) - assetScore(right.name));
  return apkAssets[0] || null;
}

function assetScore(name) {
  const lower = name.toLowerCase();
  if (lower.includes("unsigned")) {
    return 100;
  }
  if (lower.includes("release")) {
    return 0;
  }
  if (lower.includes("debug")) {
    return 1;
  }
  return 2;
}

async function fetchChecksumForAsset(assets, apkAsset) {
  const checksumAsset = assets.find((asset) => asset.name === `${apkAsset.name}.sha256`)
    || assets.find((asset) => asset.name.toLowerCase() === "sha256sums")
    || assets.find((asset) => asset.name.toLowerCase() === "sha256sums.txt")
    || assets.find((asset) => asset.name.toLowerCase() === "checksums.txt");

  if (!checksumAsset) {
    return null;
  }

  const response = await fetchWithTimeout(checksumAsset.browser_download_url, {
    headers: { "user-agent": USER_AGENT },
  }, 15000);
  if (!response.ok) {
    throw new Error(`Checksum download failed (${response.status})`);
  }
  const text = await response.text();
  const line = text.split(/\r?\n/).find((entry) => entry.includes(apkAsset.name)) || text;
  const match = line.match(/[a-fA-F0-9]{64}/);
  return match ? match[0].toLowerCase() : null;
}

async function downloadApk(url, checksum, source, args) {
  const directory = mkdtempSync(join(tmpdir(), "droidlm-"));
  const destination = join(directory, safeApkName(url));
  console.log(`Downloading APK from ${url}`);
  await downloadFile(url, destination);

  if (checksum && !args["skip-checksum"]) {
    const actual = await sha256File(destination);
    const expected = normalizeChecksum(checksum);
    if (actual !== expected) {
      throw new Error(`APK checksum mismatch. Expected ${expected}, got ${actual}.`);
    }
    console.log(`Verified SHA256 ${actual}`);
  }

  return { path: destination, source };
}

function safeApkName(url) {
  try {
    const parsed = new URL(url);
    const name = basename(parsed.pathname);
    return name.toLowerCase().endsWith(".apk") ? name : "droidlm.apk";
  } catch {
    return "droidlm.apk";
  }
}

async function downloadFile(url, destination) {
  const response = await fetchWithTimeout(url, {
    headers: { "user-agent": USER_AGENT },
    redirect: "follow",
  }, 120000);
  if (!response.ok) {
    throw new Error(`Download failed (${response.status}): ${await response.text()}`);
  }
  if (!response.body) {
    throw new Error("Download response did not include a body");
  }
  await pipeline(Readable.fromWeb(response.body), createWriteStream(destination));
}

async function sha256File(path) {
  const hash = createHash("sha256");
  for await (const chunk of createReadStream(path)) {
    hash.update(chunk);
  }
  return hash.digest("hex");
}

function normalizeChecksum(value) {
  const match = String(value).match(/[a-fA-F0-9]{64}/);
  if (!match) {
    throw new Error("Checksum must contain a 64-character SHA256 hex digest");
  }
  return match[0].toLowerCase();
}

async function checkRelay(url) {
  const response = await fetchWithTimeout(`${normalizeBaseUrl(url)}/health`, {
    headers: { "user-agent": USER_AGENT },
  }, 5000);
  const text = await response.text();
  if (!response.ok) {
    return { ok: false, detail: `HTTP ${response.status} ${text}`.trim() };
  }
  if (!text) {
    return { ok: true, detail: "empty response" };
  }
  try {
    const json = JSON.parse(text);
    return { ok: json.ok !== false, detail: text };
  } catch {
    return { ok: true, detail: text };
  }
}

async function fetchWithTimeout(url, options, timeoutMs) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), timeoutMs);
  try {
    return await fetch(url, { ...options, signal: controller.signal });
  } finally {
    clearTimeout(timeout);
  }
}

function getRelayUrl(args) {
  return stringOption(args, "relay-url")
    || stringOption(args, "url")
    || process.env.DROIDLM_RELAY_URL
    || DEFAULT_RELAY_URL;
}

function normalizeBaseUrl(url) {
  return String(url).replace(/\/+$/, "");
}

function findAdb(args) {
  const explicit = stringOption(args, "adb") || process.env.ANDROID_ADB;
  if (explicit) {
    return resolve(explicit);
  }

  const pathAdb = findOnPath("adb");
  if (pathAdb) {
    return pathAdb;
  }

  const sdkRoots = [process.env.ANDROID_HOME, process.env.ANDROID_SDK_ROOT].filter(Boolean);
  for (const root of sdkRoots) {
    const candidate = join(root, "platform-tools", executableName("adb"));
    if (isExecutable(candidate)) {
      return candidate;
    }
  }
  return null;
}

function findOnPath(command) {
  const pathValue = process.env.PATH || "";
  for (const directory of pathValue.split(delimiter)) {
    if (!directory) {
      continue;
    }
    const candidate = join(directory, executableName(command));
    if (isExecutable(candidate)) {
      return candidate;
    }
  }
  return null;
}

function executableName(command) {
  return process.platform === "win32" && !command.toLowerCase().endsWith(".exe") ? `${command}.exe` : command;
}

function isExecutable(path) {
  try {
    const stats = statSync(path);
    if (!stats.isFile()) {
      return false;
    }
    if (process.platform !== "win32") {
      accessSync(path, constants.X_OK);
    }
    return true;
  } catch {
    return false;
  }
}

function getAdbDevices(adb) {
  const result = run(adb, ["devices"]);
  if (result.status !== 0) {
    return { ok: false, detail: formatRunFailure(result), devices: [] };
  }
  const devices = result.stdout
    .split(/\r?\n/)
    .slice(1)
    .map((line) => line.trim())
    .filter(Boolean)
    .map((line) => {
      const [serial, state] = line.split(/\s+/);
      return { serial, state };
    })
    .filter((device) => device.serial && device.state);
  return { ok: true, detail: result.stdout, devices };
}

function chooseDevice(args, onlineDevices, strict) {
  const requested = stringOption(args, "device") || process.env.ANDROID_SERIAL;
  if (requested) {
    const found = onlineDevices.find((device) => device.serial === requested);
    if (!found && strict) {
      throw new Error(`Requested device is not connected and online: ${requested}`);
    }
    return requested;
  }
  if (onlineDevices.length === 1) {
    return onlineDevices[0].serial;
  }
  if (onlineDevices.length === 0) {
    if (strict) {
      throw new Error("No connected Android device is online. Check adb devices.");
    }
    return null;
  }
  if (strict) {
    throw new Error(`Multiple devices are online (${onlineDevices.map((device) => device.serial).join(", ")}). Pass --device <serial>.`);
  }
  return null;
}

function run(command, args) {
  return spawnSync(command, args, { encoding: "utf8" });
}

function formatRunFailure(result) {
  if (result.error) {
    return result.error.message;
  }
  const stderr = (result.stderr || "").trim();
  const stdout = (result.stdout || "").trim();
  return stderr || stdout || `exit code ${result.status}`;
}

function firstLine(value) {
  return (value || "").split(/\r?\n/).find(Boolean) || "";
}

function printCheck(status, label, detail) {
  const suffix = detail ? ` - ${detail}` : "";
  console.log(`[${status}] ${label}${suffix}`);
}

function stringOption(args, name) {
  const value = args[name];
  return typeof value === "string" && value.trim() ? value.trim() : null;
}

function isHelp(value) {
  return value === "help" || value === "--help" || value === "-h";
}

function loadVersion() {
  try {
    return JSON.parse(readFileSync(new URL("../package.json", import.meta.url), "utf8")).version;
  } catch {
    return "0.0.0";
  }
}

main().catch((error) => {
  console.error(`Error: ${error.message}`);
  process.exitCode = 1;
});

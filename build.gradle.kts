plugins {
    id("com.android.application") version "8.12.0" apply false
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20" apply false
}

data class WorkspaceApp(
    val label: String,
    val packageName: String,
    val installableFromPlay: Boolean = true
)

data class AndroidE2eSuite(
    val className: String,
    val sourcePath: String,
    val artifactSubdirectory: String,
    val instrumentationArgs: Map<String, String> = emptyMap()
)

val googleWorkspaceApps = listOf(
    WorkspaceApp("Google Drive", "com.google.android.apps.docs"),
    WorkspaceApp("Gmail", "com.google.android.gm"),
    WorkspaceApp("Google Docs", "com.google.android.apps.docs.editors.docs"),
    WorkspaceApp("Google Sheets", "com.google.android.apps.docs.editors.sheets"),
    WorkspaceApp("Google Play Store", "com.android.vending", installableFromPlay = false)
)

fun org.gradle.api.Project.androidAdbPath(): String {
    val androidHome = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
        ?: error("ANDROID_HOME or ANDROID_SDK_ROOT must point to the Android SDK")
    return file("$androidHome/platform-tools/adb").absolutePath
}

fun org.gradle.api.Project.adbOutput(adb: String, vararg arguments: String): String {
    val output = providers.exec {
        commandLine(adb, *arguments)
        isIgnoreExitValue = true
    }
    val stdout = output.standardOutput.asText.get().trim()
    val stderr = output.standardError.asText.get().trim()
    return listOf(stdout, stderr).filter { it.isNotBlank() }.joinToString("\n")
}

fun org.gradle.api.Project.isPackageInstalled(adb: String, packageName: String): Boolean =
    adbOutput(adb, "shell", "pm", "path", packageName).contains("package:")

fun org.gradle.api.Project.localEnvValue(name: String): String? {
    val envFile = file(".env.local")
    if (!envFile.isFile) return null
    return envFile.readLines()
        .map { it.trim() }
        .firstOrNull { it.startsWith("$name=") }
        ?.substringAfter('=')
        ?.trim()
        ?.takeIf { it.isNotBlank() }
}

fun org.gradle.api.Project.androidTestMethodNames(sourcePath: String): List<String> {
    val text = file(sourcePath).readText()
    return Regex("@Test\\s+fun\\s+(\\w+)\\s*\\(")
        .findAll(text)
        .map { it.groupValues[1] }
        .toList()
        .ifEmpty { error("No @Test methods found in $sourcePath") }
}

fun org.gradle.api.Project.sanitizeArtifactName(value: String): String =
    value.replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-').ifBlank { "artifact" }
fun org.gradle.api.Project.shouldRecordE2eVideos(): Boolean =
    (System.getenv("DROIDLM_E2E_RECORD_VIDEO") ?: "true").toBooleanStrictOrNull() ?: true


fun org.gradle.api.Project.startScreenRecording(adb: String, deviceVideoPath: String): Process {
    adbOutput(adb, "shell", "rm", "-f", deviceVideoPath)
    return ProcessBuilder(adb, "shell", "screenrecord", "--time-limit", "180", deviceVideoPath)
        .redirectErrorStream(true)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .start()
}

fun org.gradle.api.Project.stopScreenRecording(adb: String, recorder: Process) {
    adbOutput(adb, "shell", "pkill", "-INT", "screenrecord")
    recorder.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
    if (recorder.isAlive) recorder.destroyForcibly()
    Thread.sleep(1500)
}

fun org.gradle.api.Project.pullRecordedVideo(adb: String, deviceVideoPath: String, hostVideo: java.io.File) {
    val pullOutput = adbOutput(adb, "pull", deviceVideoPath, hostVideo.absolutePath)
    if (pullOutput.isNotBlank()) println(pullOutput)
    if (!hostVideo.isFile || hostVideo.length() == 0L) {
        error("Expected recorded video at ${hostVideo.absolutePath}")
    }
    adbOutput(adb, "shell", "rm", "-f", deviceVideoPath)
}

fun org.gradle.api.Project.runInstrumentedSuiteWithVideos(adb: String, suite: AndroidE2eSuite) {
    val artifactDir = file("test-artifacts/e2e-videos/${suite.artifactSubdirectory}").apply { mkdirs() }
    val deviceVideoDir = "/sdcard/Documents/DroidLMTestRuns/videos"
    adbOutput(adb, "shell", "mkdir", "-p", deviceVideoDir)
    val failures = mutableListOf<String>()
    val recordVideo = shouldRecordE2eVideos()

    androidTestMethodNames(suite.sourcePath).forEach { methodName ->
        val selector = "${suite.className}#$methodName"
        val videoFileName = "${System.currentTimeMillis()}-${sanitizeArtifactName(methodName)}.mp4"
        val hostVideo = artifactDir.resolve(videoFileName)
        val deviceVideoPath = "$deviceVideoDir/$videoFileName"
        val recorder = if (recordVideo) {
            println("Recording $selector to ${hostVideo.relativeTo(projectDir)}")
            startScreenRecording(adb, deviceVideoPath).also { Thread.sleep(1000) }
        } else {
            println("Running $selector without video recording")
            null
        }
        val output = java.io.ByteArrayOutputStream()

        try {
            val args = mutableListOf("shell", "am", "instrument", "-w", "-r")
            suite.instrumentationArgs.forEach { (key, value) ->
                args += listOf("-e", key, value)
            }
            args += listOf("-e", "class", selector)
            args += "ai.droidlm.debug.test/androidx.test.runner.AndroidJUnitRunner"

            val result = exec {
                commandLine(adb, *args.toTypedArray())
                standardOutput = output
                errorOutput = output
                isIgnoreExitValue = true
            }
            val text = output.toString()
            print(text)
            if (result.exitValue != 0 || text.contains("FAILURES!!!")) {
                failures += selector
            }
        } finally {
            if (recordVideo && recorder != null) {
                runCatching { stopScreenRecording(adb, recorder) }
                runCatching { pullRecordedVideo(adb, deviceVideoPath, hostVideo) }
                    .onFailure { failures += "$selector (video capture failed: ${it.message})" }
            }
        }
    }

    if (failures.isNotEmpty()) {
        error("Android E2E failures: ${failures.joinToString(", ")}")
    }
}

fun org.gradle.api.Project.hostOutput(vararg arguments: String): String {
    val output = providers.exec {
        commandLine(*arguments)
        isIgnoreExitValue = true
    }
    val stdout = output.standardOutput.asText.get().trim()
    val stderr = output.standardError.asText.get().trim()
    return listOf(stdout, stderr).filter { it.isNotBlank() }.joinToString("\n")
}

fun org.gradle.api.Project.e2ePython(requireGrpc: Boolean = false): String {
    System.getenv("DROIDLM_E2E_PYTHON")?.takeIf { it.isNotBlank() }?.let { return it }
    if (requireGrpc) {
        fun grpcImportWorks(python: String): Boolean {
            val stdout = java.io.ByteArrayOutputStream()
            val stderr = java.io.ByteArrayOutputStream()
            return exec {
                commandLine(python, "-c", "import grpc, grpc_tools")
                isIgnoreExitValue = true
                standardOutput = stdout
                errorOutput = stderr
            }.exitValue == 0
        }

        if (grpcImportWorks("python3")) return "python3"

        val venvDir = java.io.File("/tmp/droidlm-e2e-python")
        val venvPython = java.io.File(venvDir, "bin/python")
        if (!venvPython.isFile) {
            println("Creating temporary DroidLM E2E Python environment at ${venvDir.absolutePath}")
            exec {
                commandLine("python3", "-m", "venv", venvDir.absolutePath)
            }
        }
        val grpcReady = grpcImportWorks(venvPython.absolutePath)
        if (!grpcReady) {
            println("Installing grpcio dependencies into ${venvDir.absolutePath}")
            exec {
                commandLine(
                    java.io.File(venvDir, "bin/pip").absolutePath,
                    "install",
                    "grpcio",
                    "grpcio-tools"
                )
            }
        }
        return venvPython.absolutePath
    }
    return "python3"
}

fun org.gradle.api.Project.ensureVirtualMicForE2e() {
    val pactl = providers.exec {
        commandLine("bash", "-lc", "command -v pactl")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim()
    if (pactl.isBlank()) error("pactl is required for mic-injection E2E tests")
    val sinkName = System.getenv("DROIDLM_E2E_PULSE_SINK") ?: "droidlm_e2e_mic"
    val sourceName = System.getenv("DROIDLM_E2E_PULSE_SOURCE") ?: "droidlm_e2e_mic_source"
    hostOutput("bash", "-lc", "pactl list short modules | awk '/droidlm_e2e_mic/ {print \$1}' | xargs -r -n1 pactl unload-module")

    hostOutput("pactl", "load-module", "module-null-sink", "sink_name=$sinkName", "sink_properties=device.description=DroidLM_E2E_Mic_Sink")
    hostOutput("pactl", "load-module", "module-remap-source", "master=$sinkName.monitor", "source_name=$sourceName", "source_properties=device.description=DroidLM_E2E_Mic_Source")
    hostOutput("pactl", "set-default-source", sourceName)
    hostOutput("bash", "-lc", "pactl list short modules | awk '/module-native-protocol-tcp/ {print \$1}' | xargs -r -n1 pactl unload-module")
    hostOutput("pactl", "load-module", "module-native-protocol-tcp", "auth-anonymous=1", "port=4713")
    val sources = hostOutput("pactl", "list", "short", "sources")
    if (!sources.contains(sourceName)) {
        error("Could not create $sourceName. pactl sources:\n$sources")
    }
}

fun org.gradle.api.Project.micInjectionMode(): String =
    (System.getenv("DROIDLM_E2E_MIC_INJECTION_MODE") ?: "grpc").lowercase()

fun org.gradle.api.Project.ensureEmulatorHostAudioForE2e(adb: String) {
    val mode = micInjectionMode()
    val useHostAudio = mode == "pulse"
    val ps = hostOutput("bash", "-lc", "ps -ef | grep '[e]mulator' || true")
    val connected = adbOutput(adb, "devices").lineSequence().any { it.endsWith("\tdevice") }
    if (ps.isBlank() && connected && useHostAudio) {
        adbOutput(adb, "emu", "avd", "hostmicoff")
    }

    val avdName = if (connected) {
        adbOutput(adb, "emu", "avd", "name")
            .lineSequence()
            .firstOrNull { it.isNotBlank() && !it.startsWith("OK") }
            ?: System.getenv("DROIDLM_E2E_AVD") ?: "pixel_6"
    } else {
        System.getenv("DROIDLM_E2E_AVD") ?: "pixel_6"
    }
    println("Starting emulator '$avdName' for $mode mic-injection E2E")
    adbOutput(adb, "emu", "kill")
    val stopDeadline = System.currentTimeMillis() + 30_000L
    while (System.currentTimeMillis() < stopDeadline) {
        val running = hostOutput("bash", "-lc", "ps -ef | grep '[e]mulator' || true")
        if (running.isBlank()) break
        Thread.sleep(1_000)
    }

    val androidHome = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
        ?: error("ANDROID_HOME or ANDROID_SDK_ROOT must point to the Android SDK")
    val logFile = file("test-artifacts/e2e-videos/hover-mic-audio/emulator-host-audio.log").apply { parentFile.mkdirs() }
    val runtimeDir = System.getenv("XDG_RUNTIME_DIR") ?: "/run/user/${hostOutput("id", "-u").trim()}"
    val pulseServer = System.getenv("DROIDLM_E2E_PULSE_SERVER") ?: "tcp:127.0.0.1:4713"
    val pulseSource = System.getenv("DROIDLM_E2E_PULSE_SOURCE") ?: "droidlm_e2e_mic_source"
    val pulseSink = System.getenv("DROIDLM_E2E_PULSE_SINK") ?: "droidlm_e2e_mic"
    val qemuPulseServer = System.getenv("DROIDLM_E2E_QEMU_PA_SERVER") ?: pulseServer.removePrefix("tcp:")
    val defaultAudioDriver = if (useHostAudio) "pa" else ""
    val audioDriver = System.getenv("DROIDLM_E2E_AUDIO_DRIVER") ?: defaultAudioDriver
    val grpcPort = System.getenv("DROIDLM_E2E_GRPC_PORT") ?: "8554"
    val emulatorPort = System.getenv("ANDROID_SERIAL")
        ?.takeIf { it.startsWith("emulator-") }
        ?.substringAfter("emulator-")
        ?.takeIf { value -> value.all(Char::isDigit) }
    val command = mutableListOf(
        "$androidHome/emulator/emulator",
        "-avd", avdName,
        "-no-window",
        "-no-snapshot",
        "-gpu", "swiftshader_indirect"
    )
    emulatorPort?.let { command += listOf("-port", it) }
    if (audioDriver.isNotBlank()) command += listOf("-audio", audioDriver)
    command += listOf("-grpc", grpcPort)
    if (useHostAudio) command += "-allow-host-audio"
    val emulatorProcess = ProcessBuilder(command)
    val emulatorEnvironment = emulatorProcess.environment()
    emulatorEnvironment["XDG_RUNTIME_DIR"] = runtimeDir
    emulatorEnvironment["PULSE_SERVER"] = pulseServer
    if (audioDriver.isNotBlank()) {
        emulatorEnvironment["QEMU_AUDIO_DRV"] = audioDriver
        emulatorEnvironment["QEMU_AUDIO_IN_DRV"] = audioDriver
        emulatorEnvironment["QEMU_AUDIO_OUT_DRV"] = audioDriver
    } else {
        emulatorEnvironment.remove("QEMU_AUDIO_DRV")
        emulatorEnvironment.remove("QEMU_AUDIO_IN_DRV")
        emulatorEnvironment.remove("QEMU_AUDIO_OUT_DRV")
    }
    if (useHostAudio) {
        emulatorEnvironment["QEMU_PA_SERVER"] = qemuPulseServer
        emulatorEnvironment["QEMU_PA_SOURCE"] = pulseSource
        emulatorEnvironment["QEMU_PA_SINK"] = pulseSink
    } else {
        emulatorEnvironment.remove("QEMU_PA_SERVER")
        emulatorEnvironment.remove("QEMU_PA_SOURCE")
        emulatorEnvironment.remove("QEMU_PA_SINK")
    }
    emulatorProcess
        .redirectErrorStream(true)
        .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
        .start()

    exec { commandLine(adb, "wait-for-device") }
    val deadline = System.currentTimeMillis() + 180_000L
    while (System.currentTimeMillis() < deadline) {
        val booted = adbOutput(adb, "shell", "getprop", "sys.boot_completed")
        if (booted.trim() == "1") {
            if (useHostAudio) adbOutput(adb, "emu", "avd", "hostmicon")
            Thread.sleep(3_000)
            return
        }
        Thread.sleep(2_000)
    }
    error("Timed out waiting for emulator to boot for $mode mic injection. See ${logFile.relativeTo(projectDir)}")
}

fun org.gradle.api.Project.generateOpenAiTts(text: String, outputFile: java.io.File) {
    exec {
        commandLine(e2ePython(), "scripts/generate-openai-tts.py", "--text", text, "--output", outputFile.absolutePath)
    }
}

fun org.gradle.api.Project.copyBundledE2eAudioIfPresent(assetName: String, outputFile: java.io.File): Boolean {
    val bundled = file("app/src/androidTest/assets/$assetName")
    if (!bundled.isFile) return false
    outputFile.parentFile.mkdirs()
    bundled.copyTo(outputFile, overwrite = true)
    return outputFile.length() > 1024L
}

fun org.gradle.api.Project.prepareOpenGoogleDriveE2eWav(outputFile: java.io.File) {
    if (copyBundledE2eAudioIfPresent("droidlm_open_google_drive.wav", outputFile)) return
    generateOpenAiTts("Open Google Drive", outputFile)
}

fun org.gradle.api.Project.convertAudioToPcm(inputFile: java.io.File, outputFile: java.io.File) {
    outputFile.parentFile.mkdirs()
    exec {
        commandLine(
            "ffmpeg",
            "-y",
            "-i", inputFile.absolutePath,
            "-ac", "1",
            "-ar", "16000",
            "-f", "s16le",
            outputFile.absolutePath
        )
    }
}

fun org.gradle.api.Project.convertPcm16MonoToWav(inputFile: java.io.File, outputFile: java.io.File, gainDb: Int = 0) {
    outputFile.parentFile.mkdirs()
    exec {
        val command = mutableListOf(
            "ffmpeg",
            "-y",
            "-f", "s16le",
            "-ar", "16000",
            "-ac", "1",
            "-i", inputFile.absolutePath
        )
        if (gainDb != 0) command += listOf("-filter:a", "volume=${gainDb}dB")
        command += listOf(
            "-ac", "1",
            "-ar", "16000",
            "-sample_fmt", "s16",
            outputFile.absolutePath
        )
        commandLine(command)
    }
}

fun org.gradle.api.Project.convertAudioToWav(inputFile: java.io.File, outputFile: java.io.File) {
    outputFile.parentFile.mkdirs()
    exec {
        commandLine(
            "ffmpeg",
            "-y",
            "-i", inputFile.absolutePath,
            "-ac", "1",
            "-ar", "16000",
            "-sample_fmt", "s16",
            outputFile.absolutePath
        )
    }
}

fun org.gradle.api.Project.convertAudioToDelayedWav(inputFile: java.io.File, outputFile: java.io.File, delayMs: Long) {
    outputFile.parentFile.mkdirs()
    exec {
        commandLine(
            "ffmpeg",
            "-y",
            "-i", inputFile.absolutePath,
            "-ac", "1",
            "-ar", "16000",
            "-sample_fmt", "s16",
            "-af", "adelay=${delayMs}:all=1",
            outputFile.absolutePath
        )
    }
}

fun org.gradle.api.Project.injectAudioIntoEmulatorMic(audioFile: java.io.File) {
    val grpcHost = System.getenv("DROIDLM_E2E_GRPC_HOST") ?: "127.0.0.1"
    val grpcPort = System.getenv("DROIDLM_E2E_GRPC_PORT") ?: "8554"
    val grpcChunkMs = System.getenv("DROIDLM_E2E_GRPC_CHUNK_MS") ?: "20"
    val command = mutableListOf(
        e2ePython(requireGrpc = true),
        "scripts/inject-emulator-audio.py",
        "--wav", audioFile.absolutePath,
        "--host", grpcHost,
        "--port", grpcPort,
        "--generated-dir", file("build/emulator-grpc-python").absolutePath,
        "--chunk-ms", grpcChunkMs
    )
    if (System.getenv("DROIDLM_E2E_GRPC_NO_REALTIME") == "true") command += "--no-realtime"
    val result = exec {
        commandLine(command)
        isIgnoreExitValue = true
    }
    if (result.exitValue != 0) error("Emulator gRPC audio injection failed for ${audioFile.absolutePath}")
}

fun org.gradle.api.Project.checkEmulatorGrpcStatus(audioFile: java.io.File) {
    val grpcHost = System.getenv("DROIDLM_E2E_GRPC_HOST") ?: "127.0.0.1"
    val grpcPort = System.getenv("DROIDLM_E2E_GRPC_PORT") ?: "8554"
    val result = exec {
        commandLine(
            e2ePython(requireGrpc = true),
            "scripts/inject-emulator-audio.py",
            "--wav", audioFile.absolutePath,
            "--host", grpcHost,
            "--port", grpcPort,
            "--generated-dir", file("build/emulator-grpc-python").absolutePath,
            "--status-only"
        )
        isIgnoreExitValue = true
    }
    if (result.exitValue != 0) error("Emulator gRPC status smoke check failed")
}

fun org.gradle.api.Project.runMicInjectedInstrumentedTest(
    adb: String,
    suite: AndroidE2eSuite,
    methodName: String,
    audioFile: java.io.File,
    speechDelayMs: Long
) {
    val artifactDir = file("test-artifacts/e2e-videos/${suite.artifactSubdirectory}").apply { mkdirs() }
    val deviceVideoDir = "/sdcard/Documents/DroidLMTestRuns/videos"
    val markerPath = "/sdcard/Documents/DroidLMTestRuns/mic-audio-ready-${System.currentTimeMillis()}.marker"
    adbOutput(adb, "shell", "mkdir", "-p", deviceVideoDir)
    adbOutput(adb, "shell", "rm", "-f", markerPath)
    val recordVideo = shouldRecordE2eVideos()

    val selector = "${suite.className}#$methodName"
    val videoFileName = "${System.currentTimeMillis()}-${sanitizeArtifactName(methodName)}.mp4"
    val hostVideo = artifactDir.resolve(videoFileName)
    val deviceVideoPath = "$deviceVideoDir/$videoFileName"
    val recorder = if (recordVideo) {
        println("Recording $selector with mic injection to ${hostVideo.relativeTo(projectDir)}")
        startScreenRecording(adb, deviceVideoPath).also { Thread.sleep(1000) }
    } else {
        println("Running $selector with mic injection without video recording")
        null
    }

    val args = mutableListOf("shell", "am", "instrument", "-w", "-r")
    suite.instrumentationArgs.forEach { (key, value) -> args += listOf("-e", key, value) }
    args += listOf("-e", "micAudioMarkerPath", markerPath)
    args += listOf("-e", "class", selector)
    args += "ai.droidlm.debug.test/androidx.test.runner.AndroidJUnitRunner"

    val instrumentation = ProcessBuilder(adb, *args.toTypedArray()).redirectErrorStream(true).start()
    val output = java.io.ByteArrayOutputStream()
    val reader = Thread {
        instrumentation.inputStream.use { input -> input.copyTo(output) }
    }.apply { start() }

    var played = false
    val instrumentationTimeoutMs = System.getenv("DROIDLM_E2E_MIC_INJECTION_TIMEOUT_MS")?.toLongOrNull() ?: 60_000L
    val deadline = System.currentTimeMillis() + instrumentationTimeoutMs
    while (instrumentation.isAlive && System.currentTimeMillis() < deadline) {
        val marker = adbOutput(adb, "shell", "if", "[", "-f", markerPath, "];", "then", "echo", "ready;", "fi")
        if (!played && marker.contains("ready")) {
            Thread.sleep(speechDelayMs)
            injectAudioIntoEmulatorMic(audioFile)
            played = true
        }
        Thread.sleep(100)
    }
    if (instrumentation.isAlive) instrumentation.destroyForcibly()
    reader.join(5000)
    val text = output.toString()
    print(text)

    if (recordVideo && recorder != null) {
        runCatching { stopScreenRecording(adb, recorder) }
        runCatching { pullRecordedVideo(adb, deviceVideoPath, hostVideo) }
    }
    adbOutput(adb, "shell", "rm", "-f", markerPath)

    if (!played) error("Mic audio was never injected because marker was not created by $selector")
    if (instrumentation.exitValue() != 0 || text.contains("FAILURES!!!")) {
        error("Android mic-injection E2E failure: $selector")
    }
}

fun org.gradle.api.Project.runSupportLogMicRegressionE2e(adb: String) {
    val supportLogPcm = file("app/src/androidTest/assets/private-vosk-fixture.pcm")
    require(supportLogPcm.isFile) {
        "Missing May 10 support-log PCM fixture: ${supportLogPcm.relativeTo(projectDir)}"
    }
    val supportLogWav = file("build/e2e-audio/sanitized-short-audio-regression.wav")
    if (micInjectionMode() == "pulse") ensureVirtualMicForE2e()
    ensureEmulatorHostAudioForE2e(adb)
    convertPcm16MonoToWav(supportLogPcm, supportLogWav, gainDb = 30)
    checkEmulatorGrpcStatus(supportLogWav)
    adbOutput(adb, "uninstall", "ai.droidlm.debug")
    adbOutput(adb, "uninstall", "ai.droidlm.debug.test")
    adbOutput(adb, "install", "-r", "app/build/outputs/apk/debug/app-debug.apk")
    adbOutput(adb, "install", "-r", "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk")
    runMicInjectedInstrumentedTest(
        adb = adb,
        suite = AndroidE2eSuite(
            className = "ai.droidlm.e2e.DroidLmHoverMicAudioE2ETest",
            sourcePath = "app/src/androidTest/kotlin/ai/droidlm/e2e/DroidLmHoverMicAudioE2ETest.kt",
            artifactSubdirectory = "support-log-mic-regression",
            instrumentationArgs = mapOf("preferOfflineSpeechRecognition" to "true")
        ),
        methodName = "hoverRecordSupportLogAudioReproducesAmbiguousOpenRegression",
        audioFile = supportLogWav,
        speechDelayMs = 250L
    )
}

tasks.register("ensureDriveForE2e") {
    group = "verification"
    description = "Ensures com.google.android.apps.docs exists on the connected emulator, installing the test stub only when real Drive is missing."
    dependsOn(":driveStub:assembleDebug")

    doLast {
        val adb = project.androidAdbPath()
        if (project.isPackageInstalled(adb, "com.google.android.apps.docs")) {
            println("Google Drive package already installed; using existing com.google.android.apps.docs")
        } else {
            val stubApk = project(":driveStub").layout.buildDirectory
                .file("outputs/apk/debug/driveStub-debug.apk")
                .get()
                .asFile
                .absolutePath
            val installOutput = project.adbOutput(adb, "install", "-r", stubApk)
            if (installOutput.isNotBlank()) println(installOutput)
        }
    }
}

tasks.register("workspaceEmulatorReport") {
    group = "verification"
    description = "Reports Google Workspace app availability and account state on the connected emulator."

    doLast {
        val adb = project.androidAdbPath()
        println(project.adbOutput(adb, "devices"))

        val missing = mutableListOf<String>()
        googleWorkspaceApps.forEach { app ->
            if (!project.isPackageInstalled(adb, app.packageName)) {
                missing += app.label
                println("${app.label}: missing (${app.packageName})")
                return@forEach
            }

            val launcher = project.adbOutput(adb, "shell", "cmd", "package", "resolve-activity", "--brief", app.packageName)
                .lineSequence()
                .filter { it.isNotBlank() && !it.startsWith("priority=") }
                .lastOrNull()
            val launcherStatus = if (launcher.isNullOrBlank() || launcher.contains("No activity found")) {
                "installed; no launcher resolved"
            } else {
                "installed; launcher $launcher"
            }
            println("${app.label}: $launcherStatus")
        }

        val accountListOutput = project.adbOutput(adb, "shell", "cmd", "account", "list")
        val accountDumpOutput = if (accountListOutput.isBlank()) {
            project.adbOutput(adb, "shell", "dumpsys", "account")
        } else {
            ""
        }
        val accountCount = maxOf(
            Regex("Account \\{").findAll(accountListOutput).count(),
            Regex("Account \\{name=").findAll(accountDumpOutput).count()
        )
        val accountStatus = when {
            accountCount > 0 -> "$accountCount account(s) reported; names hidden"
            accountListOutput.isBlank() && accountDumpOutput.isBlank() -> "no accounts reported"
            else -> "account command returned data; names hidden"
        }
        println("Android accounts: $accountStatus")

        if (missing.isNotEmpty()) {
            println("Missing Google Workspace apps: ${missing.joinToString(", ")}")
            println("Run `./gradlew openWorkspaceInstallPages` to open the first missing app install URL.")
        }
    }
}

tasks.register("checkWorkspaceAppsInstalled") {
    group = "verification"
    description = "Fails when any expected Google Workspace app is missing from the connected emulator."

    doLast {
        val adb = project.androidAdbPath()
        val missing = googleWorkspaceApps.filterNot { project.isPackageInstalled(adb, it.packageName) }
        if (missing.isNotEmpty()) {
            error("Missing Google Workspace apps: ${missing.joinToString(", ") { it.label }}")
        }
        println("All expected Google Workspace apps are installed.")
    }
}

tasks.register("openWorkspaceInstallPages") {
    group = "verification"
    description = "Opens an install URL for the first missing installable Google Workspace app."

    doLast {
        val adb = project.androidAdbPath()
        val missing = googleWorkspaceApps.filter { app ->
            app.installableFromPlay && !project.isPackageInstalled(adb, app.packageName)
        }
        val app = missing.firstOrNull()
        if (app == null) {
            println("No installable Google Workspace apps are missing.")
            return@doLast
        }

        println("Opening Play Store page for ${app.label} (${app.packageName})")
        val marketResult = project.adbOutput(
            adb,
            "shell",
            "am",
            "start",
            "-a",
            "android.intent.action.VIEW",
            "-d",
            "market://details?id=${app.packageName}"
        )
        if (marketResult.isNotBlank()) println(marketResult)

        if (marketResult.contains("Error: Activity not started")) {
            println("market:// did not resolve; trying the HTTPS Play Store URL.")
            val webResult = project.adbOutput(
                adb,
                "shell",
                "am",
                "start",
                "-a",
                "android.intent.action.VIEW",
                "-d",
                "https://play.google.com/store/apps/details?id=${app.packageName}"
            )
            if (webResult.isNotBlank()) println(webResult)
            if (webResult.contains("Error: Activity not started")) {
                println("No Play Store or browser handler resolved. Use a Google Play emulator image or sideload ${app.label} manually.")
                return@doLast
            }

        }

        println("Finish installation in the emulator UI, then rerun `./gradlew workspaceEmulatorReport`.")
    }
}

tasks.register("prepareWorkspaceApps", org.gradle.api.tasks.Exec::class) {
    group = "verification"
    description = "Installs Workspace apps from local APKs and optionally signs the connected emulator into a Google account."
    environment("ADB", project.androidAdbPath())
    commandLine("bash", "scripts/prepare-google-workspace-emulator.sh")
}

tasks.register("installWorkspaceFixtures", org.gradle.api.tasks.Exec::class) {
    group = "verification"
    description = "Pushes public document, image, and spreadsheet fixtures to the connected emulator."
    environment("ADB", project.androidAdbPath())
    commandLine("bash", "scripts/install-workspace-fixtures.sh", "install")
    mustRunAfter("prepareWorkspaceApps")
}

tasks.register("verifyWorkspaceFixtures", org.gradle.api.tasks.Exec::class) {
    group = "verification"
    description = "Opens every Workspace fixture on the connected emulator through Android intents."
    environment("ADB", project.androidAdbPath())
    commandLine("bash", "scripts/install-workspace-fixtures.sh", "verify")
    mustRunAfter("installWorkspaceFixtures")
}

tasks.register("prepareWorkspaceEmulator") {
    group = "verification"
    description = "Installs Workspace apps and fixture files on the connected emulator."
    dependsOn("prepareWorkspaceApps", "installWorkspaceFixtures")
}

tasks.register("verifyWorkspaceEmulator") {
    group = "verification"
    description = "Verifies Workspace apps, account availability, and fixture file opening on the connected emulator."
    dependsOn("checkWorkspaceAppsInstalled", "workspaceEmulatorReport", "verifyWorkspaceFixtures")
}

tasks.register("connectedWorkspaceFileOpsE2e") {
    group = "verification"
    description = "Runs hover-widget Workspace file operation E2E tests against the connected emulator."
    dependsOn("prepareWorkspaceEmulator", ":app:installDebug", ":app:installDebugAndroidTest")

    doLast {
        val adb = project.androidAdbPath()
        val instrumentationArgs = mutableMapOf(
            "workspaceFileOpsE2e" to "true"
        )
        instrumentationArgs["openAiApiKey"] = project.localEnvValue("OPENAI_API_KEY")
            ?.takeIf { it.startsWith("sk-") }
            ?: "sk-e2e-placeholder"
        runInstrumentedSuiteWithVideos(
            adb,
            AndroidE2eSuite(
                className = "ai.droidlm.e2e.DroidLmWorkspaceFileOpsE2ETest",
                sourcePath = "app/src/androidTest/kotlin/ai/droidlm/e2e/DroidLmWorkspaceFileOpsE2ETest.kt",
                artifactSubdirectory = "workspace-file-ops",
                instrumentationArgs = instrumentationArgs
            )
        )
    }
}

tasks.register("connectedWorkspaceFileOpsReleaseE2e") {
    group = "verification"
    description = "Runs deterministic Workspace file operation E2E tests using bundled Docs and Sheets stubs."
    dependsOn("installWorkspaceFixtures", ":docsStub:assembleDebug", ":sheetsStub:assembleDebug", ":app:assembleDebug", ":app:assembleDebugAndroidTest")

    doLast {
        val adb = project.androidAdbPath()
        val docsStubApk = project(":docsStub").layout.buildDirectory.file("outputs/apk/debug/docsStub-debug.apk").get().asFile.absolutePath
        val sheetsStubApk = project(":sheetsStub").layout.buildDirectory.file("outputs/apk/debug/sheetsStub-debug.apk").get().asFile.absolutePath
        project.adbOutput(adb, "uninstall", "com.google.android.apps.docs.editors.docs")
        project.adbOutput(adb, "uninstall", "com.google.android.apps.docs.editors.sheets")
        project.adbOutput(adb, "install", "-r", docsStubApk)
        project.adbOutput(adb, "install", "-r", sheetsStubApk)
        project.adbOutput(adb, "uninstall", "ai.droidlm.debug")
        project.adbOutput(adb, "uninstall", "ai.droidlm.debug.test")
        project.adbOutput(adb, "install", "-r", "app/build/outputs/apk/debug/app-debug.apk")
        project.adbOutput(adb, "install", "-r", "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk")
        runInstrumentedSuiteWithVideos(
            adb,
            AndroidE2eSuite(
                className = "ai.droidlm.e2e.DroidLmWorkspaceFileOpsReleaseE2ETest",
                sourcePath = "app/src/androidTest/kotlin/ai/droidlm/e2e/DroidLmWorkspaceFileOpsReleaseE2ETest.kt",
                artifactSubdirectory = "workspace-file-ops-release"
            )
        )
    }
}

tasks.register("connectedDocsAgentLoopReleaseE2e") {
    group = "verification"
    description = "Runs deterministic Google Docs AGENT_LOOP E2E tests using the bundled Docs stub."
    dependsOn("installWorkspaceFixtures", ":docsStub:assembleDebug", ":app:assembleDebug", ":app:assembleDebugAndroidTest")

    doLast {
        val adb = project.androidAdbPath()
        val docsStubApk = project(":docsStub").layout.buildDirectory.file("outputs/apk/debug/docsStub-debug.apk").get().asFile.absolutePath
        project.adbOutput(adb, "uninstall", "com.google.android.apps.docs.editors.docs")
        project.adbOutput(adb, "install", "-r", docsStubApk)
        project.adbOutput(adb, "uninstall", "ai.droidlm.debug")
        project.adbOutput(adb, "uninstall", "ai.droidlm.debug.test")
        project.adbOutput(adb, "install", "-r", "app/build/outputs/apk/debug/app-debug.apk")
        project.adbOutput(adb, "install", "-r", "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk")
        project.adbOutput(adb, "shell", "appops", "set", "ai.droidlm.debug", "MANAGE_EXTERNAL_STORAGE", "allow")
        project.adbOutput(adb, "shell", "cmd", "appops", "set", "ai.droidlm.debug", "MANAGE_EXTERNAL_STORAGE", "allow")
        project.adbOutput(adb, "shell", "settings", "put", "secure", "enabled_accessibility_services", "ai.droidlm.debug/ai.droidlm.portal.DroidLMAccessibilityService")
        project.adbOutput(adb, "shell", "settings", "put", "secure", "accessibility_enabled", "1")
        runInstrumentedSuiteWithVideos(
            adb,
            AndroidE2eSuite(
                className = "ai.droidlm.e2e.DroidLmDocsAgentLoopReleaseE2ETest",
                sourcePath = "app/src/androidTest/kotlin/ai/droidlm/e2e/DroidLmDocsAgentLoopReleaseE2ETest.kt",
                artifactSubdirectory = "docs-agent-loop-release",
                instrumentationArgs = mapOf("docsAgentLoopReleaseE2e" to "true")
            )
        )
    }
}

tasks.register("connectedDocsAgentLoopStressReleaseE2e") {
    group = "verification"
    description = "Runs stress-oriented Google Docs AGENT_LOOP E2E tests to probe current system limits."
    dependsOn("installWorkspaceFixtures", ":docsStub:assembleDebug", ":app:assembleDebug", ":app:assembleDebugAndroidTest")

    doLast {
        val adb = project.androidAdbPath()
        val docsStubApk = project(":docsStub").layout.buildDirectory.file("outputs/apk/debug/docsStub-debug.apk").get().asFile.absolutePath
        project.adbOutput(adb, "uninstall", "com.google.android.apps.docs.editors.docs")
        project.adbOutput(adb, "install", "-r", docsStubApk)
        project.adbOutput(adb, "uninstall", "ai.droidlm.debug")
        project.adbOutput(adb, "uninstall", "ai.droidlm.debug.test")
        project.adbOutput(adb, "install", "-r", "app/build/outputs/apk/debug/app-debug.apk")
        project.adbOutput(adb, "install", "-r", "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk")
        project.adbOutput(adb, "shell", "appops", "set", "ai.droidlm.debug", "MANAGE_EXTERNAL_STORAGE", "allow")
        project.adbOutput(adb, "shell", "cmd", "appops", "set", "ai.droidlm.debug", "MANAGE_EXTERNAL_STORAGE", "allow")
        project.adbOutput(adb, "shell", "settings", "put", "secure", "enabled_accessibility_services", "ai.droidlm.debug/ai.droidlm.portal.DroidLMAccessibilityService")
        project.adbOutput(adb, "shell", "settings", "put", "secure", "accessibility_enabled", "1")
        runInstrumentedSuiteWithVideos(
            adb,
            AndroidE2eSuite(
                className = "ai.droidlm.e2e.DroidLmDocsAgentLoopStressReleaseE2ETest",
                sourcePath = "app/src/androidTest/kotlin/ai/droidlm/e2e/DroidLmDocsAgentLoopStressReleaseE2ETest.kt",
                artifactSubdirectory = "docs-agent-loop-stress-release",
                instrumentationArgs = mapOf("docsAgentLoopStressReleaseE2e" to "true")
            )
        )
    }
}

gradle.projectsEvaluated {
    tasks.findByPath(":app:connectedDebugAndroidTest")?.mustRunAfter(tasks.findByPath(":ensureDriveForE2e"))
}

tasks.register("connectedDebugInstallUpgradeE2e", org.gradle.api.tasks.Exec::class) {
    group = "verification"
    description = "Runs local debug APK clean-install and upgrade checks on a connected emulator."
    dependsOn(":app:assembleDebug")
    environment("DROIDLM_INSTALL_E2E_LATEST_APK", layout.projectDirectory.file("app/build/outputs/apk/debug/app-debug.apk").asFile.absolutePath)
    environment("DROIDLM_INSTALL_E2E_LATEST_TAG", "local-debug")
    commandLine("bash", "scripts/debug-install-upgrade-e2e.sh")
}

tasks.register("connectedVoiceE2e") {
    group = "verification"
    description = "Runs DroidLM emulator voice invocation E2E tests. Requires a running emulator."
    dependsOn("ensureDriveForE2e", ":app:assembleDebug", ":app:assembleDebugAndroidTest")

    doLast {
        val adbPath = project.androidAdbPath()
        project.adbOutput(adbPath, "uninstall", "ai.droidlm.debug")
        project.adbOutput(adbPath, "uninstall", "ai.droidlm.debug.test")
        project.adbOutput(adbPath, "install", "-r", "app/build/outputs/apk/debug/app-debug.apk")
        project.adbOutput(adbPath, "install", "-r", "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk")
        runInstrumentedSuiteWithVideos(
            adbPath,
            AndroidE2eSuite(
                className = "ai.droidlm.e2e.DroidLmOverlayRecordPermissionE2ETest",
                sourcePath = "app/src/androidTest/kotlin/ai/droidlm/e2e/DroidLmOverlayRecordPermissionE2ETest.kt",
                artifactSubdirectory = "overlay-record-permission"
            )
        )
        runInstrumentedSuiteWithVideos(
            adbPath,
            AndroidE2eSuite(
                className = "ai.droidlm.e2e.DroidLmDriveVoiceInvocationE2ETest",
                sourcePath = "app/src/androidTest/kotlin/ai/droidlm/e2e/DroidLmDriveVoiceInvocationE2ETest.kt",
                artifactSubdirectory = "drive-voice"
            )
        )
    }
}

tasks.register("connectedVoskOfflineE2e") {
    group = "verification"
    description = "Runs offline Vosk and shared support-log transcription instrumentation tests."
    dependsOn(":app:assembleDebug", ":app:assembleDebugAndroidTest")

    doLast {
        val adbPath = project.androidAdbPath()
        project.adbOutput(adbPath, "uninstall", "ai.droidlm.debug")
        project.adbOutput(adbPath, "uninstall", "ai.droidlm.debug.test")
        project.adbOutput(adbPath, "install", "-r", "app/build/outputs/apk/debug/app-debug.apk")
        project.adbOutput(adbPath, "install", "-r", "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk")
        runInstrumentedSuiteWithVideos(
            adbPath,
            AndroidE2eSuite(
                className = "ai.droidlm.e2e.DroidLmVoskOfflineE2ETest",
                sourcePath = "app/src/androidTest/kotlin/ai/droidlm/e2e/DroidLmVoskOfflineE2ETest.kt",
                artifactSubdirectory = "vosk-offline"
            )
        )
    }
}

tasks.register("connectedDebugLogUploadE2e") {
    group = "verification"
    description = "Runs hidden debug log upload E2E tests against the connected emulator."
    dependsOn(":app:assembleDebug", ":app:assembleDebugAndroidTest")

    doLast {
        val adbPath = project.androidAdbPath()
        val instrumentationArgs = mutableMapOf("debugLogUploadE2e" to "true")
        val uploadUrl = System.getenv("DROIDLM_E2E_DEBUG_LOG_UPLOAD_URL")
            ?: System.getenv("DROIDLM_E2E_DEBUG_LOG_RELAY_URL")
            ?: System.getenv("DROIDLM_E2E_RELAY_URL")
        uploadUrl?.takeIf { it.isNotBlank() }?.let { instrumentationArgs["debugLogUploadUrl"] = it }
        project.adbOutput(adbPath, "uninstall", "ai.droidlm.debug.test")
        project.adbOutput(adbPath, "uninstall", "ai.droidlm.debug")
        project.adbOutput(adbPath, "install", "-r", "app/build/outputs/apk/debug/app-debug.apk")
        project.adbOutput(adbPath, "install", "-r", "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk")
        runInstrumentedSuiteWithVideos(
            adbPath,
            AndroidE2eSuite(
                className = "ai.droidlm.e2e.DroidLmDebugLogUploadE2ETest",
                sourcePath = "app/src/androidTest/kotlin/ai/droidlm/e2e/DroidLmDebugLogUploadE2ETest.kt",
                artifactSubdirectory = "debug-log-upload",
                instrumentationArgs = instrumentationArgs
            )
        )
    }
}


tasks.register("connectedActionKnownIssuesE2e") {
    group = "verification"
    description = "Runs E2E probes for action semantics and regression-sensitive executor behavior. Requires a running emulator."
    dependsOn(":app:installDebug", ":app:installDebugAndroidTest")

    doLast {
        val adbPath = project.androidAdbPath()
        runInstrumentedSuiteWithVideos(
            adbPath,
            AndroidE2eSuite(
                className = "ai.droidlm.e2e.DroidLmActionKnownIssuesE2ETest",
                sourcePath = "app/src/androidTest/kotlin/ai/droidlm/e2e/DroidLmActionKnownIssuesE2ETest.kt",
                artifactSubdirectory = "action-known-issues",
                instrumentationArgs = mapOf("actionKnownIssuesE2e" to "true")
            )
        )
    }
}

tasks.register("connectedHoverMicAudioE2e") {
    group = "verification"
    description = "Runs hover-widget E2E with OpenAI-generated speech injected through the emulator microphone."
    dependsOn(":driveStub:assembleDebug", ":app:assembleDebug", ":app:assembleDebugAndroidTest")

    doLast {
        val adbPath = project.androidAdbPath()
        if (project.micInjectionMode() == "pulse") project.ensureVirtualMicForE2e()
        project.ensureEmulatorHostAudioForE2e(adbPath)
        if (!project.isPackageInstalled(adbPath, "com.google.android.apps.docs")) {
            val stubApk = project(":driveStub").layout.buildDirectory.file("outputs/apk/debug/driveStub-debug.apk").get().asFile.absolutePath
            project.adbOutput(adbPath, "install", "-r", stubApk)
        }
        val sourceAudioFile = file("build/e2e-audio/open-google-drive.wav")
        val audioFile = file("build/e2e-audio/open-google-drive-hover-16k-mono.wav")
        project.prepareOpenGoogleDriveE2eWav(sourceAudioFile)
        project.convertAudioToWav(sourceAudioFile, audioFile)
        project.checkEmulatorGrpcStatus(audioFile)
        project.adbOutput(adbPath, "uninstall", "ai.droidlm.debug")
        project.adbOutput(adbPath, "uninstall", "ai.droidlm.debug.test")
        project.adbOutput(adbPath, "install", "-r", "app/build/outputs/apk/debug/app-debug.apk")
        project.adbOutput(adbPath, "install", "-r", "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk")
        val instrumentationArgs = mutableMapOf("preferOfflineSpeechRecognition" to "true")
        project.localEnvValue("OPENAI_API_KEY")?.let { key ->
            instrumentationArgs["openAiApiKey"] = key
        }
        project.runMicInjectedInstrumentedTest(
            adb = adbPath,
            suite = AndroidE2eSuite(
                className = "ai.droidlm.e2e.DroidLmHoverMicAudioE2ETest",
                sourcePath = "app/src/androidTest/kotlin/ai/droidlm/e2e/DroidLmHoverMicAudioE2ETest.kt",
                artifactSubdirectory = "hover-mic-audio",
                instrumentationArgs = instrumentationArgs
            ),
            methodName = "hoverRecordCapturesInjectedOpenGoogleDriveAudio",
            audioFile = audioFile,
            speechDelayMs = 250L
        )
    }
}

tasks.register("connectedHoverMicCaptureRegressionE2e") {
    group = "verification"
    description = "Runs hover-widget mic capture diagnostics for the Vosk live-capture starvation regression."
    dependsOn(":driveStub:assembleDebug", ":app:assembleDebug", ":app:assembleDebugAndroidTest")

    doLast {
        val adbPath = project.androidAdbPath()
        if (project.micInjectionMode() == "pulse") project.ensureVirtualMicForE2e()
        project.ensureEmulatorHostAudioForE2e(adbPath)
        if (!project.isPackageInstalled(adbPath, "com.google.android.apps.docs")) {
            val stubApk = project(":driveStub").layout.buildDirectory.file("outputs/apk/debug/driveStub-debug.apk").get().asFile.absolutePath
            project.adbOutput(adbPath, "install", "-r", stubApk)
        }
        val sourceAudioFile = file("build/e2e-audio/open-google-drive.wav")
        val audioFile = file("build/e2e-audio/open-google-drive-hover-16k-mono.wav")
        project.prepareOpenGoogleDriveE2eWav(sourceAudioFile)
        project.convertAudioToWav(sourceAudioFile, audioFile)
        project.checkEmulatorGrpcStatus(audioFile)
        project.adbOutput(adbPath, "uninstall", "ai.droidlm.debug")
        project.adbOutput(adbPath, "uninstall", "ai.droidlm.debug.test")
        project.adbOutput(adbPath, "install", "-r", "app/build/outputs/apk/debug/app-debug.apk")
        project.adbOutput(adbPath, "install", "-r", "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk")
        val instrumentationArgs = mutableMapOf("preferOfflineSpeechRecognition" to "true")
        mapOf(
            "DROIDLM_E2E_CAPTURE_RECORD_HOLD_MS" to "captureRecordHoldMs",
            "DROIDLM_E2E_CAPTURE_INJECT_BEFORE_LISTENING" to "captureInjectBeforeListening",
            "DROIDLM_E2E_CAPTURE_ASSERT_METRICS" to "captureAssertMetrics",
            "DROIDLM_E2E_CAPTURE_MIN_AUDIO_DURATION_MS" to "captureMinAudioDurationMs",
            "DROIDLM_E2E_CAPTURE_MIN_EFFICIENCY" to "captureMinEfficiency",
            "DROIDLM_E2E_CAPTURE_MAX_READ_GAP_MS" to "captureMaxReadGapMs",
            "DROIDLM_E2E_CAPTURE_MAX_COMPLETION_LATENCY_MS" to "captureMaxCompletionLatencyMs",
            "DROIDLM_E2E_CAPTURE_CPU_STRESS_THREADS" to "captureCpuStressThreads",
            "DROIDLM_E2E_CAPTURE_MEMORY_PRESSURE_MB" to "captureMemoryPressureMb"
        ).forEach { (envName, argName) ->
            System.getenv(envName)?.takeIf { it.isNotBlank() }?.let { value -> instrumentationArgs[argName] = value }
        }
        val speechDelayMs = System.getenv("DROIDLM_E2E_CAPTURE_SPEECH_DELAY_MS")?.toLongOrNull() ?: 250L
        project.runMicInjectedInstrumentedTest(
            adb = adbPath,
            suite = AndroidE2eSuite(
                className = "ai.droidlm.e2e.DroidLmHoverMicAudioE2ETest",
                sourcePath = "app/src/androidTest/kotlin/ai/droidlm/e2e/DroidLmHoverMicAudioE2ETest.kt",
                artifactSubdirectory = "hover-mic-capture-regression",
                instrumentationArgs = instrumentationArgs
            ),
            methodName = "hoverRecordOpenGoogleDriveAudioCapturesEnoughPcm",
            audioFile = audioFile,
            speechDelayMs = speechDelayMs
        )
    }
}

tasks.register("connectedSupportLogMicRegressionE2e") {
    group = "verification"
    description = "Replays the May 10 support-log audio through emulator mic injection and asserts the ambiguous-open regression path."
    dependsOn(":app:assembleDebug", ":app:assembleDebugAndroidTest")

    doLast {
        val adbPath = project.androidAdbPath()
        project.runSupportLogMicRegressionE2e(adbPath)
    }
}

tasks.register("connectedEmulatorMicProbeE2e") {
    group = "verification"
    description = "Runs an AudioRecord probe to verify injected audio reaches the emulator guest microphone."
    dependsOn(":app:assembleDebug", ":app:assembleDebugAndroidTest")

    doLast {
        val adbPath = project.androidAdbPath()
        val sourceAudioFile = file("build/e2e-audio/open-google-drive.wav")
        val audioFile = file("build/e2e-audio/open-google-drive-16k-mono.wav")
        project.prepareOpenGoogleDriveE2eWav(sourceAudioFile)
        project.convertAudioToWav(sourceAudioFile, audioFile)
        project.ensureEmulatorHostAudioForE2e(adbPath)
        project.checkEmulatorGrpcStatus(audioFile)
        project.adbOutput(adbPath, "uninstall", "ai.droidlm.debug")
        project.adbOutput(adbPath, "uninstall", "ai.droidlm.debug.test")
        project.adbOutput(adbPath, "install", "-r", "app/build/outputs/apk/debug/app-debug.apk")
        project.adbOutput(adbPath, "install", "-r", "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk")
        project.runMicInjectedInstrumentedTest(
            adb = adbPath,
            suite = AndroidE2eSuite(
                className = "ai.droidlm.e2e.DroidLmEmulatorMicProbeE2ETest",
                sourcePath = "app/src/androidTest/kotlin/ai/droidlm/e2e/DroidLmEmulatorMicProbeE2ETest.kt",
                artifactSubdirectory = "emulator-mic-probe"
            ),
            methodName = "injectedAudioProducesGuestMicEnergy",
            audioFile = audioFile,
            speechDelayMs = 250L
        )
    }
}

tasks.register("connectedOnDeviceAudioSourceE2e") {
    group = "verification"
    description = "Runs on-device SpeechRecognizer E2E using RecognizerIntent.EXTRA_AUDIO_SOURCE with generated audio. This validates Android STT without using the live emulator microphone."
    dependsOn(":app:assembleDebug", ":app:assembleDebugAndroidTest")

    doLast {
        val adbPath = project.androidAdbPath()
        val wavFile = file("build/e2e-audio/open-google-drive.wav")
        val pcmFile = file("build/e2e-audio/open-google-drive-16k-mono.pcm")
        val tmpAudioPath = "/data/local/tmp/droidlm-open-google-drive-16k-mono.pcm"
        val deviceAudioPath = "/data/data/ai.droidlm.debug/files/droidlm-open-google-drive-16k-mono.pcm"
        project.prepareOpenGoogleDriveE2eWav(wavFile)
        project.convertAudioToPcm(wavFile, pcmFile)
        project.adbOutput(adbPath, "uninstall", "ai.droidlm.debug")
        project.adbOutput(adbPath, "uninstall", "ai.droidlm.debug.test")
        project.adbOutput(adbPath, "install", "-r", "app/build/outputs/apk/debug/app-debug.apk")
        project.adbOutput(adbPath, "install", "-r", "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk")
        project.adbOutput(adbPath, "push", pcmFile.absolutePath, tmpAudioPath)
        project.adbOutput(adbPath, "shell", "run-as", "ai.droidlm.debug", "mkdir", "-p", "files")
        project.adbOutput(adbPath, "shell", "run-as", "ai.droidlm.debug", "cp", tmpAudioPath, "files/droidlm-open-google-drive-16k-mono.pcm")
        runInstrumentedSuiteWithVideos(
            adbPath,
            AndroidE2eSuite(
                className = "ai.droidlm.e2e.DroidLmOnDeviceAudioSourceE2ETest",
                sourcePath = "app/src/androidTest/kotlin/ai/droidlm/e2e/DroidLmOnDeviceAudioSourceE2ETest.kt",
                artifactSubdirectory = "on-device-audio-source",
                instrumentationArgs = mapOf(
                    "audioSourcePath" to deviceAudioPath,
                    "preferOfflineSpeechRecognition" to "false"
                )
            )
        )
    }
}

tasks.register("containerizedHoverMicAudioE2e", org.gradle.api.tasks.Exec::class) {
    group = "verification"
    description = "Builds a single Docker container with Android Emulator + gRPC mic injection and runs connectedHoverMicAudioE2e."
    commandLine("bash", "scripts/run-containerized-virtual-mic-e2e.sh")
}

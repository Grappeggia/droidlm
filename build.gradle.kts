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
        val args = mutableListOf(
            "shell",
            "am",
            "instrument",
            "-w",
            "-r",
            "-e",
            "workspaceFileOpsE2e",
            "true",
            "-e",
            "class",
            "ai.droidlm.e2e.DroidLmWorkspaceFileOpsE2ETest"
        )
        project.localEnvValue("OPENAI_API_KEY")?.let { key ->
            args += listOf("-e", "openAiApiKey", key)
        }
        args += "ai.droidlm.test/androidx.test.runner.AndroidJUnitRunner"
        val output = java.io.ByteArrayOutputStream()
        val result = exec {
            commandLine(adb, *args.toTypedArray())
            standardOutput = output
            errorOutput = output
            isIgnoreExitValue = true
        }
        val text = output.toString()
        print(text)
        if (result.exitValue != 0 || text.contains("FAILURES!!!")) {
            throw org.gradle.api.GradleException("Workspace file operation E2E tests failed")
        }
    }
}

gradle.projectsEvaluated {
    tasks.findByPath(":app:connectedDebugAndroidTest")?.mustRunAfter(tasks.findByPath(":ensureDriveForE2e"))
}

tasks.register("connectedVoiceE2e") {
    group = "verification"
    description = "Runs DroidLM emulator voice invocation E2E tests. Requires a running emulator."
    dependsOn(":ensureDriveForE2e", ":app:connectedDebugAndroidTest")
}


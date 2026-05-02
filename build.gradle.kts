plugins {
    id("com.android.application") version "8.12.0" apply false
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20" apply false
}

tasks.register("ensureDriveForE2e") {
    group = "verification"
    description = "Ensures com.google.android.apps.docs exists on the connected emulator, installing the test stub only when real Drive is missing."
    dependsOn(":driveStub:assembleDebug")

    doLast {
        val androidHome = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
            ?: error("ANDROID_HOME or ANDROID_SDK_ROOT must point to the Android SDK")
        val adb = file("$androidHome/platform-tools/adb").absolutePath
        val packageCheck = java.io.ByteArrayOutputStream()
        exec {
            commandLine(adb, "shell", "pm", "path", "com.google.android.apps.docs")
            standardOutput = packageCheck
            errorOutput = packageCheck
            isIgnoreExitValue = true
        }
        if (packageCheck.toString().contains("package:")) {
            println("Google Drive package already installed; using existing com.google.android.apps.docs")
        } else {
            val stubApk = project(":driveStub").layout.buildDirectory
                .file("outputs/apk/debug/driveStub-debug.apk")
                .get()
                .asFile
                .absolutePath
            exec { commandLine(adb, "install", "-r", stubApk) }
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


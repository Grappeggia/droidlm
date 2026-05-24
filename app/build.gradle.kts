import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.URI
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
}

val hasGoogleServicesConfig = file("google-services.json").isFile


val baseVersionCode = 29
val baseVersionName = "0.1.28"

val releaseStoreFile = System.getenv("DROIDLM_RELEASE_STORE_FILE")
val releaseStorePassword = System.getenv("DROIDLM_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = System.getenv("DROIDLM_RELEASE_KEY_ALIAS")
val releaseKeyPassword = System.getenv("DROIDLM_RELEASE_KEY_PASSWORD")
val debugIteration = providers.gradleProperty("droidlm.debugIteration")
    .orElse(providers.environmentVariable("DROIDLM_DEBUG_ITERATION"))
    .orNull
    ?.takeIf { it.isNotBlank() }
val debugIterationNumber = debugIteration?.let { value ->
    val number = value.toIntOrNull() ?: error("droidlm.debugIteration must be a number")
    require(number in 1..999) { "droidlm.debugIteration must be between 1 and 999" }
    number
}
val debugVersionCode = baseVersionCode * 1000 + (debugIterationNumber ?: 0)
val defaultDebugLogUploadUrl = "https://us-central1-droidlm-495821.cloudfunctions.net/droidlm-debug-log-upload"
val debugLogUploadUrl = providers.gradleProperty("droidlm.debugLogUploadUrl")
    .orElse(providers.environmentVariable("DROIDLM_DEBUG_LOG_UPLOAD_URL"))
    .orElse(defaultDebugLogUploadUrl)
val defaultAllowlistCheckUrl = defaultDebugLogUploadUrl
val allowlistCheckUrl = providers.gradleProperty("droidlm.allowlistCheckUrl")
    .orElse(providers.environmentVariable("DROIDLM_ALLOWLIST_CHECK_URL"))
    .orElse(defaultAllowlistCheckUrl)
val defaultCloudScreenshotAnalysisUrl = ""
val cloudScreenshotAnalysisUrl = providers.gradleProperty("droidlm.cloudScreenshotAnalysisUrl")
    .orElse(providers.environmentVariable("DROIDLM_CLOUD_SCREENSHOT_ANALYSIS_URL"))
    .orElse(defaultCloudScreenshotAnalysisUrl)


fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(128 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
}

val sherpaParakeetModelName = "sherpa-onnx-nemo-parakeet-unified-en-0.6b-int8-non-streaming"
val sherpaParakeetArchiveName = "$sherpaParakeetModelName.tar.bz2"
val sherpaParakeetUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$sherpaParakeetArchiveName"
val sherpaParakeetSha256 = "99f63605b3a85a54c250c0869670a687b7d6598a47bf2421515e1f839a76e150"
val sherpaDownloadDir = layout.buildDirectory.dir("downloads/sherpa")
val sherpaGeneratedAssetsDir = layout.buildDirectory.dir("generated/sherpaAssets")
val sherpaParakeetArchive = sherpaDownloadDir.map { it.file(sherpaParakeetArchiveName) }
val llamaSourceCommit = "549b9d84330c327e6791fa812a7d60c0cf63572e"
val llamaSourceArchiveName = "llama.cpp-$llamaSourceCommit.tar.gz"
val llamaSourceUrl = "https://github.com/ggml-org/llama.cpp/archive/$llamaSourceCommit.tar.gz"
val llamaSourceSha256 = "35cb424e97ddce6699f14e9c6312fa26eaaa490f9622e3ae0169d0acd5634008"
val llamaDownloadDir = layout.buildDirectory.dir("downloads/llama")
val llamaGeneratedSourceDir = layout.buildDirectory.dir("generated/llamaSource")
val llamaSourceArchive = llamaDownloadDir.map { it.file(llamaSourceArchiveName) }
val llamaSourceDir = llamaGeneratedSourceDir.map { it.dir("llama.cpp-$llamaSourceCommit") }

val downloadSherpaParakeetModel by tasks.registering {
    outputs.file(sherpaParakeetArchive)
    doLast {
        val archive = sherpaParakeetArchive.get().asFile
        if (archive.isFile && sha256(archive).equals(sherpaParakeetSha256, ignoreCase = true)) return@doLast
        archive.parentFile.mkdirs()
        val temp = File(archive.parentFile, "${archive.name}.tmp")
        temp.delete()
        URI(sherpaParakeetUrl).toURL().openStream().use { input ->
            temp.outputStream().use { output -> input.copyTo(output) }
        }
        val actualSha256 = sha256(temp)
        require(actualSha256.equals(sherpaParakeetSha256, ignoreCase = true)) {
            temp.delete()
            "Downloaded Sherpa Parakeet model checksum mismatch"
        }
        archive.delete()
        require(temp.renameTo(archive)) { "Could not move downloaded Sherpa Parakeet archive" }
    }
}

val unpackSherpaParakeetModel by tasks.registering(Sync::class) {
    dependsOn(downloadSherpaParakeetModel)
    from(tarTree(resources.bzip2(sherpaParakeetArchive)))
    into(sherpaGeneratedAssetsDir.map { it.dir("sherpa") })
}

val downloadLlamaSource by tasks.registering {
    outputs.file(llamaSourceArchive)
    doLast {
        val archive = llamaSourceArchive.get().asFile
        if (archive.isFile && sha256(archive).equals(llamaSourceSha256, ignoreCase = true)) return@doLast
        archive.parentFile.mkdirs()
        val temp = File(archive.parentFile, "${archive.name}.tmp")
        temp.delete()
        URI(llamaSourceUrl).toURL().openStream().use { input ->
            temp.outputStream().use { output -> input.copyTo(output) }
        }
        val actualSha256 = sha256(temp)
        require(actualSha256.equals(llamaSourceSha256, ignoreCase = true)) {
            temp.delete()
            "Downloaded llama.cpp source checksum mismatch"
        }
        archive.delete()
        require(temp.renameTo(archive)) { "Could not move downloaded llama.cpp archive" }
    }
}

val unpackLlamaSource by tasks.registering(Sync::class) {
    dependsOn(downloadLlamaSource)
    from(tarTree(resources.gzip(llamaSourceArchive)))
    into(llamaGeneratedSourceDir)
}

fun buildConfigString(value: String): String = value
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")

val hasReleaseSigning = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword
).all { !it.isNullOrBlank() }

android {
    namespace = "ai.droidlm"
    compileSdk = 36
    ndkVersion = "27.1.12297006"

    defaultConfig {
        applicationId = "com.studionext54.droidlm"
        minSdk = 29
        targetSdk = 36
        versionCode = baseVersionCode
        buildConfigField("boolean", "FIREBASE_AUTH_CONFIGURED", hasGoogleServicesConfig.toString())
        versionName = baseVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "DEBUG_LOG_UPLOAD_URL", "\"${buildConfigString(debugLogUploadUrl.get().trim())}\"")
        buildConfigField("String", "CLOUD_SCREENSHOT_ANALYSIS_URL", "\"${buildConfigString(cloudScreenshotAnalysisUrl.get().trim())}\"")
        buildConfigField("String", "ALLOWLIST_CHECK_URL", "\"${buildConfigString(allowlistCheckUrl.get().trim())}\"")
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += "-DLLAMA_SRC_DIR=${llamaSourceDir.get().asFile.absolutePath}"
            }
        }
    }

    sourceSets["main"].assets.srcDir(sherpaGeneratedAssetsDir)

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = debugIteration?.let { "-debug.$it" } ?: "-debug"
        }

        release {
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        variant.outputs.forEach { output ->
            output.versionCode.set(debugVersionCode)
        }
    }
}

tasks.named("preBuild") {
    dependsOn(unpackSherpaParakeetModel)
    dependsOn(unpackLlamaSource)
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.05.01"))
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.05.01"))

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation(platform("com.google.firebase:firebase-bom:34.13.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.bihe0832.android:lib-sherpa-onnx:6.25.21")
    implementation("com.alphacephei:vosk-android:0.3.47")

    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
}

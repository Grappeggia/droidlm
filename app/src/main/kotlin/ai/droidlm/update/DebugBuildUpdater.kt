package ai.droidlm.update

import ai.droidlm.BuildConfig
import ai.droidlm.download.DownloadProgress
import ai.droidlm.download.copyStreamWithProgress
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

private const val DEFAULT_REPO = "Grappeggia/droidlm"
private const val RELEASE_LIMIT = 30
private const val USER_AGENT = "DroidLM-Debug-Updater"
private const val CACHE_DIRECTORY_NAME = "debug-updates"
private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
private val DEBUG_VERSION_REGEX = Regex("^v?(\\d+)\\.(\\d+)\\.(\\d+)(?:-debug\\.|-)(\\d+)$")

data class PreparedDebugBuild(
    val tagName: String,
    val versionName: String,
    val versionCode: Long,
    val apkFile: File,
    val assetName: String,
    val publishedAt: String?
)

sealed class DebugBuildPreparationResult {
    data class ReadyToInstall(val build: PreparedDebugBuild) : DebugBuildPreparationResult()

    data class AlreadyLatest(
        val installedVersionName: String?,
        val installedVersionCode: Long,
        val availableVersionName: String,
        val availableVersionCode: Long
    ) : DebugBuildPreparationResult()

    data class Failure(val message: String, val errorCode: String? = null, val cause: Throwable? = null) : DebugBuildPreparationResult()
}

internal data class GitHubDebugRelease(
    val tagName: String,
    val assetName: String,
    val assetUrl: String,
    val assetSizeBytes: Long?,
    val publishedAt: String?,
    val version: DebugTagVersion
)

internal data class DebugTagVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val iteration: Int
) : Comparable<DebugTagVersion> {
    val compactName: String
        get() = "$major.$minor.$patch-$iteration"

    override fun compareTo(other: DebugTagVersion): Int = compareValuesBy(
        this,
        other,
        DebugTagVersion::major,
        DebugTagVersion::minor,
        DebugTagVersion::patch,
        DebugTagVersion::iteration
    )
}

private sealed class ReleaseLookupResult {
    data class Success(val release: GitHubDebugRelease) : ReleaseLookupResult()
    data class Failure(val message: String, val errorCode: String? = null, val cause: Throwable? = null) : ReleaseLookupResult()
}

private sealed class DownloadResult {
    data class Success(val file: File) : DownloadResult()
    data class Failure(val message: String, val errorCode: String? = null, val cause: Throwable? = null) : DownloadResult()
}

class DebugBuildUpdater(
    private val context: Context,
    private val repo: String = DEFAULT_REPO,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .build()
) {
    val isSupported: Boolean
        get() = BuildConfig.DEBUG

    internal suspend fun prepareLatestInstall(onDownloadProgress: ((DownloadProgress) -> Unit)? = null): DebugBuildPreparationResult = withContext(Dispatchers.IO) {
        if (!isSupported) {
            return@withContext DebugBuildPreparationResult.Failure(
                message = "Debug build upgrades are only available in debug builds.",
                errorCode = "NOT_DEBUG_BUILD"
            )
        }

        val release = when (val lookup = fetchLatestDebugRelease()) {
            is ReleaseLookupResult.Success -> lookup.release
            is ReleaseLookupResult.Failure -> return@withContext DebugBuildPreparationResult.Failure(
                message = lookup.message,
                errorCode = lookup.errorCode,
                cause = lookup.cause
            )
        }

        val apkFile = when (val download = downloadReleaseApk(release, onDownloadProgress)) {
            is DownloadResult.Success -> download.file
            is DownloadResult.Failure -> return@withContext DebugBuildPreparationResult.Failure(
                message = download.message,
                errorCode = download.errorCode,
                cause = download.cause
            )
        }

        val archiveInfo = packageArchiveInfo(apkFile) ?: return@withContext failureDeleting(
            apkFile,
            message = "Downloaded debug APK could not be read.",
            errorCode = "INVALID_APK"
        )
        val installedInfo = installedPackageInfo() ?: return@withContext failureDeleting(
            apkFile,
            message = "Installed app version information is unavailable.",
            errorCode = "NO_INSTALLED_PACKAGE_INFO"
        )

        if (archiveInfo.packageName != context.packageName) {
            return@withContext failureDeleting(
                apkFile,
                message = "Latest GitHub debug build targets ${archiveInfo.packageName}, not ${context.packageName}.",
                errorCode = "PACKAGE_MISMATCH"
            )
        }

        val rawAvailableVersionName = archiveInfo.versionName?.takeIf { it.isNotBlank() } ?: release.tagName.removePrefix("v")
        val availableVersionName = compactDebugVersionName(rawAvailableVersionName) ?: rawAvailableVersionName
        val availableVersionCode = PackageInfoCompat.getLongVersionCode(archiveInfo)
        val installedVersionCode = PackageInfoCompat.getLongVersionCode(installedInfo)
        if (availableVersionCode <= installedVersionCode) {
            apkFile.delete()
            return@withContext DebugBuildPreparationResult.AlreadyLatest(
                installedVersionName = compactDebugVersionName(installedInfo.versionName),
                installedVersionCode = installedVersionCode,
                availableVersionName = availableVersionName,
                availableVersionCode = availableVersionCode
            )
        }

        val installedSignatures = signingDigests(installedInfo)
        val archiveSignatures = signingDigests(archiveInfo)
        if (installedSignatures.isNotEmpty() && archiveSignatures.isNotEmpty() && installedSignatures != archiveSignatures) {
            return@withContext failureDeleting(
                apkFile,
                message = "Latest GitHub debug build is signed with a different certificate and cannot upgrade this install.",
                errorCode = "SIGNATURE_MISMATCH"
            )
        }

        DebugBuildPreparationResult.ReadyToInstall(
            PreparedDebugBuild(
                tagName = release.tagName,
                versionName = availableVersionName,
                versionCode = availableVersionCode,
                apkFile = apkFile,
                assetName = release.assetName,
                publishedAt = release.publishedAt
            )
        )
    }

    fun canRequestPackageInstalls(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun installPermissionIntent(): Intent = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${context.packageName}")
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun launchInstaller(build: PreparedDebugBuild) {
        context.startActivity(installerIntent(build))
    }

    internal fun installerIntent(build: PreparedDebugBuild): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            build.apkFile
        )
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME_TYPE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun fetchLatestDebugRelease(): ReleaseLookupResult {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$repo/releases?per_page=$RELEASE_LIMIT")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", USER_AGENT)
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    ReleaseLookupResult.Failure(
                        message = "GitHub release lookup failed (${response.code}): ${body.ifBlank { "no response body" }}",
                        errorCode = "HTTP_${response.code}"
                    )
                } else {
                    val body = response.body?.string().orEmpty()
                    val release = latestDebugReleaseFromJson(body)
                    if (release == null) {
                        ReleaseLookupResult.Failure(
                            message = "No published debug prerelease APK was found on GitHub.",
                            errorCode = "NO_DEBUG_RELEASE"
                        )
                    } else {
                        ReleaseLookupResult.Success(release)
                    }
                }
            }
        } catch (error: IOException) {
            ReleaseLookupResult.Failure(
                message = error.message ?: "GitHub release lookup failed.",
                errorCode = "NETWORK_ERROR",
                cause = error
            )
        } catch (error: Exception) {
            ReleaseLookupResult.Failure(
                message = error.message ?: "GitHub release lookup failed.",
                errorCode = "INVALID_GITHUB_RESPONSE",
                cause = error
            )
        }
    }

    private fun downloadReleaseApk(
        release: GitHubDebugRelease,
        onDownloadProgress: ((DownloadProgress) -> Unit)? = null
    ): DownloadResult {
        val targetDirectory = File(context.cacheDir, CACHE_DIRECTORY_NAME).apply { mkdirs() }
        val targetFile = File(targetDirectory, release.assetName)
        val tempFile = File(targetDirectory, "${release.assetName}.part")
        targetDirectory.listFiles()?.forEach { existing ->
            if (existing != targetFile && existing != tempFile) existing.delete()
        }
        targetFile.delete()
        tempFile.delete()

        val request = Request.Builder()
            .url(release.assetUrl)
            .header("Accept", "application/octet-stream")
            .header("User-Agent", USER_AGENT)
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    DownloadResult.Failure(
                        message = "Debug APK download failed (${response.code}): ${body.ifBlank { "no response body" }}",
                        errorCode = "HTTP_${response.code}"
                    )
                } else {
                    val body = response.body ?: return DownloadResult.Failure(
                        message = "GitHub returned an empty debug APK response.",
                        errorCode = "EMPTY_DOWNLOAD"
                    )
                    val totalBytes = body.contentLength().takeIf { it > 0L } ?: release.assetSizeBytes
                    tempFile.outputStream().use { output ->
                        body.byteStream().use { input ->
                            copyStreamWithProgress(
                                input = input,
                                output = output,
                                totalBytes = totalBytes,
                                onProgress = onDownloadProgress
                            )
                        }
                    }
                    if (!tempFile.renameTo(targetFile)) {
                        tempFile.copyTo(targetFile, overwrite = true)
                        tempFile.delete()
                    }
                    if (!targetFile.exists() || targetFile.length() <= 0L) {
                        targetFile.delete()
                        DownloadResult.Failure(
                            message = "Downloaded debug APK is empty.",
                            errorCode = "EMPTY_DOWNLOAD"
                        )
                    } else {
                        DownloadResult.Success(targetFile)
                    }
                }
            }
        } catch (error: IOException) {
            tempFile.delete()
            targetFile.delete()
            DownloadResult.Failure(
                message = error.message ?: "Could not download the latest debug APK.",
                errorCode = "NETWORK_ERROR",
                cause = error
            )
        } catch (error: Exception) {
            tempFile.delete()
            targetFile.delete()
            DownloadResult.Failure(
                message = error.message ?: "Could not download the latest debug APK.",
                errorCode = "DOWNLOAD_FAILED",
                cause = error
            )
        }
    }

    private fun installedPackageInfo(): PackageInfo? = if (Build.VERSION.SDK_INT >= 33) {
        context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
        )
    } else {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    }

    private fun packageArchiveInfo(apkFile: File): PackageInfo? = if (Build.VERSION.SDK_INT >= 33) {
        context.packageManager.getPackageArchiveInfo(
            apkFile.absolutePath,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
        )
    } else {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
    }

    private fun signingDigests(packageInfo: PackageInfo): Set<String> {
        val signingInfo = packageInfo.signingInfo ?: return emptySet()
        val signatures = if (signingInfo.hasMultipleSigners()) {
            signingInfo.apkContentsSigners.toList()
        } else {
            signingInfo.signingCertificateHistory.toList()
        }
        return signatures.map { signature -> sha256(signature.toByteArray()) }.toSet()
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun failureDeleting(apkFile: File, message: String, errorCode: String): DebugBuildPreparationResult.Failure {
        apkFile.delete()
        return DebugBuildPreparationResult.Failure(message = message, errorCode = errorCode)
    }
}

internal fun latestDebugReleaseFromJson(json: String): GitHubDebugRelease? {
    val releases = JSONArray(json)
    return (0 until releases.length())
        .mapNotNull { index -> releases.optJSONObject(index)?.toDebugRelease() }
        .maxByOrNull { release -> release.version }
}

internal fun parseDebugTag(tagName: String): DebugTagVersion? = parseDebugVersion(tagName)

internal fun compactDebugVersionName(versionName: String?): String? {
    val trimmed = versionName?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return parseDebugVersion(trimmed)?.compactName ?: trimmed
}

private fun parseDebugVersion(value: String): DebugTagVersion? {
    val match = DEBUG_VERSION_REGEX.matchEntire(value) ?: return null
    return DebugTagVersion(
        major = match.groupValues[1].toInt(),
        minor = match.groupValues[2].toInt(),
        patch = match.groupValues[3].toInt(),
        iteration = match.groupValues[4].toInt()
    )
}

internal fun expectedDebugAssetName(tagName: String): String = "DroidLM-${tagName.removePrefix("v")}-debug.apk"

private fun JSONObject.toDebugRelease(): GitHubDebugRelease? {
    if (!optBoolean("prerelease")) return null
    val tagName = optString("tag_name").takeIf { it.isNotBlank() } ?: return null
    val version = parseDebugTag(tagName) ?: return null
    val assets = optJSONArray("assets") ?: return null
    val expectedAssetName = expectedDebugAssetName(tagName)
    val tagFragment = tagName.removePrefix("v")
    val asset = (0 until assets.length())
        .mapNotNull { index -> assets.optJSONObject(index) }
        .filter { assetObject -> assetObject.optString("name").endsWith(".apk", ignoreCase = true) }
        .minByOrNull { assetObject ->
            when {
                assetObject.optString("name") == expectedAssetName -> 0
                assetObject.optString("name").contains(tagFragment) -> 1
                assetObject.optString("name").endsWith("-debug.apk") -> 2
                else -> 3
            }
        } ?: return null
    val assetUrl = asset.optString("browser_download_url").takeIf { it.isNotBlank() } ?: return null
    return GitHubDebugRelease(
        tagName = tagName,
        assetName = asset.optString("name"),
        assetUrl = assetUrl,
        assetSizeBytes = asset.optLong("size").takeIf { it > 0L },
        publishedAt = optString("published_at").takeIf { it.isNotBlank() },
        version = version
    )
}

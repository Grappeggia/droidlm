package ai.droidlm.download

import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

internal data class DownloadProgress(
    val downloadedBytes: Long,
    val totalBytes: Long? = null
) {
    val progressFraction: Float?
        get() {
            val total = normalizedTotalBytes ?: return null
            if (total <= 0L) return null
            return (downloadedBytes.coerceIn(0L, total).toFloat() / total.toFloat())
        }

    val label: String
        get() = formatDownloadProgress(downloadedBytes, totalBytes)

    private val normalizedTotalBytes: Long?
        get() = totalBytes?.takeIf { it > 0L }?.coerceAtLeast(downloadedBytes.coerceAtLeast(0L))
}

internal fun formatDownloadProgress(downloadedBytes: Long, totalBytes: Long?): String {
    val normalizedDownloaded = downloadedBytes.coerceAtLeast(0L)
    val normalizedTotal = totalBytes?.takeIf { it > 0L }?.coerceAtLeast(normalizedDownloaded)
    return if (normalizedTotal != null) {
        val percent = ((normalizedDownloaded.toDouble() / normalizedTotal.toDouble()) * 100.0)
            .toInt()
            .coerceIn(0, 100)
        "$percent% (${formatMegabytes(normalizedDownloaded)} / ${formatMegabytes(normalizedTotal)})"
    } else {
        "${formatMegabytes(normalizedDownloaded)} downloaded"
    }
}

internal fun copyStreamWithProgress(
    input: InputStream,
    output: OutputStream,
    totalBytes: Long? = null,
    bufferSize: Int = DEFAULT_DOWNLOAD_BUFFER_BYTES,
    onChunk: ((buffer: ByteArray, bytesRead: Int) -> Unit)? = null,
    onProgress: ((DownloadProgress) -> Unit)? = null
): Long {
    require(bufferSize > 0) { "bufferSize must be positive" }

    val normalizedTotal = totalBytes?.takeIf { it > 0L }
    var downloaded = 0L
    if (normalizedTotal != null) {
        onProgress?.invoke(DownloadProgress(downloadedBytes = 0L, totalBytes = normalizedTotal))
    }

    val buffer = ByteArray(bufferSize)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        output.write(buffer, 0, read)
        onChunk?.invoke(buffer, read)
        downloaded += read.toLong()
        onProgress?.invoke(DownloadProgress(downloadedBytes = downloaded, totalBytes = normalizedTotal))
    }
    return downloaded
}

private fun formatMegabytes(bytes: Long): String = String.format(
    Locale.US,
    "%.1f MB",
    bytes.coerceAtLeast(0L).toDouble() / BYTES_PER_MB
)

private const val BYTES_PER_MB = 1_000_000.0
private const val DEFAULT_DOWNLOAD_BUFFER_BYTES = 256 * 1024

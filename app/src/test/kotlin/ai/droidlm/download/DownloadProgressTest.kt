package ai.droidlm.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class DownloadProgressTest {
    @Test fun formatDownloadProgressShowsPercentAndMegabytes() {
        assertEquals(
            "42% (18.4 MB / 43.8 MB)",
            formatDownloadProgress(downloadedBytes = 18_400_000L, totalBytes = 43_800_000L)
        )
    }

    @Test fun formatDownloadProgressFallsBackToDownloadedMegabytesWhenTotalUnknown() {
        assertEquals(
            "18.4 MB downloaded",
            formatDownloadProgress(downloadedBytes = 18_400_000L, totalBytes = null)
        )
    }

    @Test fun downloadProgressComputesFractionOnlyWhenTotalKnown() {
        assertEquals(0.5f, DownloadProgress(downloadedBytes = 50L, totalBytes = 100L).progressFraction)
        assertNull(DownloadProgress(downloadedBytes = 50L, totalBytes = null).progressFraction)
    }

    @Test fun copyStreamWithProgressCopiesBytesAndEmitsProgress() {
        val payload = ByteArray(10) { index -> index.toByte() }
        val output = ByteArrayOutputStream()
        val events = mutableListOf<DownloadProgress>()

        val copied = copyStreamWithProgress(
            input = ByteArrayInputStream(payload),
            output = output,
            totalBytes = payload.size.toLong(),
            bufferSize = 4,
            onProgress = events::add
        )

        assertEquals(payload.size.toLong(), copied)
        assertEquals(payload.toList(), output.toByteArray().toList())
        assertEquals(listOf(0L, 4L, 8L, 10L), events.map(DownloadProgress::downloadedBytes))
        assertEquals(payload.size.toLong(), events.last().totalBytes)
        assertEquals("100% (0.0 MB / 0.0 MB)", events.last().label)
    }
}

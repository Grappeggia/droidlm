package ai.droidlm.voice

import java.io.File

data class RecordedCommand(
    val file: File,
    val durationMs: Long,
    val mimeType: String
)

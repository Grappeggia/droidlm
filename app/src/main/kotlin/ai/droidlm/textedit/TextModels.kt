package ai.droidlm.textedit

import android.graphics.Rect

data class EditableTarget(
    val nodeId: String?,
    val packageName: String?,
    val className: String?,
    val bounds: Rect,
    val isFocused: Boolean,
    val isEditable: Boolean,
    val supportsSetText: Boolean,
    val supportsSetSelection: Boolean,
    val supportsKeyboardInput: Boolean
)

data class EditableTextSnapshot(
    val text: String,
    val selectionStart: Int?,
    val selectionEnd: Int?,
    val hint: String?,
    val source: TextSnapshotSource
)

enum class TextSnapshotSource {
    ACCESSIBILITY,
    OCR,
    OPENAI_VISION,
    UNKNOWN
}

package ai.droidlm.prompts

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class PromptHistoryEntry(
    val prompt: String,
    val timestampMs: Long,
    val source: String
)

class PromptHistoryRepository(
    context: Context,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val nowProvider: () -> Long = { System.currentTimeMillis() }
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val _prompts = MutableStateFlow(loadPrompts())
    val prompts: StateFlow<List<PromptHistoryEntry>> = _prompts.asStateFlow()

    fun record(prompt: String, source: String) {
        val trimmed = prompt.trim()
        if (trimmed.isBlank()) return
        val updated = (listOf(PromptHistoryEntry(trimmed, nowProvider(), source)) + _prompts.value)
            .take(maxEntries.coerceAtLeast(1))
        _prompts.value = updated
        savePrompts(updated)
    }

    fun clear() {
        _prompts.value = emptyList()
        preferences.edit().remove(KEY_PROMPTS).apply()
    }

    private fun loadPrompts(): List<PromptHistoryEntry> {
        val raw = preferences.getString(KEY_PROMPTS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val prompt = item.optString("prompt").trim()
                    if (prompt.isBlank()) continue
                    add(
                        PromptHistoryEntry(
                            prompt = prompt,
                            timestampMs = item.optLong("timestampMs", 0L),
                            source = item.optString("source", "unknown").ifBlank { "unknown" }
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun savePrompts(entries: List<PromptHistoryEntry>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject()
                    .put("prompt", entry.prompt)
                    .put("timestampMs", entry.timestampMs)
                    .put("source", entry.source)
            )
        }
        preferences.edit().putString(KEY_PROMPTS, array.toString()).apply()
    }

    companion object {
        private const val PREFERENCES_NAME = "prompt_history"
        private const val KEY_PROMPTS = "prompts"
        private const val DEFAULT_MAX_ENTRIES = 50
    }
}

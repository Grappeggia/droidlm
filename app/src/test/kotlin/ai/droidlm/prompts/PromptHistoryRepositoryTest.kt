package ai.droidlm.prompts

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PromptHistoryRepositoryTest {
    @Test fun recordsNewestFirstAndTrims() {
        val context = testContext()
        var now = 1_000L
        val repository = PromptHistoryRepository(context, maxEntries = 2, nowProvider = { now })

        repository.record(" first prompt ", "voice_prompt")
        now = 2_000L
        repository.record("second prompt", "manual_command")
        now = 3_000L
        repository.record("third prompt", "voice_prompt")

        val prompts = repository.prompts.value
        assertEquals(listOf("third prompt", "second prompt"), prompts.map { it.prompt })
        assertEquals(listOf(3_000L, 2_000L), prompts.map { it.timestampMs })
        assertEquals(listOf("voice_prompt", "manual_command"), prompts.map { it.source })
    }

    @Test fun persistsAndClearsPrompts() {
        val context = testContext()
        val repository = PromptHistoryRepository(context, nowProvider = { 4_000L })
        repository.record("open drive", "voice_prompt")

        val restored = PromptHistoryRepository(context)
        assertEquals("open drive", restored.prompts.value.single().prompt)
        assertEquals(4_000L, restored.prompts.value.single().timestampMs)

        restored.clear()
        assertEquals(emptyList<PromptHistoryEntry>(), restored.prompts.value)
        assertEquals(emptyList<PromptHistoryEntry>(), PromptHistoryRepository(context).prompts.value)
    }

    private fun testContext(): Context {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("prompt_history", Context.MODE_PRIVATE).edit().clear().commit()
        return context
    }
}

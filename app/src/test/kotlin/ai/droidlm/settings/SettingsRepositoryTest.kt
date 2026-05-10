package ai.droidlm.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsRepositoryTest {
    @Test fun openAiConfiguredReflectsReadableSecureKey() = runTest {
        val repository = SettingsRepository(ApplicationProvider.getApplicationContext<Context>())
        repository.clearOpenAiApiKey()
        assertFalse(repository.settings.first().openAiApiKeyConfigured)

        repository.saveOpenAiApiKey(" sk-test ")
        assertTrue(repository.settings.first().openAiApiKeyConfigured)
        assertTrue(repository.getOpenAiApiKey() == "sk-test")

        repository.saveOpenAiApiKey("   ")
        assertFalse(repository.settings.first().openAiApiKeyConfigured)
        assertTrue(repository.getOpenAiApiKey().isNullOrBlank())
    }

    @Test fun debugLoggingCanBeToggled() = runTest {
        val repository = SettingsRepository(ApplicationProvider.getApplicationContext<Context>())

        repository.updateDebugLoggingEnabled(false)
        assertFalse(repository.settings.first().debugLoggingEnabled)

        repository.updateDebugLoggingEnabled(true)
        assertTrue(repository.settings.first().debugLoggingEnabled)
    }

    @Test fun debugLogUploadUrlIsTrimmedAndPersisted() = runTest {
        val repository = SettingsRepository(ApplicationProvider.getApplicationContext<Context>())

        repository.updateDebugLogUploadUrl(" https://example.com/upload ")

        assertEquals("https://example.com/upload", repository.settings.first().debugLogUploadUrl)
    }

    @Test fun agentLimitsAreClampedConservatively() = runTest {
        val repository = SettingsRepository(ApplicationProvider.getApplicationContext<Context>())

        repository.updateExecutionMode(ExecutionMode.AGENT_LOOP)
        repository.updateMaxAgentTurns(99)
        repository.updateMaxAgentToolCalls(99)
        val settings = repository.settings.first()

        assertTrue(settings.executionMode == ExecutionMode.AGENT_LOOP)
        assertTrue(settings.maxAgentTurns == 8)
        assertTrue(settings.maxAgentToolCalls == 16)
    }
}

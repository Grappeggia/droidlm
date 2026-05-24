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

    @Test fun privacyModeCanBeToggled() = runTest {
        val repository = SettingsRepository(ApplicationProvider.getApplicationContext<Context>())

        repository.updatePrivacyModeEnabled(false)
        assertFalse(repository.settings.first().privacyModeEnabled)

        repository.updatePrivacyModeEnabled(true)
        assertTrue(repository.settings.first().privacyModeEnabled)
    }

    @Test fun settingsDoNotExposeDebugLogUploadUrls() {
        val fieldNames = DroidLmSettings::class.java.declaredFields.map { it.name }.toSet()

        assertFalse(fieldNames.contains("relayBaseUrl"))
        assertFalse(fieldNames.contains("debugLogUploadUrl"))
    }

    @Test fun agentLimitsAreClampedConservatively() = runTest {
        val repository = SettingsRepository(ApplicationProvider.getApplicationContext<Context>())

        repository.updateExecutionMode(ExecutionMode.AGENT_LOOP)
        repository.updateMaxAgentTurns(99)
        repository.updateMaxAgentToolCalls(99)
        val settings = repository.settings.first()

        assertTrue(settings.executionMode == ExecutionMode.AGENT_LOOP)
        assertTrue(settings.maxAgentTurns == 16)
        assertTrue(settings.maxAgentToolCalls == 32)
    }
}

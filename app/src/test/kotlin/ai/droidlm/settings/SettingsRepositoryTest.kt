package ai.droidlm.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
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
}

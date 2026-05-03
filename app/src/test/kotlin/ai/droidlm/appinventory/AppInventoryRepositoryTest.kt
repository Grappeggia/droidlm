package ai.droidlm.appinventory

import ai.droidlm.portal.AppPackage
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppInventoryRepositoryTest {
    @Test fun installedAppsAreCachedForOneDay() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        clearInventoryCache(context)
        var now = 1_000L
        var loads = 0
        val repository = AppInventoryRepository(
            context = context,
            nowProvider = { now },
            packageLoader = {
                loads += 1
                listOf(AppPackage("pkg.$loads", "App $loads"))
            }
        )

        assertEquals("pkg.1", repository.getInstalledApps().single().packageName)
        assertEquals("pkg.1", repository.getInstalledApps().single().packageName)
        assertEquals(1, loads)

        now += 24L * 60L * 60L * 1000L
        assertEquals("pkg.2", repository.getInstalledApps().single().packageName)
        assertEquals(2, loads)
    }

    @Test fun packageEventInvalidatesInstalledAppCache() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        clearInventoryCache(context)
        var loads = 0
        val repository = AppInventoryRepository(
            context = context,
            packageLoader = {
                loads += 1
                listOf(AppPackage("pkg.$loads", "App $loads"))
            }
        )

        assertEquals("pkg.1", repository.getInstalledApps().single().packageName)
        AppInventoryRepository.invalidate(context)
        assertEquals("pkg.2", repository.getInstalledApps().single().packageName)
        assertEquals(2, loads)
    }

    private fun clearInventoryCache(context: Context) {
        context.getSharedPreferences("app_inventory", Context.MODE_PRIVATE).edit().clear().commit()
    }
}

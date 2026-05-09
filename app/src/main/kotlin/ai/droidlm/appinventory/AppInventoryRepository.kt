package ai.droidlm.appinventory

import ai.droidlm.relay.ActiveApp
import ai.droidlm.portal.AppPackage
import ai.droidlm.portal.PortalState
import android.content.Context
import android.content.pm.ApplicationInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class AppInventoryRepository(
    private val context: Context,
    private val nowProvider: () -> Long = { System.currentTimeMillis() },
    private val packageLoader: () -> List<AppPackage>? = { null }
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    suspend fun getInstalledApps(forceRefresh: Boolean = false): List<AppPackage> = withContext(Dispatchers.Default) {
        val now = nowProvider()
        val cachedJson = preferences.getString(KEY_APPS_JSON, null)
        val lastRefresh = preferences.getLong(KEY_LAST_REFRESH_MS, 0L)
        if (!forceRefresh && cachedJson != null && now - lastRefresh < CACHE_TTL_MS) {
            val cachedApps = parseApps(cachedJson)
            if (cachedApps.isNotEmpty() && cachedApps.all { it.enabled != null && it.launchable != null }) {
                cachedApps
            } else {
                loadAndCacheApps(now)
            }
        } else {
            loadAndCacheApps(now)
        }
    }

    private fun loadAndCacheApps(now: Long): List<AppPackage> {
        val apps = packageLoader() ?: queryInstalledApps()
        preferences.edit()
            .putString(KEY_APPS_JSON, appsToJson(apps).toString())
            .putLong(KEY_LAST_REFRESH_MS, now)
            .apply()
        return apps
    }

    suspend fun activeAppFor(state: PortalState?): ActiveApp? {
        val packageName = state?.packageName ?: return null
        val label = getInstalledApps().firstOrNull { it.packageName == packageName }?.label
        return ActiveApp(
            packageName = packageName,
            activityName = state.activityName,
            label = label
        )
    }

    fun invalidate() {
        preferences.edit().remove(KEY_LAST_REFRESH_MS).apply()
    }

    @Suppress("DEPRECATION")
    private fun queryInstalledApps(): List<AppPackage> {
        val packageManager = context.packageManager
        return packageManager.getInstalledApplications(0)
            .map { info ->
                val launchIntent = packageManager.getLaunchIntentForPackage(info.packageName)
                val launchActivity = launchIntent?.component?.flattenToShortString()
                    ?: launchIntent?.resolveActivity(packageManager)?.flattenToShortString()
                AppPackage(
                    packageName = info.packageName,
                    label = runCatching { packageManager.getApplicationLabel(info).toString() }.getOrNull(),
                    isSystemApp = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    enabled = info.enabled,
                    launchable = launchIntent != null,
                    launchActivity = launchActivity
                )
            }
            .sortedWith(compareBy<AppPackage> { it.label?.lowercase().orEmpty() }.thenBy { it.packageName })
    }

    private fun parseApps(json: String): List<AppPackage> = runCatching {
        val array = JSONArray(json)
        (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let { obj ->
                AppPackage(
                    packageName = obj.optString("packageName"),
                    label = obj.optString("label").takeIf { it.isNotBlank() },
                    isSystemApp = obj.optBoolean("isSystemApp", false),
                    enabled = obj.optNullableBoolean("enabled"),
                    launchable = obj.optNullableBoolean("launchable"),
                    launchActivity = obj.optString("launchActivity").takeIf { it.isNotBlank() }
                )
            }
        }.filter { it.packageName.isNotBlank() }
    }.getOrDefault(emptyList())

    private fun appsToJson(apps: List<AppPackage>): JSONArray = JSONArray(apps.map { app ->
        JSONObject()
            .put("packageName", app.packageName)
            .put("label", app.label)
            .put("isSystemApp", app.isSystemApp)
            .put("enabled", app.enabled)
            .put("launchable", app.launchable)
            .put("launchActivity", app.launchActivity)
    })

    private fun JSONObject.optNullableBoolean(name: String): Boolean? =
        if (has(name) && !isNull(name)) optBoolean(name) else null

    companion object {
        private const val PREFERENCES_NAME = "app_inventory"
        private const val KEY_APPS_JSON = "apps_json"
        private const val KEY_LAST_REFRESH_MS = "last_refresh_ms"
        private const val CACHE_TTL_MS = 24L * 60L * 60L * 1000L

        fun invalidate(context: Context) {
            context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_LAST_REFRESH_MS)
                .apply()
        }
    }
}

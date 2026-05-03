package ai.droidlm.context

import ai.droidlm.appinventory.AppInventoryRepository
import ai.droidlm.portal.PortalState
import ai.droidlm.relay.DeviceContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class DeviceContextAggregator(
    private val appInventoryRepository: AppInventoryRepository,
    private val providers: List<DeviceContextProvider>
) {
    suspend fun collect(
        goal: String?,
        state: PortalState?,
        history: List<String> = emptyList()
    ): DeviceContext = withContext(Dispatchers.Default) {
        val packages = appInventoryRepository.getInstalledApps()
        val activeApp = appInventoryRepository.activeAppFor(state)
        val request = DeviceContextRequest(
            goal = goal,
            state = state,
            activeApp = activeApp,
            packages = packages,
            history = history
        )
        val extras = JSONObject()
        providers.forEach { provider ->
            merge(extras, runCatching { provider.collect(request) }.getOrDefault(JSONObject()))
        }
        DeviceContext(
            activeApp = activeApp,
            packages = packages,
            extras = extras
        )
    }

    private fun merge(target: JSONObject, source: JSONObject) {
        source.keys().forEach { key -> target.put(key, source.opt(key)) }
    }
}

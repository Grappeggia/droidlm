package ai.droidlm.context

import ai.droidlm.portal.AppPackage
import ai.droidlm.portal.PortalState
import ai.droidlm.relay.ActiveApp
import org.json.JSONObject

data class DeviceContextRequest(
    val goal: String?,
    val state: PortalState?,
    val activeApp: ActiveApp?,
    val packages: List<AppPackage>,
    val history: List<String> = emptyList()
)

interface DeviceContextProvider {
    suspend fun collect(request: DeviceContextRequest): JSONObject
}

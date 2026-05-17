package ai.droidlm.context

import org.json.JSONObject

class AccessibilityContentContextProvider(
    private val maxContentChars: Int = AccessibilityContentLimits.LONG_CONTEXT_MAX_CHARS
) : DeviceContextProvider {
    override suspend fun collect(request: DeviceContextRequest): JSONObject {
        val state = request.state ?: return JSONObject()
        val extraction = AccessibilityContentExtractor.extract(state, maxContentChars)
        if (extraction.lines.isEmpty()) return JSONObject()
        return JSONObject().put("accessibilityContentContext", extraction.toJson())
    }
}

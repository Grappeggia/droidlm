package ai.droidlm.runtime

import ai.droidlm.portal.AccessibilityGateway
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

enum class OverlayNoticeKind {
    INFO,
    SUCCESS,
    ERROR
}

data class OverlayNotice(
    val title: String,
    val details: String = "",
    val kind: OverlayNoticeKind = OverlayNoticeKind.INFO,
    val createdAtMs: Long = System.currentTimeMillis()
)

class OverlayRuntime {
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()
    private val _notice = MutableStateFlow<OverlayNotice?>(null)
    val notice: StateFlow<OverlayNotice?> = _notice.asStateFlow()

    fun setRunning(value: Boolean) {
        _isRunning.value = value
    }

    fun showNotice(title: String, details: String = "", kind: OverlayNoticeKind = OverlayNoticeKind.INFO) {
        _notice.value = OverlayNotice(title = title, details = details, kind = kind)
    }

    fun clearNotice() {
        _notice.value = null
    }
}

class ListeningRuntime {
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    fun setRunning(value: Boolean) {
        _isRunning.value = value
    }
}

data class AccessibilityEventSnapshot(
    val sequence: Long,
    val eventType: Int?,
    val packageName: String?,
    val className: String?,
    val contentChangeTypes: Int?,
    val windowChangeTypes: Int?,
    val eventTimeMs: Long?,
    val observedAtElapsedMs: Long
)

data class AccessibilityEventStreamState(
    val sequence: Long = 0,
    val lastEvent: AccessibilityEventSnapshot? = null,
    val previousEvent: AccessibilityEventSnapshot? = null
) {
    val hasEvents: Boolean
        get() = lastEvent != null

    fun quietForMs(nowElapsedMs: Long): Long? = lastEvent?.let { event ->
        (nowElapsedMs - event.observedAtElapsedMs).coerceAtLeast(0L)
    }
}

class AccessibilityRuntime {
    private val lock = Any()
    private val registrationSequence = AtomicLong(0)
    private val _isConnected = MutableStateFlow(false)
    private val eventSequence = AtomicLong(0)
    private val _eventState = MutableStateFlow(AccessibilityEventStreamState())

    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
    val eventState: StateFlow<AccessibilityEventStreamState> = _eventState.asStateFlow()

    private var registration: GatewayRegistration? = null

    fun attach(gateway: AccessibilityGateway): Long = synchronized(lock) {
        val token = registrationSequence.incrementAndGet()
        registration = GatewayRegistration(token, gateway)
        _isConnected.value = true
        eventSequence.set(0)
        _eventState.value = AccessibilityEventStreamState()
        token
    }

    fun detach(token: Long) = synchronized(lock) {
        if (registration?.token == token) {
            registration = null
            _isConnected.value = false
            eventSequence.set(0)
            _eventState.value = AccessibilityEventStreamState()
        }
    }

    fun currentGateway(): AccessibilityGateway? = synchronized(lock) {
        registration?.gateway
    }

    fun recordAccessibilityEvent(
        eventType: Int?,
        packageName: String?,
        className: String?,
        contentChangeTypes: Int?,
        windowChangeTypes: Int?,
        eventTimeMs: Long?,
        observedAtElapsedMs: Long
    ): AccessibilityEventSnapshot = synchronized(lock) {
        val sequence = eventSequence.incrementAndGet()
        val previous = _eventState.value.lastEvent
        val snapshot = AccessibilityEventSnapshot(
            sequence = sequence,
            eventType = eventType,
            packageName = packageName?.takeIf { it.isNotBlank() },
            className = className?.takeIf { it.isNotBlank() },
            contentChangeTypes = contentChangeTypes,
            windowChangeTypes = windowChangeTypes,
            eventTimeMs = eventTimeMs,
            observedAtElapsedMs = observedAtElapsedMs
        )
        _eventState.value = AccessibilityEventStreamState(
            sequence = sequence,
            lastEvent = snapshot,
            previousEvent = previous
        )
        snapshot
    }

    private data class GatewayRegistration(
        val token: Long,
        val gateway: AccessibilityGateway
    )
}

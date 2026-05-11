package ai.droidlm.runtime

import ai.droidlm.portal.AccessibilityGateway
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

class OverlayRuntime {
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    fun setRunning(value: Boolean) {
        _isRunning.value = value
    }
}

class ListeningRuntime {
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    fun setRunning(value: Boolean) {
        _isRunning.value = value
    }
}

class AccessibilityRuntime {
    private val lock = Any()
    private val registrationSequence = AtomicLong(0)
    private val _isConnected = MutableStateFlow(false)

    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private var registration: GatewayRegistration? = null

    fun attach(gateway: AccessibilityGateway): Long = synchronized(lock) {
        val token = registrationSequence.incrementAndGet()
        registration = GatewayRegistration(token, gateway)
        _isConnected.value = true
        token
    }

    fun detach(token: Long) = synchronized(lock) {
        if (registration?.token == token) {
            registration = null
            _isConnected.value = false
        }
    }

    fun currentGateway(): AccessibilityGateway? = synchronized(lock) {
        registration?.gateway
    }

    private data class GatewayRegistration(
        val token: Long,
        val gateway: AccessibilityGateway
    )
}

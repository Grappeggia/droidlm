package ai.droidlm.voice

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

data class WakeEvent(
    val phrase: String,
    val timestampMs: Long
)

interface WakeWordEngine {
    val isRunning: StateFlow<Boolean>
    val events: Flow<WakeEvent>
    suspend fun start()
    suspend fun stop()
}

class ManualWakeWordEngine : WakeWordEngine {
    private val _isRunning = MutableStateFlow(false)
    private val _events = MutableSharedFlow<WakeEvent>(extraBufferCapacity = 4)
    override val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()
    override val events: Flow<WakeEvent> = _events.asSharedFlow()

    override suspend fun start() { _isRunning.value = true }
    override suspend fun stop() { _isRunning.value = false }

    fun emitWake(phrase: String = "DroidLM") {
        _events.tryEmit(WakeEvent(phrase, System.currentTimeMillis()))
    }
}

class PorcupineWakeWordEngine : WakeWordEngine {
    private val _isRunning = MutableStateFlow(false)
    private val _events = MutableSharedFlow<WakeEvent>(extraBufferCapacity = 4)
    override val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()
    override val events: Flow<WakeEvent> = _events.asSharedFlow()

    override suspend fun start() {
        _isRunning.value = false
        throw IllegalStateException("Porcupine wake word is not bundled in this MVP. Configure Picovoice and add the SDK to enable it; push-to-talk remains available.")
    }

    override suspend fun stop() { _isRunning.value = false }
}

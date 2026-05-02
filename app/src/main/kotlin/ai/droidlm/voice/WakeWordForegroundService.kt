package ai.droidlm.voice

import ai.droidlm.DroidLMApp
import ai.droidlm.logs.ActionLogType
import ai.droidlm.relay.RelayCallResult
import ai.droidlm.settings.TranscriptionProvider
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class WakeWordForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var commandJob: Job? = null
    private lateinit var app: DroidLMApp

    override fun onCreate() {
        super.onCreate()
        app = application as DroidLMApp
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_LISTENING -> startListeningForeground()
            ACTION_STOP_LISTENING -> stopListening()
            ACTION_CANCEL -> cancelCurrent()
            ACTION_PUSH_TO_TALK -> startPushToTalk()
            else -> startListeningForeground()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        commandJob?.cancel()
        scope.cancel()
        isRunningState.value = false
        super.onDestroy()
    }

    private fun startListeningForeground() {
        isRunningState.value = true
        startForegroundSafely("DroidLM is listening for the wake phrase or push-to-talk.")
        app.actionLogRepository.log(ActionLogType.ACTION_RESULT, "Foreground listening service running")
    }

    private fun stopListening() {
        isRunningState.value = false
        commandJob?.cancel()
        app.commandRecorder.cancelCurrent()
        app.speechRecognitionController.cancelCurrent()
        stopForegroundCompat()
        stopSelf()
    }

    private fun cancelCurrent() {
        commandJob?.cancel()
        app.commandRecorder.cancelCurrent()
        app.speechRecognitionController.cancelCurrent()
        app.executor.cancelActive()
        app.actionLogRepository.log(ActionLogType.CANCELLED, "Current DroidLM task cancelled")
        if (!isRunningState.value) {
            stopForegroundCompat()
            stopSelf()
        }
    }

    private fun startPushToTalk() {
        startForegroundSafely("DroidLM is listening for your push-to-talk command.")
        app.actionLogRepository.log(ActionLogType.WAKE_DETECTED, "Push-to-talk started")
        commandJob?.cancel()
        commandJob = scope.launch {
            runCatching {
                val settings = app.settingsRepository.settings.first()
                when (settings.transcriptionProvider) {
                    TranscriptionProvider.ANDROID_SPEECH_RECOGNIZER -> {
                        app.actionLogRepository.log(ActionLogType.TRANSCRIPTION_REQUEST, "Using Android SpeechRecognizer")
                        val transcript = app.speechRecognitionController.recognizeCommand(
                            preferOffline = settings.preferOfflineSpeechRecognition
                        )
                        app.executor.planTranscript(transcript)
                    }
                    TranscriptionProvider.OPENAI_RELAY -> {
                        val recorded = app.commandRecorder.recordCommand()
                        try {
                            app.actionLogRepository.log(ActionLogType.TRANSCRIPTION_REQUEST, "Sending command audio to relay")
                            when (val transcription = app.relayClient.transcribe(settings.relayBaseUrl, recorded.file, recorded.mimeType)) {
                                is RelayCallResult.Success -> {
                                    app.actionLogRepository.log(ActionLogType.TRANSCRIPTION_RESULT, transcription.value.text)
                                    app.executor.planTranscript(transcription.value.text)
                                }
                                is RelayCallResult.Failure -> {
                                    app.actionLogRepository.log(ActionLogType.ERROR, transcription.message, transcription.errorCode)
                                }
                            }
                        } finally {
                            if (!settings.debugAudioRetention) recorded.file.delete()
                        }
                    }
                }
            }.onFailure { error ->
                app.actionLogRepository.log(ActionLogType.ERROR, error.message ?: "Push-to-talk failed")
            }
            if (!isRunningState.value) {
                stopForegroundCompat()
                stopSelf()
            } else {
                startForegroundSafely("DroidLM is listening for the wake phrase or push-to-talk.")
            }
        }
    }

    private fun startForegroundSafely(content: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(ai.droidlm.R.drawable.ic_droidlm)
            .setContentTitle("DroidLM")
            .setContentText(content)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Cancel task", pendingService(ACTION_CANCEL))
            .addAction(0, "Stop listening", pendingService(ACTION_STOP_LISTENING))
            .build()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && hasMicPermission()) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.onFailure {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun pendingService(action: String): PendingIntent {
        val intent = Intent(this, WakeWordForegroundService::class.java).setAction(action)
        return PendingIntent.getService(
            this,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "DroidLM listening", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Visible microphone/listening notification for DroidLM"
                }
            )
        }
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
    }

    companion object {
        const val ACTION_START_LISTENING = "ai.droidlm.action.START_LISTENING"
        const val ACTION_STOP_LISTENING = "ai.droidlm.action.STOP_LISTENING"
        const val ACTION_CANCEL = "ai.droidlm.action.CANCEL"
        const val ACTION_PUSH_TO_TALK = "ai.droidlm.action.PUSH_TO_TALK"
        private const val CHANNEL_ID = "droidlm_listening"
        private const val NOTIFICATION_ID = 4201
        val isRunningState = MutableStateFlow(false)

        fun intent(context: Context, action: String): Intent = Intent(context, WakeWordForegroundService::class.java).setAction(action)
    }
}

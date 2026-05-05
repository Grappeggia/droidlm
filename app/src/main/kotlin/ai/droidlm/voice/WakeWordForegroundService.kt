package ai.droidlm.voice

import ai.droidlm.DroidLMApp
import ai.droidlm.logs.ActionLogType
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
        val action = intent?.action
        val diagnosticSessionId = intent?.getStringExtra(EXTRA_DIAGNOSTIC_SESSION_ID)
        app.speechDiagnosticsLogger.record(
            diagnosticSessionId,
            "foreground_onStartCommand",
            mapOf("action" to (action ?: "default"), "startId" to startId, "flags" to flags)
        )
        when (action) {
            ACTION_START_LISTENING -> startListeningForeground()
            ACTION_STOP_LISTENING -> stopListening()
            ACTION_CANCEL -> cancelCurrent()
            ACTION_PUSH_TO_TALK -> startPushToTalk(diagnosticSessionId)
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
        app.speechDiagnosticsLogger.record(null, "foreground_listening_started")
        app.actionLogRepository.log(ActionLogType.ACTION_RESULT, "Foreground listening service running")
    }

    private fun stopListening() {
        app.speechDiagnosticsLogger.record(null, "foreground_stop_listening_requested")
        if (app.speechRecognitionController.stopCurrent()) return
        isRunningState.value = false
        commandJob?.cancel()
        app.commandRecorder.cancelCurrent()
        stopForegroundCompat()
        stopSelf()
    }

    private fun cancelCurrent() {
        app.speechDiagnosticsLogger.record(null, "foreground_cancel_requested")
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

    private fun startPushToTalk(diagnosticSessionId: String?) {
        val sessionId = diagnosticSessionId ?: app.speechDiagnosticsLogger.startSession("foreground_push_to_talk")
        startForegroundSafely("DroidLM is listening for your push-to-talk command.")
        app.actionLogRepository.log(ActionLogType.WAKE_DETECTED, "Push-to-talk started")
        app.speechDiagnosticsLogger.record(sessionId, "push_to_talk_started", mapOf("hadPreviousJob" to (commandJob != null)))
        commandJob?.cancel()
        commandJob = scope.launch {
            runCatching {
                val settings = app.settingsRepository.settings.first()
                app.speechDiagnosticsLogger.record(
                    sessionId,
                    "push_to_talk_settings_loaded",
                    mapOf("preferOfflineSpeechRecognition" to settings.preferOfflineSpeechRecognition)
                )
                app.actionLogRepository.log(ActionLogType.TRANSCRIPTION_REQUEST, "Starting push-to-talk speech recognition")
                val transcript = app.speechRecognitionController.recognizeCommand(
                    preferOffline = settings.preferOfflineSpeechRecognition,
                    diagnosticSessionId = sessionId
                )
                app.speechDiagnosticsLogger.record(
                    sessionId,
                    "push_to_talk_transcript_ready",
                    mapOf("transcriptLength" to transcript.length, "transcript" to transcript.take(160))
                )
                app.speechDiagnosticsLogger.record(sessionId, "push_to_talk_planning_started")
                val result = app.executor.planTranscript(transcript, sessionId)
                app.speechDiagnosticsLogger.record(
                    sessionId,
                    if (result.success) "push_to_talk_planning_succeeded" else "push_to_talk_planning_failed",
                    mapOf("message" to result.message, "errorCode" to result.errorCode)
                )
            }.onFailure { error ->
                val cancelled = error.message?.contains("cancelled", ignoreCase = true) == true
                app.speechDiagnosticsLogger.record(
                    sessionId,
                    if (cancelled) "push_to_talk_cancelled" else "push_to_talk_failed",
                    mapOf("errorClass" to error::class.java.name, "message" to error.message)
                )
                app.actionLogRepository.log(
                    if (cancelled) ActionLogType.CANCELLED else ActionLogType.ERROR,
                    error.message ?: "Push-to-talk failed"
                )
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

        const val EXTRA_DIAGNOSTIC_SESSION_ID = "ai.droidlm.extra.DIAGNOSTIC_SESSION_ID"

        fun intent(context: Context, action: String, diagnosticSessionId: String? = null): Intent =
            Intent(context, WakeWordForegroundService::class.java).setAction(action).apply {
                if (diagnosticSessionId != null) putExtra(EXTRA_DIAGNOSTIC_SESSION_ID, diagnosticSessionId)
            }
    }
}

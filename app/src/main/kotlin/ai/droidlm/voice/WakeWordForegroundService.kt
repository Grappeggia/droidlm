package ai.droidlm.voice

import ai.droidlm.di.appGraph
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

import kotlinx.coroutines.launch

class WakeWordForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val deps by lazy { applicationContext.appGraph().wakeWordForegroundServiceDeps() }
    private val pushToTalkSessionRunner by lazy {
        PushToTalkSessionRunner(deps) { extra -> serviceLifecycleFields(extra) }
    }
    private var commandJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        deps.speechDiagnosticsLogger.record(null, "foreground_service_created", serviceLifecycleFields())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val diagnosticSessionId = intent?.getStringExtra(EXTRA_DIAGNOSTIC_SESSION_ID)
        deps.speechDiagnosticsLogger.record(
            diagnosticSessionId,
            "foreground_onStartCommand",
            serviceLifecycleFields(mapOf("action" to (action ?: "default"), "startId" to startId, "flags" to flags))
        )
        when (action) {
            ACTION_START_LISTENING -> startListeningForeground(diagnosticSessionId)
            ACTION_STOP_LISTENING -> stopListening(diagnosticSessionId)
            ACTION_CANCEL -> cancelCurrent(diagnosticSessionId)
            ACTION_PUSH_TO_TALK -> startPushToTalk(diagnosticSessionId)
            else -> startListeningForeground(diagnosticSessionId)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        deps.speechDiagnosticsLogger.record(null, "foreground_service_destroyed", serviceLifecycleFields(mapOf("hadCommandJob" to (commandJob != null), "commandJobActive" to (commandJob?.isActive == true))))
        commandJob?.cancel()
        scope.cancel()
        deps.listeningRuntime.setRunning(false)
        super.onDestroy()
    }

    private fun startListeningForeground(diagnosticSessionId: String?) {
        deps.listeningRuntime.setRunning(true)
        startForegroundSafely("DroidLM is listening for the wake phrase or push-to-talk.", diagnosticSessionId)
        deps.speechDiagnosticsLogger.record(diagnosticSessionId, "foreground_listening_started", serviceLifecycleFields())
        deps.actionLogRepository.log(ActionLogType.ACTION_RESULT, "Foreground listening service running")
    }

    private fun stopListening(diagnosticSessionId: String?) {
        deps.speechDiagnosticsLogger.record(diagnosticSessionId, "foreground_stop_listening_requested", serviceLifecycleFields())
        if (deps.speechRecognitionController.stopCurrent()) return
        deps.listeningRuntime.setRunning(false)
        commandJob?.cancel()
        deps.commandRecorder.cancelCurrent()
        stopForegroundCompat()
        deps.speechDiagnosticsLogger.record(diagnosticSessionId, "foreground_stop_listening_completed", serviceLifecycleFields())
        stopSelf()
    }

    private fun cancelCurrent(diagnosticSessionId: String?) {
        deps.speechDiagnosticsLogger.record(diagnosticSessionId, "foreground_cancel_requested", serviceLifecycleFields())
        commandJob?.cancel()
        deps.commandRecorder.cancelCurrent()
        deps.speechRecognitionController.cancelCurrent()
        deps.executor.cancelActive()
        deps.actionLogRepository.log(ActionLogType.CANCELLED, "Current DroidLM task cancelled")
        if (!deps.listeningRuntime.isRunning.value) {
            stopForegroundCompat()
            deps.speechDiagnosticsLogger.record(diagnosticSessionId, "foreground_cancel_stopping_service", serviceLifecycleFields())
            stopSelf()
        }
    }

    private fun startPushToTalk(diagnosticSessionId: String?) {
        val sessionId = diagnosticSessionId ?: deps.speechDiagnosticsLogger.startSession("foreground_push_to_talk")
        val previousJob = commandJob
        val hadActiveJob = previousJob?.isActive == true
        startForegroundSafely(if (hadActiveJob) "DroidLM is processing your speech command." else "DroidLM is listening for your push-to-talk command.", sessionId)
        if (hadActiveJob) {
            deps.speechDiagnosticsLogger.record(
                sessionId,
                "push_to_talk_ignored_busy",
                mapOf(
                    "speechActive" to deps.speechRecognitionController.state.value.isActive,
                    "speechStopping" to deps.speechRecognitionController.state.value.isStopping,
                    "executionStatus" to deps.executor.uiState.value.status
                )
            )
            deps.actionLogRepository.log(ActionLogType.ACTION_RESULT, "Already processing speech")
            return
        }
        deps.actionLogRepository.log(ActionLogType.WAKE_DETECTED, "Push-to-talk started")
        deps.speechDiagnosticsLogger.record(
            sessionId,
            "push_to_talk_started",
            mapOf("hadPreviousJob" to (previousJob != null), "hadActiveJob" to false)
        )
        val job = scope.launch {
            pushToTalkSessionRunner.run(sessionId)
            if (!deps.listeningRuntime.isRunning.value) {
                stopForegroundCompat()
                deps.speechDiagnosticsLogger.record(sessionId, "push_to_talk_service_stopping_after_job", serviceLifecycleFields())
                stopSelf()
            } else {
                startForegroundSafely("DroidLM is listening for the wake phrase or push-to-talk.", sessionId)
            }
        }
        commandJob = job
        job.invokeOnCompletion {
            deps.speechDiagnosticsLogger.record(sessionId, "push_to_talk_job_completed", serviceLifecycleFields(mapOf("cancelled" to it?.message?.contains("cancel", ignoreCase = true), "errorClass" to it?.javaClass?.name, "message" to it?.message)))
            if (commandJob === job) commandJob = null
        }
    }

    private fun startForegroundSafely(content: String, diagnosticSessionId: String? = null) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(ai.droidlm.R.drawable.ic_stat_droidlm)
            .setContentTitle("DroidLM")
            .setContentText(content)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Cancel task", pendingService(ACTION_CANCEL))
            .addAction(0, "Stop listening", pendingService(ACTION_STOP_LISTENING))
            .build()
        val useMicrophoneType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && hasMicPermission()
        deps.speechDiagnosticsLogger.record(
            diagnosticSessionId,
            "foreground_notification_start_requested",
            serviceLifecycleFields(mapOf("contentLength" to content.length, "useMicrophoneForegroundType" to useMicrophoneType))
        )
        runCatching {
            if (useMicrophoneType) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.fold(
            onSuccess = { deps.speechDiagnosticsLogger.record(diagnosticSessionId, "foreground_notification_started", serviceLifecycleFields(mapOf("usedMicrophoneForegroundType" to useMicrophoneType))) },
            onFailure = { error ->
                deps.speechDiagnosticsLogger.record(diagnosticSessionId, "foreground_notification_start_failed", serviceLifecycleFields(mapOf("usedMicrophoneForegroundType" to useMicrophoneType, "errorClass" to error::class.java.name, "message" to error.message)))
                runCatching { startForeground(NOTIFICATION_ID, notification) }
                    .onSuccess { deps.speechDiagnosticsLogger.record(diagnosticSessionId, "foreground_notification_fallback_started", serviceLifecycleFields()) }
                    .onFailure { fallbackError ->
                        deps.speechDiagnosticsLogger.record(diagnosticSessionId, "foreground_notification_fallback_failed", serviceLifecycleFields(mapOf("errorClass" to fallbackError::class.java.name, "message" to fallbackError.message)))
                        throw fallbackError
                    }
            }
        )
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
            deps.speechDiagnosticsLogger.record(null, "foreground_notification_channel_ready", serviceLifecycleFields(mapOf("channelId" to CHANNEL_ID, "importance" to NotificationManager.IMPORTANCE_LOW)))
        }
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        deps.speechDiagnosticsLogger.record(null, "foreground_stop_foreground_requested", serviceLifecycleFields())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
        deps.speechDiagnosticsLogger.record(null, "foreground_stop_foreground_completed", serviceLifecycleFields())
    }

    private fun serviceLifecycleFields(extra: Map<String, Any?> = emptyMap()): Map<String, Any?> = mapOf(
        "runningState" to deps.listeningRuntime.isRunning.value,
        "hasMicPermission" to hasMicPermission(),
        "hasCommandJob" to (commandJob != null),
        "commandJobActive" to (commandJob?.isActive == true),
        "speechActive" to deps.speechRecognitionController.state.value.isActive,
        "speechStarting" to deps.speechRecognitionController.state.value.isStarting,
        "speechListening" to deps.speechRecognitionController.state.value.isListening,
        "speechStopping" to deps.speechRecognitionController.state.value.isStopping,
        "executionStatus" to deps.executor.uiState.value.status,
        "hasPendingPlan" to (deps.executor.pendingPlan.value != null)
    ) + extra

    companion object {
        const val ACTION_START_LISTENING = "ai.droidlm.action.START_LISTENING"
        const val ACTION_STOP_LISTENING = "ai.droidlm.action.STOP_LISTENING"
        const val ACTION_CANCEL = "ai.droidlm.action.CANCEL"
        const val ACTION_PUSH_TO_TALK = "ai.droidlm.action.PUSH_TO_TALK"
        private const val CHANNEL_ID = "droidlm_listening"
        private const val NOTIFICATION_ID = 4201

        const val EXTRA_DIAGNOSTIC_SESSION_ID = "ai.droidlm.extra.DIAGNOSTIC_SESSION_ID"

        fun intent(context: Context, action: String, diagnosticSessionId: String? = null): Intent =
            Intent(context, WakeWordForegroundService::class.java).setAction(action).apply {
                if (diagnosticSessionId != null) putExtra(EXTRA_DIAGNOSTIC_SESSION_ID, diagnosticSessionId)
            }
    }
}

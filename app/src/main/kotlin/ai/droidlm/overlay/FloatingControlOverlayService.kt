package ai.droidlm.overlay

import ai.droidlm.DroidLMApp
import ai.droidlm.MainActivity
import ai.droidlm.logs.ActionLogType
import ai.droidlm.voice.WakeWordForegroundService
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class FloatingControlOverlayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var app: DroidLMApp
    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var stateJob: Job? = null
    private var recordButton: Button? = null
    private var statusText: TextView? = null

    override fun onCreate() {
        super.onCreate()
        app = application as DroidLMApp
        windowManager = getSystemService(WindowManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopOverlay()
            ACTION_TOGGLE_RECORD -> toggleRecord()
            ACTION_OPEN_APP -> openFullApp()
            ACTION_SHOW, null -> showOverlay()
            else -> showOverlay()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopOverlay(removeSelf = false)
        scope.cancel()
        super.onDestroy()
    }

    private fun showOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            app.actionLogRepository.log(
                ActionLogType.ERROR,
                "Floating controls need Display over other apps permission",
                "OVERLAY_PERMISSION_MISSING"
            )
            stopSelf()
            return
        }
        if (overlayView != null) return
        scope.launch {
            val settings = app.settingsRepository.settings.first()
            val layoutParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = settings.overlayX
                y = settings.overlayY
            }
            params = layoutParams
            overlayView = createOverlayView(layoutParams)
            windowManager.addView(overlayView, layoutParams)
            isRunningState.value = true
            app.settingsRepository.updateFloatingOverlayEnabled(true)
            app.actionLogRepository.log(ActionLogType.ACTION_RESULT, "Floating controls shown")
            observeState()
        }
    }

    private fun createOverlayView(layoutParams: WindowManager.LayoutParams): View {
        val density = resources.displayMetrics.density
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((8 * density).toInt(), (6 * density).toInt(), (8 * density).toInt(), (6 * density).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 28 * density
                setColor(Color.argb(232, 21, 61, 59))
                setStroke((1 * density).toInt(), Color.argb(200, 231, 183, 95))
            }
        }

        recordButton = Button(this).apply {
            text = OverlayStatusFormatter.recordButton(false, "Idle")
            textSize = 18f
            minWidth = (48 * density).toInt()
            minHeight = (48 * density).toInt()
            setTextColor(Color.WHITE)
            setOnClickListener { toggleRecord() }
        }
        statusText = TextView(this).apply {
            text = "Tap circle to speak"
            textSize = 13f
            setTextColor(Color.WHITE)
            maxWidth = (220 * density).toInt()
            setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0)
        }
        val moreButton = Button(this).apply {
            text = "..."
            textSize = 18f
            minWidth = (48 * density).toInt()
            minHeight = (48 * density).toInt()
            setTextColor(Color.WHITE)
            setOnClickListener { openFullApp() }
        }

        pill.addView(recordButton)
        pill.addView(statusText)
        pill.addView(moreButton)
        attachDragHandler(pill, layoutParams)
        return pill
    }

    private fun attachDragHandler(view: View, layoutParams: WindowManager.LayoutParams) {
        var startX = 0
        var startY = 0
        var touchStartX = 0f
        var touchStartY = 0f
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = layoutParams.x
                    startY = layoutParams.y
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchStartX).toInt()
                    val dy = (event.rawY - touchStartY).toInt()
                    if (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8) {
                        layoutParams.x = (startX + dx).coerceAtLeast(0)
                        layoutParams.y = (startY + dy).coerceAtLeast(0)
                        overlayView?.let { windowManager.updateViewLayout(it, layoutParams) }
                        true
                    } else {
                        false
                    }
                }
                MotionEvent.ACTION_UP -> {
                    scope.launch { app.settingsRepository.updateOverlayPosition(layoutParams.x, layoutParams.y) }
                    false
                }
                else -> false
            }
        }
    }

    private fun observeState() {
        stateJob?.cancel()
        stateJob = scope.launch {
            launch {
                app.speechRecognitionController.state.collect { updateOverlayText() }
            }
            launch {
                app.executor.uiState.collect { updateOverlayText() }
            }
        }
    }

    private fun updateOverlayText() {
        val speech = app.speechRecognitionController.state.value
        val execution = app.executor.uiState.value
        recordButton?.text = OverlayStatusFormatter.recordButton(speech.isListening, execution.status)
        statusText?.text = OverlayStatusFormatter.label(
            isListening = speech.isListening,
            partialTranscript = speech.partialTranscript,
            finalTranscript = speech.finalTranscript,
            executionStatus = execution.status,
            lastResult = execution.lastResult
        )
    }

    private fun toggleRecord() {
        val speech = app.speechRecognitionController.state.value
        val execution = app.executor.uiState.value
        if (speech.isListening || execution.status !in setOf("Idle", "Error")) {
            startService(WakeWordForegroundService.intent(this, WakeWordForegroundService.ACTION_CANCEL))
        } else {
            ContextCompat.startForegroundService(
                this,
                WakeWordForegroundService.intent(this, WakeWordForegroundService.ACTION_PUSH_TO_TALK)
            )
        }
    }

    private fun openFullApp() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
    }

    private fun stopOverlay(removeSelf: Boolean = true) {
        stateJob?.cancel()
        stateJob = null
        overlayView?.let { view -> runCatching { windowManager.removeView(view) } }
        overlayView = null
        params = null
        isRunningState.value = false
        scope.launch { app.settingsRepository.updateFloatingOverlayEnabled(false) }
        if (removeSelf) stopSelf()
    }

    private fun overlayType(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
        @Suppress("DEPRECATION")
        WindowManager.LayoutParams.TYPE_PHONE
    }

    companion object {
        const val ACTION_SHOW = "ai.droidlm.action.SHOW_OVERLAY"
        const val ACTION_STOP = "ai.droidlm.action.STOP_OVERLAY"
        const val ACTION_TOGGLE_RECORD = "ai.droidlm.action.OVERLAY_TOGGLE_RECORD"
        const val ACTION_OPEN_APP = "ai.droidlm.action.OVERLAY_OPEN_APP"
        val isRunningState = MutableStateFlow(false)

        fun intent(context: Context, action: String): Intent =
            Intent(context, FloatingControlOverlayService::class.java).setAction(action)
    }
}

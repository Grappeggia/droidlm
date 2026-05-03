package ai.droidlm.overlay

import ai.droidlm.DroidLMApp
import ai.droidlm.MainActivity
import ai.droidlm.logs.ActionLogType
import ai.droidlm.voice.WakeWordForegroundService
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal object FloatingOverlayBounds {
    private const val MIN_BOTTOM_GUARD_DP = 48f
    private const val EDGE_MARGIN_DP = 8f

    fun safeY(
        requestedY: Int,
        displayHeight: Int,
        viewHeight: Int,
        bottomInset: Int,
        density: Float
    ): Int {
        val bottomGuard = kotlin.math.max(bottomInset, (MIN_BOTTOM_GUARD_DP * density).toInt())
        val edgeMargin = (EDGE_MARGIN_DP * density).toInt()
        val safeMaxY = (displayHeight - bottomGuard - edgeMargin - viewHeight).coerceAtLeast(0)
        return requestedY.coerceIn(0, safeMaxY)
    }
}

class FloatingControlOverlayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var app: DroidLMApp
    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var stateJob: Job? = null
    private var recordButton: Button? = null
    private var acceptPlanButton: Button? = null
    private var rejectPlanButton: Button? = null
    private var enableAccessibilityButton: Button? = null
    private var checkAccessibilityButton: Button? = null
    private var moreButton: Button? = null
    private var statusText: TextView? = null
    private var accessibilityEnabled: Boolean = false
    private var accessibilityPollingJob: Job? = null

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
            ACTION_OPEN_ACCESSIBILITY_SETTINGS -> openAccessibilitySettings()
            ACTION_CHECK_ACCESSIBILITY -> refreshAccessibilityStatus()
            ACTION_SHOW, null -> showOverlay()
            else -> showOverlay()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        overlayView?.post { applySafeOverlayPosition(persist = true) }
    }

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
        if (overlayView != null) {
            overlayView?.post { applySafeOverlayPosition(persist = true) }
            return
        }
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
            val view = createOverlayView(layoutParams)
            view.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            val clampedOnStart = clampLayoutParamsY(layoutParams, view.measuredHeight)
            params = layoutParams
            overlayView = view
            windowManager.addView(view, layoutParams)
            isRunningState.value = true
            app.settingsRepository.updateFloatingOverlayEnabled(true)
            if (clampedOnStart) app.settingsRepository.updateOverlayPosition(layoutParams.x, layoutParams.y)
            app.actionLogRepository.log(ActionLogType.ACTION_RESULT, "Floating controls shown")
            observeState()
            refreshAccessibilityStatus()
        }
    }

    private fun createOverlayView(layoutParams: WindowManager.LayoutParams): View {
        val density = resources.displayMetrics.density
        val edgePadding = (1.6f * density).toInt().coerceAtLeast(1)
        val verticalPadding = (1.2f * density).toInt().coerceAtLeast(1)
        val buttonSize = (48 * density).toInt()
        fun Button.applySquareButton(size: Int) {
            minWidth = size
            minimumWidth = size
            minHeight = size
            minimumHeight = size
            this.layoutParams = LinearLayout.LayoutParams(size, size)
            setPadding(0, 0, 0, 0)
            setTextColor(Color.WHITE)
        }
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(edgePadding, verticalPadding, edgePadding, verticalPadding)
            background = GradientDrawable().apply {
                cornerRadius = 8 * density
                setColor(Color.argb(232, 21, 61, 59))
                setStroke((1 * density).toInt(), Color.TRANSPARENT)
            }
        }

        recordButton = Button(this).apply {
            text = OverlayStatusFormatter.recordButton(false, "Idle")
            contentDescription = RECORD_BUTTON_CONTENT_DESCRIPTION
            textSize = 18f
            applySquareButton(buttonSize)
            setOnClickListener { toggleRecord() }
        }
        statusText = TextView(this).apply {
            text = "Tap circle to speak"
            textSize = 13f
            setTextColor(Color.WHITE)
            maxWidth = (220 * density).toInt()
            setPadding(edgePadding, 0, edgePadding, 0)
        }
        acceptPlanButton = Button(this).apply {
            text = "Y"
            contentDescription = ACCEPT_PLAN_BUTTON_CONTENT_DESCRIPTION
            textSize = 16f
            applySquareButton(buttonSize)
            visibility = View.GONE
            setOnClickListener { acceptPendingPlan() }
        }
        rejectPlanButton = Button(this).apply {
            text = "N"
            contentDescription = REJECT_PLAN_BUTTON_CONTENT_DESCRIPTION
            textSize = 16f
            applySquareButton(buttonSize)
            visibility = View.GONE
            setOnClickListener { rejectPendingPlan() }
        }
        enableAccessibilityButton = Button(this).apply {
            text = "Enable"
            contentDescription = ENABLE_ACCESSIBILITY_BUTTON_CONTENT_DESCRIPTION
            textSize = 13f
            minWidth = (56 * density).toInt()
            minimumWidth = (56 * density).toInt()
            minHeight = (48 * density).toInt()
            setPadding((4 * density).toInt(), 0, (4 * density).toInt(), 0)
            setTextColor(Color.WHITE)
            visibility = View.GONE
            setOnClickListener { openAccessibilitySettings() }
        }
        checkAccessibilityButton = Button(this).apply {
            text = "Check"
            contentDescription = CHECK_ACCESSIBILITY_BUTTON_CONTENT_DESCRIPTION
            textSize = 13f
            minWidth = (52 * density).toInt()
            minimumWidth = (52 * density).toInt()
            minHeight = (48 * density).toInt()
            setPadding((4 * density).toInt(), 0, (4 * density).toInt(), 0)
            setTextColor(Color.WHITE)
            visibility = View.GONE
            setOnClickListener { refreshAccessibilityStatus() }
        }
        moreButton = Button(this).apply {
            text = "..."
            contentDescription = MORE_BUTTON_CONTENT_DESCRIPTION
            textSize = 18f
            applySquareButton(buttonSize)
            setOnClickListener { openFullApp() }
        }

        pill.addView(recordButton)
        pill.addView(acceptPlanButton)
        pill.addView(rejectPlanButton)
        pill.addView(enableAccessibilityButton)
        pill.addView(checkAccessibilityButton)
        pill.addView(moreButton)
        pill.addView(statusText)
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
                        layoutParams.y = safeOverlayY(startY + dy, view.height)
                        overlayView?.let { windowManager.updateViewLayout(it, layoutParams) }
                        true
                    } else {
                        false
                    }
                }
                MotionEvent.ACTION_UP -> {
                    applySafeOverlayPosition(persist = false)
                    scope.launch { app.settingsRepository.updateOverlayPosition(layoutParams.x, layoutParams.y) }
                    false
                }
                else -> false
            }
        }
    }

    private fun applySafeOverlayPosition(persist: Boolean) {
        val layoutParams = params ?: return
        val view = overlayView ?: return
        val oldY = layoutParams.y
        val clamped = clampLayoutParamsY(layoutParams, view.height)
        if (clamped) runCatching { windowManager.updateViewLayout(view, layoutParams) }
        if (persist && (clamped || oldY != layoutParams.y)) {
            scope.launch { app.settingsRepository.updateOverlayPosition(layoutParams.x, layoutParams.y) }
        }
    }

    private fun clampLayoutParamsY(layoutParams: WindowManager.LayoutParams, measuredHeight: Int): Boolean {
        val safeY = safeOverlayY(layoutParams.y, measuredHeight)
        if (safeY == layoutParams.y) return false
        layoutParams.y = safeY
        return true
    }

    private fun safeOverlayY(requestedY: Int, measuredHeight: Int): Int {
        val density = resources.displayMetrics.density
        val fallbackHeight = (64 * density).toInt()
        return FloatingOverlayBounds.safeY(
            requestedY = requestedY,
            displayHeight = displayHeight(),
            viewHeight = measuredHeight.takeIf { it > 0 } ?: fallbackHeight,
            bottomInset = bottomSystemInset(),
            density = density
        )
    }

    private fun displayHeight(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds.height()
        } else {
            @Suppress("DEPRECATION")
            resources.displayMetrics.heightPixels
        }
    }

    private fun bottomSystemInset(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return windowManager.currentWindowMetrics.windowInsets
                .getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars())
                .bottom
        }
        val resourceId = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (resourceId > 0) resources.getDimensionPixelSize(resourceId) else 0
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
            launch {
                app.executor.pendingPlan.collect { updateOverlayText() }
            }
        }
    }

    private fun updateOverlayText() {
        val pendingPlan = app.executor.pendingPlan.value
        val speech = app.speechRecognitionController.state.value
        val execution = app.executor.uiState.value
        val hasPendingPlan = pendingPlan != null
        if (!accessibilityEnabled) {
            recordButton?.visibility = View.GONE
            acceptPlanButton?.visibility = View.GONE
            rejectPlanButton?.visibility = View.GONE
            enableAccessibilityButton?.visibility = View.VISIBLE
            checkAccessibilityButton?.visibility = View.VISIBLE
            moreButton?.visibility = View.VISIBLE
            statusText?.text = OverlayStatusFormatter.accessibilitySetupLabel()
            return
        }
        recordButton?.visibility = View.VISIBLE
        enableAccessibilityButton?.visibility = View.GONE
        checkAccessibilityButton?.visibility = View.GONE
        acceptPlanButton?.visibility = if (hasPendingPlan) View.VISIBLE else View.GONE
        rejectPlanButton?.visibility = if (hasPendingPlan) View.VISIBLE else View.GONE
        moreButton?.visibility = if (hasPendingPlan) View.GONE else View.VISIBLE
        recordButton?.text = OverlayStatusFormatter.recordButton(speech.isListening, execution.status)
        statusText?.text = pendingPlan?.let { OverlayStatusFormatter.compactPlan(it.plan) } ?: OverlayStatusFormatter.label(
            isListening = speech.isListening,
            partialTranscript = speech.partialTranscript,
            finalTranscript = speech.finalTranscript,
            executionStatus = execution.status,
            lastResult = execution.lastResult
        )
    }

    private fun toggleRecord() {
        scope.launch {
            if (!app.portalController.isAccessibilityEnabled()) {
                accessibilityEnabled = false
                updateOverlayText()
                return@launch
            }
            accessibilityEnabled = true
            updateOverlayText()
            val speech = app.speechRecognitionController.state.value
            val execution = app.executor.uiState.value
            if (speech.isListening) {
                startService(WakeWordForegroundService.intent(this@FloatingControlOverlayService, WakeWordForegroundService.ACTION_STOP_LISTENING))
            } else if (execution.status !in setOf("Idle", "Error", "Cancelled")) {
                startService(WakeWordForegroundService.intent(this@FloatingControlOverlayService, WakeWordForegroundService.ACTION_CANCEL))
            } else {
                ContextCompat.startForegroundService(
                    this@FloatingControlOverlayService,
                    WakeWordForegroundService.intent(this@FloatingControlOverlayService, WakeWordForegroundService.ACTION_PUSH_TO_TALK)
                )
            }
        }
    }

    private fun acceptPendingPlan() {
        scope.launch { app.executor.acceptPendingPlan(false) }
    }

    private fun rejectPendingPlan() {
        app.executor.rejectPendingPlan()
    }


    private fun openAccessibilitySettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.fold(
            onSuccess = {
                app.actionLogRepository.log(ActionLogType.ACTION_RESULT, "Opened Accessibility Settings from floating controls")
                startAccessibilityPolling()
            },
            onFailure = { error ->
                app.actionLogRepository.log(ActionLogType.ERROR, "Failed to open Accessibility Settings: ${error.message}", "OPEN_ACCESSIBILITY_SETTINGS_FAILED")
                openFullApp()
            }
        )
    }

    private fun refreshAccessibilityStatus() {
        scope.launch {
            accessibilityEnabled = app.portalController.isAccessibilityEnabled()
            if (accessibilityEnabled) accessibilityPollingJob?.cancel()
            updateOverlayText()
        }
    }

    private fun startAccessibilityPolling() {
        accessibilityPollingJob?.cancel()
        accessibilityPollingJob = scope.launch {
            repeat(60) {
                accessibilityEnabled = app.portalController.isAccessibilityEnabled()
                updateOverlayText()
                if (accessibilityEnabled) return@launch
                delay(1000)
            }
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
        accessibilityPollingJob?.cancel()
        accessibilityPollingJob = null
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
        const val ACTION_OPEN_ACCESSIBILITY_SETTINGS = "ai.droidlm.action.OVERLAY_OPEN_ACCESSIBILITY_SETTINGS"
        const val ACTION_CHECK_ACCESSIBILITY = "ai.droidlm.action.OVERLAY_CHECK_ACCESSIBILITY"
        const val RECORD_BUTTON_CONTENT_DESCRIPTION = "DroidLM record command"
        const val MORE_BUTTON_CONTENT_DESCRIPTION = "DroidLM open full app"
        const val ACCEPT_PLAN_BUTTON_CONTENT_DESCRIPTION = "DroidLM accept plan"
        const val REJECT_PLAN_BUTTON_CONTENT_DESCRIPTION = "DroidLM reject plan"
        const val ENABLE_ACCESSIBILITY_BUTTON_CONTENT_DESCRIPTION = "DroidLM enable Accessibility service"
        const val CHECK_ACCESSIBILITY_BUTTON_CONTENT_DESCRIPTION = "DroidLM check Accessibility service"
        val isRunningState = MutableStateFlow(false)

        fun intent(context: Context, action: String): Intent =
            Intent(context, FloatingControlOverlayService::class.java).setAction(action)
    }
}

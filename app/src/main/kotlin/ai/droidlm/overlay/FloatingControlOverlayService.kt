package ai.droidlm.overlay

import ai.droidlm.DroidLMApp
import ai.droidlm.MainActivity
import ai.droidlm.logs.ActionLogType
import ai.droidlm.permissions.RecordingPermissionActivity
import ai.droidlm.voice.WakeWordForegroundService
import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
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

internal object FloatingOverlayDismissTarget {
    private const val TARGET_SIZE_DP = 64f
    private const val TARGET_MARGIN_BOTTOM_DP = 24f
    private const val TARGET_LABEL_HEIGHT_DP = 20f
    private const val HOVER_EXPANSION_DP = 10f

    fun targetRect(
        displayWidth: Int,
        displayHeight: Int,
        bottomInset: Int,
        density: Float
    ): Rect {
        val targetSize = (TARGET_SIZE_DP * density).toInt()
        val labelHeight = (TARGET_LABEL_HEIGHT_DP * density).toInt()
        val targetHeight = targetSize + labelHeight
        val bottomMargin = kotlin.math.max(bottomInset, (TARGET_MARGIN_BOTTOM_DP * density).toInt())
        val left = ((displayWidth - targetSize) / 2).coerceAtLeast(0)
        val top = (displayHeight - bottomMargin - targetHeight).coerceAtLeast(0)
        return Rect(left, top, left + targetSize, top + targetSize)
    }

    fun isWithinDismissZone(
        centerX: Int,
        centerY: Int,
        targetRect: Rect,
        density: Float
    ): Boolean {
        val expansion = (HOVER_EXPANSION_DP * density).toInt()
        val left = targetRect.left - expansion
        val top = targetRect.top - expansion
        val right = targetRect.right + expansion
        val bottom = targetRect.bottom + expansion
        return centerX >= left && centerX < right && centerY >= top && centerY < bottom
    }
}

class FloatingControlOverlayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var app: DroidLMApp
    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var dismissTargetView: View? = null
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
    private var accessibilitySettingsOpened: Boolean = false
    private var accessibilityPollingJob: Job? = null
    private var transientStatusMessage: String? = null
    private var isDraggingOverlay: Boolean = false
    private var isInDismissZone: Boolean = false
    private var lastRecordButtonText: String? = null

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
            ACTION_MIC_PERMISSION_READY -> showMicPermissionReady()
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
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var startX = 0
        var startY = 0
        var touchStartX = 0f
        var touchStartY = 0f
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    isDraggingOverlay = false
                    isInDismissZone = false
                    startX = layoutParams.x
                    startY = layoutParams.y
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchStartX).toInt()
                    val dy = (event.rawY - touchStartY).toInt()
                    if (!isDraggingOverlay && (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop)) {
                        isDraggingOverlay = true
                        showDismissTarget()
                    }
                    if (isDraggingOverlay) {
                        val candidateX = (startX + dx).coerceAtLeast(0)
                        val candidateY = safeOverlayY(startY + dy, view.height)
                        val hovered = updateDismissTargetHover(candidateX, candidateY, view.width, view.height)
                        if (hovered) {
                            val targetRect = dismissTargetRect()
                            val snappedX = (targetRect.centerX() - (view.width / 2)).coerceAtLeast(0)
                            val snappedY = safeOverlayY(targetRect.centerY() - (view.height / 2), view.height)
                            layoutParams.x = ((candidateX * 2) + snappedX) / 3
                            layoutParams.y = ((candidateY * 2) + snappedY) / 3
                        } else {
                            layoutParams.x = candidateX
                            layoutParams.y = candidateY
                        }
                        overlayView?.let { windowManager.updateViewLayout(it, layoutParams) }
                        true
                    } else {
                        false
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val droppedOnDismiss = isDraggingOverlay && isInDismissZone
                    hideDismissTarget()
                    isDraggingOverlay = false
                    isInDismissZone = false
                    if (droppedOnDismiss) {
                        app.actionLogRepository.log(ActionLogType.ACTION_RESULT, "Removed floating controls from dismiss target")
                        stopOverlay()
                        true
                    } else {
                        applySafeOverlayPosition(persist = false)
                        scope.launch { app.settingsRepository.updateOverlayPosition(layoutParams.x, layoutParams.y) }
                        false
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    hideDismissTarget()
                    isDraggingOverlay = false
                    isInDismissZone = false
                    false
                }
                else -> false
            }
        }
    }

    private fun showDismissTarget() {
        if (dismissTargetView != null) return
        val density = resources.displayMetrics.density
        val targetSize = (64 * density).toInt()
        val labelHeight = (20 * density).toInt()
        val targetWidth = targetSize
        val targetHeight = targetSize + labelHeight
        val targetRect = dismissTargetRect()
        val layoutParams = WindowManager.LayoutParams(
            targetWidth,
            targetHeight,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = targetRect.left
            y = targetRect.top
        }
        val targetView = createDismissTargetView(targetSize, labelHeight)
        dismissTargetView = targetView
        windowManager.addView(targetView, layoutParams)
        targetView.alpha = 0f
        targetView.scaleX = 0.9f
        targetView.scaleY = 0.9f
        targetView.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160).start()
    }

    private fun hideDismissTarget() {
        updateDismissTargetAppearance(false)
        dismissTargetView?.animate()?.cancel()
        dismissTargetView?.let { view -> runCatching { windowManager.removeView(view) } }
        dismissTargetView = null
    }

    private fun createDismissTargetView(targetSize: Int, labelHeight: Int): View {
        val density = resources.displayMetrics.density
        val circleBackground = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb(230, 32, 32, 32))
        }
        val circle = TextView(this).apply {
            text = "X"
            gravity = Gravity.CENTER
            textSize = 28f
            setTextColor(Color.WHITE)
            background = circleBackground
            contentDescription = "Remove DroidLM floating controls"
            layoutParams = LinearLayout.LayoutParams(targetSize, targetSize)
        }
        val label = TextView(this).apply {
            text = "Remove"
            gravity = Gravity.CENTER
            textSize = 12f
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, labelHeight)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
            setPadding((6 * density).toInt(), 0, (6 * density).toInt(), 0)
            addView(circle)
            addView(label)
            tag = circleBackground
        }
    }

    private fun updateDismissTargetHover(candidateX: Int, candidateY: Int, viewWidth: Int, viewHeight: Int): Boolean {
        val hovered = FloatingOverlayDismissTarget.isWithinDismissZone(
            centerX = candidateX + (viewWidth / 2),
            centerY = candidateY + (viewHeight / 2),
            targetRect = dismissTargetRect(),
            density = resources.displayMetrics.density
        )
        if (hovered != isInDismissZone) {
            isInDismissZone = hovered
            updateDismissTargetAppearance(hovered)
            if (hovered) overlayView?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
        return hovered
    }

    private fun updateDismissTargetAppearance(hovered: Boolean) {
        val targetView = dismissTargetView ?: return
        val background = targetView.tag as? GradientDrawable ?: return
        val color = if (hovered) Color.argb(245, 198, 40, 40) else Color.argb(230, 32, 32, 32)
        background.setColor(color)
        targetView.animate().cancel()
        targetView.animate()
            .scaleX(if (hovered) 1.12f else 1f)
            .scaleY(if (hovered) 1.12f else 1f)
            .alpha(1f)
            .setDuration(120)
            .start()
    }

    private fun dismissTargetRect(): Rect = FloatingOverlayDismissTarget.targetRect(
        displayWidth = displayWidth(),
        displayHeight = displayHeight(),
        bottomInset = bottomSystemInset(),
        density = resources.displayMetrics.density
    )

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

    private fun displayWidth(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds.width()
        } else {
            @Suppress("DEPRECATION")
            resources.displayMetrics.widthPixels
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
            statusText?.text = OverlayStatusFormatter.accessibilitySetupLabel(settingsOpened = accessibilitySettingsOpened)
            return
        }
        recordButton?.visibility = View.VISIBLE
        enableAccessibilityButton?.visibility = View.GONE
        checkAccessibilityButton?.visibility = View.GONE
        acceptPlanButton?.visibility = if (hasPendingPlan) View.VISIBLE else View.GONE
        rejectPlanButton?.visibility = if (hasPendingPlan) View.VISIBLE else View.GONE
        moreButton?.visibility = if (hasPendingPlan) View.GONE else View.VISIBLE
        val recordText = OverlayStatusFormatter.recordButton(speech.isActive, execution.status)
        if (recordText != lastRecordButtonText) {
            lastRecordButtonText = recordText
            app.speechDiagnosticsLogger.record(
                null,
                "overlay_record_button_state",
                mapOf(
                    "text" to recordText,
                    "speechStarting" to speech.isStarting,
                    "speechListening" to speech.isListening,
                    "executionStatus" to execution.status
                )
            )
        }
        recordButton?.text = recordText
        transientStatusMessage?.let { message ->
            statusText?.text = message
            return
        }
        statusText?.text = pendingPlan?.let { OverlayStatusFormatter.compactPlan(it.plan) } ?: OverlayStatusFormatter.label(
            isStarting = speech.isStarting,
            isListening = speech.isListening,
            partialTranscript = speech.partialTranscript,
            finalTranscript = speech.finalTranscript,
            executionStatus = execution.status,
            lastResult = execution.lastResult
        )
    }

    private fun toggleRecord() {
        val diagnosticSessionId = app.speechDiagnosticsLogger.startSession(
            "overlay_record_tap",
            mapOf(
                "hasMicPermission" to hasMicPermission(),
                "accessibilityEnabledCached" to accessibilityEnabled,
                "overlayVisible" to (overlayView != null)
            )
        )
        transientStatusMessage = null
        scope.launch {
            val currentAccessibilityEnabled = app.portalController.isAccessibilityEnabled()
            app.speechDiagnosticsLogger.record(
                diagnosticSessionId,
                "overlay_record_accessibility_checked",
                mapOf("enabled" to currentAccessibilityEnabled)
            )
            if (!currentAccessibilityEnabled) {
                accessibilityEnabled = false
                updateOverlayText()
                app.speechDiagnosticsLogger.endSession(diagnosticSessionId, "accessibility_missing")
                return@launch
            }
            accessibilityEnabled = true
            updateOverlayText()
            val speech = app.speechRecognitionController.state.value
            val execution = app.executor.uiState.value
            if (speech.isActive) {
                app.speechDiagnosticsLogger.record(diagnosticSessionId, "overlay_record_stopping_active_speech")
                startService(WakeWordForegroundService.intent(this@FloatingControlOverlayService, WakeWordForegroundService.ACTION_STOP_LISTENING, diagnosticSessionId))
            } else if (execution.status !in setOf("Idle", "Error", "Cancelled")) {
                app.speechDiagnosticsLogger.record(diagnosticSessionId, "overlay_record_cancelling_execution", mapOf("executionStatus" to execution.status))
                startService(WakeWordForegroundService.intent(this@FloatingControlOverlayService, WakeWordForegroundService.ACTION_CANCEL, diagnosticSessionId))
            } else if (!hasMicPermission()) {
                app.speechDiagnosticsLogger.record(diagnosticSessionId, "overlay_record_missing_mic_permission")
                app.speechDiagnosticsLogger.endSession(diagnosticSessionId, "mic_permission_missing")
                statusText?.text = OverlayStatusFormatter.microphonePermissionLabel()
                app.actionLogRepository.log(ActionLogType.ERROR, "Microphone permission is required for push-to-talk", "RECORD_AUDIO_PERMISSION_MISSING")
                startActivity(RecordingPermissionActivity.intent(this@FloatingControlOverlayService))
            } else {
                startPushToTalkService(diagnosticSessionId)
            }
        }
    }

    private fun acceptPendingPlan() {
        scope.launch { app.executor.acceptPendingPlan(false) }
    }

    private fun rejectPendingPlan() {
        app.executor.rejectPendingPlan()
    }


    private fun showMicPermissionReady() {
        showOverlay()
        scope.launch {
            accessibilityEnabled = true
            val diagnosticSessionId = app.speechDiagnosticsLogger.startSession("overlay_mic_permission_ready")
            transientStatusMessage = OverlayStatusFormatter.microphoneStartingLabel()
            updateOverlayText()
            delay(600)
            startPushToTalkService(diagnosticSessionId)
            repeat(100) {
                val speech = app.speechRecognitionController.state.value
                if (speech.isListening) {
                    transientStatusMessage = OverlayStatusFormatter.microphoneReadyLabel()
                    updateOverlayText()
                    return@launch
                }
                if (speech.isStarting) {
                    transientStatusMessage = OverlayStatusFormatter.microphoneStartingLabel()
                    updateOverlayText()
                }
                delay(50)
            }
            transientStatusMessage = null
            updateOverlayText()
        }
    }

    private fun startPushToTalkService(diagnosticSessionId: String? = null) {
        app.speechDiagnosticsLogger.record(diagnosticSessionId, "overlay_start_push_to_talk_service")
        ContextCompat.startForegroundService(
            this,
            WakeWordForegroundService.intent(this, WakeWordForegroundService.ACTION_PUSH_TO_TALK, diagnosticSessionId)
        )
    }


    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED


    private fun openAccessibilitySettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.fold(
            onSuccess = {
                accessibilitySettingsOpened = true
                updateOverlayText()
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
            if (accessibilityEnabled) {
                accessibilitySettingsOpened = false
                accessibilityPollingJob?.cancel()
            }
            updateOverlayText()
        }
    }

    private fun startAccessibilityPolling() {
        accessibilityPollingJob?.cancel()
        accessibilityPollingJob = scope.launch {
            repeat(60) {
                accessibilityEnabled = app.portalController.isAccessibilityEnabled()
                if (accessibilityEnabled) accessibilitySettingsOpened = false
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
        hideDismissTarget()
        isDraggingOverlay = false
        isInDismissZone = false
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
        const val ACTION_MIC_PERMISSION_READY = "ai.droidlm.action.MIC_PERMISSION_READY"
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

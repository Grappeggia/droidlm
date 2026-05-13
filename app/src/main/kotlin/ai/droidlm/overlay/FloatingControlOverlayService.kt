package ai.droidlm.overlay

import ai.droidlm.MainActivity
import ai.droidlm.di.appGraph
import ai.droidlm.execution.PendingConfirmation
import ai.droidlm.execution.PendingPlan
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
import android.graphics.Typeface
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

private sealed interface OverlayPendingDecision {
    val headerLabel: String
    val title: String
    val details: String
    val primaryButtonLabel: String
    val secondaryButtonLabel: String

    data class Confirmation(val pending: PendingConfirmation) : OverlayPendingDecision {
        override val headerLabel: String = "Confirm action"
        override val title: String = "Confirmation required"
        override val details: String = OverlayStatusFormatter.confirmationDetails(pending)
        override val primaryButtonLabel: String = "Confirm"
        override val secondaryButtonLabel: String = "Cancel"
    }

    data class Plan(val pending: PendingPlan) : OverlayPendingDecision {
        override val headerLabel: String = "Review plan"
        override val title: String = if (pending.plan.isSafe) "Plan ready" else "Review plan"
        override val details: String = OverlayStatusFormatter.fullPlan(pending.plan)
        override val primaryButtonLabel: String = "Run plan"
        override val secondaryButtonLabel: String = "Reject"
    }
}

class FloatingControlOverlayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val deps by lazy { applicationContext.appGraph().overlayServiceDeps() }
    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var dismissTargetView: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var stateJob: Job? = null
    private var recordButton: Button? = null
    private var primaryDecisionButton: Button? = null
    private var secondaryDecisionButton: Button? = null
    private var enableAccessibilityButton: Button? = null
    private var checkAccessibilityButton: Button? = null
    private var moreButton: Button? = null
    private var statusText: TextView? = null
    private var reviewCard: LinearLayout? = null
    private var reviewTitleText: TextView? = null
    private var reviewDetailsText: TextView? = null
    private var accessibilityEnabled: Boolean = false
    private var accessibilitySettingsOpened: Boolean = false
    private var accessibilityPollingJob: Job? = null
    private var transientStatusMessage: String? = null
    private var isDraggingOverlay: Boolean = false
    private var isInDismissZone: Boolean = false
    private var lastRecordButtonText: String? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        deps.speechDiagnosticsLogger.record(null, "overlay_service_created", overlayLifecycleFields())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        deps.speechDiagnosticsLogger.record(
            null,
            "overlay_onStartCommand",
            overlayLifecycleFields(mapOf("action" to (action ?: "default"), "flags" to flags, "startId" to startId))
        )
        when (action) {
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
        deps.speechDiagnosticsLogger.record(
            null,
            "overlay_configuration_changed",
            overlayLifecycleFields(mapOf("orientation" to newConfig.orientation, "screenLayout" to newConfig.screenLayout))
        )
        overlayView?.post { applySafeOverlayPosition(persist = true) }
    }

    override fun onDestroy() {
        deps.speechDiagnosticsLogger.record(null, "overlay_service_destroyed", overlayLifecycleFields(mapOf("hadOverlayView" to (overlayView != null))))
        stopOverlay(removeSelf = false)
        scope.cancel()
        super.onDestroy()
    }

    private fun showOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            deps.speechDiagnosticsLogger.record(null, "overlay_show_denied", overlayLifecycleFields(mapOf("reason" to "overlay_permission_missing")))
            deps.actionLogRepository.log(
                ActionLogType.ERROR,
                "Floating controls need Display over other apps permission",
                "OVERLAY_PERMISSION_MISSING"
            )
            stopSelf()
            return
        }
        if (overlayView != null) {
            deps.speechDiagnosticsLogger.record(null, "overlay_show_existing", overlayLifecycleFields())
            overlayView?.post { applySafeOverlayPosition(persist = true) }
            return
        }
        scope.launch {
            val settings = deps.settingsRepository.settings.first()
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
            deps.speechDiagnosticsLogger.record(
                null,
                "overlay_add_view_started",
                overlayLifecycleFields(
                    mapOf(
                        "requestedX" to layoutParams.x,
                        "requestedY" to settings.overlayY,
                        "clampedY" to layoutParams.y,
                        "clampedOnStart" to clampedOnStart,
                        "overlayType" to overlayType(),
                        "screenWidth" to displayWidth(),
                        "screenHeight" to displayHeight(),
                        "bottomInset" to bottomSystemInset()
                    )
                )
            )
            params = layoutParams
            overlayView = view
            runCatching { windowManager.addView(view, layoutParams) }
                .onFailure { error ->
                    overlayView = null
                    params = null
                    deps.speechDiagnosticsLogger.record(
                        null,
                        "overlay_add_view_failed",
                        overlayLifecycleFields(mapOf("errorClass" to error::class.java.name, "message" to error.message))
                    )
                    throw error
                }
            deps.overlayRuntime.setRunning(true)
            deps.settingsRepository.updateFloatingOverlayEnabled(true)
            if (clampedOnStart) deps.settingsRepository.updateOverlayPosition(layoutParams.x, layoutParams.y)
            deps.speechDiagnosticsLogger.record(
                null,
                "overlay_add_view_succeeded",
                overlayLifecycleFields(mapOf("x" to layoutParams.x, "y" to layoutParams.y, "width" to view.measuredWidth, "height" to view.measuredHeight))
            )
            deps.actionLogRepository.log(ActionLogType.ACTION_RESULT, "Floating controls shown")
            observeState()
            refreshAccessibilityStatus()
        }
    }

    private fun createOverlayView(layoutParams: WindowManager.LayoutParams): View {
        val density = resources.displayMetrics.density
        val edgePadding = (1.6f * density).toInt().coerceAtLeast(1)
        val verticalPadding = (1.2f * density).toInt().coerceAtLeast(1)
        val buttonSize = (48 * density).toInt()
        val reviewSpacing = (6 * density).toInt()
        fun Button.applySquareButton(size: Int) {
            minWidth = size
            minimumWidth = size
            minHeight = size
            minimumHeight = size
            this.layoutParams = LinearLayout.LayoutParams(size, size)
            setPadding(0, 0, 0, 0)
            setTextColor(Color.WHITE)
        }
        fun Button.applyActionButton(minWidthDp: Float) {
            minWidth = (minWidthDp * density).toInt()
            minimumWidth = (minWidthDp * density).toInt()
            minHeight = (40 * density).toInt()
            setPadding((10 * density).toInt(), 0, (10 * density).toInt(), 0)
            setTextColor(Color.WHITE)
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
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
        val reviewBackground = GradientDrawable().apply {
            cornerRadius = 10 * density
            setColor(Color.argb(236, 26, 34, 52))
            setStroke((1 * density).toInt(), Color.argb(100, 255, 255, 255))
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
            maxLines = 2
            setPadding(edgePadding, 0, edgePadding, 0)
            this.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
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
        reviewTitleText = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.argb(220, 255, 255, 255))
            setTypeface(typeface, Typeface.BOLD)
        }
        reviewDetailsText = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.WHITE)
            maxWidth = (300 * density).toInt()
            setLineSpacing(0f, 1.08f)
        }
        primaryDecisionButton = Button(this).apply {
            text = "Confirm"
            contentDescription = ACCEPT_PLAN_BUTTON_CONTENT_DESCRIPTION
            textSize = 13f
            applyActionButton(76f)
            visibility = View.GONE
            setOnClickListener { acceptPendingDecision() }
        }
        secondaryDecisionButton = Button(this).apply {
            text = "Cancel"
            contentDescription = REJECT_PLAN_BUTTON_CONTENT_DESCRIPTION
            textSize = 13f
            applyActionButton(72f)
            visibility = View.GONE
            setOnClickListener { rejectPendingDecision() }
        }
        val reviewActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, reviewSpacing, 0, 0)
            addView(primaryDecisionButton)
            addView(secondaryDecisionButton)
        }
        (secondaryDecisionButton?.layoutParams as? LinearLayout.LayoutParams)?.marginStart = reviewSpacing
        reviewCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding((10 * density).toInt(), (9 * density).toInt(), (10 * density).toInt(), (10 * density).toInt())
            background = reviewBackground
            this.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = reviewSpacing
            }
            addView(reviewTitleText)
            addView(reviewDetailsText)
            addView(reviewActions)
        }

        pill.addView(recordButton)
        pill.addView(statusText)
        pill.addView(enableAccessibilityButton)
        pill.addView(checkAccessibilityButton)
        pill.addView(moreButton)
        container.addView(pill)
        container.addView(reviewCard)
        attachDragHandler(pill, layoutParams)
        return container
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
                        deps.speechDiagnosticsLogger.record(null, "overlay_drag_dropped_on_dismiss", overlayLifecycleFields(mapOf("x" to layoutParams.x, "y" to layoutParams.y)))
                        deps.actionLogRepository.log(ActionLogType.ACTION_RESULT, "Removed floating controls from dismiss target")
                        stopOverlay()
                        true
                    } else {
                        applySafeOverlayPosition(persist = false)
                        deps.speechDiagnosticsLogger.record(null, "overlay_drag_finished", overlayLifecycleFields(mapOf("x" to layoutParams.x, "y" to layoutParams.y)))
                        scope.launch { deps.settingsRepository.updateOverlayPosition(layoutParams.x, layoutParams.y) }
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
            scope.launch { deps.settingsRepository.updateOverlayPosition(layoutParams.x, layoutParams.y) }
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
                deps.speechRecognitionController.state.collect { updateOverlayText() }
            }
            launch {
                deps.executor.uiState.collect { updateOverlayText() }
            }
            launch {
                deps.executor.pendingPlan.collect { updateOverlayText() }
            }
            launch {
                deps.executor.pendingConfirmation.collect { updateOverlayText() }
            }
        }
    }

    private fun updateOverlayText() {
        val speech = deps.speechRecognitionController.state.value
        val execution = deps.executor.uiState.value
        val pendingDecision = currentPendingDecision()
        updateReviewCard(pendingDecision)
        if (!accessibilityEnabled) {
            recordButton?.visibility = View.GONE
            enableAccessibilityButton?.visibility = View.VISIBLE
            checkAccessibilityButton?.visibility = View.VISIBLE
            moreButton?.visibility = View.VISIBLE
            statusText?.text = if (pendingDecision != null) {
                "Review action and enable Accessibility"
            } else {
                OverlayStatusFormatter.accessibilitySetupLabel(settingsOpened = accessibilitySettingsOpened)
            }
            return
        }
        recordButton?.visibility = View.VISIBLE
        enableAccessibilityButton?.visibility = View.GONE
        checkAccessibilityButton?.visibility = View.GONE
        moreButton?.visibility = View.VISIBLE
        val recordText = OverlayStatusFormatter.recordButton(
            isActive = speech.isActive,
            executionStatus = execution.status,
            isStopping = speech.isStopping
        )
        if (recordText != lastRecordButtonText) {
            lastRecordButtonText = recordText
            deps.speechDiagnosticsLogger.record(
                null,
                "overlay_record_button_state",
                mapOf(
                    "text" to recordText,
                    "speechStarting" to speech.isStarting,
                    "speechListening" to speech.isListening,
                    "speechStopping" to speech.isStopping,
                    "executionStatus" to execution.status
                )
            )
        }
        recordButton?.text = recordText
        transientStatusMessage?.let { message ->
            statusText?.text = message
            return
        }
        statusText?.text = pendingDecision?.headerLabel ?: OverlayStatusFormatter.label(
            isStarting = speech.isStarting,
            isListening = speech.isListening,
            partialTranscript = speech.partialTranscript,
            finalTranscript = speech.finalTranscript,
            executionStatus = execution.status,
            lastResult = execution.lastResult,
            isStopping = speech.isStopping
        )
    }

    private fun currentPendingDecision(): OverlayPendingDecision? {
        deps.executor.pendingConfirmation.value?.let { return OverlayPendingDecision.Confirmation(it) }
        deps.executor.pendingPlan.value?.let { return OverlayPendingDecision.Plan(it) }
        return null
    }

    private fun updateReviewCard(decision: OverlayPendingDecision?) {
        reviewCard?.visibility = if (decision == null) View.GONE else View.VISIBLE
        primaryDecisionButton?.visibility = if (decision == null) View.GONE else View.VISIBLE
        secondaryDecisionButton?.visibility = if (decision == null) View.GONE else View.VISIBLE
        when (decision) {
            null -> {
                reviewTitleText?.text = ""
                reviewDetailsText?.text = ""
            }
            is OverlayPendingDecision.Confirmation -> {
                reviewTitleText?.text = decision.title
                reviewDetailsText?.text = decision.details
                primaryDecisionButton?.text = decision.primaryButtonLabel
                secondaryDecisionButton?.text = decision.secondaryButtonLabel
            }
            is OverlayPendingDecision.Plan -> {
                reviewTitleText?.text = decision.title
                reviewDetailsText?.text = decision.details
                primaryDecisionButton?.text = decision.primaryButtonLabel
                secondaryDecisionButton?.text = decision.secondaryButtonLabel
            }
        }
        if (decision != null) overlayView?.post { applySafeOverlayPosition(persist = true) }
    }

    private fun toggleRecord() {
        val diagnosticSessionId = deps.speechDiagnosticsLogger.startSession(
            "overlay_record_tap",
            mapOf(
                "hasMicPermission" to hasMicPermission(),
                "accessibilityEnabledCached" to accessibilityEnabled,
                "overlayVisible" to (overlayView != null)
            )
        )
        transientStatusMessage = null
        scope.launch {
            val currentAccessibilityEnabled = deps.portalController.isAccessibilityEnabled()
            deps.speechDiagnosticsLogger.record(
                diagnosticSessionId,
                "overlay_record_accessibility_checked",
                mapOf("enabled" to currentAccessibilityEnabled)
            )
            if (!currentAccessibilityEnabled) {
                accessibilityEnabled = false
                updateOverlayText()
                deps.speechDiagnosticsLogger.endSession(diagnosticSessionId, "accessibility_missing")
                return@launch
            }
            accessibilityEnabled = true
            updateOverlayText()
            val speech = deps.speechRecognitionController.state.value
            val execution = deps.executor.uiState.value
            if (speech.isStarting || speech.isListening) {
                deps.speechDiagnosticsLogger.record(diagnosticSessionId, "overlay_record_stopping_active_speech")
                startService(WakeWordForegroundService.intent(this@FloatingControlOverlayService, WakeWordForegroundService.ACTION_STOP_LISTENING, diagnosticSessionId))
            } else if (speech.isStopping || execution.status !in setOf("Idle", "Error", "Cancelled")) {
                deps.speechDiagnosticsLogger.record(
                    diagnosticSessionId,
                    "overlay_record_cancelling_execution",
                    mapOf("executionStatus" to execution.status, "speechStopping" to speech.isStopping)
                )
                startService(WakeWordForegroundService.intent(this@FloatingControlOverlayService, WakeWordForegroundService.ACTION_CANCEL, diagnosticSessionId))
            } else if (!hasMicPermission()) {
                deps.speechDiagnosticsLogger.record(diagnosticSessionId, "overlay_record_missing_mic_permission")
                deps.speechDiagnosticsLogger.endSession(diagnosticSessionId, "mic_permission_missing")
                statusText?.text = OverlayStatusFormatter.microphonePermissionLabel()
                deps.actionLogRepository.log(ActionLogType.ERROR, "Microphone permission is required for push-to-talk", "RECORD_AUDIO_PERMISSION_MISSING")
                startActivity(RecordingPermissionActivity.intent(this@FloatingControlOverlayService))
            } else {
                startPushToTalkService(diagnosticSessionId)
            }
        }
    }

    private fun acceptPendingDecision() {
        val pendingConfirmation = deps.executor.pendingConfirmation.value
        if (pendingConfirmation != null) {
            deps.speechDiagnosticsLogger.record(
                null,
                "overlay_accept_pending_confirmation",
                overlayLifecycleFields(mapOf("hasPendingConfirmation" to true))
            )
            deps.executor.respondToConfirmation(true)
            return
        }
        deps.speechDiagnosticsLogger.record(
            null,
            "overlay_accept_pending_plan",
            overlayLifecycleFields(mapOf("hasPendingPlan" to (deps.executor.pendingPlan.value != null)))
        )
        scope.launch { deps.executor.acceptPendingPlan(false) }
    }

    private fun rejectPendingDecision() {
        val pendingConfirmation = deps.executor.pendingConfirmation.value
        if (pendingConfirmation != null) {
            deps.speechDiagnosticsLogger.record(
                null,
                "overlay_reject_pending_confirmation",
                overlayLifecycleFields(mapOf("hasPendingConfirmation" to true))
            )
            deps.executor.respondToConfirmation(false)
            return
        }
        deps.speechDiagnosticsLogger.record(
            null,
            "overlay_reject_pending_plan",
            overlayLifecycleFields(mapOf("hasPendingPlan" to (deps.executor.pendingPlan.value != null)))
        )
        deps.executor.rejectPendingPlan()
    }


    private fun showMicPermissionReady() {
        deps.speechDiagnosticsLogger.record(null, "overlay_mic_permission_ready_received", overlayLifecycleFields())
        showOverlay()
        scope.launch {
            accessibilityEnabled = true
            val diagnosticSessionId = deps.speechDiagnosticsLogger.startSession("overlay_mic_permission_ready")
            transientStatusMessage = OverlayStatusFormatter.microphoneStartingLabel()
            updateOverlayText()
            delay(600)
            startPushToTalkService(diagnosticSessionId)
            repeat(100) {
                val speech = deps.speechRecognitionController.state.value
                if (speech.isListening) {
                    transientStatusMessage = OverlayStatusFormatter.microphoneReadyLabel()
                    updateOverlayText()
                    deps.speechDiagnosticsLogger.record(diagnosticSessionId, "overlay_mic_permission_ready_listening", overlayLifecycleFields())
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
            deps.speechDiagnosticsLogger.record(diagnosticSessionId, "overlay_mic_permission_ready_timeout", overlayLifecycleFields())
        }
    }

    private fun startPushToTalkService(diagnosticSessionId: String? = null) {
        deps.speechDiagnosticsLogger.record(diagnosticSessionId, "overlay_start_push_to_talk_service", overlayLifecycleFields())
        runCatching {
            ContextCompat.startForegroundService(
                this,
                WakeWordForegroundService.intent(this, WakeWordForegroundService.ACTION_PUSH_TO_TALK, diagnosticSessionId)
            )
        }.fold(
            onSuccess = { deps.speechDiagnosticsLogger.record(diagnosticSessionId, "overlay_start_push_to_talk_service_succeeded", overlayLifecycleFields()) },
            onFailure = { error ->
                deps.speechDiagnosticsLogger.record(diagnosticSessionId, "overlay_start_push_to_talk_service_failed", overlayLifecycleFields(mapOf("errorClass" to error::class.java.name, "message" to error.message)))
                throw error
            }
        )
    }


    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED


    private fun openAccessibilitySettings() {
        deps.speechDiagnosticsLogger.record(null, "overlay_open_accessibility_settings_requested", overlayLifecycleFields())
        runCatching {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.fold(
            onSuccess = {
                accessibilitySettingsOpened = true
                updateOverlayText()
                deps.speechDiagnosticsLogger.record(null, "overlay_open_accessibility_settings_succeeded", overlayLifecycleFields())
                deps.actionLogRepository.log(ActionLogType.ACTION_RESULT, "Opened Accessibility Settings from floating controls")
                startAccessibilityPolling()
            },
            onFailure = { error ->
                deps.speechDiagnosticsLogger.record(null, "overlay_open_accessibility_settings_failed", overlayLifecycleFields(mapOf("errorClass" to error::class.java.name, "message" to error.message)))
                deps.actionLogRepository.log(ActionLogType.ERROR, "Failed to open Accessibility Settings: ${error.message}", "OPEN_ACCESSIBILITY_SETTINGS_FAILED")
                openFullApp()
            }
        )
    }

    private fun refreshAccessibilityStatus() {
        scope.launch {
            accessibilityEnabled = deps.portalController.isAccessibilityEnabled()
            deps.speechDiagnosticsLogger.record(null, "overlay_accessibility_status_refreshed", overlayLifecycleFields(mapOf("enabled" to accessibilityEnabled)))
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
                accessibilityEnabled = deps.portalController.isAccessibilityEnabled()
                deps.speechDiagnosticsLogger.record(null, "overlay_accessibility_poll", overlayLifecycleFields(mapOf("enabled" to accessibilityEnabled, "attempt" to (it + 1))))
                if (accessibilityEnabled) accessibilitySettingsOpened = false
                updateOverlayText()
                if (accessibilityEnabled) return@launch
                delay(1000)
            }
        }
    }


    private fun openFullApp() {
        deps.speechDiagnosticsLogger.record(null, "overlay_open_full_app", overlayLifecycleFields())
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
    }

    private fun stopOverlay(removeSelf: Boolean = true) {
        deps.speechDiagnosticsLogger.record(null, "overlay_stop_requested", overlayLifecycleFields(mapOf("removeSelf" to removeSelf, "hadOverlayView" to (overlayView != null))))
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
        deps.overlayRuntime.setRunning(false)
        scope.launch { deps.settingsRepository.updateFloatingOverlayEnabled(false) }
        deps.speechDiagnosticsLogger.record(null, "overlay_stopped", overlayLifecycleFields(mapOf("removeSelf" to removeSelf)))
        if (removeSelf) stopSelf()
    }

    private fun overlayLifecycleFields(extra: Map<String, Any?> = emptyMap()): Map<String, Any?> = mapOf(
        "overlayVisible" to (overlayView != null),
        "dismissTargetVisible" to (dismissTargetView != null),
        "runningState" to deps.overlayRuntime.isRunning.value,
        "hasOverlayPermission" to Settings.canDrawOverlays(this),
        "hasMicPermission" to hasMicPermission(),
        "accessibilityEnabledCached" to accessibilityEnabled,
        "accessibilitySettingsOpened" to accessibilitySettingsOpened,
        "isDraggingOverlay" to isDraggingOverlay,
        "isInDismissZone" to isInDismissZone,
        "overlayX" to params?.x,
        "overlayY" to params?.y,
        "displayWidth" to runCatching { displayWidth() }.getOrNull(),
        "displayHeight" to runCatching { displayHeight() }.getOrNull(),
        "bottomInset" to runCatching { bottomSystemInset() }.getOrNull(),
        "speechActive" to deps.speechRecognitionController.state.value.isActive,
        "speechListening" to deps.speechRecognitionController.state.value.isListening,
        "executionStatus" to deps.executor.uiState.value.status,
        "hasPendingPlan" to (deps.executor.pendingPlan.value != null),
        "hasPendingConfirmation" to (deps.executor.pendingConfirmation.value != null)
    ) + extra

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
        const val ACCEPT_PLAN_BUTTON_CONTENT_DESCRIPTION = "DroidLM approve pending action"
        const val REJECT_PLAN_BUTTON_CONTENT_DESCRIPTION = "DroidLM reject pending action"
        const val ENABLE_ACCESSIBILITY_BUTTON_CONTENT_DESCRIPTION = "DroidLM enable Accessibility service"
        const val CHECK_ACCESSIBILITY_BUTTON_CONTENT_DESCRIPTION = "DroidLM check Accessibility service"

        fun intent(context: Context, action: String): Intent =
            Intent(context, FloatingControlOverlayService::class.java).setAction(action)
    }
}

package ai.droidlm

import ai.droidlm.di.appGraph
import ai.droidlm.execution.PendingPlan
import ai.droidlm.execution.PlannerKeySetupRequest
import ai.droidlm.intent.ActionUiFormatter
import ai.droidlm.logs.ActionLogEntry
import ai.droidlm.settings.DroidLmSettings
import ai.droidlm.update.DebugUpdatePhase
import ai.droidlm.update.DebugUpdateUiState
import ai.droidlm.update.compactDebugVersionName

import ai.droidlm.ui.DroidLmViewModel
import ai.droidlm.ui.DroidLmViewModelFactory
import ai.droidlm.voice.SpeechRecognitionUiState
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.util.Locale

private const val MAX_DEBUG_ISSUE_DESCRIPTION_CHARS = 4_000

class MainActivity : ComponentActivity() {
    private val mainActivityDeps by lazy { applicationContext.appGraph().mainActivityDeps() }
    private val viewModel: DroidLmViewModel by viewModels {
        DroidLmViewModelFactory(application, mainActivityDeps.viewModelDeps)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { DroidLMTheme { DroidLmScreen(viewModel) } }
        recordLifecycle("main_activity_created", mapOf("restoredState" to (savedInstanceState != null)))
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshAccessibility()
        viewModel.refreshOverlayPermission()
        recordLifecycle("main_activity_resumed")
    }

    override fun onPause() {
        recordLifecycle("main_activity_paused")
        super.onPause()
    }

    override fun onDestroy() {
        recordLifecycle("main_activity_destroyed", mapOf("finishing" to isFinishing, "changingConfigurations" to isChangingConfigurations))
        super.onDestroy()
    }

    private fun recordLifecycle(event: String, fields: Map<String, Any?> = emptyMap()) {
        runCatching { mainActivityDeps.speechDiagnosticsLogger.record(null, event, fields) }
    }
}

@Composable
private fun DroidLMTheme(content: @Composable () -> Unit) {
    val colors = androidx.compose.material3.lightColorScheme(
        primary = DroidLmColors.Accent,
        onPrimary = Color.White,
        secondary = DroidLmColors.TextMuted,
        tertiary = DroidLmColors.Danger,
        background = DroidLmColors.Background,
        surface = DroidLmColors.Surface,
        onSurface = DroidLmColors.Text
    )
    MaterialTheme(colorScheme = colors, typography = MaterialTheme.typography, content = content)
}

private object DroidLmColors {
    val Background = Color(0xFFF6F7F9)
    val Surface = Color(0xFFFFFFFF)
    val SurfaceAlt = Color(0xFFEFF2F6)
    val Text = Color(0xFF121417)
    val TextMuted = Color(0xFF667085)
    val Accent = Color(0xFF2563EB)
    val Danger = Color(0xFFDC2626)
    val SuccessSurface = Color(0xFFEFF8F2)
    val WarningSurface = Color(0xFFFFF7E8)
}

@Composable
private fun DroidLmScreen(viewModel: DroidLmViewModel) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsState(initial = DroidLmSettings())
    val speechRecognition by viewModel.speechRecognitionState.collectAsState()
    val accessibilityEnabled by viewModel.accessibilityEnabled.collectAsState()
    val listening by viewModel.listeningState.collectAsState()
    val execution by viewModel.executionState.collectAsState()
    val pendingConfirmation by viewModel.pendingConfirmation.collectAsState()
    val overlayRunning by viewModel.overlayState.collectAsState()
    val overlayGranted by viewModel.overlayPermissionGranted.collectAsState()
    val pendingPlan by viewModel.pendingPlan.collectAsState()
    val plannerKeySetup by viewModel.plannerKeySetupRequest.collectAsState()
    val needsOnboarding = settings.onboardingCompletedVersion < DroidLmViewModel.ONBOARDING_VERSION

    var showSettings by remember { mutableStateOf(false) }
    var micGranted by remember { mutableStateOf(hasPermission(context, Manifest.permission.RECORD_AUDIO)) }
    var notificationGranted by remember { mutableStateOf(hasNotificationPermission(context)) }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> micGranted = granted }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> notificationGranted = granted }
    var pendingOverlayStart by remember { mutableStateOf(false) }
    var overlayPermissionNeedsRetry by remember { mutableStateOf(false) }
    var showOverlayPermissionInstructions by remember { mutableStateOf(false) }
    val overlayLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val granted = viewModel.refreshOverlayPermission()
        if (granted) {
            overlayPermissionNeedsRetry = false
            if (pendingOverlayStart) viewModel.startOverlay()
        } else if (pendingOverlayStart) {
            overlayPermissionNeedsRetry = true
        }
        pendingOverlayStart = false
    }

    fun startListeningWithPermission() {
        when {
            !micGranted -> micLauncher.launch(Manifest.permission.RECORD_AUDIO)
            !notificationGranted && Build.VERSION.SDK_INT >= 33 -> notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            else -> viewModel.startListening()
        }
    }

    fun pushToTalkWithPermission() {
        when {
            !micGranted -> micLauncher.launch(Manifest.permission.RECORD_AUDIO)
            !notificationGranted && Build.VERSION.SDK_INT >= 33 -> notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            else -> viewModel.pushToTalk()
        }
    }

    fun requestOverlayPermission() {
        pendingOverlayStart = true
        overlayPermissionNeedsRetry = false
        showOverlayPermissionInstructions = true
    }

    fun openOverlayPermissionSettings() {
        pendingOverlayStart = true
        showOverlayPermissionInstructions = false
        overlayLauncher.launch(overlayPermissionIntent(context))
    }

    fun startOverlayWithPermission() {
        val granted = viewModel.refreshOverlayPermission()
        if (granted) {
            pendingOverlayStart = false
            overlayPermissionNeedsRetry = false
            viewModel.startOverlay()
        } else {
            requestOverlayPermission()
        }
    }

    LaunchedEffect(overlayGranted) {
        if (overlayGranted) {
            overlayPermissionNeedsRetry = false
            if (pendingOverlayStart) {
                pendingOverlayStart = false
                viewModel.startOverlay()
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.refreshAccessibility()
    }
    LaunchedEffect(
        needsOnboarding,
        settings.preferOfflineSpeechRecognition,
        speechRecognition.speechSetupChecked,
        speechRecognition.speechSetupChecking
    ) {
        if (
            needsOnboarding &&
            settings.preferOfflineSpeechRecognition &&
            !speechRecognition.speechSetupChecked &&
            !speechRecognition.speechSetupChecking
        ) {
            viewModel.checkSpeechSetup(settings.preferOfflineSpeechRecognition)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(DroidLmColors.Background, DroidLmColors.SurfaceAlt)
                )
            )
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 18.dp, top = 18.dp, end = 18.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (!showSettings) {
                item { Header() }
            }
            if (needsOnboarding) {
                item {
                    OnboardingPage(
                        settings = settings,
                        viewModel = viewModel,
                        plannerKeySetup = plannerKeySetup,
                        accessibilityEnabled = accessibilityEnabled,
                        micGranted = micGranted,
                        notificationGranted = notificationGranted,
                        speechRecognition = speechRecognition,
                        onOpenAccessibility = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                        onRequestMicPermission = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                        onRequestNotificationPermission = {
                            if (Build.VERSION.SDK_INT >= 33) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        },
                        onSaveOpenAiKey = viewModel::saveOpenAiApiKey,
                        onClearOpenAiKey = viewModel::clearOpenAiApiKey,
                        onDismissPlannerKeySetup = viewModel::dismissPlannerKeySetup,
                        onDone = viewModel::completeOnboarding,
                        onOpenSettings = {
                            viewModel.completeOnboarding()
                            showSettings = true
                        }
                    )
                }
            } else if (showSettings) {
                item {
                    SettingsPage(
                        settings = settings,
                        viewModel = viewModel,
                        plannerKeySetup = plannerKeySetup,
                        accessibilityEnabled = accessibilityEnabled,
                        micGranted = micGranted,
                        notificationGranted = notificationGranted,
                        onOpenAccessibility = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                        onRequestMicPermission = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                        onRequestNotificationPermission = {
                            if (Build.VERSION.SDK_INT >= 33) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        },
                        onOpenSpeechSettings = viewModel::openSpeechRecognitionSettings,
                        onSaveOpenAiKey = viewModel::saveOpenAiApiKey,
                        onClearOpenAiKey = viewModel::clearOpenAiApiKey,
                        onDismissPlannerKeySetup = viewModel::dismissPlannerKeySetup,
                        speechRecognition = speechRecognition,
                    )
                }
            } else {
                plannerKeySetup?.let { setup ->
                    item {
                        PlannerSettingsCard(
                            settings = settings,
                            plannerKeySetup = setup,
                            onSave = viewModel::saveOpenAiApiKey,
                            onClear = viewModel::clearOpenAiApiKey,
                            onCancel = viewModel::dismissPlannerKeySetup
                        )
                    }
                }
                pendingPlan?.let { plan ->
                    item {
                        PlanPreviewCard(
                            pendingPlan = plan,
                            onAcceptOnce = { viewModel.acceptPendingPlan(false) },
                            onAlwaysAcceptSafe = { viewModel.acceptPendingPlan(true) },
                            onReject = viewModel::rejectPendingPlan
                        )
                    }
                }
                pendingConfirmation?.let { pending ->
                    item {
                        ConfirmationCard(
                            transcript = pending.transcript,
                            action = pending.actionLabel,
                            reason = pending.reason,
                            prompt = pending.prompt,
                            onConfirm = viewModel::confirmPending,
                            onCancel = viewModel::rejectPending
                        )
                    }
                }
                speechRecognition.missingLanguageMessage?.let {
                    item {
                        DroidCard(container = DroidLmColors.WarningSurface) {
                            SpeechSetupCard(settings, speechRecognition, viewModel)
                        }
                    }
                }
                item { ExecutionCard(execution.lastTranscript, execution.parsedAction, execution.status, execution.lastResult) }
                item {
                    MainControlRow(
                        listening = listening,
                        showingSettings = showSettings,
                        overlayRunning = overlayRunning,
                        onListeningToggle = { if (listening) viewModel.stopListening() else startListeningWithPermission() },
                        onPushToTalk = ::pushToTalkWithPermission,
                        onCancel = viewModel::cancelCurrentTask,
                        onSettings = { showSettings = !showSettings },
                        onOverlayToggle = { if (overlayRunning) viewModel.stopOverlay() else startOverlayWithPermission() }
                    )
                }
            }
        }
        if (showOverlayPermissionInstructions) {
            OverlayPermissionGuideDialog(
                onOpenSettings = ::openOverlayPermissionSettings,
                onDismiss = { showOverlayPermissionInstructions = false }
            )
        }


    }
}

@Composable
private fun Header() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("DroidLM", fontSize = 38.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.SansSerif, color = DroidLmColors.Text)
    }
}

@Composable
private fun MainControlRow(
    listening: Boolean,
    showingSettings: Boolean,
    overlayRunning: Boolean,
    onListeningToggle: () -> Unit,
    onPushToTalk: () -> Unit,
    onCancel: () -> Unit,
    onSettings: () -> Unit,
    onOverlayToggle: () -> Unit
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically
    ) {
        Button(onClick = onListeningToggle) { Text(if (listening) "Stop Listening" else "Start Listening") }
        Button(onClick = onPushToTalk) { Text("Push to Talk") }
        Button(
            colors = ButtonDefaults.buttonColors(containerColor = DroidLmColors.Danger, contentColor = Color.White),
            onClick = onCancel
        ) { Text("Cancel") }
        Button(onClick = onOverlayToggle) { Text(if (overlayRunning) "Stop Floating Controls" else "Start Floating Controls") }
        OutlinedButton(onClick = onSettings) { Text(if (showingSettings) "Close Settings" else "Settings") }
    }
}

@Composable
private fun OnboardingPage(
    settings: DroidLmSettings,
    viewModel: DroidLmViewModel,
    plannerKeySetup: PlannerKeySetupRequest?,
    accessibilityEnabled: Boolean,
    micGranted: Boolean,
    notificationGranted: Boolean,
    speechRecognition: SpeechRecognitionUiState,
    onOpenAccessibility: () -> Unit,
    onRequestMicPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onSaveOpenAiKey: (String) -> Unit,
    onClearOpenAiKey: () -> Unit,
    onDismissPlannerKeySetup: () -> Unit,
    onDone: () -> Unit,
    onOpenSettings: () -> Unit
) {
    var showOpenAiKeyDialog by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        DroidCard {
            Text("Let's set up DroidLM", fontWeight = FontWeight.Bold, fontFamily = FontFamily.SansSerif, fontSize = 24.sp)
            Text(
                "Complete the essentials once. DroidLM uses Android on-device speech recognition; OpenAI is only for planning after speech is recognized.",
                color = DroidLmColors.TextMuted
            )
        }
        SetupStatusCard(
            accessibilityEnabled = accessibilityEnabled,
            micGranted = micGranted,
            notificationGranted = notificationGranted,
            settings = settings,
            onOpenAccessibility = onOpenAccessibility,
            onRequestMicPermission = onRequestMicPermission,
            onRequestNotificationPermission = onRequestNotificationPermission,
            onEnableOcr = { viewModel.updateOnDeviceOcr(true) },
            speechRecognition = speechRecognition,
            onOpenSpeechSettings = viewModel::openSpeechRecognitionSettings,
            onOpenAiKey = { showOpenAiKeyDialog = true }
        )
        DroidCard { SpeechSetupCard(settings, speechRecognition, viewModel) }
        DroidCard {
            Text("Diagnostics", fontWeight = FontWeight.SemiBold)
            ToggleRow("Debug logging", settings.debugLoggingEnabled, viewModel::updateDebugLogging)
            Text("Optional, but useful if speech setup fails. Exports are zipped for sharing.", color = DroidLmColors.TextMuted)
        }
        DroidCard {
            Button(onClick = onDone) { Text("Start using DroidLM") }
            OutlinedButton(onClick = onOpenSettings) { Text("Review all settings") }
        }
    }

    if (showOpenAiKeyDialog) {
        OpenAiKeyDialog(
            settings = settings,
            plannerKeySetup = plannerKeySetup,
            onSave = onSaveOpenAiKey,
            onClear = onClearOpenAiKey,
            onCancel = onDismissPlannerKeySetup,
            onDismiss = { showOpenAiKeyDialog = false }
        )
    }
}


@Composable
private fun SettingsPage(
    settings: DroidLmSettings,
    viewModel: DroidLmViewModel,
    plannerKeySetup: PlannerKeySetupRequest?,
    accessibilityEnabled: Boolean,
    micGranted: Boolean,
    notificationGranted: Boolean,
    onOpenAccessibility: () -> Unit,
    onRequestMicPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onOpenSpeechSettings: () -> Unit,
    onSaveOpenAiKey: (String) -> Unit,
    onClearOpenAiKey: () -> Unit,
    onDismissPlannerKeySetup: () -> Unit,
    speechRecognition: SpeechRecognitionUiState,
) {
    var showOpenAiKeyDialog by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Settings", fontWeight = FontWeight.Bold, fontFamily = FontFamily.SansSerif, fontSize = 24.sp)
        SetupStatusCard(
            accessibilityEnabled = accessibilityEnabled,
            micGranted = micGranted,
            notificationGranted = notificationGranted,
            settings = settings,
            onOpenAccessibility = onOpenAccessibility,
            onRequestMicPermission = onRequestMicPermission,
            onRequestNotificationPermission = onRequestNotificationPermission,
            onEnableOcr = { viewModel.updateOnDeviceOcr(true) },
            speechRecognition = speechRecognition,
            onOpenSpeechSettings = onOpenSpeechSettings,
            onOpenAiKey = { showOpenAiKeyDialog = true }
        )
        SettingsCard(settings, speechRecognition, viewModel)
        VersionFooter()
    }

    if (showOpenAiKeyDialog) {
        OpenAiKeyDialog(
            settings = settings,
            plannerKeySetup = plannerKeySetup,
            onSave = onSaveOpenAiKey,
            onClear = onClearOpenAiKey,
            onCancel = onDismissPlannerKeySetup,
            onDismiss = { showOpenAiKeyDialog = false }
        )
    }
}

@Composable
private fun VersionFooter() {
    val context = LocalContext.current
    val versionName = remember(context.packageName) { appVersionName(context) }
    Text(
        "Version $versionName",
        modifier = Modifier.fillMaxWidth(),
        color = DroidLmColors.TextMuted,
        fontSize = 13.sp
    )
}

@Composable
private fun SetupStatusCard(
    accessibilityEnabled: Boolean,
    micGranted: Boolean,
    notificationGranted: Boolean,
    settings: DroidLmSettings,
    onOpenAccessibility: () -> Unit,
    onRequestMicPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onEnableOcr: () -> Unit,
    speechRecognition: SpeechRecognitionUiState,
    onOpenSpeechSettings: () -> Unit,
    onOpenAiKey: (() -> Unit)? = null
) = DroidCard {
    val baseItems = listOfNotNull(
        SetupStatusItem("Accessibility", accessibilityEnabled, onOpenAccessibility),
        SetupStatusItem("Microphone", micGranted, onRequestMicPermission),
        SetupStatusItem("Notifications", notificationGranted, onRequestNotificationPermission),
        SetupStatusItem("On-device OCR", settings.onDeviceOcrEnabled, onEnableOcr),
        onOpenAiKey?.let { SetupStatusItem("API Key", settings.openAiApiKeyConfigured, it) }
    )
    val languageItem = when {
        settings.preferOfflineSpeechRecognition -> {
            val tag = speechRecognition.missingLanguageTag ?: Locale.getDefault().toLanguageTag()
            val available = speechRecognition.speechSetupChecked && speechRecognition.speechSetupAvailable == true
            val label = if (available) {
                "Offline ${displayLanguage(tag)} speech"
            } else {
                "Install/check offline ${displayLanguage(tag)} speech"
            }
            SetupStatusItem(label, available, onOpenSpeechSettings)
        }
        speechRecognition.missingLanguageTag != null -> SetupStatusItem(
            "Offline ${displayLanguage(speechRecognition.missingLanguageTag)} speech",
            false,
            onOpenSpeechSettings
        )
        else -> null
    }
    val items = baseItems + listOfNotNull(languageItem)
    val enabledItems = items.filter { it.enabled }
    val missingItems = items.filterNot { it.enabled }

    Text("Setup status", fontWeight = FontWeight.Bold, fontFamily = FontFamily.SansSerif, fontSize = 20.sp)
    Spacer(Modifier.height(10.dp))
    SetupStatusRow("Enabled", enabledItems)
    SetupStatusRow("Missing", missingItems)
}

private data class SetupStatusItem(
    val label: String,
    val enabled: Boolean,
    val onClick: () -> Unit
)

@Composable
private fun SetupStatusRow(label: String, items: List<SetupStatusItem>) {
    var expanded by rememberSaveable(label) { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("$label (${items.size})", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Hide" else "Show")
            }
        }
        if (expanded) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (items.isEmpty()) {
                    Text("None", color = DroidLmColors.TextMuted)
                } else {
                    items.forEach { item ->
                        AssistChip(onClick = item.onClick, label = { Text(item.label) })
                    }
                }
            }
        }
    }
}

@Composable
private fun OverlayPermissionGuideDialog(
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit
) = AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Enable floating controls") },
    text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Android requires this one setting before DroidLM can show the floating record button over other apps.")
            Text("1. Tap Open Android settings.")
            Text("2. Find Allow display over other apps and turn it on.")
            Text("3. Return to DroidLM; the controls start automatically.")
        }
    },
    confirmButton = {
        Button(onClick = onOpenSettings) { Text("Open Android settings") }
    },
    dismissButton = {
        OutlinedButton(onClick = onDismiss) { Text("Not now") }
    }
)

@Composable
private fun AdvancedControlsCard(
    onOpenAccessibility: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onTestOcr: () -> Unit,
    onOpenOverlayPermission: () -> Unit
) = DroidCard {
    Text("Advanced controls", fontWeight = FontWeight.Bold, fontFamily = FontFamily.SansSerif, fontSize = 20.sp)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onOpenAccessibility) { Text("Open Accessibility Settings") }
        OutlinedButton(onClick = onOpenAppSettings) { Text("Open App Settings") }
        OutlinedButton(onClick = onTestOcr) { Text("Test OCR") }
        OutlinedButton(onClick = onOpenOverlayPermission) { Text("Open Overlay Permission") }
    }
}

@Composable
private fun ConfirmationCard(
    transcript: String,
    action: String,
    reason: String,
    prompt: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) = DroidCard(container = DroidLmColors.WarningSurface) {
    Text("Confirmation required", fontWeight = FontWeight.Bold, fontFamily = FontFamily.SansSerif, fontSize = 20.sp)
    Text("Transcript: $transcript")
    Text("Action: $action")
    Text("Reason: $reason")
    if (prompt.isNotBlank()) Text(prompt, fontWeight = FontWeight.SemiBold)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = onConfirm) { Text("\u2713", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
        OutlinedButton(onClick = onCancel) { Text("X", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun OpenAiKeyDialog(
    settings: DroidLmSettings,
    plannerKeySetup: PlannerKeySetupRequest?,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit
) {
    var apiKey by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("OpenAI API key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Status: ${if (settings.openAiApiKeyConfigured) "Configured" else "Not configured"}")
                plannerKeySetup?.let { Text(it.message, color = DroidLmColors.TextMuted) }
                if (!settings.openAiApiKeyConfigured) {
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("OpenAI API key") }
                    )
                }
            }
        },
        confirmButton = {
            if (!settings.openAiApiKeyConfigured) {
                Button(onClick = {
                    onSave(apiKey)
                    apiKey = ""
                    onDismiss()
                }) { Text("Save Key") }
            } else {
                OutlinedButton(onClick = onClear) { Text("Clear Key") }
            }
        },
        dismissButton = {
            if (!settings.openAiApiKeyConfigured) {
                OutlinedButton(onClick = {
                    onCancel()
                    onDismiss()
                }) { Text("Cancel") }
            } else {
                OutlinedButton(onClick = onDismiss) { Text("Close") }
            }
        }
    )
}


@Composable
private fun PlannerSettingsCard(
    settings: DroidLmSettings,
    plannerKeySetup: PlannerKeySetupRequest?,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
    onCancel: () -> Unit
) = DroidCard(container = DroidLmColors.WarningSurface) {
    var apiKey by remember { mutableStateOf("") }
    Text("OpenAI API key", fontWeight = FontWeight.Bold, fontFamily = FontFamily.SansSerif, fontSize = 20.sp)
    Text("Status: ${if (settings.openAiApiKeyConfigured) "Configured" else "Not configured"}")
    plannerKeySetup?.let { Text(it.message, color = DroidLmColors.TextMuted) }
    if (!settings.openAiApiKeyConfigured) {
        OutlinedTextField(value = apiKey, onValueChange = { apiKey = it }, modifier = Modifier.fillMaxWidth(), label = { Text("OpenAI API key") })
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { onSave(apiKey); apiKey = "" }) { Text("Save Key") }
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
        }
    } else {
        OutlinedButton(onClick = onClear) { Text("Clear Key") }
    }
}

@Composable
private fun PlanPreviewCard(
    pendingPlan: PendingPlan,
    onAcceptOnce: () -> Unit,
    onAlwaysAcceptSafe: () -> Unit,
    onReject: () -> Unit
) = DroidCard(container = DroidLmColors.SuccessSurface) {
    val plan = pendingPlan.plan
    Text("GPT plan preview", fontWeight = FontWeight.Bold, fontFamily = FontFamily.SansSerif, fontSize = 20.sp)
    Text("Transcript: ${pendingPlan.transcript}")
    Text("Model: ${plan.model}")
    Text("Risk: ${plan.riskLevel}")
    Text(plan.summary, fontWeight = FontWeight.SemiBold)
    plan.steps.forEach { step ->
        val actionLabel = ActionUiFormatter.full(step.action, step.actionLabel, step.reason)
        Text("${step.index}. $actionLabel")
        if (step.reason.isNotBlank() && ActionUiFormatter.reasonAddsDetail(step.reason, actionLabel)) {
            Text(step.reason, color = DroidLmColors.TextMuted)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = onAcceptOnce) { Text("Accept Once") }
        if (plan.isSafe) Button(onClick = onAlwaysAcceptSafe) { Text("Always Accept Safe") }
        OutlinedButton(onClick = onReject) { Text("Reject") }
    }
}

@Composable
private fun ExecutionCard(transcript: String, action: String, status: String, result: String) = DroidCard {
    Text("Execution", fontWeight = FontWeight.Bold, fontFamily = FontFamily.SansSerif, fontSize = 20.sp)
    Text("Status: $status")
    transcript.takeIf { it.isNotBlank() }?.let { Text("Last transcript: $it") }
    action.takeIf { it.isNotBlank() }?.let { Text("Parsed action: $it") }
    result.takeIf { it.isNotBlank() }?.let { Text("Result: $it") }
}



@Composable
private fun SettingsCard(
    settings: DroidLmSettings,
    speechRecognition: SpeechRecognitionUiState,
    viewModel: DroidLmViewModel
) = DroidCard {
    val saveDebugLogsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        uri?.let(viewModel::saveDebugLogsToUri)
    }
    val debugUpdateState by viewModel.debugUpdateState.collectAsState()
    val debugInstallPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.resumePendingDebugBuildInstall()
    }

    var showDebugShareDialog by remember { mutableStateOf(false) }
    var debugIssueDescription by remember { mutableStateOf("") }

    Text("Assistant settings", fontWeight = FontWeight.Bold, fontFamily = FontFamily.SansSerif, fontSize = 20.sp)
    SpeechSetupCard(settings, speechRecognition, viewModel)

    Text("Diagnostics", fontWeight = FontWeight.SemiBold)
    ToggleRow("Debug logging", settings.debugLoggingEnabled, viewModel::updateDebugLogging)
    Text(
        "When enabled, DroidLM keeps speech diagnostic events plus retained debug audio and screenshots.",
        color = DroidLmColors.TextMuted
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { showDebugShareDialog = true }) { Text("Upload") }
        OutlinedButton(onClick = { saveDebugLogsLauncher.launch(viewModel.debugLogsExportFileName()) }) { Text("Save") }
        OutlinedButton(onClick = viewModel::clearDebugLogs) { Text("Clear") }
    }
    if (showDebugShareDialog) {
        AlertDialog(
            onDismissRequest = { showDebugShareDialog = false },
            title = { Text("Describe the issue") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Add any details that would help diagnose what happened. This description will be saved inside the uploaded zip.",
                        color = DroidLmColors.TextMuted
                    )
                    OutlinedTextField(
                        value = debugIssueDescription,
                        onValueChange = { debugIssueDescription = it.take(MAX_DEBUG_ISSUE_DESCRIPTION_CHARS) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("What were you experiencing?") },
                        minLines = 4,
                        maxLines = 8
                    )
                    Text(
                        "${debugIssueDescription.length}/$MAX_DEBUG_ISSUE_DESCRIPTION_CHARS characters",
                        color = DroidLmColors.TextMuted,
                        fontSize = 12.sp
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.shareDebugLogs(debugIssueDescription)
                        showDebugShareDialog = false
                        debugIssueDescription = ""
                    }
                ) { Text("Upload logs") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showDebugShareDialog = false }) { Text("Cancel") }
            }
        )
    }
    if (debugUpdateState.visible) {
        DebugBuildUpgradeSection(
            state = debugUpdateState,
            onUpgrade = viewModel::upgradeToLatestDebugBuild,
            onAllowInstall = {
                debugInstallPermissionLauncher.launch(viewModel.debugBuildInstallPermissionIntent())
            }
        )
    }
}

@Composable
private fun DebugBuildUpgradeSection(
    state: DebugUpdateUiState,
    onUpgrade: () -> Unit,
    onAllowInstall: () -> Unit
) {
    val buttonLabel = when {
        state.requiresInstallPermission -> "Allow Install"
        state.isBusy -> "Working..."
        else -> "Upgrade to Latest Debug Build"
    }
    Button(
        enabled = !state.isBusy,
        onClick = if (state.requiresInstallPermission) onAllowInstall else onUpgrade,
        colors = ButtonDefaults.buttonColors(containerColor = DroidLmColors.Accent)
    ) {
        Text(buttonLabel)
    }
}

@Composable
private fun SpeechSetupCard(settings: DroidLmSettings, speechRecognition: SpeechRecognitionUiState, viewModel: DroidLmViewModel) {
    val languageTag = speechRecognition.missingLanguageTag ?: Locale.getDefault().toLanguageTag()
    val languageName = displayLanguage(languageTag)
    Text("Voice recognition", fontWeight = FontWeight.SemiBold)
    speechRecognition.speechSetupMessage?.let { message ->
        Text(message, color = if (speechRecognition.speechSetupAvailable == true) DroidLmColors.TextMuted else DroidLmColors.Danger)
    }
    if (settings.preferOfflineSpeechRecognition && speechRecognition.speechSetupAvailable != true) {
        Text(
            "Install $languageName for offline speech recognition: open Android speech settings, then find Voice input or Offline speech recognition and download $languageName.",
            color = DroidLmColors.TextMuted
        )
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            enabled = !speechRecognition.speechSetupChecking,
            onClick = { viewModel.checkSpeechSetup(settings.preferOfflineSpeechRecognition) }
        ) { Text(if (speechRecognition.speechSetupChecking) "Checking..." else "Check Speech Setup") }
        OutlinedButton(onClick = viewModel::openSpeechRecognitionSettings) { Text("Open Speech Settings") }
        OutlinedButton(onClick = viewModel::openRecognizerAppSettings) { Text("Open Recognizer App") }
    }
}


@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun LogRow(entry: ActionLogEntry) = DroidCard(container = DroidLmColors.Surface) {
    Text(entry.type.name, fontWeight = FontWeight.Bold, color = DroidLmColors.Accent)
    Text(entry.message)
    entry.details?.let { Text(it, color = DroidLmColors.TextMuted) }
}

@Composable
private fun DroidCard(container: Color = DroidLmColors.Surface, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = container),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

private fun appVersionName(context: Context): String {
    val packageInfo = if (Build.VERSION.SDK_INT >= 33) {
        context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0)
    }
    return compactDebugVersionName(packageInfo.versionName) ?: "unknown"
}

private fun displayLanguage(languageTag: String): String {
    val locale = Locale.forLanguageTag(languageTag)
    return locale.getDisplayName(locale).takeIf { it.isNotBlank() } ?: languageTag
}


private fun overlayPermissionIntent(context: Context): Intent =
    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))


private fun hasPermission(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun hasNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < 33 || hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)

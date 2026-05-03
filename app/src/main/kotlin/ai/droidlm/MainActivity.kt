package ai.droidlm

import ai.droidlm.execution.PendingPlan
import ai.droidlm.execution.PlannerKeySetupRequest
import ai.droidlm.logs.ActionLogEntry
import ai.droidlm.settings.DroidLmSettings
import ai.droidlm.settings.ExecutionMode
import ai.droidlm.settings.WakeWordProvider
import ai.droidlm.ui.DroidLmViewModel
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
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

class MainActivity : ComponentActivity() {
    private val viewModel: DroidLmViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { DroidLMTheme { DroidLmScreen(viewModel) } }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshAccessibility()
    }
}

@Composable
private fun DroidLMTheme(content: @Composable () -> Unit) {
    val colors = androidx.compose.material3.lightColorScheme(
        primary = Color(0xFF153D3B),
        onPrimary = Color(0xFFFDF8EC),
        secondary = Color(0xFFE7B75F),
        tertiary = Color(0xFFB95F43),
        background = Color(0xFFF5EFE2),
        surface = Color(0xFFFFFBF2),
        onSurface = Color(0xFF17211F)
    )
    MaterialTheme(colorScheme = colors, typography = MaterialTheme.typography, content = content)
}

@Composable
private fun DroidLmScreen(viewModel: DroidLmViewModel) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsState(initial = DroidLmSettings())
    val logs by viewModel.logs.collectAsState()
    val accessibilityEnabled by viewModel.accessibilityEnabled.collectAsState()
    val listening by viewModel.listeningState.collectAsState()
    val execution by viewModel.executionState.collectAsState()
    val pendingConfirmation by viewModel.pendingConfirmation.collectAsState()
    val overlayRunning by viewModel.overlayState.collectAsState()
    val pendingPlan by viewModel.pendingPlan.collectAsState()
    val plannerKeySetup by viewModel.plannerKeySetupRequest.collectAsState()

    var showSettings by remember { mutableStateOf(false) }
    var micGranted by remember { mutableStateOf(hasPermission(context, Manifest.permission.RECORD_AUDIO)) }
    var notificationGranted by remember { mutableStateOf(hasNotificationPermission(context)) }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> micGranted = granted }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> notificationGranted = granted }
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    val overlayLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        overlayGranted = Settings.canDrawOverlays(context)
        if (overlayGranted) viewModel.startOverlay()
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

    fun startOverlayWithPermission() {
        overlayGranted = Settings.canDrawOverlays(context)
        if (overlayGranted) {
            viewModel.startOverlay()
        } else {
            overlayLauncher.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
        }
    }

    LaunchedEffect(Unit) {
        viewModel.refreshAccessibility()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFFF5EFE2), Color(0xFFE9D9B9), Color(0xFFD7E2D2))
                )
            )
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 18.dp, top = 18.dp, end = 18.dp, bottom = 110.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item { Header() }
            if (showSettings) {
                item {
                    SettingsPage(
                        settings = settings,
                        viewModel = viewModel,
                        plannerKeySetup = plannerKeySetup,
                        accessibilityEnabled = accessibilityEnabled,
                        micGranted = micGranted,
                        notificationGranted = notificationGranted,
                        listening = listening,
                        overlayRunning = overlayRunning,
                        overlayGranted = overlayGranted,
                        onOpenAccessibility = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                        onOpenAppSettings = {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                            )
                        },
                        onOpenOverlayPermission = {
                            overlayLauncher.launch(
                                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                            )
                        },
                        onSaveOpenAiKey = viewModel::saveOpenAiApiKey,
                        onClearOpenAiKey = viewModel::clearOpenAiApiKey,
                        onDismissPlannerKeySetup = viewModel::dismissPlannerKeySetup,
                        onExecuteTextCommand = viewModel::executeTextCommand
                    )
                }
                item { Text("Action log", fontWeight = FontWeight.Bold, fontSize = 20.sp, fontFamily = FontFamily.Serif) }
                items(logs) { LogRow(it) }
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
                item { ExecutionCard(execution.lastTranscript, execution.parsedAction, execution.status, execution.lastResult) }
            }
        }

        HoverControlRow(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(18.dp),
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

@Composable
private fun Header() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("DroidLM", fontSize = 38.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Serif, color = Color(0xFF153D3B))
    }
}

@Composable
private fun HoverControlRow(
    modifier: Modifier,
    listening: Boolean,
    showingSettings: Boolean,
    overlayRunning: Boolean,
    onListeningToggle: () -> Unit,
    onPushToTalk: () -> Unit,
    onCancel: () -> Unit,
    onSettings: () -> Unit,
    onOverlayToggle: () -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(30.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xEE153D3B)),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        FlowRow(
            modifier = Modifier.padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = onListeningToggle) { Text(if (listening) "Stop Listening" else "Start Listening") }
            Button(
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE7B75F), contentColor = Color(0xFF17211F)),
                onClick = onPushToTalk
            ) { Text("Push to Talk") }
            Button(
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB95F43)),
                onClick = onCancel
            ) { Text("Cancel") }
            Button(onClick = onOverlayToggle) { Text(if (overlayRunning) "Stop Floating Controls" else "Start Floating Controls") }
            OutlinedButton(onClick = onSettings) { Text(if (showingSettings) "Close" else "⚙") }
        }
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
    listening: Boolean,
    overlayRunning: Boolean,
    overlayGranted: Boolean,
    onOpenAccessibility: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenOverlayPermission: () -> Unit,
    onSaveOpenAiKey: (String) -> Unit,
    onClearOpenAiKey: () -> Unit,
    onDismissPlannerKeySetup: () -> Unit,
    onExecuteTextCommand: (String) -> Unit
) = Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
    Text("Settings", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Serif, fontSize = 24.sp)
    SetupStatusCard(
        accessibilityEnabled = accessibilityEnabled,
        micGranted = micGranted,
        notificationGranted = notificationGranted,
        listening = listening,
        overlayRunning = overlayRunning,
        overlayGranted = overlayGranted,
        settings = settings
    )
    PlannerSettingsCard(
        settings = settings,
        plannerKeySetup = plannerKeySetup,
        onSave = onSaveOpenAiKey,
        onClear = onClearOpenAiKey,
        onCancel = onDismissPlannerKeySetup
    )
    SettingsCard(settings, viewModel)
    AdvancedControlsCard(
        onOpenAccessibility = onOpenAccessibility,
        onOpenAppSettings = onOpenAppSettings,
        onTestOcr = viewModel::testOcr,
        onOpenOverlayPermission = onOpenOverlayPermission
    )
    CommandTestCard(onExecute = onExecuteTextCommand)
}

@Composable
private fun SetupStatusCard(
    accessibilityEnabled: Boolean,
    micGranted: Boolean,
    notificationGranted: Boolean,
    listening: Boolean,
    overlayRunning: Boolean,
    overlayGranted: Boolean,
    settings: DroidLmSettings
) = DroidCard {
    Text("Setup status", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Serif, fontSize = 20.sp)
    Spacer(Modifier.height(10.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusChip("Accessibility", accessibilityEnabled)
        StatusChip("Microphone", micGranted)
        StatusChip("Notifications", notificationGranted)
        StatusChip("Listening", listening)
        StatusChip("OpenAI", settings.openAiApiKeyConfigured)
        AssistChip(onClick = {}, label = { Text("Voice: Android SpeechRecognizer") })
        StatusChip("Overlay", overlayRunning)
        StatusChip("Overlay permission", overlayGranted)
        StatusChip("OCR", settings.onDeviceOcrEnabled)
        StatusChip("Cloud screenshots", settings.cloudScreenshotAnalysisEnabled)
        AssistChip(onClick = {}, label = { Text("Mode: ${settings.executionMode.name}") })
    }
}

@Composable
private fun StatusChip(label: String, ok: Boolean) {
    AssistChip(onClick = {}, label = { Text("$label: ${if (ok) "Enabled" else "Missing"}") })
}

@Composable
private fun AdvancedControlsCard(
    onOpenAccessibility: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onTestOcr: () -> Unit,
    onOpenOverlayPermission: () -> Unit
) = DroidCard {
    Text("Advanced controls", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Serif, fontSize = 20.sp)
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
) = DroidCard(container = Color(0xFFFFF1D6)) {
    Text("Confirmation required", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Serif, fontSize = 20.sp)
    Text("Transcript: $transcript")
    Text("Action: $action")
    Text("Reason: $reason")
    Text(prompt, fontWeight = FontWeight.SemiBold)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = onConfirm) { Text("Confirm") }
        OutlinedButton(onClick = onCancel) { Text("Cancel") }
    }
}

@Composable
private fun PlannerSettingsCard(
    settings: DroidLmSettings,
    plannerKeySetup: PlannerKeySetupRequest?,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
    onCancel: () -> Unit
) = DroidCard(container = Color(0xFFFFF1D6)) {
    var apiKey by remember { mutableStateOf("") }
    Text("OpenAI API key", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Serif, fontSize = 20.sp)
    Text("Status: ${if (settings.openAiApiKeyConfigured) "Configured" else "Not configured"}")
    plannerKeySetup?.let { Text(it.message, color = Color(0xFF6A4C35)) }
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
) = DroidCard(container = Color(0xFFEAF4EA)) {
    val plan = pendingPlan.plan
    Text("GPT plan preview", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Serif, fontSize = 20.sp)
    Text("Transcript: ${pendingPlan.transcript}")
    Text("Model: ${plan.model}")
    Text("Risk: ${plan.riskLevel}")
    Text(plan.summary, fontWeight = FontWeight.SemiBold)
    plan.steps.forEach { step ->
        Text("${step.index}. ${step.actionLabel}: ${step.reason}")
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = onAcceptOnce) { Text("Accept Once") }
        if (plan.isSafe) Button(onClick = onAlwaysAcceptSafe) { Text("Always Accept Safe") }
        OutlinedButton(onClick = onReject) { Text("Reject") }
    }
}

@Composable
private fun ExecutionCard(transcript: String, action: String, status: String, result: String) = DroidCard {
    Text("Execution", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Serif, fontSize = 20.sp)
    Text("Status: $status")
    transcript.takeIf { it.isNotBlank() }?.let { Text("Last transcript: $it") }
    action.takeIf { it.isNotBlank() }?.let { Text("Parsed action: $it") }
    result.takeIf { it.isNotBlank() }?.let { Text("Result: $it") }
}

@Composable
private fun CommandTestCard(onExecute: (String) -> Unit) = DroidCard {
    var command by remember { mutableStateOf("DroidLM open my Drive app") }
    Text("Text command test", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Serif, fontSize = 20.sp)
    OutlinedTextField(value = command, onValueChange = { command = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Command") })
    Button(onClick = { onExecute(command) }) { Text("Execute Text Command") }
}

@Composable
private fun SettingsCard(settings: DroidLmSettings, viewModel: DroidLmViewModel) = DroidCard {
    var openAiModel by remember(settings.openAiModel) { mutableStateOf(settings.openAiModel) }
    var maxSteps by remember(settings.maxAutonomousSteps) { mutableStateOf(settings.maxAutonomousSteps.toString()) }
    var mobilerunKey by remember { mutableStateOf("") }
    var mobilerunDeviceId by remember(settings.mobilerunDeviceId) { mutableStateOf(settings.mobilerunDeviceId) }
    var picovoiceKey by remember { mutableStateOf("") }

    Text("Assistant settings", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Serif, fontSize = 20.sp)
    OutlinedTextField(value = openAiModel, onValueChange = { openAiModel = it }, modifier = Modifier.fillMaxWidth(), label = { Text("OpenAI model") })
    Button(onClick = { viewModel.updateOpenAiModel(openAiModel) }) { Text("Save OpenAI Model") }

    Text("Voice recognition", fontWeight = FontWeight.SemiBold)
    ToggleRow("Prefer offline Android recognition", settings.preferOfflineSpeechRecognition, viewModel::updatePreferOfflineSpeech)

    Text("Floating controls", fontWeight = FontWeight.SemiBold)
    ToggleRow("Hide overlay during automation", settings.hideOverlayDuringAutomation, viewModel::updateHideOverlayDuringAutomation)
    ToggleRow("Auto-accept safe GPT plans", settings.autoAcceptSafePlans, viewModel::updateAutoAcceptSafePlans)

    Text("Wake-word provider", fontWeight = FontWeight.SemiBold)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        WakeWordProvider.entries.forEach { provider ->
            FilterChip(selected = settings.wakeWordProvider == provider, onClick = { viewModel.updateWakeWordProvider(provider) }, label = { Text(provider.name) })
        }
    }
    OutlinedTextField(value = picovoiceKey, onValueChange = { picovoiceKey = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Picovoice AccessKey (optional)") })
    OutlinedButton(onClick = { viewModel.savePicovoiceAccessKey(picovoiceKey); picovoiceKey = "" }) { Text("Save Picovoice Key") }

    Text("Execution mode", fontWeight = FontWeight.SemiBold)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ExecutionMode.entries.forEach { mode ->
            FilterChip(selected = settings.executionMode == mode, onClick = { viewModel.updateExecutionMode(mode) }, label = { Text(mode.name) })
        }
    }
    OutlinedTextField(value = maxSteps, onValueChange = { maxSteps = it.filter(Char::isDigit) }, label = { Text("Max autonomous steps") })
    OutlinedButton(onClick = { viewModel.updateMaxSteps(maxSteps.toIntOrNull() ?: 12) }) { Text("Save Max Steps") }

    ToggleRow("Require risky-action confirmation", settings.requireRiskConfirmation, viewModel::updateRiskConfirmation)
    ToggleRow("Enable on-device OCR", settings.onDeviceOcrEnabled, viewModel::updateOnDeviceOcr)
    ToggleRow("Enable cloud screenshot analysis", settings.cloudScreenshotAnalysisEnabled, viewModel::updateCloudVision)
    ToggleRow("Confirm before sending screenshots", settings.confirmBeforeSendingScreenshots, viewModel::updateConfirmScreenshots)
    ToggleRow("Debug audio retention", settings.debugAudioRetention, viewModel::updateDebugAudio)
    ToggleRow("Debug screenshot retention", settings.debugScreenshotRetention, viewModel::updateDebugScreenshots)

    OutlinedTextField(value = mobilerunDeviceId, onValueChange = { mobilerunDeviceId = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Mobilerun device ID (optional)") })
    OutlinedButton(onClick = { viewModel.updateMobilerunDeviceId(mobilerunDeviceId) }) { Text("Save Device ID") }
    OutlinedTextField(value = mobilerunKey, onValueChange = { mobilerunKey = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Mobilerun API key (optional)") })
    OutlinedButton(onClick = { viewModel.saveMobilerunApiKey(mobilerunKey); mobilerunKey = "" }) { Text("Save Mobilerun Key") }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun LogRow(entry: ActionLogEntry) = DroidCard(container = Color(0xF5FFFFF8)) {
    Text(entry.type.name, fontWeight = FontWeight.Bold, color = Color(0xFF153D3B))
    Text(entry.message)
    entry.details?.let { Text(it, color = Color(0xFF6A4C35)) }
}

@Composable
private fun DroidCard(container: Color = Color(0xF4FFFFF8), content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = container),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

private fun hasPermission(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun hasNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < 33 || hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)

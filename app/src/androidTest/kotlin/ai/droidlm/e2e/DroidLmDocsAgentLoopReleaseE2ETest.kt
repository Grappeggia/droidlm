package ai.droidlm.e2e

import ai.droidlm.DroidLMApp
import ai.droidlm.MainActivity
import ai.droidlm.intent.DialogButtonRole
import ai.droidlm.intent.ImeActionType
import ai.droidlm.intent.MenuType
import ai.droidlm.intent.ScrollDirection
import ai.droidlm.openai.OpenAiRuntimeOverrides
import ai.droidlm.portal.ActionResult
import ai.droidlm.portal.AppPackage
import ai.droidlm.portal.PortalController
import ai.droidlm.portal.PortalRuntimeOverrides
import ai.droidlm.portal.PortalState
import ai.droidlm.portal.ScreenshotResult
import ai.droidlm.portal.UiNode
import ai.droidlm.portal.UiNodeAction
import ai.droidlm.settings.ExecutionMode
import ai.droidlm.textedit.EditableTarget
import android.content.Intent
import android.graphics.Rect
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class DroidLmDocsAgentLoopReleaseE2ETest {
    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
    private val app: DroidLMApp
        get() = ApplicationProvider.getApplicationContext()

    private lateinit var server: MockWebServer
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var portal: ScriptedDocsPortalController
    private val requestBodies = CopyOnWriteArrayList<String>()
    private val requestCount = AtomicInteger(0)

    @Before
    fun setUp() {
        assumeTrue(
            "Docs agent-loop release E2E runs only via `./gradlew connectedDocsAgentLoopReleaseE2e`.",
            InstrumentationRegistry.getArguments().getString("docsAgentLoopReleaseE2e") == "true"
        )

        portal = ScriptedDocsPortalController()
        PortalRuntimeOverrides.controller = portal

        server = MockWebServer().apply {
            dispatcher = scriptedAgentDispatcher()
            start()
        }
        OpenAiRuntimeOverrides.chatCompletionsEndpoint = server.url("/v1/chat/completions").toString()

        app.rebuildGraphForTesting()
        scenario = ActivityScenario.launch(MainActivity::class.java)

        runBlocking {
            app.executor.cancelActive()
            app.settingsRepository.updateExecutionMode(ExecutionMode.AGENT_LOOP)
            app.settingsRepository.updateAutoAcceptSafePlans(true)
            app.settingsRepository.updateRequireRiskConfirmation(false)
            app.settingsRepository.updateDebugLoggingEnabled(false)
            app.settingsRepository.updateMaxAgentTurns(12)
            app.settingsRepository.updateMaxAgentToolCalls(12)
            app.settingsRepository.saveOpenAiApiKey("sk-e2e-placeholder")
        }
    }


    @After
    fun tearDown() {
        runBlocking {
            runCatching { app.settingsRepository.clearOpenAiApiKey() }
            runCatching { app.settingsRepository.updateExecutionMode(ExecutionMode.LOCAL_RULE_FIRST) }
            runCatching { app.executor.cancelActive() }
        }
        runCatching { scenario.close() }
        OpenAiRuntimeOverrides.chatCompletionsEndpoint = null
        PortalRuntimeOverrides.controller = null
        runCatching { app.rebuildGraphForTesting() }
        runCatching { server.shutdown() }
    }

    @Test
    fun shortPromptCompletesReviewChecklistInsideDocs() = runBlocking {
        val file = prepareFile(relativePath = "docs/review-ready-agent-loop.txt", seedText = SEED_DOCUMENT)
        portal.loadDocument(file)
        openDocument(file)
        assertTrue("Expected scripted Docs portal to expose an editable target before running AGENT_LOOP", waitForEditableNode(5_000))

        val result = app.executor.executeTranscript(SHORT_PROMPT)
        val settled = waitUntilSuspend(10_000) {
            currentPackageName() == DOCS_PACKAGE &&
                file.readText() == EXPECTED_DOCUMENT &&
                portal.currentHeading == "Overview"
        }
        val finalFileText = file.readText()
        val portalText = portal.snapshotText()
        val status = app.executor.uiState.value
        val recentLogs = app.actionLogRepository.logs.value.take(12).joinToString(" | ") { "${it.type}:${it.message}" }

        assertTrue(
            "Expected AGENT_LOOP review-ready flow to succeed; result=${result.message}; status=${status.status}; lastResult=${status.lastResult}; file=${finalFileText.take(500)}",
            result.success
        )
        assertTrue(
            "Expected Docs to remain foreground and final file text applied; package=${currentPackageName()}; heading=${portal.currentHeading}; requestCount=${requestCount.get()}; file=${finalFileText.take(500)}; portal=${portalText.take(500)}; logs=$recentLogs",
            settled
        )
        assertEquals(EXPECTED_DOCUMENT, finalFileText)
        assertEquals(DOCS_PACKAGE, currentPackageName())
        assertEquals("Overview", portal.currentHeading)
        assertEquals(EXPECTED_AGENT_TURNS, requestCount.get())
        assertTrue(
            "Expected first agent request to include the short prompt.",
            requestBodies.firstOrNull()?.contains(SHORT_PROMPT) == true
        )
        assertTrue(
            "Expected agent request payload to include artifact context for Docs navigation.",
            requestBodies.firstOrNull()?.contains("artifactContext") == true
        )
    }

    private fun scriptedAgentDispatcher(): Dispatcher {
        val responses = listOf(
            decision(
                message = "Navigate to Meetings",
                toolCallJson("turn_1", "NAVIGATE_TO_ARTIFACT_TARGET", "Navigate to Meetings", "label" to "Meetings", "kind" to "control")
            ),
            decision(
                message = "Finalize the release status",
                toolCallJson("turn_2", "REPLACE_TEXT_RANGE", "Finalize the release status", "targetText" to "Current release status: draft", "replacementText" to "Current release status: final")
            ),
            decision(
                message = "Add the missing meetings follow-up",
                toolCallJson("turn_3", "INSERT_TEXT_AT_ANCHOR", "Add the missing meetings follow-up", "anchorText" to "- Support triage pending", "anchorPosition" to "AFTER", "text" to "\n- Confirm rollout metrics tomorrow")
            ),
            decision(
                message = "Fill in the mobile owner action item",
                toolCallJson("turn_4", "REPLACE_TEXT_RANGE", "Fill in the mobile owner item", "targetText" to "Mobile owner: TODO", "replacementText" to "Mobile owner: Review debug log regressions")
            ),
            decision(
                message = "Record the verification note in Decision Log",
                toolCallJson("turn_5", "REPLACE_TEXT_RANGE", "Record the workspace navigation verification", "targetText" to "TODO: note that Workspace navigation fix verified in debug.14", "replacementText" to "2026-05-15: Workspace navigation fix verified in debug.14")
            ),
            decision(
                message = "Return to Overview",
                toolCallJson("turn_6", "NAVIGATE_TO_ARTIFACT_TARGET", "Return to Overview", "label" to "Overview", "kind" to "control")
            ),
            done("Review-ready checklist completed")
        )
        return object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requestBodies += request.body.readUtf8()
                val index = requestCount.getAndIncrement()
                return openAiResponse(responses.getOrElse(index) { done("No more scripted actions") })
            }
        }
    }

    private fun decision(message: String, vararg toolCalls: String): String = """
        {
          "status":"CALL_TOOLS",
          "message":${jsonString(message)},
          "toolCalls":[${toolCalls.joinToString(",")}]
        }
    """.trimIndent()

    private fun toolCallJson(id: String, name: String, reason: String, vararg args: Pair<String, String>): String {
        val argsJson = args.joinToString(",") { (key, value) ->
            "\"$key\":${jsonString(value)}"
        }
        return """
            {
              "id":"$id",
              "name":"$name",
              "reason":"$reason",
              "args":{${argsJson}}
            }
        """.trimIndent()
    }

    private fun done(message: String): String = """
        {
          "status":"DONE",
          "message":${jsonString(message)},
          "toolCalls":[]
        }
    """.trimIndent()

    private fun openAiResponse(content: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(
            """
            {
              "id":"chatcmpl-test",
              "object":"chat.completion",
              "choices":[
                {
                  "index":0,
                  "message":{
                    "role":"assistant",
                    "content":${jsonString(content)}
                  }
                }
              ]
            }
            """.trimIndent()
        )

    private fun jsonString(value: String): String = buildString {
        append('"')
        value.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
        append('"')
    }

    private fun prepareFile(relativePath: String, seedText: String): File {
        val file = File(deviceRunRoot(), relativePath)
        val directory = file.parentFile ?: throw AssertionError("Expected parent directory for ${file.absolutePath}")
        assertTrue(directory.exists() || directory.mkdirs())
        file.writeText(seedText)
        assertTrue(file.isFile)
        return file
    }

    private fun openDocument(file: File) {
        val fileUri = FileProvider.getUriForFile(targetContext, "${targetContext.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setPackage(DOCS_PACKAGE)
            .setDataAndType(fileUri, "text/plain")
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        targetContext.startActivity(intent)
    }

    private suspend fun waitForEditableNode(timeoutMs: Long): Boolean = waitUntilSuspend(timeoutMs) {
        app.portalController.findEditableNodes().isNotEmpty()
    }

    private suspend fun currentPackageName(): String? = app.portalController.getState().packageName

    private suspend fun waitUntilSuspend(timeoutMs: Long, condition: suspend () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            delay(250)
        }
        return condition()
    }

    private fun deviceRunRoot(): File {
        val documentsDir = targetContext.getExternalFilesDir(android.os.Environment.DIRECTORY_DOCUMENTS)
            ?: throw AssertionError("Expected app-specific external documents directory")
        return File(documentsDir, "DroidLMTestRuns/docs-agent-loop-release")
    }

    private class ScriptedDocsPortalController : PortalController {
        var currentHeading: String = "Overview"
            private set

        private var currentFile: File? = null
        private var documentText: String = SEED_DOCUMENT
        private var selectionStart: Int = 0
        private var selectionEnd: Int = 0
        private val headings: List<String>
            get() = extractHeadings(documentText)

        fun loadDocument(file: File) {
            currentFile = file
            documentText = file.readText()
            selectionStart = 0
            selectionEnd = 0
            currentHeading = "Overview"
        }

        override suspend fun isAccessibilityEnabled(): Boolean = true

        override suspend fun getState(): PortalState = PortalState(
            packageName = DOCS_PACKAGE,
            activityName = "StubDocsActivity",
            screenWidth = 1080,
            screenHeight = 2400,
            nodes = buildNodes()
        )

        override suspend fun getFullState(): PortalState = getState()

        override suspend fun listPackages(): List<AppPackage> = listOf(
            AppPackage(DOCS_PACKAGE, "Google Docs", enabled = true, launchable = true)
        )

        override suspend fun openApp(packageName: String): ActionResult =
            if (packageName == DOCS_PACKAGE) ActionResult.ok("Docs already active") else ActionResult.fail("Unsupported app", "UNSUPPORTED_APP")

        override suspend fun openSettings(): ActionResult = ActionResult.fail("Unsupported in scripted portal", "UNSUPPORTED")
        override suspend fun tap(x: Int, y: Int): ActionResult = ActionResult.fail("Coordinate tap unsupported", "UNSUPPORTED")

        override suspend fun tapNode(nodeId: String): ActionResult {
            headingForNodeId(nodeId)?.let { return moveToHeading(it) }
            return ActionResult.fail("Unknown node: $nodeId", "NODE_NOT_FOUND")
        }

        override suspend fun focusNode(nodeId: String): ActionResult =
            if (nodeId == EDITOR_NODE_ID) ActionResult.ok("Editor focused") else tapNode(nodeId)

        override suspend fun longPress(x: Int, y: Int, durationMs: Int): ActionResult = ActionResult.fail("Unsupported", "UNSUPPORTED")

        override suspend fun tapText(text: String, role: String?, containerNodeId: String?): ActionResult {
            val heading = headings.firstOrNull { it.equals(text, ignoreCase = true) }
            return heading?.let { moveToHeading(it) } ?: ActionResult.fail("Text not tappable: $text", "TEXT_NOT_FOUND")
        }

        override suspend fun longPressNode(nodeId: String?, text: String?, durationMs: Int): ActionResult = ActionResult.fail("Unsupported", "UNSUPPORTED")

        override suspend fun scroll(direction: ScrollDirection, targetNodeId: String?, untilText: String?): ActionResult {
            val heading = untilText?.let { requested -> headings.firstOrNull { it.equals(requested, ignoreCase = true) } }
            return heading?.let { moveToHeading(it) } ?: ActionResult.ok("Scrolled ${direction.name.lowercase()}")
        }

        override suspend fun waitForUi(text: String?, packageName: String?, nodeId: String?, timeoutMs: Int): ActionResult {
            val state = getState()
            val packageMatches = packageName == null || packageName == state.packageName
            val textMatches = text == null || state.nodes.any { listOfNotNull(it.text, it.contentDescription).any { value -> value.contains(text, ignoreCase = true) } }
            val nodeMatches = nodeId == null || state.nodes.any { it.nodeId == nodeId }
            return if (packageMatches && textMatches && nodeMatches) ActionResult.ok("UI ready") else ActionResult.fail("UI target not ready", "WAIT_TIMEOUT")
        }

        override suspend fun pressImeAction(action: ImeActionType): ActionResult = ActionResult.ok("Ignored IME action")
        override suspend fun dialogAction(buttonText: String?, role: DialogButtonRole?): ActionResult = ActionResult.fail("Unsupported", "UNSUPPORTED")
        override suspend fun openMenu(menu: MenuType): ActionResult = ActionResult.fail("Unsupported", "UNSUPPORTED")
        override suspend fun selectTab(label: String): ActionResult = tapText(label)
        override suspend fun setToggle(label: String?, nodeId: String?, value: Boolean): ActionResult = ActionResult.fail("Unsupported", "UNSUPPORTED")
        override suspend fun expandCollapse(label: String?, nodeId: String?, expanded: Boolean): ActionResult = ActionResult.fail("Unsupported", "UNSUPPORTED")
        override suspend fun setSlider(label: String?, nodeId: String?, value: Float?, percent: Int?): ActionResult = ActionResult.fail("Unsupported", "UNSUPPORTED")
        override suspend fun refresh(targetNodeId: String?): ActionResult = ActionResult.ok("Refreshed")

        override suspend fun findTextOnScreen(text: String, tapOnMatch: Boolean): ActionResult {
            val found = documentText.contains(text, ignoreCase = true) || headings.any { it.contains(text, ignoreCase = true) }
            return if (found) ActionResult.ok("Found text") else ActionResult.fail("Text not found", "TEXT_NOT_FOUND")
        }

        override suspend fun swipe(startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Int): ActionResult = ActionResult.ok("Swiped")

        override suspend fun typeText(text: String, clear: Boolean): ActionResult {
            if (clear) {
                documentText = text
                selectionStart = text.length
                selectionEnd = text.length
                persist()
                return ActionResult.ok("Text replaced")
            }
            return inputTextAtCurrentCursor(text)
        }

        override suspend fun inputTextAtCurrentCursor(text: String): ActionResult {
            val start = selectionStart.coerceIn(0, documentText.length)
            val end = selectionEnd.coerceIn(start, documentText.length)
            documentText = documentText.substring(0, start) + text + documentText.substring(end)
            val newCursor = start + text.length
            selectionStart = newCursor
            selectionEnd = newCursor
            persist()
            updateHeadingFromSelection()
            return ActionResult.ok("Inserted text")
        }

        override suspend fun sendKeyCode(keyCode: Int): ActionResult = ActionResult.fail("Unsupported", "UNSUPPORTED")
        override suspend fun pressBack(): ActionResult = ActionResult.ok("Back ignored")
        override suspend fun pressHome(): ActionResult = ActionResult.ok("Home ignored")
        override suspend fun openNotifications(): ActionResult = ActionResult.fail("Unsupported", "UNSUPPORTED")
        override suspend fun openQuickSettings(): ActionResult = ActionResult.fail("Unsupported", "UNSUPPORTED")
        override suspend fun openRecents(): ActionResult = ActionResult.fail("Unsupported", "UNSUPPORTED")
        override suspend fun openUrl(url: String): ActionResult = ActionResult.fail("Unsupported", "UNSUPPORTED")
        override suspend fun openDeepLink(uri: String): ActionResult = ActionResult.fail("Unsupported", "UNSUPPORTED")
        override suspend fun takeScreenshot(): ScreenshotResult = ScreenshotResult(false, message = "Unsupported", errorCode = "UNSUPPORTED")

        override suspend fun findFocusedEditableNode(): EditableTarget = buildEditableTarget()

        override suspend fun findEditableNodes(): List<EditableTarget> = listOf(buildEditableTarget())

        override suspend fun getNodeText(nodeId: String): String? = when (nodeId) {
            EDITOR_NODE_ID -> documentText
            else -> headingForNodeId(nodeId)
        }

        override suspend fun getNodeSelection(nodeId: String): Pair<Int, Int>? =
            if (nodeId == EDITOR_NODE_ID) selectionStart to selectionEnd else null

        override suspend fun performSetSelection(nodeId: String, start: Int, end: Int): ActionResult {
            if (nodeId != EDITOR_NODE_ID) return ActionResult.fail("Node is not editable", "NODE_NOT_EDITABLE")
            selectionStart = start.coerceIn(0, documentText.length)
            selectionEnd = end.coerceIn(selectionStart, documentText.length)
            updateHeadingFromSelection()
            return ActionResult.ok("Selection updated")
        }

        override suspend fun performSetText(nodeId: String, text: String): ActionResult {
            if (nodeId != EDITOR_NODE_ID) return ActionResult.fail("Node is not editable", "NODE_NOT_EDITABLE")
            documentText = text
            selectionStart = text.length
            selectionEnd = text.length
            persist()
            updateHeadingFromSelection()
            return ActionResult.ok("Text updated")
        }

        private fun buildNodes(): List<UiNode> {
            val nodes = mutableListOf<UiNode>()
            val title = documentText.lineSequence().firstOrNull().orEmpty()
            nodes += UiNode(
                nodeId = "title",
                text = title,
                contentDescription = null,
                className = "android.widget.TextView",
                packageName = DOCS_PACKAGE,
                bounds = Rect(0, 0, 1080, 120),
                clickable = false,
                editable = false,
                focused = false,
                enabled = true,
                selected = false
            )
            headings.forEachIndexed { index, heading ->
                nodes += UiNode(
                    nodeId = headingNodeId(heading),
                    text = heading,
                    contentDescription = null,
                    className = "android.widget.Button",
                    packageName = DOCS_PACKAGE,
                    bounds = Rect(0, 140 + index * 120, 400, 240 + index * 120),
                    clickable = true,
                    editable = false,
                    focused = false,
                    enabled = true,
                    selected = heading == currentHeading,
                    focusable = true,
                    heading = true,
                    availableActions = listOf(UiNodeAction(name = "ACTION_CLICK", droidLmAction = "TAP_NODE"))
                )
            }
            nodes += UiNode(
                nodeId = EDITOR_NODE_ID,
                text = documentText,
                contentDescription = null,
                className = "android.widget.EditText",
                packageName = DOCS_PACKAGE,
                bounds = Rect(0, 120, 1080, 2200),
                clickable = true,
                editable = true,
                focused = true,
                enabled = true,
                selected = false,
                focusable = true,
                multiLine = true,
                textSelectionStart = selectionStart,
                textSelectionEnd = selectionEnd,
                availableActions = listOf(
                    UiNodeAction(name = "ACTION_SET_SELECTION", droidLmAction = "SET_SELECTION", requiresArgs = true),
                    UiNodeAction(name = "ACTION_SET_TEXT", droidLmAction = "SET_FULL_TEXT", requiresArgs = true)
                )
            )
            return nodes
        }

        private fun buildEditableTarget(): EditableTarget = EditableTarget(
            nodeId = EDITOR_NODE_ID,
            packageName = DOCS_PACKAGE,
            className = "android.widget.EditText",
            bounds = Rect(0, 120, 1080, 2200),
            isFocused = true,
            isEditable = true,
            supportsSetText = true,
            supportsSetSelection = true,
            supportsKeyboardInput = true
        )

        private fun moveToHeading(heading: String): ActionResult {
            currentHeading = heading
            val normalizedText = documentText.replace("\r\n", "\n")
            val anchor = if (normalizedText.startsWith(heading + "\n")) 0 else normalizedText.indexOf("\n$heading\n").let { if (it >= 0) it + 1 else 0 }
            selectionStart = anchor
            selectionEnd = anchor
            return ActionResult.ok("Moved to $heading")
        }

        private fun updateHeadingFromSelection() {
            val normalized = documentText.replace("\r\n", "\n")
            var bestHeading = headings.firstOrNull().orEmpty()
            var bestIndex = -1
            headings.forEach { heading ->
                val index = if (normalized.startsWith(heading + "\n")) 0 else normalized.indexOf("\n$heading\n").let { if (it >= 0) it + 1 else -1 }
                if (index in 0..selectionStart && index >= bestIndex) {
                    bestIndex = index
                    bestHeading = heading
                }
            }
            if (bestHeading.isNotEmpty()) currentHeading = bestHeading
        }

        private fun persist() {
            currentFile?.writeText(documentText)
        }

        private fun headingForNodeId(nodeId: String?): String? = headings.firstOrNull { headingNodeId(it) == nodeId }

        private fun headingNodeId(heading: String): String = "heading_${heading.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')}"

        fun snapshotText(): String = documentText

        companion object {
            private const val EDITOR_NODE_ID = "document_editor"
        }
    }

    companion object {
        private const val DOCS_PACKAGE = "com.google.android.apps.docs.editors.docs"
        private const val SHORT_PROMPT = "Make this doc review-ready for tomorrow and leave me back at Overview."
        private const val EXPECTED_AGENT_TURNS = 7
        private val SEED_DOCUMENT = """
            Q2 Launch Readout
            
            Overview
            Tomorrow's launch review starts at 09:00.
            Make this document review-ready before then.
            Assistant checklist (leave this checklist in place):
            - Change the release status in Meetings from draft to final.
            - Resolve the missing meetings follow-up item.
            - Fill in the Mobile owner action item.
            - Record the workspace navigation verification in Decision Log.
            - When done, return to Overview.
            
            Schedule
            09:00 launch checklist
            09:30 launch readiness pass
            10:30 bug scrub
            11:15 support sync
            14:00 stakeholder recap
            17:00 release notes send
            18:00 metrics handoff
            18:30 issue triage
            
            Meetings
            Current release status: draft
            Notes:
            - Partner sync completed
            - Support triage pending
            
            Metrics Snapshot
            Crash-free sessions: 99.3%
            Activation conversion: 42%
            Escalations: 3
            Support backlog: 11
            Top issue cluster: install monitoring
            Docs navigation fix pending verification
            
            Action Items
            Backend owner: confirm alert thresholds
            Mobile owner: TODO
            QA owner: rerun smoke pack
            Support owner: confirm canned replies
            
            Decision Log
            2026-05-14: keep gradual rollout in place.
            TODO: note that Workspace navigation fix verified in debug.14
            
            Appendix
            Support log bundle reviewed.
            Next update due tomorrow morning.
        """.trimIndent()
        private val EXPECTED_DOCUMENT = """
            Q2 Launch Readout
            
            Overview
            Tomorrow's launch review starts at 09:00.
            Make this document review-ready before then.
            Assistant checklist (leave this checklist in place):
            - Change the release status in Meetings from draft to final.
            - Resolve the missing meetings follow-up item.
            - Fill in the Mobile owner action item.
            - Record the workspace navigation verification in Decision Log.
            - When done, return to Overview.
            
            Schedule
            09:00 launch checklist
            09:30 launch readiness pass
            10:30 bug scrub
            11:15 support sync
            14:00 stakeholder recap
            17:00 release notes send
            18:00 metrics handoff
            18:30 issue triage
            
            Meetings
            Current release status: final
            Notes:
            - Partner sync completed
            - Support triage pending
            - Confirm rollout metrics tomorrow
            
            Metrics Snapshot
            Crash-free sessions: 99.3%
            Activation conversion: 42%
            Escalations: 3
            Support backlog: 11
            Top issue cluster: install monitoring
            Docs navigation fix pending verification
            
            Action Items
            Backend owner: confirm alert thresholds
            Mobile owner: Review debug log regressions
            QA owner: rerun smoke pack
            Support owner: confirm canned replies
            
            Decision Log
            2026-05-14: keep gradual rollout in place.
            2026-05-15: Workspace navigation fix verified in debug.14
            
            Appendix
            Support log bundle reviewed.
            Next update due tomorrow morning.
        """.trimIndent()

        private fun extractHeadings(text: String): List<String> {
            val lines = text.replace("\r\n", "\n").split("\n")
            val headings = linkedSetOf<String>()
            for (index in 1 until lines.size) {
                val previous = lines[index - 1].trim()
                val current = lines[index].trim()
                val next = lines.getOrElse(index + 1) { "" }.trim()
                if (previous.isNotEmpty()) continue
                if (current.isEmpty() || next.isEmpty()) continue
                if (current.length > 48 || current.contains(':')) continue
                headings += current
            }
            if (headings.isEmpty()) headings += "Overview"
            return headings.toList()
        }
    }
}

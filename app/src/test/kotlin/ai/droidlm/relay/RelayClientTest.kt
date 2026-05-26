package ai.droidlm.relay

import ai.droidlm.intent.ActionConfidence
import ai.droidlm.intent.DroidLmAction
import ai.droidlm.intent.artifactToolName
import ai.droidlm.portal.AppPackage
import ai.droidlm.portal.PortalState

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class RelayClientTest {
    @Test fun healthSuccess() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"ok\":true}"))
            server.start()
            val result = RelayClient().health(server.url("/").toString())
            assertTrue(result is RelayCallResult.Success && result.value)
        }
    }

    @Test fun transcriptionJsonParse() {
        val parsed = RelayClient().parseTranscriptionJson("{\"text\":\"open drive\",\"durationMs\":123}")
        assertEquals("open drive", parsed.text)
        assertEquals(123L, parsed.durationMs)
    }

    @Test fun debugLogUploadPostsMultipartToDirectEndpoint() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"ok":true,"bucket":"example-debug-logs","objectName":"debug-logs/synthetic/test.zip","gsUri":"gs://example-debug-logs/debug-logs/synthetic/test.zip","sizeBytes":3,"contentType":"application/zip"}"""))
            server.start()
            val bundle = File.createTempFile("droidlm-debug", ".zip")
            try {
                bundle.writeBytes(byteArrayOf(1, 2, 3))
                val result = RelayClient(firebaseIdTokenProvider = { FirebaseBearerTokenResult.Success("test-token") })
                    .uploadDebugLogsToUrl(server.url("/").toString(), bundle, "com.droidlm.debug", "0.1-debug")
                if (result !is RelayCallResult.Success) error("Expected upload success")
                assertEquals("debug-logs/synthetic/test.zip", result.value.objectName)
                val request = server.takeRequest()
                assertEquals("/", request.path)
                assertEquals("Bearer test-token", request.getHeader("Authorization"))
                val body = request.body.readUtf8()
                assertTrue(body.contains("name=\"logs\""))
                assertTrue(body.contains("name=\"appPackage\""))
                assertTrue(body.contains("com.droidlm.debug"))
            } finally {
                bundle.delete()
            }
        }
    }

    @Test fun debugLogUploadCanPostToDirectFunctionUrl() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"ok":true,"bucket":"example-debug-logs","objectName":"debug-logs/synthetic/direct.zip","gsUri":"gs://example-debug-logs/debug-logs/synthetic/direct.zip","sizeBytes":3,"contentType":"application/zip"}"""))
            server.start()
            val bundle = File.createTempFile("droidlm-debug", ".zip")
            try {
                bundle.writeBytes(byteArrayOf(1, 2, 3))
                val directUrl = server.url("/upload-debug-logs").toString()
                val result = RelayClient(firebaseIdTokenProvider = { FirebaseBearerTokenResult.Success("test-token") })
                    .uploadDebugLogsToUrl(directUrl, bundle, "com.droidlm.debug", "0.1-debug")
                if (result !is RelayCallResult.Success) error("Expected upload success")
                val request = server.takeRequest()
                assertEquals("/upload-debug-logs", request.path)
                assertEquals("Bearer test-token", request.getHeader("Authorization"))
            } finally {
                bundle.delete()
            }
        }
    }

    @Test fun debugLogUploadFailsLocallyWhenTokenMissing() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{}"))
            server.start()
            val bundle = File.createTempFile("droidlm-debug", ".zip")
            try {
                bundle.writeBytes(byteArrayOf(1, 2, 3))
                val result = RelayClient(
                    firebaseIdTokenProvider = {
                        FirebaseBearerTokenResult.Unavailable("Sign in before uploading debug logs", "AUTH_TOKEN_MISSING")
                    }
                ).uploadDebugLogsToUrl(server.url("/").toString(), bundle, "com.droidlm.debug", "0.1-debug")
                assertTrue(result is RelayCallResult.Failure)
                result as RelayCallResult.Failure
                assertEquals("AUTH_TOKEN_MISSING", result.errorCode)
                assertEquals(0, server.requestCount)
            } finally {
                bundle.delete()
            }
        }
    }


    @Test fun invalidJsonError() {
        val result = runCatching { RelayClient().parseTranscriptionJson("not-json") }
        assertTrue(result.isFailure)
    }

    @Test fun timeoutError() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            server.start()
            val client = RelayClient(OkHttpClient.Builder().readTimeout(100, TimeUnit.MILLISECONDS).build())
            val result = client.health(server.url("/").toString())
            assertTrue(result is RelayCallResult.Failure)
        }
    }

    @Test fun non2xxError() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
            server.start()
            val result = RelayClient().health(server.url("/").toString())
            assertTrue(result is RelayCallResult.Failure)
        }
    }

    @Test fun planActionJsonParse() {
        val action = RelayClient().parsePlanActionJson("{\"action\":\"OPEN_APP\",\"appName\":\"Drive\",\"packageName\":\"com.google.android.apps.docs\",\"reason\":\"test\",\"requiresConfirmation\":false}")
        assertTrue(action is DroidLmAction.OpenApp)
        val planned = RelayClient().parsePlannedActionJson("{\"action\":\"OPEN_APP\",\"appName\":\"Drive\",\"packageName\":\"com.google.android.apps.docs\",\"reason\":\"test\",\"confidence\":\"HIGH\",\"expectedResult\":\"Drive opens\"}")
        assertEquals(ActionConfidence.HIGH, planned.confidence)
        assertEquals("Drive opens", planned.expectedResult)
    }

    @Test fun planActionJsonParsesAppStoreListing() {
        val action = RelayClient().parsePlanActionJson("{\"action\":\"OPEN_APP_STORE_LISTING\",\"appName\":\"Google Sheets\",\"packageName\":\"com.google.android.apps.docs.editors.sheets\",\"reason\":\"install\",\"requiresConfirmation\":true}")
        assertTrue(action is DroidLmAction.OpenAppStoreListing)
        action as DroidLmAction.OpenAppStoreListing
        assertEquals("Google Sheets", action.appName)
        assertEquals("com.google.android.apps.docs.editors.sheets", action.packageName)
    }

    @Test fun planActionJsonParsesDocumentReplacement() {
        val action = RelayClient().parsePlanActionJson(
            "{\"action\":\"REPLACE_CURRENT_DOCUMENT_TEXT\",\"fileUri\":\"file:///tmp/doc.txt\",\"targetText\":\"draft\",\"replacementText\":\"final\",\"reason\":\"test\"}"
        )
        assertTrue(action is DroidLmAction.ReplaceDocumentText)
        action as DroidLmAction.ReplaceDocumentText
        assertEquals("draft", action.targetText)
        assertEquals("final", action.replacementText)
        assertEquals("file:///tmp/doc.txt", action.fileUri)
    }

    @Test fun planActionJsonParsesSpreadsheetRow() {
        val action = RelayClient().parsePlanActionJson(
            "{\"action\":\"ADD_SPREADSHEET_ROW\",\"fileUri\":\"file:///tmp/sheet.csv\",\"values\":[\"April\",\"120\",\"approved\"],\"reason\":\"test\"}"
        )
        assertTrue(action is DroidLmAction.AddSpreadsheetRow)
        action as DroidLmAction.AddSpreadsheetRow
        assertEquals(listOf("April", "120", "approved"), action.values)
        assertEquals("file:///tmp/sheet.csv", action.fileUri)
    }

    @Test fun planActionJsonParsesNodeTargetActions() {
        val tap = RelayClient().parsePlanActionJson(
            "{\"action\":\"TAP_NODE\",\"nodeId\":\"search_bar\",\"reason\":\"open search\"}"
        )
        assertTrue(tap is DroidLmAction.TapNode)
        tap as DroidLmAction.TapNode
        assertEquals("search_bar", tap.nodeId)

        val focus = RelayClient().parsePlanActionJson(
            "{\"action\":\"FOCUS_NODE\",\"nodeId\":\"search_input\",\"reason\":\"focus search\"}"
        )
        assertTrue(focus is DroidLmAction.FocusNode)
    }

    @Test fun planActionJsonParsesArtifactNavigationAction() {
        val action = RelayClient().parsePlanActionJson(
            "{\"action\":\"NAVIGATE_TO_ARTIFACT_TARGET\",\"label\":\"Meetings\",\"nodeId\":\"heading-1\",\"kind\":\"section\",\"reason\":\"jump to the heading\"}"
        )

        assertTrue(action is DroidLmAction.NavigateToArtifactTarget)
        action as DroidLmAction.NavigateToArtifactTarget
        assertEquals("Meetings", action.label)
        assertEquals("heading-1", action.nodeId)
        assertEquals("section", action.kind)
    }

    @Test fun planActionJsonParsesAccessibilityContentSearchAction() {
        val action = RelayClient().parsePlanActionJson(
            "{\"action\":\"SEARCH_ACCESSIBILITY_CONTENT\",\"sectionLabel\":\"Meetings\",\"exclude\":\"Next\",\"ordinal\":1,\"maxMatches\":3,\"reason\":\"find first matching title\"}"
        )

        assertTrue(action is DroidLmAction.SearchAccessibilityContent)
        action as DroidLmAction.SearchAccessibilityContent
        assertEquals("Meetings", action.sectionLabel)
        assertEquals("Next", action.exclude)
        assertEquals(1, action.ordinal)
        assertEquals(3, action.maxMatches)
    }

    @Test fun planActionJsonParsesArtifactToolSet() {
        val cases = mapOf(
            "ARTIFACT_GET_STRUCTURE" to "{\"action\":\"ARTIFACT_GET_STRUCTURE\",\"artifactType\":\"document\"}",
            "ARTIFACT_RESOLVE_TARGET" to "{\"action\":\"ARTIFACT_RESOLVE_TARGET\",\"query\":\"Meetings\"}",
            "ARTIFACT_GET_CONTENT_WINDOW" to "{\"action\":\"ARTIFACT_GET_CONTENT_WINDOW\",\"label\":\"Meetings\"}",
            "ARTIFACT_GET_SELECTION_STATE" to "{\"action\":\"ARTIFACT_GET_SELECTION_STATE\"}",
            "ARTIFACT_VERIFY_END_STATE" to "{\"action\":\"ARTIFACT_VERIFY_END_STATE\",\"label\":\"Meetings\",\"requiredEndState\":\"cursor_at_target\"}",
            "ARTIFACT_NAVIGATE_TO_TARGET" to "{\"action\":\"ARTIFACT_NAVIGATE_TO_TARGET\",\"label\":\"Meetings\"}",
            "ARTIFACT_SET_CURSOR_AT_TARGET" to "{\"action\":\"ARTIFACT_SET_CURSOR_AT_TARGET\",\"label\":\"Meetings\"}",
            "ARTIFACT_SELECT_TARGET" to "{\"action\":\"ARTIFACT_SELECT_TARGET\",\"label\":\"Meetings\"}",
            "ARTIFACT_SCROLL_TO_MATCH" to "{\"action\":\"ARTIFACT_SCROLL_TO_MATCH\",\"query\":\"Meetings\"}",
            "ARTIFACT_UNDO_LAST_ACTION" to "{\"action\":\"ARTIFACT_UNDO_LAST_ACTION\"}",
            "DOC_INSERT_AT_TARGET" to "{\"action\":\"DOC_INSERT_AT_TARGET\",\"targetLabel\":\"Meetings\",\"text\":\"note\"}",
            "DOC_REPLACE_TARGET_TEXT" to "{\"action\":\"DOC_REPLACE_TARGET_TEXT\",\"targetText\":\"draft\",\"replacementText\":\"final\"}",
            "DOC_DELETE_TARGET_TEXT" to "{\"action\":\"DOC_DELETE_TARGET_TEXT\",\"targetText\":\"obsolete\"}",
            "DOC_APPLY_FORMAT" to "{\"action\":\"DOC_APPLY_FORMAT\",\"targetLabel\":\"Follow up\",\"format\":\"bullet\"}",
            "DOC_MOVE_BLOCK" to "{\"action\":\"DOC_MOVE_BLOCK\",\"blockLabel\":\"Follow up\",\"destinationLabel\":\"Meetings\"}",
            "DOC_CREATE_SECTION" to "{\"action\":\"DOC_CREATE_SECTION\",\"title\":\"Risks\",\"afterLabel\":\"Meetings\"}",
            "DOC_GET_TARGET_METADATA" to "{\"action\":\"DOC_GET_TARGET_METADATA\",\"targetLabel\":\"Meetings\"}",
            "DOC_EXTRACT_ACTION_ITEMS" to "{\"action\":\"DOC_EXTRACT_ACTION_ITEMS\",\"targetLabel\":\"Meetings\"}",
            "SHEET_RESOLVE_RANGE" to "{\"action\":\"SHEET_RESOLVE_RANGE\",\"query\":\"B2:C4\"}",
            "SHEET_SET_RANGE_VALUES" to "{\"action\":\"SHEET_SET_RANGE_VALUES\",\"range\":\"B2\",\"values\":[[\"done\"]]}",
            "SHEET_APPEND_TABLE_ROW" to "{\"action\":\"SHEET_APPEND_TABLE_ROW\",\"values\":[\"Mobile\",\"Done\"]}",
            "SHEET_UPDATE_ROW_BY_MATCH" to "{\"action\":\"SHEET_UPDATE_ROW_BY_MATCH\",\"matchValue\":\"Mobile\",\"values\":{\"Status\":\"Done\"}}",
            "SHEET_APPLY_FORMULA" to "{\"action\":\"SHEET_APPLY_FORMULA\",\"range\":\"C2\",\"formula\":\"SUM(A2:B2)\"}",
            "SHEET_SORT_FILTER_RANGE" to "{\"action\":\"SHEET_SORT_FILTER_RANGE\",\"range\":\"A1:C9\",\"sortBy\":\"Status\"}",
            "SHEET_INSERT_DELETE_ROWS_COLUMNS" to "{\"action\":\"SHEET_INSERT_DELETE_ROWS_COLUMNS\",\"operation\":\"insert\",\"axis\":\"row\",\"count\":1}",
            "SHEET_VALIDATE_TABLE_STATE" to "{\"action\":\"SHEET_VALIDATE_TABLE_STATE\",\"expectedText\":\"Done\"}",
            "NOTION_RESOLVE_BLOCK_OR_PAGE" to "{\"action\":\"NOTION_RESOLVE_BLOCK_OR_PAGE\",\"query\":\"Launch plan\"}",
            "NOTION_CREATE_PAGE_OR_BLOCK" to "{\"action\":\"NOTION_CREATE_PAGE_OR_BLOCK\",\"blockType\":\"todo\",\"text\":\"Follow up\"}",
            "NOTION_UPDATE_DATABASE_ITEM" to "{\"action\":\"NOTION_UPDATE_DATABASE_ITEM\",\"matchValue\":\"Mobile\",\"properties\":{\"Status\":\"Done\"}}",
            "NOTION_MOVE_OR_REORDER_BLOCK" to "{\"action\":\"NOTION_MOVE_OR_REORDER_BLOCK\",\"blockLabel\":\"Follow up\",\"destinationLabel\":\"Meetings\"}"
        )

        cases.forEach { (expectedTool, json) ->
            val action = RelayClient().parsePlanActionJson(json)
            assertTrue("$expectedTool should parse as an artifact tool", action is DroidLmAction.ArtifactToolAction)
            action as DroidLmAction.ArtifactToolAction
            assertEquals(expectedTool, action.artifactToolName())
        }
    }

    @Test fun malformedTapReportsMissingCoordinate() {
        val error = runCatching {
            RelayClient().parsePlanActionJson("{\"action\":\"TAP\",\"reason\":\"missing x\"}")
        }.exceptionOrNull()
        assertEquals("TAP requires x", error?.message)
    }

    @Test fun malformedSwipeWithoutCoordinatesBecomesScroll() {
        val action = RelayClient().parsePlanActionJson(
            "{\"action\":\"SWIPE\",\"reason\":\"Scroll down the list\"}"
        )
        assertTrue(action is DroidLmAction.Scroll)
        action as DroidLmAction.Scroll
        assertEquals(ai.droidlm.intent.ScrollDirection.DOWN, action.direction)
    }

    @Test fun malformedLongPressWithTextBecomesLongPressNode() {
        val action = RelayClient().parsePlanActionJson(
            "{\"action\":\"LONG_PRESS\",\"text\":\"Budget\",\"reason\":\"Open the context menu\"}"
        )
        assertTrue(action is DroidLmAction.LongPressNode)
        action as DroidLmAction.LongPressNode
        assertEquals("Budget", action.text)
    }

    @Test fun selectItemAliasParsesTapText() {
        val action = RelayClient().parsePlanActionJson(
            "{\"action\":\"SELECT_ITEM\",\"label\":\"Drive\",\"reason\":\"Open the chooser item\"}"
        )
        assertTrue(action is DroidLmAction.TapText)
        action as DroidLmAction.TapText
        assertEquals("Drive", action.text)
    }

    @Test fun planPreviewJsonParse() {
        val plan = RelayClient().parsePlanPreviewJson("""{
            "model":"gpt-5.4-nano",
            "summary":"Open Drive",
            "riskLevel":"LOW",
            "requiresConfirmation":false,
            "steps":[{"index":1,"action":"OPEN_APP","appName":"Drive","packageName":"com.google.android.apps.docs","reason":"Open Drive","requiresConfirmation":false,"confidence":"HIGH","expectedResult":"Drive opens"}]
        }""")
        assertEquals("gpt-5.4-nano", plan.model)
        assertTrue(plan.isSafe)
        assertEquals(1, plan.steps.size)
        assertTrue(plan.steps.first().action is DroidLmAction.OpenApp)
        assertEquals(ActionConfidence.HIGH, plan.steps.first().confidence)
        assertEquals("Drive opens", plan.steps.first().expectedResult)
    }

    @Test fun planActionRequestIncludesDeviceContext() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"action\":\"NO_OP\",\"message\":\"ok\"}"))
            server.start()
            val activeApp = ActiveApp("com.google.android.apps.docs.editors.docs", "DocsActivity", "Docs")
            val request = RelayPlanRequest(
                goal = "append note",
                uiState = PortalState(activeApp.packageName, activeApp.activityName, 100, 200, emptyList()),
                packages = listOf(AppPackage(activeApp.packageName, "Docs")),
                history = emptyList(),
                maxSteps = 3,
                activeApp = activeApp,
                deviceContext = DeviceContext(
                    activeApp = activeApp,
                    packages = listOf(AppPackage(activeApp.packageName, "Docs")),
                    extras = JSONObject().put("docsContext", JSONObject().put("uiMode", "DOCUMENT_EDIT"))
                )
            )

            val result = RelayClient().planAction(server.url("/").toString(), request)
            assertTrue(result is RelayCallResult.Success)
            val body = JSONObject(server.takeRequest().body.readUtf8())
            assertEquals("DOCUMENT_EDIT", body.getJSONObject("deviceContext").getJSONObject("docsContext").getString("uiMode"))
            assertEquals(activeApp.packageName, body.getJSONObject("deviceContext").getJSONObject("activeApp").getString("packageName"))
        }
    }


    @Test fun plannerStatusJsonParse() {
        val status = RelayClient().parsePlannerStatusJson("{\"openAiKeyConfigured\":true,\"plannerModel\":\"gpt-5.4-nano\",\"latestNanoModel\":\"gpt-5.4-nano\",\"relayReady\":true}")
        assertTrue(status.openAiKeyConfigured)
        assertEquals("gpt-5.4-nano", status.plannerModel)
    }

    @Test fun analyzeScreenshotJsonParse() {
        val result = RelayClient().parseVisionAnalysisJson("{\"fullText\":\"Budget\",\"suggestedAction\":{\"type\":\"TAP\",\"x\":1,\"y\":2,\"confidence\":0.74},\"lines\":[{\"text\":\"Budget\",\"boundingBox\":{\"x\":1,\"y\":2,\"width\":3,\"height\":4}}],\"elements\":[]}")
        assertEquals("Budget", result.fullText)
        assertEquals(1, result.lines.size)
        assertEquals(1, result.suggestedAction?.x)
    }
}

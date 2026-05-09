package ai.droidlm.relay

import ai.droidlm.intent.DroidLmAction
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

    @Test fun debugLogUploadPostsMultipart() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"ok":true,"bucket":"example-debug-logs","objectName":"debug-logs/synthetic/bundle.zip","gsUri":"gs://example-debug-logs/debug-logs/synthetic/bundle.zip","sizeBytes":3,"contentType":"application/zip"}"""))
            server.start()
            val bundle = File.createTempFile("droidlm-debug", ".zip")
            try {
                bundle.writeBytes(byteArrayOf(1, 2, 3))
                val result = RelayClient().uploadDebugLogs(server.url("/").toString(), bundle, "ai.droidlm.debug", "0.1-debug")
                if (result !is RelayCallResult.Success) error("Expected upload success")
                assertEquals("debug-logs/synthetic/bundle.zip", result.value.objectName)
                val request = server.takeRequest()
                assertEquals("/debug-logs", request.path)
                val body = request.body.readUtf8()
                assertTrue(body.contains("name=\"logs\""))
                assertTrue(body.contains("name=\"appPackage\""))
                assertTrue(body.contains("ai.droidlm.debug"))
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
            "steps":[{"index":1,"action":"OPEN_APP","appName":"Drive","packageName":"com.google.android.apps.docs","reason":"Open Drive","requiresConfirmation":false}]
        }""")
        assertEquals("gpt-5.4-nano", plan.model)
        assertTrue(plan.isSafe)
        assertEquals(1, plan.steps.size)
        assertTrue(plan.steps.first().action is DroidLmAction.OpenApp)
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

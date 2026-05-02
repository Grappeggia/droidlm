package ai.droidlm.intent

import ai.droidlm.portal.AppPackage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentParserTest {
    private val parser = IntentParser()

    @Test fun driveCommandOpensDrivePackage() {
        val action = parser.parse("DroidLM open my Drive app")
        assertTrue(action is DroidLmAction.OpenApp)
        assertEquals("com.google.android.apps.docs", (action as DroidLmAction.OpenApp).packageName)
    }

    @Test fun gmailCommandOpensGmailPackage() {
        val action = parser.parse("droid lm launch gmail")
        assertTrue(action is DroidLmAction.OpenApp)
        assertEquals("com.google.android.gm", (action as DroidLmAction.OpenApp).packageName)
    }

    @Test fun docsCommandOpensGoogleDocsPackage() {
        val action = parser.parse("open google docs")
        assertTrue(action is DroidLmAction.OpenApp)
        assertEquals("com.google.android.apps.docs.editors.docs", (action as DroidLmAction.OpenApp).packageName)
    }

    @Test fun sheetsCommandOpensGoogleSheetsPackage() {
        val action = parser.parse("launch sheets")
        assertTrue(action is DroidLmAction.OpenApp)
        assertEquals("com.google.android.apps.docs.editors.sheets", (action as DroidLmAction.OpenApp).packageName)
    }

    @Test fun goHomeRecognized() {
        assertEquals(DroidLmAction.PressHome, parser.parse("hey Droid L M go home"))
    }

    @Test fun openSettingsRecognized() {
        assertTrue(parser.parse("open settings") is DroidLmAction.OpenSettings)
    }

    @Test fun emptyTranscriptNoOp() {
        assertTrue(parser.parse("") is DroidLmAction.NoOp)
    }

    @Test fun garbledTranscriptNeedsPlanning() {
        assertTrue(parser.parse("florble snark around") is DroidLmAction.NeedLlmPlanning)
    }

    @Test fun cursorAfterBudgetParsesInsertion() {
        val action = parser.parse("put the cursor after budget and type comma revised")
        assertTrue(action is DroidLmAction.InsertTextAtAnchor)
        action as DroidLmAction.InsertTextAtAnchor
        assertEquals("budget", action.anchorText)
        assertEquals(AnchorPosition.AFTER, action.anchorPosition)
        assertEquals(", revised", action.text)
    }

    @Test fun replaceParsesTextRange() {
        val action = parser.parse("replace draft with final")
        assertTrue(action is DroidLmAction.ReplaceTextRange)
        action as DroidLmAction.ReplaceTextRange
        assertEquals("draft", action.targetText)
        assertEquals("final", action.replacementText)
    }

    @Test fun appendParsesNewlineAndComma() {
        val action = parser.parse("append new line signed comma Alex")
        assertTrue(action is DroidLmAction.AppendText)
        assertEquals("\nsigned, alex", (action as DroidLmAction.AppendText).text)
    }

    @Test fun fuzzyInstalledPackageMatch() {
        val action = parser.parse("open calculator", listOf(AppPackage("com.example.calc", "Calculator")))
        assertTrue(action is DroidLmAction.OpenApp)
        assertEquals("com.example.calc", (action as DroidLmAction.OpenApp).packageName)
    }
}

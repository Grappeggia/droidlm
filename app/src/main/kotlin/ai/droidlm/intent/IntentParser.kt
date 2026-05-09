package ai.droidlm.intent

import ai.droidlm.portal.AppPackage
import kotlin.math.max

class IntentParser {
    private val explicitAliases = mapOf(
        "drive" to Alias("Drive", "com.google.android.apps.docs"),
        "google drive" to Alias("Drive", "com.google.android.apps.docs"),
        "my drive" to Alias("Drive", "com.google.android.apps.docs"),
        "gmail" to Alias("Gmail", "com.google.android.gm"),
        "google mail" to Alias("Gmail", "com.google.android.gm"),
        "docs" to Alias("Google Docs", "com.google.android.apps.docs.editors.docs"),
        "google docs" to Alias("Google Docs", "com.google.android.apps.docs.editors.docs"),
        "sheets" to Alias("Google Sheets", "com.google.android.apps.docs.editors.sheets"),
        "google sheets" to Alias("Google Sheets", "com.google.android.apps.docs.editors.sheets"),
        "chrome" to Alias("Chrome", "com.android.chrome"),
        "google chrome" to Alias("Chrome", "com.android.chrome")
    )

    fun parse(transcript: String, installedPackages: List<AppPackage> = emptyList()): DroidLmAction {
        val stripped = SpeechTextNormalizer.stripWakePhrase(transcript)
        val normalized = SpeechTextNormalizer.normalizeForRecognition(stripped)
        if (normalized.isBlank()) return DroidLmAction.NoOp("No command was heard")

        parseTextEditing(stripped, normalized)?.let { return it }

        if (normalized in setOf("go home", "home", "press home")) return DroidLmAction.PressHome
        if (normalized in setOf("press back", "go back", "back")) return DroidLmAction.PressBack
        if (normalized in setOf("take screenshot", "capture screenshot", "screenshot")) return DroidLmAction.TakeScreenshot
        if (normalized in setOf("open settings", "launch settings", "open android settings", "settings")) {
            return DroidLmAction.OpenSettings()
        }
        if (normalized in setOf("open", "launch", "start")) return DroidLmAction.NoOp("Please say which app to open")

        parseOpenApp(normalized, installedPackages)?.let { return it }

        return if (normalized.length < 3) {
            DroidLmAction.NoOp("Command was too short to understand")
        } else {
            DroidLmAction.NeedLlmPlanning("No local rule matched: $stripped")
        }
    }

    private fun parseOpenApp(normalized: String, installedPackages: List<AppPackage>): DroidLmAction? {
        val prefixes = listOf("open my ", "open the ", "open ", "launch my ", "launch the ", "launch ", "start my ", "start the ", "start ")
        val prefix = prefixes.firstOrNull { normalized.startsWith(it) } ?: return null
        val rawName = normalized.removePrefix(prefix).removeSuffix(" app").trim()
        if (rawName.isBlank()) return DroidLmAction.NoOp("Please say which app to open")
        if (rawName == "settings") return null
        explicitAliases[rawName]?.let { alias ->
            if (installedPackages.isNotEmpty() && !installedPackages.hasLaunchablePackage(alias.packageName)) {
                return DroidLmAction.OpenAppStoreListing(alias.label, alias.packageName, "User asked to open ${alias.label}, but it is not installed or launchable")
            }
            return DroidLmAction.OpenApp(alias.label, alias.packageName, "User asked to open ${alias.label}")
        }
        val match = resolveInstalledPackage(rawName, installedPackages) ?: return null
        if (match.launchable == false) {
            return DroidLmAction.OpenAppStoreListing(match.label ?: rawName, match.packageName, "User asked to open ${match.label ?: rawName}, but it is not launchable")
        }
        return DroidLmAction.OpenApp(match.label ?: rawName, match.packageName, "User asked to open ${match.label ?: rawName}")
    }

    private fun parseTextEditing(original: String, normalized: String): DroidLmAction? {
        if (normalized in setOf(
                "add a bullet point on the current line",
                "add bullet point on current line",
                "bullet point current line",
                "make the current line a bullet point"
            )
        ) {
            return DroidLmAction.FormatCurrentLineAsBullet()
        }

        Regex("^append (?:a )?note saying (.+)", RegexOption.IGNORE_CASE).find(original)?.let { match ->
            return DroidLmAction.AppendDocumentNote(
                note = SpeechTextNormalizer.normalizeDictatedText(match.groupValues[1]),
                reason = "User asked to append a document note"
            )
        }

        Regex("^put (.+) in (?:the )?current cell", RegexOption.IGNORE_CASE).find(original)?.let { match ->
            return DroidLmAction.SetCurrentSheetCell(
                value = SpeechTextNormalizer.normalizeDictatedText(match.groupValues[1]),
                reason = "User asked to set the current spreadsheet cell"
            )
        }

        Regex("^add (?:a )?row with (.+)", RegexOption.IGNORE_CASE).find(original)?.let { match ->
            return DroidLmAction.AddSpreadsheetRow(
                values = parseDictatedList(match.groupValues[1]),
                reason = "User asked to add a spreadsheet row"
            )
        }
        Regex("put the cursor (after|before)(?: the word)? (.+?) and type (.+)", RegexOption.IGNORE_CASE)
            .find(original)?.let { match ->
                val position = if (match.groupValues[1].equals("after", true)) AnchorPosition.AFTER else AnchorPosition.BEFORE
                val anchor = cleanupAnchor(match.groupValues[2])
                val text = SpeechTextNormalizer.normalizeDictatedText(match.groupValues[3])
                return DroidLmAction.InsertTextAtAnchor(
                    anchorText = anchor,
                    anchorPosition = position,
                    text = text,
                    reason = "User asked to insert text ${position.name.lowercase()} the word $anchor"
                )
            }

        Regex("replace (.+?) with (.+)", RegexOption.IGNORE_CASE).find(original)?.let { match ->
            val target = cleanupAnchor(match.groupValues[1])
            val replacement = SpeechTextNormalizer.normalizeDictatedText(match.groupValues[2])
            return DroidLmAction.ReplaceTextRange(target, replacement, "User asked to replace $target with $replacement")
        }

        Regex("^(?:type|insert) (.+)", RegexOption.IGNORE_CASE).find(original)?.let { match ->
            val text = SpeechTextNormalizer.normalizeDictatedText(match.groupValues[1])
            return DroidLmAction.TypeText(text, clear = false, reason = "User asked to type dictated text")
        }

        Regex("^append (.+)", RegexOption.IGNORE_CASE).find(original)?.let { match ->
            return DroidLmAction.AppendText(SpeechTextNormalizer.normalizeDictatedText(match.groupValues[1]))
        }

        Regex("^prepend (.+)", RegexOption.IGNORE_CASE).find(original)?.let { match ->
            val text = SpeechTextNormalizer.normalizeDictatedText(match.groupValues[1]).replaceFirstChar { char ->
                if (char.isLowerCase()) char.titlecase() else char.toString()
            }
            return DroidLmAction.PrependText(text)
        }

        if (normalized == "move cursor to start" || normalized == "put cursor at start") {
            return DroidLmAction.MoveCursor("start", "User asked to move cursor to start")
        }
        if (normalized == "move cursor to end" || normalized == "put cursor at end") {
            return DroidLmAction.MoveCursor("end", "User asked to move cursor to end")
        }
        if (normalized == "select all") return DroidLmAction.SelectAll
        if (normalized == "delete selected text" || normalized == "delete selection") return DroidLmAction.DeleteSelectedText
        return null
    }

    private fun List<AppPackage>.hasLaunchablePackage(packageName: String): Boolean =
        any { it.packageName == packageName && it.launchable != false && it.enabled != false }

    private fun resolveInstalledPackage(appName: String, installedPackages: List<AppPackage>): AppPackage? {
        val cleaned = appName.lowercase()
        val exact = installedPackages.firstOrNull { it.label?.lowercase() == cleaned || it.packageName.lowercase() == cleaned }
        if (exact != null) return exact
        val contains = installedPackages.firstOrNull { pkg ->
            val label = pkg.label?.lowercase().orEmpty()
            label.contains(cleaned) || cleaned.contains(label).takeIf { label.length > 2 } == true
        }
        if (contains != null) return contains
        return installedPackages
            .map { it to similarity(cleaned, it.label?.lowercase().orEmpty()) }
            .filter { it.second >= 0.55 }
            .maxByOrNull { it.second }
            ?.first
    }

    private fun parseDictatedList(value: String): List<String> = value
        .split(",", " and ")
        .map { SpeechTextNormalizer.normalizeDictatedText(it).trim() }
        .filter { it.isNotBlank() }

    private fun cleanupAnchor(value: String): String = value
        .trim()
        .removePrefix("the word ")
        .trim(',', '.', ' ', ':', ';')

    private fun similarity(a: String, b: String): Double {
        if (a.isBlank() || b.isBlank()) return 0.0
        val distance = levenshtein(a, b).toDouble()
        return 1.0 - distance / max(a.length, b.length).toDouble()
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost)
            }
        }
        return dp[a.length][b.length]
    }

    private data class Alias(val label: String, val packageName: String)
}

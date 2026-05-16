package ai.droidlm.execution

import ai.droidlm.intent.SpeechTextNormalizer
import ai.droidlm.portal.PortalState
import ai.droidlm.portal.UiNode
import kotlin.math.max

internal data class TranscriptCorrection(
    val originalTranscript: String,
    val correctedTranscript: String,
    val targetText: String,
    val replacementText: String,
    val source: String,
    val score: Double
)

internal class SpeechTranscriptCorrector {
    fun correct(transcript: String, state: PortalState?): TranscriptCorrection? {
        val command = commandTarget(transcript) ?: return null
        if (command.targetWords.size < 2 || command.targetNormalized.length < MIN_TARGET_CHARS) return null

        visibleLabelCorrection(command, state)?.let { return it }
        return workspaceAliasCorrection(command, state)
    }

    private fun visibleLabelCorrection(command: CommandTarget, state: PortalState?): TranscriptCorrection? {
        val candidates = visibleLabelCandidates(state).distinctBy { normalizeLabel(it.label) }
        val scored = candidates
            .map { candidate -> candidate to phraseSimilarity(command.targetNormalized, normalizeLabel(candidate.label)) }
            .filter { (_, score) -> score >= VISIBLE_LABEL_THRESHOLD }
            .sortedByDescending { (_, score) -> score }
        val best = scored.firstOrNull() ?: return null
        val secondScore = scored.drop(1).firstOrNull()?.second ?: 0.0
        val label = best.first.label
        val normalizedLabel = normalizeLabel(label)
        if (normalizedLabel == command.targetNormalized) return null
        if (best.second - secondScore < MIN_SCORE_GAP) return null
        return command.correction(label, "visible_label", best.second)
    }

    private fun workspaceAliasCorrection(command: CommandTarget, state: PortalState?): TranscriptCorrection? {
        if (!isWorkspaceContext(state)) return null
        val tokens = command.targetWords
        val corrected = tokens.toMutableList()
        var changed = false
        tokens.forEachIndexed { index, token ->
            val alias = WORKSPACE_ALIASES.firstOrNull { alias -> tokenLooksLikeAlias(token, alias) } ?: return@forEachIndexed
            if (token != alias && aliasReplacementAllowed(tokens, index, alias)) {
                corrected[index] = alias
                changed = true
            }
        }
        if (!changed) return null
        val replacement = corrected.joinToString(" ")
        if (replacement == command.targetNormalized) return null
        return command.correction(replacement, "workspace_alias", phraseSimilarity(command.targetNormalized, replacement))
    }

    private fun aliasReplacementAllowed(tokens: List<String>, index: Int, alias: String): Boolean {
        if (tokens.size < 2) return false
        if (index == tokens.lastIndex && index > 0 && tokens[index - 1] in WORKSPACE_PREPOSITIONS) return true
        if (index == tokens.lastIndex && tokens.firstOrNull() == "google") return true
        return tokens.size <= 3 && index == tokens.lastIndex && alias in setOf("docs", "drive", "sheets")
    }

    private fun tokenLooksLikeAlias(token: String, alias: String): Boolean {
        if (token == alias) return false
        if (token.length < 3 || alias.length < 3) return false
        return tokenSimilarity(token, alias) >= ALIAS_TOKEN_THRESHOLD || soundex(token) == soundex(alias)
    }

    private fun isWorkspaceContext(state: PortalState?): Boolean {
        val packageName = state?.packageName ?: return false
        return packageName in WORKSPACE_PACKAGES
    }

    private fun visibleLabelCandidates(state: PortalState?): List<LabelCandidate> = state?.nodes.orEmpty()
        .asSequence()
        .filter { it.visible && it.enabled && !it.password }
        .flatMap { node -> nodeLabels(node).asSequence() }
        .mapNotNull(::cleanCandidate)
        .filterNot { isGenericLabel(it) }
        .map { LabelCandidate(it) }
        .take(MAX_CANDIDATES)
        .toList()

    private fun nodeLabels(node: UiNode): List<String> = buildList {
        listOfNotNull(node.text, node.contentDescription, node.hintText, node.stateDescription).forEach { raw ->
            val label = cleanLine(raw)
            if (label.isNotBlank()) add(label)
            if (label.startsWith(MORE_ACTIONS_PREFIX, ignoreCase = true)) {
                add(label.substring(MORE_ACTIONS_PREFIX.length).trim())
            }
        }
    }

    private fun cleanCandidate(value: String): String? {
        val cleaned = cleanLine(value)
        if (cleaned.length !in MIN_LABEL_CHARS..MAX_LABEL_CHARS) return null
        if (cleaned.any { it.isLetter() }) return cleaned
        return null
    }

    private fun commandTarget(transcript: String): CommandTarget? {
        val stripped = SpeechTextNormalizer.stripWakePhrase(transcript).trim()
        val normalized = stripped.replace(Regex("\\s+"), " ").trim()
        val lowered = normalized.lowercase()
        val prefix = COMMAND_PREFIXES.firstOrNull { lowered.startsWith(it) } ?: return null
        val target = lowered.removePrefix(prefix).removeSuffix(" app").trim()
        if (target.isBlank()) return null
        val words = target.split(' ').filter { it.isNotBlank() }
        return CommandTarget(
            originalTranscript = stripped,
            prefix = prefix,
            targetNormalized = target,
            targetWords = words
        )
    }

    private fun CommandTarget.correction(replacement: String, source: String, score: Double): TranscriptCorrection = TranscriptCorrection(
        originalTranscript = originalTranscript,
        correctedTranscript = "$prefix$replacement",
        targetText = targetNormalized,
        replacementText = replacement,
        source = source,
        score = score
    )

    private fun phraseSimilarity(a: String, b: String): Double {
        if (a.isBlank() || b.isBlank()) return 0.0
        val full = tokenSimilarity(a, b)
        val aTokens = a.split(' ').filter { it.isNotBlank() }
        val bTokens = b.split(' ').filter { it.isNotBlank() }
        val covered = aTokens.count { token -> bTokens.any { other -> token == other || tokenSimilarity(token, other) >= TOKEN_COVERAGE_THRESHOLD || soundex(token) == soundex(other) } }
        val coverage = if (aTokens.isEmpty()) 0.0 else covered.toDouble() / aTokens.size.toDouble()
        return max(full, (full * 0.8) + (coverage * 0.2))
    }

    private fun tokenSimilarity(a: String, b: String): Double {
        if (a.isBlank() || b.isBlank()) return 0.0
        if (a == b) return 1.0
        val distance = levenshtein(a, b).toDouble()
        return 1.0 - distance / max(a.length, b.length).toDouble()
    }

    private fun soundex(value: String): String {
        val letters = value.lowercase().filter { it in 'a'..'z' }
        if (letters.isBlank()) return ""
        val first = letters.first().uppercaseChar()
        val digits = letters.drop(1).map(::soundexDigit)
        val deduped = buildString {
            var previous = soundexDigit(letters.first())
            digits.forEach { digit ->
                if (digit != '0' && digit != previous) append(digit)
                previous = digit
            }
        }
        return (first + deduped).padEnd(4, '0').take(4)
    }

    private fun soundexDigit(char: Char): Char = when (char) {
        'b', 'f', 'p', 'v' -> '1'
        'c', 'g', 'j', 'k', 'q', 's', 'x', 'z' -> '2'
        'd', 't' -> '3'
        'l' -> '4'
        'm', 'n' -> '5'
        'r' -> '6'
        else -> '0'
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

    private fun cleanLine(value: String): String = value
        .replace(Regex("[\\u0000-\\u001F\\uE000-\\uF8FF]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim(' ', '-', ':', '|', '\u2022')

    private fun normalizeLabel(value: String): String = value
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun isGenericLabel(label: String): Boolean {
        val normalized = normalizeLabel(label)
        if (normalized.isBlank()) return true
        if (normalized.length == 1 && normalized.first().isLetterOrDigit()) return true
        return normalized in GENERIC_LABELS
    }

    private data class CommandTarget(
        val originalTranscript: String,
        val prefix: String,
        val targetNormalized: String,
        val targetWords: List<String>
    )

    private data class LabelCandidate(val label: String)

    private companion object {
        const val MIN_TARGET_CHARS = 6
        const val MIN_LABEL_CHARS = 2
        const val MAX_LABEL_CHARS = 120
        const val MAX_CANDIDATES = 120
        const val VISIBLE_LABEL_THRESHOLD = 0.84
        const val ALIAS_TOKEN_THRESHOLD = 0.6
        const val TOKEN_COVERAGE_THRESHOLD = 0.72
        const val MIN_SCORE_GAP = 0.03
        const val MORE_ACTIONS_PREFIX = "More actions for "

        val COMMAND_PREFIXES = listOf(
            "open my ", "open the ", "open ",
            "launch my ", "launch the ", "launch ",
            "start my ", "start the ", "start ",
            "find the ", "find ",
            "search for the ", "search for ", "search ",
            "go to the ", "go to ",
            "navigate to the ", "navigate to "
        )
        val WORKSPACE_ALIASES = setOf("docs", "drive", "sheets")
        val WORKSPACE_PREPOSITIONS = setOf("of", "in", "from", "on")
        val WORKSPACE_PACKAGES = setOf(
            "com.google.android.apps.docs",
            "com.google.android.apps.docs.editors.docs",
            "com.google.android.apps.docs.editors.sheets"
        )
        val GENERIC_LABELS = setOf(
            "back", "close", "done", "cancel", "ok", "save", "edit", "share", "search", "find", "more options",
            "menu", "home", "recent", "recents", "files", "folders", "docs", "sheets", "drive", "google docs",
            "google sheets", "google drive", "comments", "format", "undo", "redo", "search docs", "view as list"
        )
    }
}

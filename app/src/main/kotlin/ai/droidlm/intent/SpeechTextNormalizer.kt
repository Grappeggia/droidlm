package ai.droidlm.intent

object SpeechTextNormalizer {
    private val punctuation = mapOf(
        "comma" to ",",
        "period" to ".",
        "full stop" to ".",
        "colon" to ":",
        "semicolon" to ";",
        "question mark" to "?",
        "exclamation mark" to "!"
    )

    fun stripWakePhrase(transcript: String): String {
        var value = transcript.trim()
        val variants = listOf(
            "hey droid l m",
            "hey droid lm",
            "hey droidlm",
            "droid l m",
            "droid lm",
            "droidlm"
        )
        var changed: Boolean
        do {
            changed = false
            val lowered = value.lowercase().trimStart(',', '.', ' ', ':', ';')
            val match = variants.firstOrNull { lowered == it || lowered.startsWith("$it ") || lowered.startsWith("$it,") }
            if (match != null) {
                val index = value.lowercase().indexOf(match)
                if (index >= 0) {
                    value = value.substring(index + match.length).trimStart(',', '.', ' ', ':', ';')
                    changed = true
                }
            }
        } while (changed)
        return value.trim()
    }

    fun normalizeForRecognition(value: String): String = stripWakePhrase(value)
        .lowercase()
        .replace(Regex("[^a-z0-9\\s]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    fun normalizeDictatedText(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("the word ", ignoreCase = true)) {
            return trimmed.substringAfter("the word ", "").trim()
        }
        val normalized = trimmed.lowercase().replace(Regex("\\s+"), " ").trim()
        if (normalized.isEmpty()) return ""
        val tokens = normalized.split(" ")
        val out = StringBuilder()
        var index = 0
        var needsSpaceAfterPunctuation = false
        while (index < tokens.size) {
            val two = tokens.drop(index).take(2).joinToString(" ")
            val replacement = when {
                two == "new line" -> "\n"
                two == "full stop" -> "."
                two == "question mark" -> "?"
                two == "exclamation mark" -> "!"
                tokens[index] == "newline" -> "\n"
                tokens[index] == "space" -> " "
                punctuation.containsKey(tokens[index]) -> punctuation.getValue(tokens[index])
                else -> null
            }
            if (replacement != null) {
                if (replacement == "\n" || replacement == " ") {
                    out.append(replacement)
                    needsSpaceAfterPunctuation = false
                } else {
                    trimTrailingSpace(out)
                    out.append(replacement)
                    needsSpaceAfterPunctuation = true
                }
                index += if (two in setOf("new line", "full stop", "question mark", "exclamation mark")) 2 else 1
            } else {
                if (out.isNotEmpty() && out.last() != ' ' && out.last() != '\n') {
                    out.append(if (needsSpaceAfterPunctuation) " " else " ")
                }
                out.append(tokens[index])
                needsSpaceAfterPunctuation = false
                index++
            }
        }
        return out.toString()
    }

    private fun trimTrailingSpace(builder: StringBuilder) {
        while (builder.isNotEmpty() && builder.last() == ' ') {
            builder.deleteCharAt(builder.length - 1)
        }
    }
}

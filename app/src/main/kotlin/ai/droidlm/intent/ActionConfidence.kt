package ai.droidlm.intent

enum class ActionConfidence {
    HIGH,
    MEDIUM,
    LOW;

    companion object {
        fun parse(value: String?, default: ActionConfidence = LOW): ActionConfidence {
            val normalized = value?.trim()?.uppercase().orEmpty()
            return entries.firstOrNull { it.name == normalized } ?: default
        }
    }
}

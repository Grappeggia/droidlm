package ai.droidlm.openai

object OpenAiRuntimeOverrides {
    @Volatile
    var chatCompletionsEndpoint: String? = null
}

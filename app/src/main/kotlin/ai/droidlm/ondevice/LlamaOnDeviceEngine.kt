package ai.droidlm.ondevice

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

internal class LlamaOnDeviceEngine(
    context: Context
) {
    private val nativeLibDir: String = context.applicationInfo.nativeLibraryDir.orEmpty()
    private val engineMutex = Mutex()
    private var loadedModelPath: String? = null

    init {
        require(nativeLibDir.isNotBlank()) { "Expected a valid native library path" }
        System.loadLibrary(NATIVE_LIBRARY_NAME)
        nativeInit(nativeLibDir)
    }

    suspend fun ensureModelLoaded(modelPath: String, contextSize: Int) = engineMutex.withLock {
        if (loadedModelPath == modelPath) return@withLock
        val modelFile = File(modelPath)
        require(modelFile.isFile && modelFile.canRead()) { "Model file is not readable: $modelPath" }
        loadedModelPath?.let {
            nativeUnloadModel()
            loadedModelPath = null
        }
        val loadResult = nativeLoadModel(modelPath, contextSize)
        if (loadResult != 0) {
            throw IOException("Could not load local model (code $loadResult)")
        }
        loadedModelPath = modelPath
    }

    suspend fun generateJson(
        systemPrompt: String,
        userPrompt: String,
        jsonSchema: String,
        maxTokens: Int,
        temperature: Float,
        topK: Int,
        topP: Float,
        minP: Float,
        presencePenalty: Float
    ): String = engineMutex.withLock {
        val response = nativeGenerateJson(
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            jsonSchema = jsonSchema,
            maxTokens = maxTokens,
            temperature = temperature,
            topK = topK,
            topP = topP,
            minP = minP,
            presencePenalty = presencePenalty
        ) ?: throw IOException("Local planner returned no response")
        if (response.startsWith(NATIVE_ERROR_PREFIX)) {
            throw IOException(response.removePrefix(NATIVE_ERROR_PREFIX))
        }
        return@withLock response
    }

    suspend fun unloadModel() = engineMutex.withLock {
        if (loadedModelPath != null) {
            nativeUnloadModel()
            loadedModelPath = null
        }
    }

    suspend fun shutdown() = withContext(Dispatchers.IO) {
        engineMutex.withLock {
            if (loadedModelPath != null) {
                nativeUnloadModel()
                loadedModelPath = null
            }
            nativeShutdown()
        }
    }

    private external fun nativeInit(nativeLibDir: String)
    private external fun nativeLoadModel(modelPath: String, contextSize: Int): Int
    private external fun nativeGenerateJson(
        systemPrompt: String,
        userPrompt: String,
        jsonSchema: String,
        maxTokens: Int,
        temperature: Float,
        topK: Int,
        topP: Float,
        minP: Float,
        presencePenalty: Float
    ): String?
    private external fun nativeUnloadModel()
    private external fun nativeShutdown()

    companion object {
        private const val NATIVE_LIBRARY_NAME = "droidlm_ondevice"
        private const val NATIVE_ERROR_PREFIX = "__DROIDLM_ERROR__:"
    }
}

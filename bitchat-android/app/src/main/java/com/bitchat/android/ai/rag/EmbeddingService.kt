package com.bitchat.android.ai.rag

import android.content.Context
import android.util.Log
import com.nexa.sdk.EmbedderWrapper
import com.nexa.sdk.bean.EmbedderCreateInput
import com.nexa.sdk.bean.EmbeddingConfig
import com.nexa.sdk.bean.ModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * On-device embedding service using Nexa SDK EmbedderWrapper.
 *
 * Generates vector embeddings for text chunks using models such as
 * EmbeddingGemma 300M (768 dimensions) or Nomic Embed Text v1.5 (137M).
 *
 * API follows the cookbook at nexa-sdk/cookbook/android/RAG-LLM/ exactly:
 *   EmbedderWrapper.builder()
 *       .embedderCreateInput(EmbedderCreateInput(...))
 *       .build()
 *   wrapper.embed(arrayOf(text), EmbeddingConfig())
 */
class EmbeddingService(private val context: Context) {

    companion object {
        private const val TAG = "EmbeddingService"
        const val DEFAULT_CHUNK_SIZE = 128 // words per chunk (cookbook default)
        const val MIN_CHUNK_SIZE = 16
        const val MAX_CHUNK_SIZE = 512
    }

    private var embedderWrapper: EmbedderWrapper? = null
    private var isInitialized = false
    private var embeddingDimension: Int = 768

    // ------------------------------------------------------------------ //
    // Lifecycle
    // ------------------------------------------------------------------ //

    /**
     * Initialize the embedder with a downloaded embedding model directory.
     *
     * The directory must contain the model file (.gguf), an optional
     * tokenizer (tokenizer.model / tokenizer.json / vocab.txt), and an
     * optional nexa.manifest for NPU models.
     *
     * @param modelDir Root directory of the embedding model.
     * @return true if the embedder was created successfully.
     */
    suspend fun initialize(modelDir: File): Boolean = withContext(Dispatchers.IO) {
        if (isInitialized && embedderWrapper != null) return@withContext true

        try {
            val modelPath = findModelFile(modelDir)
            if (modelPath == null) {
                Log.e(TAG, "No model file found in ${modelDir.absolutePath}")
                return@withContext false
            }

            val tokenizerPath = findTokenizerFile(modelDir)?.absolutePath
            val manifest = readNexaManifest(modelDir)
            val pluginId = manifest?.pluginId ?: "cpu_gpu"
            val modelName = manifest?.modelName ?: ""

            Log.d(TAG, "Initializing embedder: model=$modelPath, tokenizer=$tokenizerPath, plugin=$pluginId")

            val createInput = EmbedderCreateInput(
                model_name = modelName,
                model_path = modelPath.absolutePath,
                tokenizer_path = tokenizerPath,
                config = ModelConfig(
                    npu_lib_folder_path = context.applicationInfo.nativeLibraryDir,
                    npu_model_folder_path = modelDir.absolutePath,
                    nGpuLayers = 999
                ),
                plugin_id = pluginId,
                device_id = null
            )

            EmbedderWrapper.builder()
                .embedderCreateInput(createInput)
                .build()
                .onSuccess { wrapper ->
                    embedderWrapper = wrapper
                    isInitialized = true
                    // Probe embedding dimension with a tiny test string
                    probeDimension()
                    Log.d(TAG, "EmbedderWrapper initialized (dim=$embeddingDimension)")
                }
                .onFailure { error ->
                    Log.e(TAG, "Failed to build EmbedderWrapper", error)
                }

            return@withContext isInitialized
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing EmbeddingService", e)
            return@withContext false
        }
    }

    /** Release native resources. Safe to call multiple times. */
    fun release() {
        try {
            embedderWrapper?.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "Error destroying EmbedderWrapper", e)
        }
        embedderWrapper = null
        isInitialized = false
        Log.d(TAG, "EmbeddingService released")
    }

    fun isReady(): Boolean = isInitialized && embedderWrapper != null

    fun getDimension(): Int = embeddingDimension

    // ------------------------------------------------------------------ //
    // Embedding generation
    // ------------------------------------------------------------------ //

    /**
     * Generate an embedding vector for a single text string.
     *
     * Follows the cookbook pattern:
     *   embedderWrapper.embed(arrayOf(text), EmbeddingConfig())
     */
    suspend fun embed(text: String): FloatArray? = withContext(Dispatchers.IO) {
        val wrapper = embedderWrapper
        if (wrapper == null) {
            Log.w(TAG, "embed() called but embedder not initialized")
            return@withContext null
        }
        if (text.isBlank()) return@withContext null

        var result: FloatArray? = null
        try {
            wrapper.embed(arrayOf(text), EmbeddingConfig())
                .onSuccess { embedResult ->
                    result = embedResult.embeddings
                }
                .onFailure { error ->
                    Log.e(TAG, "Embedding failed for text (${text.take(60)}...): $error")
                }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during embed()", e)
        }
        return@withContext result
    }

    /**
     * Generate embeddings for multiple texts sequentially.
     *
     * Returns a list the same size as [texts]. Entries that fail to embed
     * are null.
     */
    suspend fun embedBatch(texts: List<String>): List<FloatArray?> = withContext(Dispatchers.IO) {
        texts.map { text -> embed(text) }
    }

    // ------------------------------------------------------------------ //
    // Text chunking  (cookbook: split by word count)
    // ------------------------------------------------------------------ //

    /**
     * Split [text] into chunks of approximately [chunkSize] words.
     *
     * Matches the cookbook pattern in GenerateEmbedStringsUtil.chunkText():
     *   words.subList(i, min(i + chunkSize, words.size)).joinToString(" ")
     */
    fun chunkText(
        text: String,
        chunkSize: Int = DEFAULT_CHUNK_SIZE
    ): List<String> {
        val effectiveSize = chunkSize.coerceIn(MIN_CHUNK_SIZE, MAX_CHUNK_SIZE)
        val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()

        val chunks = mutableListOf<String>()
        var i = 0
        while (i < words.size) {
            val endIndex = minOf(i + effectiveSize, words.size)
            val chunk = words.subList(i, endIndex).joinToString(" ")
            chunks.add(chunk)
            i += effectiveSize
        }
        return chunks
    }

    // ------------------------------------------------------------------ //
    // Cosine similarity  (cookbook: GenerateEmbedStringsUtil / IndexFragment)
    // ------------------------------------------------------------------ //

    /**
     * Compute cosine similarity between two embedding vectors.
     *
     * Copied verbatim from the cookbook (GenerateEmbedStringsUtil.computeCosineSimilarity).
     */
    fun cosineSimilarity(embedding1: FloatArray?, embedding2: FloatArray?): Float {
        if (embedding1 == null || embedding2 == null) return 0.0f
        if (embedding1.isEmpty() || embedding2.isEmpty()) return 0.0f
        if (embedding1.size != embedding2.size) return 0.0f

        var dotProduct = 0.0f
        var norm1 = 0.0f
        var norm2 = 0.0f

        for (i in embedding1.indices) {
            dotProduct += embedding1[i] * embedding2[i]
            norm1 += embedding1[i] * embedding1[i]
            norm2 += embedding2[i] * embedding2[i]
        }

        val epsilon = 1e-8f
        norm1 = kotlin.math.sqrt(norm1 + epsilon)
        norm2 = kotlin.math.sqrt(norm2 + epsilon)
        return dotProduct / (norm1 * norm2)
    }

    // ------------------------------------------------------------------ //
    // Internal helpers
    // ------------------------------------------------------------------ //

    /** Probe embedding dimension with a short test string. */
    private suspend fun probeDimension() {
        val test = embed("test")
        if (test != null && test.isNotEmpty()) {
            embeddingDimension = test.size
        }
    }

    /** Locate the .gguf model file inside [dir]. */
    private fun findModelFile(dir: File): File? {
        return dir.listFiles()?.firstOrNull { it.extension == "gguf" }
            ?: dir.listFiles()?.firstOrNull { it.extension == "bin" }
            ?: dir.listFiles()?.firstOrNull { it.name.contains("model") && it.isFile }
    }

    /** Locate a tokenizer file (cookbook pattern). */
    private fun findTokenizerFile(dir: File): File? {
        val names = listOf("tokenizer.model", "tokenizer.json", "vocab.txt")
        return names.map { File(dir, it) }.firstOrNull { it.exists() }
    }

    /** Read optional nexa.manifest for NPU/plugin metadata. */
    private data class NexaManifest(
        val modelName: String?,
        val modelType: String?,
        val pluginId: String?
    )

    private fun readNexaManifest(modelDir: File): NexaManifest? {
        val file = File(modelDir, "nexa.manifest")
        if (!file.exists()) return null
        return try {
            val json = file.readText()
            val obj = JSONObject(json)
            NexaManifest(
                modelName = obj.optString("ModelName", null) ?: obj.optString("Name", null),
                modelType = obj.optString("ModelType", null),
                pluginId = obj.optString("PluginId", null)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse nexa.manifest", e)
            null
        }
    }
}

package com.bitchat.android.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Model Manager - handles model downloads and storage
 */
class ModelManager(private val context: Context) {

    companion object {
        private const val TAG = "ModelManager"
        private const val MODELS_DIR = "models"
        private const val BUFFER_SIZE = 8 * 1024 // 8 KB
    }

    // Single-slot dispatcher so downloads don't starve BLE's shared IO pool.
    private val downloadDispatcher = Dispatchers.IO.limitedParallelism(1)

    private val modelsDir: File by lazy {
        File(context.filesDir, MODELS_DIR).also { it.mkdirs() }
    }

    // External files dir is ADB-writable without root — used by push_debug_models.sh
    private val externalModelsDir: File? by lazy {
        context.getExternalFilesDir(MODELS_DIR)?.also { it.mkdirs() }
    }

    private fun findFile(name: String): File? {
        val internal = File(modelsDir, name)
        if (internal.exists() && internal.length() > 256) return internal
        val external = externalModelsDir?.let { File(it, name) }
        if (external != null && external.exists() && external.length() > 256) return external
        return null
    }

    // Native SDK (JNI) can't open files from external storage paths.
    // This migrates a model from external to internal storage if needed,
    // then returns the internal path the SDK can safely open.
    private fun ensureInternalPath(name: String): File? {
        val internal = File(modelsDir, name)
        if (internal.exists() && internal.length() > 256) return internal
        val external = externalModelsDir?.let { File(it, name) } ?: return null
        if (!external.exists() || external.length() <= 256) return null
        Log.d(TAG, "Migrating $name from external to internal (${external.length() / 1_048_576} MB) ...")
        return try {
            external.copyTo(internal, overwrite = true)
            Log.d(TAG, "Migration complete: $name")
            internal
        } catch (e: Exception) {
            Log.e(TAG, "Migration failed for $name: ${e.message}")
            null
        }
    }

    fun isModelDownloaded(model: ModelInfo): Boolean {
        return findFile("${model.id}.gguf") != null
    }

    fun getDownloadedModels(): List<ModelInfo> {
        return ModelCatalog.LLM_MODELS.filter { isModelDownloaded(it) }
    }

    suspend fun downloadModel(
        model: ModelInfo,
        onProgress: (progress: Float, downloadedMB: Float, totalMB: Float) -> Unit
    ): Result<Unit> = withContext(downloadDispatcher) {
        val url = model.downloadUrl.ifBlank {
            return@withContext Result.failure(
                IllegalArgumentException("No download URL configured for model '${model.id}'")
            )
        }

        val modelFile = File(modelsDir, "${model.id}.gguf")
        val tmpFile = File(modelsDir, "${model.id}.gguf.tmp")

        try {
            Log.d(TAG, "Starting download of ${model.name} from $url")

            var connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 30_000
                readTimeout = 60_000
                instanceFollowRedirects = true
            }

            // Follow up to 5 redirects manually (handles HF CDN hops)
            var redirects = 0
            while (connection.responseCode in 301..302 && redirects < 5) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                connection = (URL(location).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 30_000
                    readTimeout = 60_000
                    instanceFollowRedirects = true
                }
                redirects++
            }

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                connection.disconnect()
                return@withContext Result.failure(
                    RuntimeException("HTTP ${connection.responseCode} for $url")
                )
            }

            val contentLength = connection.contentLengthLong
            val totalMB = if (contentLength > 0) contentLength / (1024f * 1024f)
                          else model.fileSizeMB.toFloat()

            connection.inputStream.use { input ->
                FileOutputStream(tmpFile).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var downloaded = 0L
                    var bytesRead: Int

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloaded += bytesRead
                        val downloadedMB = downloaded / (1024f * 1024f)
                        val progress = if (contentLength > 0) downloaded.toFloat() / contentLength else 0f
                        onProgress(progress.coerceIn(0f, 1f), downloadedMB, totalMB)
                    }
                }
            }

            connection.disconnect()

            // Atomic rename: only replace if download completed fully
            tmpFile.renameTo(modelFile)
            Log.d(TAG, "Download completed: ${model.name} (${modelFile.length() / 1_048_576} MB)")
            onProgress(1f, modelFile.length() / (1024f * 1024f), totalMB)
            Result.success(Unit)
        } catch (e: Exception) {
            tmpFile.delete()
            Log.e(TAG, "Download failed for ${model.name}", e)
            Result.failure(e)
        }
    }

    fun getModelPath(model: ModelInfo): String? =
        ensureInternalPath("${model.id}.gguf")?.absolutePath

    fun deleteModel(model: ModelInfo): Boolean {
        val modelFile = File(modelsDir, "${model.id}.gguf")
        return modelFile.delete()
    }

    // ─── AIModel (richer type) helpers ────────────────────────────────────────

    fun isAIModelDownloaded(model: AIModel): Boolean {
        val ext = if (model.type == ModelType.NPU) ".nexa" else ".gguf"
        return findFile("${model.id}$ext") != null
    }

    fun getAIModelPath(model: AIModel): String? {
        val ext = if (model.type == ModelType.NPU) ".nexa" else ".gguf"
        return ensureInternalPath("${model.id}$ext")?.absolutePath
    }

    /**
     * Download an [AIModel] and all its declared dependencies in order.
     * For NPU models, the GGUF base model is downloaded first.
     *
     * Calls [onProgress] with (modelId, progress, downloadedMB, totalMB).
     */
    suspend fun downloadAIModel(
        model: AIModel,
        onProgress: (modelId: String, progress: Float, downloadedMB: Float, totalMB: Float) -> Unit
    ): Result<Unit> = withContext(downloadDispatcher) {
        // Resolve dependencies (e.g. NPU model requires its GGUF base)
        val deps = AIModelCatalog.resolveDependencies(model)
        val toDownload = deps + model   // deps first, then the model itself

        for (m in toDownload) {
            if (isAIModelDownloaded(m)) {
                Log.d(TAG, "Already downloaded: ${m.name}")
                continue
            }
            val url = m.downloadUrl.ifBlank {
                return@withContext Result.failure(
                    IllegalArgumentException("No download URL for ${m.id}")
                )
            }
            val ext  = if (m.type == ModelType.NPU) ".nexa" else ".gguf"
            val dest = File(modelsDir, "${m.id}$ext")
            val tmp  = File(modelsDir, "${m.id}$ext.tmp")

            try {
                Log.d(TAG, "Downloading dependency ${m.name} from $url")
                var conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 30_000
                    readTimeout    = 60_000
                    instanceFollowRedirects = true
                }
                var redirects = 0
                while (conn.responseCode in 301..302 && redirects < 5) {
                    val loc = conn.getHeaderField("Location")
                    conn.disconnect()
                    conn = (java.net.URL(loc).openConnection() as java.net.HttpURLConnection).apply {
                        requestMethod = "GET"; connectTimeout = 30_000
                        readTimeout = 60_000; instanceFollowRedirects = true
                    }
                    redirects++
                }
                if (conn.responseCode != java.net.HttpURLConnection.HTTP_OK) {
                    conn.disconnect()
                    return@withContext Result.failure(
                        RuntimeException("HTTP ${conn.responseCode} for $url")
                    )
                }
                val contentLength = conn.contentLengthLong
                val totalMB = if (contentLength > 0) contentLength / (1024f * 1024f) else m.fileSizeMB.toFloat()

                conn.inputStream.use { input ->
                    java.io.FileOutputStream(tmp).use { out ->
                        val buffer = ByteArray(8 * 1024)
                        var downloaded = 0L
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            out.write(buffer, 0, bytesRead)
                            downloaded += bytesRead
                            val dlMB = downloaded / (1024f * 1024f)
                            val pct  = if (contentLength > 0) downloaded.toFloat() / contentLength else 0f
                            onProgress(m.id, pct.coerceIn(0f, 1f), dlMB, totalMB)
                        }
                    }
                }
                conn.disconnect()
                tmp.renameTo(dest)
                Log.d(TAG, "Downloaded: ${m.name}")
            } catch (e: Exception) {
                tmp.delete()
                Log.e(TAG, "Download failed for ${m.name}", e)
                return@withContext Result.failure(e)
            }
        }
        Result.success(Unit)
    }
}

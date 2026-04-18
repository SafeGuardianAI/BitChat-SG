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

    private val modelsDir: File by lazy {
        File(context.filesDir, MODELS_DIR).also { it.mkdirs() }
    }

    fun isModelDownloaded(model: ModelInfo): Boolean {
        val modelFile = File(modelsDir, "${model.id}.gguf")
        // A placeholder written by a previous stub has length <= 256 bytes — treat as not downloaded
        return modelFile.exists() && modelFile.length() > 256
    }

    fun getDownloadedModels(): List<ModelInfo> {
        return ModelCatalog.LLM_MODELS.filter { isModelDownloaded(it) }
    }

    suspend fun downloadModel(
        model: ModelInfo,
        onProgress: (progress: Float, downloadedMB: Float, totalMB: Float) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
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

    fun getModelPath(model: ModelInfo): String? {
        val modelFile = File(modelsDir, "${model.id}.gguf")
        return if (isModelDownloaded(model)) modelFile.absolutePath else null
    }

    fun deleteModel(model: ModelInfo): Boolean {
        val modelFile = File(modelsDir, "${model.id}.gguf")
        return modelFile.delete()
    }

    // ─── AIModel (richer type) helpers ────────────────────────────────────────

    fun isAIModelDownloaded(model: AIModel): Boolean {
        val ext = if (model.type == ModelType.NPU) ".nexa" else ".gguf"
        val f   = File(modelsDir, "${model.id}$ext")
        return f.exists() && f.length() > 256
    }

    fun getAIModelPath(model: AIModel): String? {
        val ext  = if (model.type == ModelType.NPU) ".nexa" else ".gguf"
        val file = File(modelsDir, "${model.id}$ext")
        return if (file.exists() && file.length() > 256) file.absolutePath else null
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
    ): Result<Unit> = withContext(Dispatchers.IO) {
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

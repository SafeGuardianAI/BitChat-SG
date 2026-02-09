package com.bitchat.android.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Model Manager - handles model downloads and storage
 */
class ModelManager(private val context: Context) {
    
    companion object {
        private const val TAG = "ModelManager"
        private const val MODELS_DIR = "models"
    }
    
    private val modelsDir: File by lazy {
        File(context.filesDir, MODELS_DIR).also { it.mkdirs() }
    }
    
    fun isModelDownloaded(model: ModelInfo): Boolean {
        val modelFile = File(modelsDir, "${model.id}.gguf")
        return modelFile.exists() && modelFile.length() > 0
    }
    
    fun getDownloadedModels(): List<ModelInfo> {
        return ModelCatalog.LLM_MODELS.filter { isModelDownloaded(it) }
    }
    
    suspend fun downloadModel(
        model: ModelInfo,
        onProgress: (progress: Float, downloadedMB: Float, totalMB: Float) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // Simulate download progress
            Log.d(TAG, "Starting download of ${model.name}")
            for (i in 1..10) {
                kotlinx.coroutines.delay(500)
                val progress = i / 10f
                onProgress(progress, progress * model.fileSizeMB, model.fileSizeMB.toFloat())
            }
            
            // Create a placeholder file
            val modelFile = File(modelsDir, "${model.id}.gguf")
            modelFile.writeText("placeholder")
            
            Log.d(TAG, "Download completed: ${model.name}")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            Result.failure(e)
        }
    }
    
    fun getModelPath(model: ModelInfo): String? {
        val modelFile = File(modelsDir, "${model.id}.gguf")
        return if (modelFile.exists()) modelFile.absolutePath else null
    }
    
    fun deleteModel(model: ModelInfo): Boolean {
        val modelFile = File(modelsDir, "${model.id}.gguf")
        return modelFile.delete()
    }
}

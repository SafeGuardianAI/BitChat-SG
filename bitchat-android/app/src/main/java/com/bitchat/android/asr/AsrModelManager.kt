package com.bitchat.android.asr

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Resolves ASR model path in assets/files.
 * Checks model presence and provides user-readable status.
 */
class AsrModelManager(private val context: Context) {

    companion object {
        private const val TAG = "AsrModelManager"
        const val MODEL_FOLDER = "parakeet-tdt-0.6b-v3-npu-mobile"
        private const val ASSETS_PATH = "nexa_models/$MODEL_FOLDER"
    }

    /**
     * Get model directory. Prefers filesDir (extracted/copied), then assets.
     */
    fun getModelDir(): File? {
        val filesDir = File(context.filesDir, "nexa_models/$MODEL_FOLDER")
        if (filesDir.exists()) return filesDir
        if (hasModelInAssets()) return File(context.filesDir, "nexa_models/$MODEL_FOLDER")
        return null
    }

    /**
     * Check if the ASR model is present.
     */
    fun isModelPresent(): Boolean =
        getModelDir()?.exists() == true || hasModelInAssets()

    private fun hasModelInAssets(): Boolean = try {
        context.assets.list("nexa_models")?.contains(MODEL_FOLDER) == true
    } catch (_: Exception) {
        false
    }

    /**
     * User-readable status string.
     */
    fun getStatus(): String = when {
        getModelDir()?.exists() == true -> "ASR model ready (${MODEL_FOLDER})"
        hasModelInAssets() -> "ASR model in assets (${MODEL_FOLDER})"
        else -> "ASR model not found. Place model under assets/$ASSETS_PATH"
    }

    /**
     * Get absolute path for model loading.
     * Copies from assets to filesDir if needed.
     */
    fun resolveModelPath(): String? {
        val filesDir = File(context.filesDir, "nexa_models/$MODEL_FOLDER")
        if (filesDir.exists()) return filesDir.absolutePath

        if (hasModelInAssets()) {
            Log.i(TAG, "Model in assets; copy to filesDir for loading")
            filesDir.parentFile?.mkdirs()
            try {
                val entries = context.assets.list(ASSETS_PATH) ?: emptyArray()
                for (entry in entries) {
                    val dst = File(filesDir, entry)
                    dst.parentFile?.mkdirs()
                    context.assets.open("$ASSETS_PATH/$entry").use { input ->
                        dst.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
                return filesDir.absolutePath
            } catch (e: Exception) {
                Log.e(TAG, "Failed to copy model from assets", e)
            }
        }
        return null
    }
}

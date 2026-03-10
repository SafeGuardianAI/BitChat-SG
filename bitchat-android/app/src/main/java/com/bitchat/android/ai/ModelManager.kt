package com.bitchat.android.ai

import android.content.Context
import android.util.Log
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileOutputStream
import java.io.StringReader
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Manages AI model downloads, storage, and validation
 *
 * Battery-efficient features:
 * - Pause/resume downloads
 * - Automatic cleanup of incomplete downloads
 * - Storage space validation
 * - WiFi-only option
 *
 * Multi-file NPU support:
 * - S3 directory listing with HuggingFace fallback
 * - Sequential multi-file download with aggregate progress
 * - Subdirectory storage for NPU model bundles
 */
class ModelManager(private val context: Context) {

    companion object {
        private const val TAG = "ModelManager"
        private const val MODELS_DIR = "ai_models"
        private const val DOWNLOAD_TIMEOUT_SECONDS = 300L  // 5 minutes per chunk
        private const val BUFFER_SIZE = 8192  // 8KB chunks (battery efficient)
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(DOWNLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    // Download state for each model
    private val _downloadStates = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val downloadStates: StateFlow<Map<String, DownloadState>> = _downloadStates.asStateFlow()

    // Model directory
    private val modelsDir: File by lazy {
        File(context.filesDir, MODELS_DIR).apply {
            if (!exists()) mkdirs()
        }
    }

    // ============================================
    // Multi-file / NPU helpers
    // ============================================

    data class RemoteFile(val url: String, val fileName: String, val size: Long)

    private fun isMultiFileModel(model: AIModel): Boolean {
        return model.type == ModelType.NPU && model.baseUrl != null
    }

    fun getModelDir(model: AIModel): File {
        return File(modelsDir, model.id)
    }

    private fun findMainNexaFile(dir: File): File? {
        return dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".nexa") }
            ?.sortedBy { it.name }
            ?.firstOrNull()
    }

    // ============================================
    // S3 / HuggingFace file discovery
    // ============================================

    private fun listS3Files(baseUrl: String): List<RemoteFile> {
        val parsedUrl = URL(baseUrl)
        val host = parsedUrl.host
        val prefix = parsedUrl.path.trimStart('/')
        val listUrl = "https://$host/?list-type=2&prefix=$prefix"

        Log.d(TAG, "Listing S3 files: $listUrl")

        val request = Request.Builder().url(listUrl).build()
        val response = httpClient.newCall(request).execute()

        if (!response.isSuccessful) {
            response.close()
            throw Exception("S3 listing failed: HTTP ${response.code}")
        }

        val xml = response.body?.string() ?: throw Exception("Empty S3 listing response")
        response.close()

        return parseS3ListingXml(xml, "https://$host/")
    }

    private fun parseS3ListingXml(xml: String, baseDownloadUrl: String): List<RemoteFile> {
        val files = mutableListOf<RemoteFile>()
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(xml))

        var currentKey: String? = null
        var currentSize: Long = 0
        var inContents = false
        var tagName = ""

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    tagName = parser.name
                    if (tagName == "Contents") {
                        inContents = true
                        currentKey = null
                        currentSize = 0
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inContents) {
                        when (tagName) {
                            "Key" -> currentKey = parser.text
                            "Size" -> currentSize = parser.text.toLongOrNull() ?: 0
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "Contents" && inContents) {
                        inContents = false
                        if (currentKey != null && currentSize > 0 && !currentKey.endsWith("/")) {
                            val fileName = currentKey.substringAfterLast('/')
                            files.add(RemoteFile(
                                url = "$baseDownloadUrl$currentKey",
                                fileName = fileName,
                                size = currentSize
                            ))
                        }
                    }
                    tagName = ""
                }
            }
            eventType = parser.next()
        }

        return files
    }

    private fun listHuggingFaceFiles(model: AIModel): List<RemoteFile> {
        val baseUrl = model.baseUrl ?: throw Exception("No baseUrl for HuggingFace fallback")
        val repoName = baseUrl.trimEnd('/').substringAfterLast('/')
        val apiUrl = "https://huggingface.co/api/models/NexaAI/$repoName/tree/main"

        Log.d(TAG, "Listing HuggingFace files: $apiUrl")

        val request = Request.Builder().url(apiUrl).build()
        val response = httpClient.newCall(request).execute()

        if (!response.isSuccessful) {
            response.close()
            throw Exception("HuggingFace listing failed: HTTP ${response.code}")
        }

        val json = response.body?.string() ?: throw Exception("Empty HuggingFace response")
        response.close()

        val files = mutableListOf<RemoteFile>()
        val jsonArray = JSONArray(json)

        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            if (obj.getString("type") == "file") {
                val path = obj.getString("path")
                val size = obj.optLong("size", 0)
                val downloadUrl = "https://huggingface.co/NexaAI/$repoName/resolve/main/$path"
                files.add(RemoteFile(
                    url = downloadUrl,
                    fileName = path.substringAfterLast('/'),
                    size = size
                ))
            }
        }

        return files
    }

    private fun discoverRemoteFiles(model: AIModel): List<RemoteFile> {
        // Try S3 first
        val baseUrl = model.baseUrl
        if (baseUrl != null) {
            try {
                val files = listS3Files(baseUrl)
                if (files.isNotEmpty()) {
                    Log.d(TAG, "Discovered ${files.size} files via S3 for ${model.id}")
                    return files
                }
            } catch (e: Exception) {
                Log.w(TAG, "S3 listing failed for ${model.id}: ${e.message}")
            }
        }

        // Fall back to HuggingFace
        try {
            val files = listHuggingFaceFiles(model)
            if (files.isNotEmpty()) {
                Log.d(TAG, "Discovered ${files.size} files via HuggingFace for ${model.id}")
                return files
            }
        } catch (e: Exception) {
            Log.w(TAG, "HuggingFace listing failed for ${model.id}: ${e.message}")
        }

        throw Exception("Failed to discover remote files for ${model.id}")
    }

    // ============================================
    // Storage / status methods
    // ============================================

    /**
     * Check if model is downloaded
     */
    fun isModelDownloaded(model: AIModel): Boolean {
        if (isMultiFileModel(model)) {
            val modelDir = getModelDir(model)
            return modelDir.exists() && modelDir.isDirectory &&
                findMainNexaFile(modelDir) != null
        }

        val modelFile = File(modelsDir, model.modelFileName)
        if (modelFile.exists() && modelFile.length() > 0) {
            return true
        }

        // Check if model exists in assets and copy it
        return copyModelFromAssets(model)
    }

    /**
     * Get downloaded model file
     */
    fun getModelFile(model: AIModel): File? {
        if (isMultiFileModel(model)) {
            val modelDir = getModelDir(model)
            if (modelDir.exists()) {
                return findMainNexaFile(modelDir)
            }
            // Try to copy from assets
            if (copyModelFromAssets(model)) {
                return findMainNexaFile(getModelDir(model))
            }
            return null
        }

        val file = File(modelsDir, model.modelFileName)
        if (file.exists()) {
            return file
        }

        // Try to copy from assets if not found
        if (copyModelFromAssets(model)) {
            return File(modelsDir, model.modelFileName)
        }

        return null
    }

    /**
     * Get model file path
     */
    fun getModelPath(model: AIModel): String? {
        return getModelFile(model)?.absolutePath
    }

    /**
     * Check available storage space
     */
    fun hasEnoughSpace(requiredMB: Int): Boolean {
        val availableBytes = modelsDir.usableSpace
        val availableMB = availableBytes / (1024 * 1024)
        return availableMB >= requiredMB
    }

    /**
     * Get available storage in MB
     */
    fun getAvailableStorageMB(): Long {
        return modelsDir.usableSpace / (1024 * 1024)
    }

    /**
     * Get total storage used by models in MB
     */
    fun getTotalStorageUsedMB(): Long {
        return modelsDir.walkTopDown()
            .filter { it.isFile }
            .map { it.length() }
            .sum() / (1024 * 1024)
    }

    // ============================================
    // Download flow
    // ============================================

    /**
     * Download a model with S3→HuggingFace fallback.
     * Dispatches to single-file or multi-file download based on model type.
     */
    suspend fun downloadModel(
        model: AIModel,
        onProgress: ((Int, Long, Long) -> Unit)? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        if (isMultiFileModel(model)) {
            downloadMultiFileModel(model, onProgress)
        } else {
            downloadSingleFileModel(model, onProgress)
        }
    }

    /**
     * Single-file download (GGUF models) — existing behavior preserved
     */
    private suspend fun downloadSingleFileModel(
        model: AIModel,
        onProgress: ((Int, Long, Long) -> Unit)?
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting single-file download for ${model.name}")

            // Check storage
            if (!hasEnoughSpace(model.fileSizeMB + 100)) {  // +100MB buffer
                return@withContext Result.failure(
                    Exception("Insufficient storage. Need ${model.fileSizeMB}MB, have ${getAvailableStorageMB()}MB")
                )
            }

            // Update state to downloading
            updateDownloadState(model.id, DownloadState.Downloading(0, 0, model.fileSizeMB))

            // Prepare file
            val outputFile = File(modelsDir, model.modelFileName)
            val tempFile = File(modelsDir, "${model.modelFileName}.tmp")

            // Delete existing temp file
            if (tempFile.exists()) tempFile.delete()

            // Try primary URL first, then fallback
            val urls = mutableListOf(model.downloadUrl)
            if (!model.fallbackUrl.isNullOrBlank()) {
                urls.add(model.fallbackUrl)
            }

            var lastException: Exception? = null

            for ((index, url) in urls.withIndex()) {
                try {
                    val label = if (index == 0) "primary (S3)" else "fallback (HuggingFace)"
                    Log.d(TAG, "Trying $label URL for ${model.name}: $url")

                    downloadFromUrl(url, tempFile, model, onProgress)

                    // Rename temp file to final file
                    if (outputFile.exists()) outputFile.delete()
                    if (!tempFile.renameTo(outputFile)) {
                        throw Exception("Failed to rename temp file")
                    }

                    Log.d(TAG, "Download completed via $label: ${model.name} (${outputFile.length()} bytes)")
                    updateDownloadState(model.id, DownloadState.Completed)
                    return@withContext Result.success(outputFile)

                } catch (e: Exception) {
                    Log.w(TAG, "Download failed via ${if (index == 0) "primary" else "fallback"}: ${e.message}")
                    lastException = e
                    // Clean up temp file before retry
                    if (tempFile.exists()) tempFile.delete()
                }
            }

            throw lastException ?: Exception("All download URLs failed")

        } catch (e: Exception) {
            Log.e(TAG, "Download failed for ${model.name}", e)
            updateDownloadState(model.id, DownloadState.Failed(e.message ?: "Unknown error"))
            Result.failure(e)
        }
    }

    /**
     * Multi-file download (NPU models) — discovers files via S3/HuggingFace,
     * downloads sequentially into a temp directory, then renames to final location.
     */
    private suspend fun downloadMultiFileModel(
        model: AIModel,
        onProgress: ((Int, Long, Long) -> Unit)?
    ): Result<File> = withContext(Dispatchers.IO) {
        val modelDir = getModelDir(model)
        val tempDir = File(modelsDir, "${model.id}.tmp")

        try {
            Log.d(TAG, "Starting multi-file download for ${model.name}")

            if (!hasEnoughSpace(model.fileSizeMB + 100)) {
                return@withContext Result.failure(
                    Exception("Insufficient storage. Need ${model.fileSizeMB}MB, have ${getAvailableStorageMB()}MB")
                )
            }

            updateDownloadState(model.id, DownloadState.Downloading(0, 0, model.fileSizeMB))

            // Discover remote files
            val remoteFiles = discoverRemoteFiles(model)
            if (remoteFiles.isEmpty()) {
                throw Exception("No files found for ${model.id}")
            }

            Log.d(TAG, "Found ${remoteFiles.size} files to download for ${model.id}")

            // Prepare temp directory
            if (tempDir.exists()) tempDir.deleteRecursively()
            tempDir.mkdirs()

            // Calculate total size for aggregate progress
            val totalBytes = remoteFiles.sumOf { it.size }
            var bytesDownloaded = 0L

            // Download each file sequentially
            for ((fileIndex, remoteFile) in remoteFiles.withIndex()) {
                val outputFile = File(tempDir, remoteFile.fileName)
                Log.d(TAG, "Downloading file ${fileIndex + 1}/${remoteFiles.size}: ${remoteFile.fileName}")

                downloadFromUrl(
                    url = remoteFile.url,
                    tempFile = outputFile,
                    model = model,
                    onProgress = onProgress,
                    bytesAlreadyDownloaded = bytesDownloaded,
                    totalBytesAllFiles = totalBytes
                )

                bytesDownloaded += outputFile.length()
            }

            // Rename temp dir to final dir
            if (modelDir.exists()) modelDir.deleteRecursively()
            if (!tempDir.renameTo(modelDir)) {
                throw Exception("Failed to rename temp directory to ${modelDir.name}")
            }

            val mainFile = findMainNexaFile(modelDir)
                ?: throw Exception("No .nexa file found after download")

            Log.d(TAG, "Multi-file download completed: ${model.name} (${remoteFiles.size} files)")
            updateDownloadState(model.id, DownloadState.Completed)
            Result.success(mainFile)

        } catch (e: Exception) {
            Log.e(TAG, "Multi-file download failed for ${model.name}", e)
            if (tempDir.exists()) tempDir.deleteRecursively()
            updateDownloadState(model.id, DownloadState.Failed(e.message ?: "Unknown error"))
            Result.failure(e)
        }
    }

    /**
     * Download from a single URL with progress tracking.
     * Supports aggregate progress for multi-file downloads via bytesAlreadyDownloaded/totalBytesAllFiles.
     */
    private fun downloadFromUrl(
        url: String,
        tempFile: File,
        model: AIModel,
        onProgress: ((Int, Long, Long) -> Unit)?,
        bytesAlreadyDownloaded: Long = 0,
        totalBytesAllFiles: Long = 0
    ) {
        val request = Request.Builder()
            .url(url)
            .build()

        val response = httpClient.newCall(request).execute()

        if (!response.isSuccessful) {
            response.close()
            throw Exception("HTTP ${response.code}: ${response.message}")
        }

        val body = response.body ?: throw Exception("Response body is null")
        val contentLength = body.contentLength()

        // For aggregate multi-file progress, use totalBytesAllFiles if provided
        val useAggregate = totalBytesAllFiles > 0
        val effectiveTotalBytes = if (useAggregate) totalBytesAllFiles else contentLength

        if (!useAggregate && contentLength <= 0) {
            throw Exception("Invalid content length")
        }

        body.byteStream().use { inputStream ->
            FileOutputStream(tempFile).use { outputStream ->
                val buffer = ByteArray(BUFFER_SIZE)
                var bytesRead: Int
                var fileBytesRead = 0L

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    fileBytesRead += bytesRead

                    if (effectiveTotalBytes > 0) {
                        val totalRead = if (useAggregate) {
                            bytesAlreadyDownloaded + fileBytesRead
                        } else {
                            fileBytesRead
                        }

                        val progress = ((totalRead * 100) / effectiveTotalBytes).toInt()
                        val downloadedMB = totalRead / (1024 * 1024)
                        val totalMB = effectiveTotalBytes / (1024 * 1024)

                        updateDownloadState(
                            model.id,
                            DownloadState.Downloading(progress, downloadedMB.toInt(), totalMB.toInt())
                        )

                        onProgress?.invoke(progress, downloadedMB, totalMB)
                    }
                }
            }
        }

        // Validate download: only enforce size match for single-file downloads with known length
        if (!useAggregate && contentLength > 0 && tempFile.length() != contentLength) {
            throw Exception("Download incomplete: ${tempFile.length()} != $contentLength")
        }
    }

    // ============================================
    // Cancel / Delete / Cleanup
    // ============================================

    /**
     * Cancel download (if in progress)
     */
    fun cancelDownload(model: AIModel) {
        if (isMultiFileModel(model)) {
            val tempDir = File(modelsDir, "${model.id}.tmp")
            if (tempDir.exists()) {
                tempDir.deleteRecursively()
                Log.d(TAG, "Cancelled multi-file download: ${model.name}")
            }
        } else {
            val tempFile = File(modelsDir, "${model.modelFileName}.tmp")
            if (tempFile.exists()) {
                tempFile.delete()
                Log.d(TAG, "Cancelled download: ${model.name}")
            }
        }

        updateDownloadState(model.id, DownloadState.NotStarted)
    }

    /**
     * Delete a downloaded model
     */
    fun deleteModel(model: AIModel): Boolean {
        val deleted = if (isMultiFileModel(model)) {
            val modelDir = getModelDir(model)
            if (modelDir.exists()) modelDir.deleteRecursively() else false
        } else {
            val file = File(modelsDir, model.modelFileName)
            file.delete()
        }

        if (deleted) {
            Log.d(TAG, "Deleted model: ${model.name}")
            updateDownloadState(model.id, DownloadState.NotStarted)
        }

        return deleted
    }

    /**
     * Delete all models
     */
    fun deleteAllModels() {
        modelsDir.listFiles()?.forEach { entry ->
            if (entry.isDirectory) {
                entry.deleteRecursively()
            } else {
                entry.delete()
            }
        }
        _downloadStates.value = emptyMap()
        Log.d(TAG, "Deleted all models")
    }

    /**
     * Get list of downloaded models
     */
    fun getDownloadedModels(): List<AIModel> {
        return ModelCatalog.getAllModels().filter { isModelDownloaded(it) }
    }

    /**
     * Validate model integrity
     */
    suspend fun validateModel(model: AIModel): Boolean = withContext(Dispatchers.IO) {
        try {
            if (isMultiFileModel(model)) {
                val modelDir = getModelDir(model)
                if (!modelDir.exists() || !modelDir.isDirectory) return@withContext false
                val nexaFiles = modelDir.listFiles()
                    ?.filter { it.isFile && it.name.endsWith(".nexa") }
                    ?: return@withContext false
                return@withContext nexaFiles.isNotEmpty() && nexaFiles.all { it.length() > 0 }
            }

            val file = getModelFile(model) ?: return@withContext false

            // Basic validation: check file exists and has size
            if (!file.exists() || file.length() == 0L) {
                return@withContext false
            }

            true
        } catch (e: Exception) {
            Log.e(TAG, "Validation failed for ${model.name}", e)
            false
        }
    }

    /**
     * Calculate SHA256 checksum (for validation)
     */
    private suspend fun calculateSHA256(file: File): String = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            var bytesRead: Int

            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }

        digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Get download progress for a model
     */
    fun getDownloadProgress(modelId: String): DownloadState {
        return _downloadStates.value[modelId] ?: DownloadState.NotStarted
    }

    /**
     * Update download state
     */
    private fun updateDownloadState(modelId: String, state: DownloadState) {
        _downloadStates.value = _downloadStates.value.toMutableMap().apply {
            this[modelId] = state
        }
    }

    /**
     * Clean up incomplete downloads (call on app start)
     */
    fun cleanupIncompleteDownloads() {
        modelsDir.listFiles()?.forEach { entry ->
            if (entry.isFile && entry.name.endsWith(".tmp")) {
                entry.delete()
                Log.d(TAG, "Cleaned up incomplete download: ${entry.name}")
            } else if (entry.isDirectory && entry.name.endsWith(".tmp")) {
                entry.deleteRecursively()
                Log.d(TAG, "Cleaned up incomplete multi-file download: ${entry.name}")
            }
        }
    }

    // ============================================
    // Storage info
    // ============================================

    /**
     * Get storage info
     */
    data class StorageInfo(
        val totalSpaceMB: Long,
        val availableSpaceMB: Long,
        val usedByModelsMB: Long,
        val usedPercentage: Int
    )

    fun getStorageInfo(): StorageInfo {
        val totalSpace = modelsDir.totalSpace / (1024 * 1024)
        val availableSpace = getAvailableStorageMB()
        val usedByModels = getTotalStorageUsedMB()
        val usedPercentage = ((usedByModels * 100) / totalSpace).toInt()

        return StorageInfo(
            totalSpaceMB = totalSpace,
            availableSpaceMB = availableSpace,
            usedByModelsMB = usedByModels,
            usedPercentage = usedPercentage
        )
    }

    // ============================================
    // Copy from assets
    // ============================================

    /**
     * Copy model from assets to runtime directory
     * Handles filename variations (e.g., Q4_K_M vs Q4_0)
     */
    private fun copyModelFromAssets(model: AIModel): Boolean {
        try {
            if (isMultiFileModel(model)) {
                return copyMultiFileModelFromAssets(model)
            }

            // Try exact filename first
            var assetFileName = model.modelFileName
            var assetPath = "models/$assetFileName"

            // Check if exact file exists in assets
            try {
                context.assets.open(assetPath).use { }
            } catch (e: Exception) {
                // Try alternative filename patterns
                val alternativeNames = getAlternativeAssetNames(model)
                var found = false

                for (altName in alternativeNames) {
                    try {
                        context.assets.open("models/$altName").use { }
                        assetFileName = altName
                        assetPath = "models/$altName"
                        found = true
                        break
                    } catch (e: Exception) {
                        // Continue to next alternative
                    }
                }

                if (!found) {
                    Log.d(TAG, "Model not found in assets: ${model.name}")
                    return false
                }
            }

            // Copy from assets to runtime directory
            val outputFile = File(modelsDir, model.modelFileName)
            if (outputFile.exists()) {
                Log.d(TAG, "Model already exists: ${model.name}")
                return true
            }

            Log.d(TAG, "Copying model from assets: $assetPath -> ${outputFile.absolutePath}")

            context.assets.open(assetPath).use { inputStream ->
                outputFile.outputStream().use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }

            Log.i(TAG, "Model copied successfully: ${model.name}")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy model from assets: ${model.name}", e)
            return false
        }
    }

    /**
     * Copy multi-file NPU model from assets directory
     */
    private fun copyMultiFileModelFromAssets(model: AIModel): Boolean {
        try {
            val assetDirPath = "models/${model.id}"
            val assetFiles = try {
                context.assets.list(assetDirPath) ?: emptyArray()
            } catch (e: Exception) {
                emptyArray()
            }

            if (assetFiles.isEmpty()) {
                Log.d(TAG, "NPU model not found in assets: ${model.name}")
                return false
            }

            val modelDir = getModelDir(model)
            if (modelDir.exists() && findMainNexaFile(modelDir) != null) {
                Log.d(TAG, "NPU model already exists: ${model.name}")
                return true
            }

            modelDir.mkdirs()

            Log.d(TAG, "Copying NPU model from assets: $assetDirPath -> ${modelDir.absolutePath}")

            for (fileName in assetFiles) {
                context.assets.open("$assetDirPath/$fileName").use { inputStream ->
                    File(modelDir, fileName).outputStream().use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
            }

            Log.i(TAG, "NPU model copied successfully: ${model.name} (${assetFiles.size} files)")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy NPU model from assets: ${model.name}", e)
            return false
        }
    }

    /**
     * Get alternative asset filenames for a model
     * Handles different quantization naming conventions and legacy filenames
     */
    private fun getAlternativeAssetNames(model: AIModel): List<String> {
        val alternatives = mutableListOf<String>()

        when (model.id) {
            "granite-4.0-micro-Q4_0" -> {
                alternatives.addAll(listOf(
                    "granite-4.0-micro-Q4_K_M.gguf",
                    "granite-4.0-micro-Q4_K_S.gguf",
                    "granite-4.0-micro-Q4_1.gguf",
                    "granite-4.0-micro-q4.gguf"  // Legacy name
                ))
            }
            "granite-4.0-micro-q8" -> {
                alternatives.addAll(listOf(
                    "granite-4.0-micro-Q8_0.gguf",
                    "granite-4.0-micro-Q8_K_M.gguf"
                ))
            }
            "Granite-4.0-h-350M-NPU-mobile" -> {
                alternatives.addAll(listOf(
                    "files-1-2.nexa",  // Original NPU filename
                    "Granite-4.0-h-350M-NPU-mobile/files-1-2.nexa"
                ))
            }
            "embeddinggemma-300m" -> {
                alternatives.addAll(listOf(
                    "embeddinggemma-300m-medical-q4_k_m.gguf",
                    "embeddinggemma-300m-Q4_K_M.gguf",
                    "embeddinggemma-300m-Q8_0.gguf"
                ))
            }
        }

        // For NPU models, also try the generic files-1-2.nexa
        if (model.type == ModelType.NPU && !alternatives.contains("files-1-2.nexa")) {
            alternatives.add("files-1-2.nexa")
            alternatives.add("${model.id}/files-1-2.nexa")
        }

        return alternatives
    }
}

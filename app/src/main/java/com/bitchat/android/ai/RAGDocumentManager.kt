package com.bitchat.android.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.URL

/**
 * Helper class for managing RAG documents and model loading
 */
class RAGDocumentManager(private val context: Context) {

    companion object {
        private const val TAG = "RAGDocumentManager"
        private const val DOCUMENTS_DIR = "documents"
        private const val MODELS_DIR = "models"
    }

    /**
     * Load PDF files from assets directory
     */
    suspend fun loadPDFsFromAssets(): List<String> = withContext(Dispatchers.IO) {
        try {
            val documents = mutableListOf<String>()
            val assetsDir = "documents"
            
            // List all files in assets/documents directory
            val assetFiles = context.assets.list(assetsDir) ?: emptyArray()
            
            for (fileName in assetFiles) {
                if (fileName.lowercase().endsWith(".pdf")) {
                    try {
                        val inputStream = context.assets.open("$assetsDir/$fileName")
                        val pdfText = extractTextFromPDF(inputStream)
                        if (pdfText.isNotEmpty()) {
                            documents.add(pdfText)
                            Log.d(TAG, "Loaded PDF from assets: $fileName (${pdfText.length} chars)")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to load PDF from assets: $fileName", e)
                    }
                }
            }
            
            Log.i(TAG, "Loaded ${documents.size} PDF documents from assets")
            return@withContext documents
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load PDFs from assets", e)
            return@withContext emptyList()
        }
    }

    /**
     * Load PDF files from app's internal storage
     */
    suspend fun loadPDFsFromStorage(): List<String> = withContext(Dispatchers.IO) {
        try {
            val documents = mutableListOf<String>()
            val documentsDir = File(context.filesDir, DOCUMENTS_DIR)
            
            if (!documentsDir.exists()) {
                Log.d(TAG, "Documents directory does not exist")
                return@withContext emptyList()
            }
            
            val pdfFiles = documentsDir.listFiles { file ->
                file.isFile && file.name.lowercase().endsWith(".pdf")
            } ?: emptyArray()
            
            for (pdfFile in pdfFiles) {
                try {
                    val pdfText = extractTextFromPDF(pdfFile.inputStream())
                    if (pdfText.isNotEmpty()) {
                        documents.add(pdfText)
                        Log.d(TAG, "Loaded PDF from storage: ${pdfFile.name} (${pdfText.length} chars)")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to load PDF from storage: ${pdfFile.name}", e)
                }
            }
            
            Log.i(TAG, "Loaded ${documents.size} PDF documents from storage")
            return@withContext documents
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load PDFs from storage", e)
            return@withContext emptyList()
        }
    }

    /**
     * Copy PDF from assets to internal storage
     */
    suspend fun copyPDFFromAssetsToStorage(assetFileName: String): Result<File> = withContext(Dispatchers.IO) {
        try {
            val documentsDir = File(context.filesDir, DOCUMENTS_DIR)
            documentsDir.mkdirs()
            
            val targetFile = File(documentsDir, assetFileName)
            
            context.assets.open("documents/$assetFileName").use { inputStream ->
                targetFile.outputStream().use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            
            Log.d(TAG, "Copied PDF from assets to storage: $assetFileName")
            Result.success(targetFile)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy PDF from assets", e)
            Result.failure(e)
        }
    }

    /**
     * Download reranker model from HuggingFace
     */
    suspend fun downloadRerankerModel(): Result<File> = withContext(Dispatchers.IO) {
        try {
            val modelsDir = File(context.filesDir, MODELS_DIR)
            modelsDir.mkdirs()
            
            val modelFile = File(modelsDir, "jina-reranker-v3-Q8_0.gguf")
            
            if (modelFile.exists() && modelFile.length() > 0) {
                Log.d(TAG, "Reranker model already exists: ${modelFile.length()} bytes")
                return@withContext Result.success(modelFile)
            }
            
            val modelUrl = "https://huggingface.co/jinaai/jina-reranker-v3-GGUF/resolve/main/jina-reranker-v3-Q8_0.gguf"
            Log.d(TAG, "Downloading reranker model from: $modelUrl")
            
            val url = URL(modelUrl)
            val connection = url.openConnection()
            connection.connectTimeout = 30000 // 30 seconds
            connection.readTimeout = 300000   // 5 minutes
            
            connection.getInputStream().use { inputStream ->
                modelFile.outputStream().use { outputStream ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalBytes = 0L
                    
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                        totalBytes += bytesRead
                        
                        // Log progress every 1MB
                        if (totalBytes % (1024 * 1024) == 0L) {
                            Log.d(TAG, "Downloaded ${totalBytes / (1024 * 1024)}MB")
                        }
                    }
                }
            }
            
            Log.i(TAG, "Downloaded reranker model: ${modelFile.length()} bytes")
            Result.success(modelFile)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download reranker model", e)
            Result.failure(e)
        }
    }

    /**
     * Check if reranker model is available
     */
    fun isRerankerModelAvailable(): Boolean {
        val modelFile = File(context.filesDir, "$MODELS_DIR/jina-reranker-v3-Q8_0.gguf")
        return modelFile.exists() && modelFile.length() > 0
    }

    /**
     * Get reranker model file path
     */
    fun getRerankerModelPath(): String {
        return File(context.filesDir, "$MODELS_DIR/jina-reranker-v3-Q8_0.gguf").absolutePath
    }

    /**
     * Extract text from PDF (placeholder implementation)
     * In a real implementation, you would use a PDF parsing library like PDFBox
     */
    private fun extractTextFromPDF(inputStream: InputStream): String {
        // Placeholder implementation - replace with actual PDF parsing
        // For now, return a sample text for demonstration
        return """
            SafeGuardian Privacy Policy
            
            SafeGuardian is committed to protecting your privacy. All AI processing 
            happens entirely on your device, ensuring that your conversations and 
            data never leave your device.
            
            Key Privacy Features:
            - On-device AI processing
            - No data transmission to external servers
            - Local model storage
            - Encrypted preferences storage
            
            This document explains how SafeGuardian protects your privacy while 
            providing powerful AI capabilities through local processing.
        """.trimIndent()
    }

    /**
     * Get list of available PDF files in storage
     */
    fun getAvailablePDFs(): List<String> {
        val documentsDir = File(context.filesDir, DOCUMENTS_DIR)
        if (!documentsDir.exists()) return emptyList()
        
        return documentsDir.listFiles { file ->
            file.isFile && file.name.lowercase().endsWith(".pdf")
        }?.map { it.name } ?: emptyList()
    }

    /**
     * Get list of available PDF files in assets
     */
    fun getAvailablePDFsInAssets(): List<String> {
        return try {
            context.assets.list("documents")?.filter { 
                it.lowercase().endsWith(".pdf") 
            } ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to list PDFs in assets", e)
            emptyList()
        }
    }

    /**
     * Initialize RAG with sample documents
     */
    suspend fun initializeRAGWithSampleDocuments(aiManager: AIManager): Result<Int> = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "═══════════════════════════════════════════════════════")
            Log.i(TAG, "📚 Initializing RAG with documents")
            Log.i(TAG, "═══════════════════════════════════════════════════════")
            
            // Load PDFs from assets first
            Log.i(TAG, "   📂 Step 1: Loading PDFs from assets...")
            val assetDocuments = loadPDFsFromAssets()
            Log.i(TAG, "   ✅ Loaded ${assetDocuments.size} documents from assets")
            
            // Load PDFs from storage
            Log.i(TAG, "   📂 Step 2: Loading PDFs from storage...")
            val storageDocuments = loadPDFsFromStorage()
            Log.i(TAG, "   ✅ Loaded ${storageDocuments.size} documents from storage")
            
            // Combine all documents
            val allDocuments = assetDocuments + storageDocuments
            Log.i(TAG, "   📊 Total documents found: ${allDocuments.size}")
            
            if (allDocuments.isEmpty()) {
                Log.w(TAG, "   ⚠️ No documents found, creating sample documents")
                // Create some sample documents for demonstration
                val sampleDocuments = listOf(
                    """
                    SafeGuardian User Guide
                    
                    Welcome to SafeGuardian, your privacy-focused AI assistant. This guide 
                    will help you get started with the app's features.
                    
                    Getting Started:
                    1. Enable AI in settings
                    2. Download recommended models
                    3. Start chatting with your AI assistant
                    
                    Privacy Features:
                    - All processing happens on your device
                    - No data is sent to external servers
                    - Your conversations remain private
                    """.trimIndent(),
                    
                    """
                    AI Model Information
                    
                    SafeGuardian uses several AI models for different purposes:
                    
                    LLM Models:
                    - Granite 4.0 Micro: Main language model for chat
                    - Qwen3 0.6B: Alternative lightweight model
                    
                    Embedding Models:
                    - EmbeddingGemma 300M: For document search and RAG
                    
                    Reranker Models:
                    - JinaAI Reranker v2: Improves search result quality
                    
                    All models are optimized for mobile devices with low battery impact.
                    """.trimIndent(),
                    
                    """
                    Troubleshooting Guide
                    
                    Common Issues and Solutions:
                    
                    1. AI Not Responding:
                       - Check if AI is enabled in settings
                       - Verify model is downloaded and loaded
                       - Restart the app if needed
                    
                    2. Slow Performance:
                       - Switch to power saver mode
                       - Reduce context length
                       - Close other apps
                    
                    3. Memory Issues:
                       - Use smaller models
                       - Enable power saver mode
                       - Clear app cache
                    
                    For more help, check the app's help section.
                    """.trimIndent()
                )
                
                // Add sample documents to RAG
                Log.i(TAG, "   📝 Step 3: Adding sample documents to RAG index...")
                val result = aiManager.getRAGService().addDocuments(sampleDocuments)
                if (result.isSuccess) {
                    Log.i(TAG, "   ✅ Added ${result.getOrNull()} sample document chunks to RAG")
                } else {
                    Log.e(TAG, "   ❌ Failed to add sample documents: ${result.exceptionOrNull()?.message}")
                }
                Log.i(TAG, "═══════════════════════════════════════════════════════")
                return@withContext result
            }
            
            // Add real documents to RAG
            Log.i(TAG, "   📝 Step 3: Adding documents to RAG index...")
            val result = aiManager.getRAGService().addDocuments(allDocuments)
            if (result.isSuccess) {
                Log.i(TAG, "   ✅ Added ${result.getOrNull()} document chunks to RAG")
            } else {
                Log.e(TAG, "   ❌ Failed to add documents: ${result.exceptionOrNull()?.message}")
            }
            Log.i(TAG, "═══════════════════════════════════════════════════════")
            return@withContext result
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to initialize RAG with documents", e)
            Log.i(TAG, "═══════════════════════════════════════════════════════")
            Result.failure(e)
        }
    }
}

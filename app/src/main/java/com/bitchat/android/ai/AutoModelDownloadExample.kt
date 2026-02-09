package com.bitchat.android.ai

import android.content.Context
import kotlinx.coroutines.runBlocking

/**
 * Example demonstrating automatic model downloading
 * 
 * This shows how the system automatically downloads the JinaAI reranker model
 * if it doesn't exist locally
 */
class AutoModelDownloadExample(private val context: Context) {

    /**
     * Example: Automatic model downloading during initialization
     */
    fun demonstrateAutoDownload() = runBlocking {
        try {
            println("🚀 Starting automatic model download demonstration")
            println("=" * 50)
            
            // 1. Initialize AI Manager (this will trigger model download if needed)
            println("📱 Initializing AI Manager...")
            val aiManager = AIManager(context)
            
            // Check if reranker model exists before initialization
            val documentManager = aiManager.getDocumentManager()
            val modelExistsBefore = documentManager.isRerankerModelAvailable()
            println("📥 Reranker model exists before init: $modelExistsBefore")
            
            // Initialize - this will automatically download the model if missing
            val initResult = aiManager.initialize()
            
            if (initResult.isSuccess) {
                println("✅ AI Manager initialized successfully")
                
                // Check if model exists after initialization
                val modelExistsAfter = documentManager.isRerankerModelAvailable()
                println("📥 Reranker model exists after init: $modelExistsAfter")
                
                if (!modelExistsBefore && modelExistsAfter) {
                    println("🎉 Model was automatically downloaded during initialization!")
                } else if (modelExistsBefore) {
                    println("ℹ️ Model was already available")
                }
                
                // 2. Test the system
                testRAGWithAutoDownload(aiManager)
                
            } else {
                println("❌ Failed to initialize AI: ${initResult.exceptionOrNull()?.message}")
            }
            
        } catch (e: Exception) {
            println("❌ Error in demonstration: ${e.message}")
        }
    }

    /**
     * Example: Manual model download with progress tracking
     */
    suspend fun demonstrateManualDownload() {
        try {
            println("\n📥 Manual model download demonstration")
            println("-" * 30)
            
            val aiManager = AIManager(context)
            val documentManager = aiManager.getDocumentManager()
            
            // Check current status
            val modelExists = documentManager.isRerankerModelAvailable()
            println("📊 Model exists: $modelExists")
            
            if (!modelExists) {
                println("⬇️ Starting manual download...")
                
                // Download with progress tracking
                val downloadResult = documentManager.downloadRerankerModel()
                
                if (downloadResult.isSuccess) {
                    val modelFile = downloadResult.getOrNull()
                    println("✅ Download completed!")
                    println("📁 Model file: ${modelFile?.absolutePath}")
                    println("📏 File size: ${modelFile?.length()} bytes")
                } else {
                    println("❌ Download failed: ${downloadResult.exceptionOrNull()?.message}")
                }
            } else {
                println("ℹ️ Model already exists, skipping download")
            }
            
        } catch (e: Exception) {
            println("❌ Error in manual download: ${e.message}")
        }
    }

    /**
     * Example: Test RAG system with automatic model management
     */
    private suspend fun testRAGWithAutoDownload(aiManager: AIManager) {
        try {
            println("\n🔍 Testing RAG system with auto-downloaded model")
            println("-" * 40)
            
            // Initialize RAG with documents
            val ragResult = aiManager.initializeRAGWithDocuments()
            
            if (ragResult.isSuccess) {
                val chunkCount = ragResult.getOrNull() ?: 0
                println("✅ RAG initialized with $chunkCount document chunks")
                
                // Test search with reranking
                val aiChat = AIChatService(context, aiManager)
                
                // Enable reranking
                aiManager.preferences.rerankEnabled = true
                aiManager.preferences.rerankTopN = 3
                
                val query = "How does SafeGuardian protect privacy?"
                println("🔍 Searching for: $query")
                
                val searchResults = aiChat.searchRAG(query, topK = 5)
                println("📋 Found ${searchResults.size} relevant documents")
                
                searchResults.forEachIndexed { index, chunk ->
                    println("${index + 1}. [${chunk.source}] ${chunk.content.take(80)}...")
                }
                
                // Test chat with RAG context
                println("\n💬 Testing chat with RAG context...")
                val response = aiChat.processMessage(query, useRAG = true)
                println("🤖 AI Response: ${response.take(200)}...")
                
            } else {
                println("❌ Failed to initialize RAG: ${ragResult.exceptionOrNull()?.message}")
            }
            
        } catch (e: Exception) {
            println("❌ Error testing RAG: ${e.message}")
        }
    }

    /**
     * Example: Check model status and download if needed
     */
    fun checkAndDownloadIfNeeded() = runBlocking {
        try {
            println("\n🔍 Checking model status and downloading if needed")
            println("-" * 40)
            
            val aiManager = AIManager(context)
            val documentManager = aiManager.getDocumentManager()
            
            // Check current status
            val modelExists = documentManager.isRerankerModelAvailable()
            val modelPath = documentManager.getRerankerModelPath()
            
            println("📊 Model Status:")
            println("  - Exists: $modelExists")
            println("  - Path: $modelPath")
            
            if (!modelExists) {
                println("⬇️ Model not found, downloading...")
                
                val downloadResult = documentManager.downloadRerankerModel()
                
                if (downloadResult.isSuccess) {
                    println("✅ Model downloaded successfully")
                    
                    // Verify download
                    val modelFile = downloadResult.getOrNull()
                    if (modelFile != null && modelFile.exists()) {
                        println("📏 Downloaded size: ${modelFile.length()} bytes")
                        println("📁 File path: ${modelFile.absolutePath}")
                    }
                } else {
                    println("❌ Download failed: ${downloadResult.exceptionOrNull()?.message}")
                }
            } else {
                println("ℹ️ Model already exists, no download needed")
            }
            
        } catch (e: Exception) {
            println("❌ Error checking/downloading model: ${e.message}")
        }
    }

    /**
     * Example: Complete setup with automatic model management
     */
    suspend fun completeAutoSetup() {
        try {
            println("\n🚀 Complete automatic setup demonstration")
            println("=" * 40)
            
            // Step 1: Initialize AI Manager (auto-downloads models if needed)
            println("1️⃣ Initializing AI Manager...")
            val aiManager = AIManager(context)
            val initResult = aiManager.initialize()
            
            if (initResult.isFailure) {
                println("❌ AI initialization failed: ${initResult.exceptionOrNull()?.message}")
                return
            }
            
            println("✅ AI Manager initialized")
            
            // Step 2: Check model status
            println("\n2️⃣ Checking model status...")
            val documentManager = aiManager.getDocumentManager()
            val modelExists = documentManager.isRerankerModelAvailable()
            println("📥 Reranker model available: $modelExists")
            
            // Step 3: Initialize RAG with documents
            println("\n3️⃣ Initializing RAG with documents...")
            val ragResult = aiManager.initializeRAGWithDocuments()
            
            if (ragResult.isSuccess) {
                val chunkCount = ragResult.getOrNull() ?: 0
                println("✅ RAG initialized with $chunkCount chunks")
            } else {
                println("❌ RAG initialization failed: ${ragResult.exceptionOrNull()?.message}")
            }
            
            // Step 4: Configure settings
            println("\n4️⃣ Configuring settings...")
            aiManager.preferences.ragEnabled = true
            aiManager.preferences.rerankEnabled = true
            aiManager.preferences.rerankTopN = 3
            
            println("✅ Settings configured")
            
            // Step 5: Test the complete system
            println("\n5️⃣ Testing complete system...")
            val aiChat = AIChatService(context, aiManager)
            
            val testQueries = listOf(
                "How does SafeGuardian protect privacy?",
                "What AI models does SafeGuardian use?",
                "How do I troubleshoot performance issues?"
            )
            
            for (query in testQueries) {
                println("\n🔍 Query: $query")
                val response = aiChat.processMessage(query, useRAG = true)
                println("🤖 Response: ${response.take(150)}...")
            }
            
            println("\n✅ Complete setup and testing finished!")
            
        } catch (e: Exception) {
            println("❌ Error in complete setup: ${e.message}")
        }
    }

    /**
     * Run all demonstrations
     */
    suspend fun runAllDemonstrations() {
        demonstrateAutoDownload()
        demonstrateManualDownload()
        checkAndDownloadIfNeeded()
        completeAutoSetup()
    }
}

/**
 * Extension function for string repetition
 */
private operator fun String.times(n: Int): String = this.repeat(n)






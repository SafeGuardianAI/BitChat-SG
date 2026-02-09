package com.bitchat.android.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * RAG Service for Retrieval Augmented Generation
 * 
 * Provides document indexing, embedding generation, vector search,
 * and reranking for improved context retrieval
 */
class RAGService(
    private val context: Context,
    private val preferences: AIPreferences,
    private val rerankerService: RerankerService
) {

    companion object {
        private const val TAG = "RAGService"
        private const val INDEX_FILE = "rag_index.json"
        private const val VECTORS_FILE = "rag_vectors.bin"
        private const val DEFAULT_CHUNK_SIZE = 1000
        private const val DEFAULT_CHUNK_OVERLAP = 150
        private const val DEFAULT_TOP_K = 5
    }

    private var isInitialized = false
    private var documentIndex: MutableList<DocumentChunk> = mutableListOf()
    private var embeddingMatrix: FloatArray? = null
    private var embeddingDimension: Int = 0

    /**
     * Initialize the RAG service
     */
    suspend fun initialize(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "═══════════════════════════════════════════════════════")
            Log.i(TAG, "🔧 Initializing RAGService")
            Log.i(TAG, "═══════════════════════════════════════════════════════")
            
            // Load existing index if available
            loadIndex()
            
            isInitialized = true
            
            val stats = getIndexStats()
            Log.i(TAG, "✅ RAGService initialized successfully")
            Log.i(TAG, "   📊 Total chunks: ${stats.totalChunks}")
            Log.i(TAG, "   📏 Embedding dimension: ${stats.embeddingDimension}")
            Log.i(TAG, "   🔍 Has embeddings: ${stats.hasEmbeddings}")
            Log.i(TAG, "   ✅ Ready: ${stats.isReady}")
            Log.i(TAG, "═══════════════════════════════════════════════════════")
            
            Result.success(Unit)
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to initialize RAGService", e)
            Result.failure(e)
        }
    }

    /**
     * Check if RAG service is ready
     */
    fun isReady(): Boolean {
        return isInitialized && documentIndex.isNotEmpty()
    }

    /**
     * Add documents to the RAG index
     * 
     * @param documents List of documents to index
     * @param chunkSize Size of text chunks
     * @param overlap Overlap between chunks
     */
    suspend fun addDocuments(
        documents: List<String>,
        chunkSize: Int = DEFAULT_CHUNK_SIZE,
        overlap: Int = DEFAULT_CHUNK_OVERLAP
    ): Result<Int> = withContext(Dispatchers.IO) {
        
        try {
            Log.i(TAG, "═══════════════════════════════════════════════════════")
            Log.i(TAG, "📚 Adding ${documents.size} documents to RAG index")
            Log.i(TAG, "   📄 Chunk size: $chunkSize, Overlap: $overlap")
            
            if (documents.isEmpty()) {
                Log.w(TAG, "⚠️ No documents provided to add")
                return@withContext Result.success(0)
            }
            
            var totalChunks = 0
            val startIndex = documentIndex.size
            
            for ((docIndex, document) in documents.withIndex()) {
                Log.d(TAG, "   📖 Processing document $docIndex (${document.length} chars)")
                
                if (document.trim().isEmpty()) {
                    Log.w(TAG, "   ⚠️ Document $docIndex is empty, skipping")
                    continue
                }
                
                val chunks = createChunks(document, chunkSize, overlap)
                Log.d(TAG, "   ✂️ Created ${chunks.size} chunks from document $docIndex")
                
                for ((chunkIndex, chunk) in chunks.withIndex()) {
                    val documentChunk = DocumentChunk(
                        id = documentIndex.size + totalChunks,
                        content = chunk,
                        source = "document_$docIndex",
                        chunkIndex = chunkIndex,
                        embedding = null // Will be generated later
                    )
                    documentIndex.add(documentChunk)
                    totalChunks++
                    
                    if (chunkIndex < 3) { // Log first 3 chunks as sample
                        Log.d(TAG, "   📝 Chunk $chunkIndex preview: ${chunk.take(100)}...")
                    }
                }
            }
            
            Log.i(TAG, "   ✅ Created $totalChunks total chunks")
            
            // Generate embeddings for new chunks
            if (totalChunks > 0) {
                Log.i(TAG, "   🔢 Generating embeddings for $totalChunks chunks...")
                generateEmbeddings()
                Log.i(TAG, "   💾 Saving index...")
                saveIndex()
                Log.i(TAG, "   ✅ Index saved successfully")
            } else {
                Log.w(TAG, "   ⚠️ No chunks created, skipping embedding generation")
            }
            
            val finalStats = getIndexStats()
            Log.i(TAG, "═══════════════════════════════════════════════════════")
            Log.i(TAG, "✅ Successfully added $totalChunks chunks from ${documents.size} documents")
            Log.i(TAG, "   📊 Total chunks in index: ${finalStats.totalChunks}")
            Log.i(TAG, "   🔍 Index ready: ${finalStats.isReady}")
            Log.i(TAG, "═══════════════════════════════════════════════════════")
            
            Result.success(totalChunks)
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to add documents", e)
            Result.failure(e)
        }
    }

    /**
     * Search for relevant documents using vector similarity and reranking
     * 
     * @param query Search query
     * @param topK Number of initial candidates to retrieve
     * @param useReranking Whether to use reranking
     * @return List of relevant document chunks
     */
    suspend fun search(
        query: String,
        topK: Int = DEFAULT_TOP_K,
        useReranking: Boolean = true
    ): List<DocumentChunk> = withContext(Dispatchers.IO) {
        
        Log.i(TAG, "═══════════════════════════════════════════════════════")
        Log.i(TAG, "🔍 RAG Search Request")
        Log.i(TAG, "   Query: \"$query\"")
        Log.i(TAG, "   TopK: $topK, Reranking: $useReranking")
        
        if (!isReady()) {
            Log.w(TAG, "❌ RAG service not ready")
            Log.w(TAG, "   Initialized: $isInitialized")
            Log.w(TAG, "   Document count: ${documentIndex.size}")
            Log.w(TAG, "   Embedding dimension: $embeddingDimension")
            Log.i(TAG, "═══════════════════════════════════════════════════════")
            return@withContext emptyList()
        }

        try {
            val stats = getIndexStats()
            Log.i(TAG, "   📊 Index stats: ${stats.totalChunks} chunks, ${stats.embeddingDimension}D embeddings")
            
            // Step 1: Generate query embedding
            Log.d(TAG, "   🔢 Step 1: Generating query embedding...")
            val queryEmbedding = generateQueryEmbedding(query)
            if (queryEmbedding == null) {
                Log.e(TAG, "   ❌ Failed to generate query embedding")
                Log.i(TAG, "═══════════════════════════════════════════════════════")
                return@withContext emptyList()
            }
            Log.d(TAG, "   ✅ Query embedding generated (${queryEmbedding.size} dimensions)")
            
            // Step 2: Vector similarity search
            Log.d(TAG, "   🔍 Step 2: Performing vector similarity search...")
            val candidates = performVectorSearch(queryEmbedding, topK)
            Log.i(TAG, "   ✅ Found ${candidates.size} candidates from vector search")
            
            if (candidates.isNotEmpty()) {
                Log.d(TAG, "   📋 Top candidates:")
                candidates.take(3).forEachIndexed { index, chunk ->
                    Log.d(TAG, "      ${index + 1}. [${chunk.source}] ${chunk.content.take(80)}...")
                }
            } else {
                Log.w(TAG, "   ⚠️ No candidates found from vector search")
            }
            
            // Step 3: Reranking (if enabled and reranker is available)
            val finalResults = if (useReranking && preferences.rerankEnabled && rerankerService.isReady()) {
                Log.d(TAG, "   🎯 Step 3: Reranking with ${preferences.rerankTopN} top results...")
                val rerankedIndices = rerankerService.rerank(
                    query = query,
                    documents = candidates.map { it.content },
                    topN = preferences.rerankTopN
                )
                
                val reranked = rerankedIndices.mapNotNull { index ->
                    candidates.getOrNull(index)
                }
                Log.i(TAG, "   ✅ Reranking complete: ${reranked.size} final results")
                reranked
            } else {
                if (!useReranking) {
                    Log.d(TAG, "   ⏭️ Reranking disabled, using vector search results")
                } else if (!preferences.rerankEnabled) {
                    Log.d(TAG, "   ⏭️ Reranking disabled in preferences")
                } else if (!rerankerService.isReady()) {
                    Log.w(TAG, "   ⚠️ Reranker service not ready, using vector search results")
                }
                candidates
            }
            
            Log.i(TAG, "═══════════════════════════════════════════════════════")
            Log.i(TAG, "✅ Search complete: ${finalResults.size} documents retrieved")
            if (finalResults.isNotEmpty()) {
                Log.i(TAG, "   📄 Results:")
                finalResults.forEachIndexed { index, chunk ->
                    Log.i(TAG, "      ${index + 1}. [${chunk.source}] ${chunk.content.take(100)}...")
                }
            }
            Log.i(TAG, "═══════════════════════════════════════════════════════")
            
            return@withContext finalResults
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error during search", e)
            Log.i(TAG, "═══════════════════════════════════════════════════════")
            return@withContext emptyList()
        }
    }

    /**
     * Create text chunks from a document
     */
    private fun createChunks(text: String, chunkSize: Int, overlap: Int): List<String> {
        val normalizedText = text.replace("\r\n", "\n").trim()
        if (normalizedText.isEmpty()) return emptyList()
        
        val chunks = mutableListOf<String>()
        var start = 0
        
        while (start < normalizedText.length) {
            val end = minOf(start + chunkSize, normalizedText.length)
            val chunk = normalizedText.substring(start, end)
            chunks.add(chunk)
            
            if (end >= normalizedText.length) break
            
            // Move start forward with overlap
            start = end - overlap
            if (start < 0) start = 0
        }
        
        return chunks
    }

    /**
     * Generate embeddings for all document chunks
     * This is a placeholder implementation - in a real app you would use an embedding model
     */
    private suspend fun generateEmbeddings() {
        try {
            Log.d(TAG, "Generating embeddings for ${documentIndex.size} chunks")
            
            // Placeholder: Generate random embeddings for demonstration
            // In a real implementation, you would use the embedding model
            embeddingDimension = 768 // Standard embedding dimension
            
            val embeddings = FloatArray(documentIndex.size * embeddingDimension)
            val random = java.util.Random(42) // Fixed seed for reproducibility
            
            for (i in documentIndex.indices) {
                val startIdx = i * embeddingDimension
                for (j in 0 until embeddingDimension) {
                    embeddings[startIdx + j] = random.nextGaussian().toFloat()
                }
                
                // Normalize the embedding
                val embeddingStart = startIdx
                val embeddingEnd = startIdx + embeddingDimension
                val embeddingSlice = embeddings.sliceArray(embeddingStart until embeddingEnd)
                normalizeVector(embeddingSlice)
                
                // Store embedding in document chunk
                documentIndex[i] = documentIndex[i].copy(embedding = embeddingSlice)
            }
            
            embeddingMatrix = embeddings
            Log.i(TAG, "Generated embeddings with dimension $embeddingDimension")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate embeddings", e)
            throw e
        }
    }

    /**
     * Generate embedding for a query (placeholder implementation)
     */
    private suspend fun generateQueryEmbedding(query: String): FloatArray? {
        try {
            // Placeholder: Generate random query embedding
            // In a real implementation, you would use the embedding model
            val embedding = FloatArray(embeddingDimension)
            val random = java.util.Random(query.hashCode().toLong())
            
            for (i in 0 until embeddingDimension) {
                embedding[i] = random.nextGaussian().toFloat()
            }
            
            normalizeVector(embedding)
            return embedding
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate query embedding", e)
            return null
        }
    }

    /**
     * Perform vector similarity search using cosine similarity
     */
    private fun performVectorSearch(queryEmbedding: FloatArray, topK: Int): List<DocumentChunk> {
        val similarities = mutableListOf<Pair<Int, Float>>()
        
        for (i in documentIndex.indices) {
            val chunkEmbedding = documentIndex[i].embedding
            if (chunkEmbedding != null) {
                val similarity = cosineSimilarity(queryEmbedding, chunkEmbedding)
                similarities.add(Pair(i, similarity))
            }
        }
        
        // Sort by similarity (descending) and take top K
        val topSimilarities = similarities
            .sortedByDescending { it.second }
            .take(topK)
        
        return topSimilarities.map { documentIndex[it.first] }
    }

    /**
     * Calculate cosine similarity between two vectors
     */
    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        
        var dotProduct = 0f
        var normA = 0f
        var normB = 0f
        
        for (i in a.indices) {
            dotProduct += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        
        val denominator = sqrt(normA) * sqrt(normB)
        return if (denominator > 0f) dotProduct / denominator else 0f
    }

    /**
     * Normalize a vector to unit length
     */
    private fun normalizeVector(vector: FloatArray) {
        val norm = sqrt(vector.sumOf { it.toDouble() * it.toDouble() }.toFloat())
        if (norm > 0f) {
            for (i in vector.indices) {
                vector[i] /= norm
            }
        }
    }

    /**
     * Save the RAG index to storage
     */
    private suspend fun saveIndex() = withContext(Dispatchers.IO) {
        try {
            val indexFile = File(context.filesDir, INDEX_FILE)
            val vectorsFile = File(context.filesDir, VECTORS_FILE)
            
            // Save document metadata
            val indexData = RAGIndexData(
                chunks = documentIndex.map { chunk ->
                    RAGChunkData(
                        id = chunk.id,
                        content = chunk.content,
                        source = chunk.source,
                        chunkIndex = chunk.chunkIndex
                    )
                },
                embeddingDimension = embeddingDimension,
                totalChunks = documentIndex.size
            )
            
            indexFile.writeText(com.google.gson.Gson().toJson(indexData))
            
            // Save embedding matrix as binary file
            if (embeddingMatrix != null) {
                val buffer = ByteBuffer.allocate(embeddingMatrix!!.size * 4)
                buffer.order(ByteOrder.LITTLE_ENDIAN)
                for (value in embeddingMatrix!!) {
                    buffer.putFloat(value)
                }
                vectorsFile.writeBytes(buffer.array())
            }
            
            Log.d(TAG, "Saved RAG index with ${documentIndex.size} chunks")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save RAG index", e)
        }
    }

    /**
     * Load the RAG index from storage
     */
    private suspend fun loadIndex() = withContext(Dispatchers.IO) {
        try {
            val indexFile = File(context.filesDir, INDEX_FILE)
            val vectorsFile = File(context.filesDir, VECTORS_FILE)
            
            Log.d(TAG, "   📂 Checking for existing RAG index...")
            Log.d(TAG, "      Index file: ${indexFile.absolutePath} (exists: ${indexFile.exists()})")
            Log.d(TAG, "      Vectors file: ${vectorsFile.absolutePath} (exists: ${vectorsFile.exists()})")
            
            if (!indexFile.exists()) {
                Log.i(TAG, "   ℹ️ No existing RAG index found - starting with empty index")
                Log.i(TAG, "   💡 Use '/init-rag' command to load documents")
                return@withContext
            }
            
            Log.d(TAG, "   📖 Loading RAG index from storage...")
            
            // Load document metadata
            val indexData = com.google.gson.Gson().fromJson(
                indexFile.readText(),
                RAGIndexData::class.java
            )
            
            Log.d(TAG, "   📊 Index metadata: ${indexData.totalChunks} chunks, ${indexData.embeddingDimension}D")
            
            documentIndex.clear()
            embeddingDimension = indexData.embeddingDimension
            
            // Load embeddings
            if (vectorsFile.exists() && embeddingDimension > 0) {
                Log.d(TAG, "   🔢 Loading embeddings from vectors file...")
                val buffer = ByteBuffer.wrap(vectorsFile.readBytes())
                buffer.order(ByteOrder.LITTLE_ENDIAN)
                
                val embeddings = FloatArray(indexData.totalChunks * embeddingDimension)
                for (i in embeddings.indices) {
                    embeddings[i] = buffer.float
                }
                
                embeddingMatrix = embeddings
                
                // Reconstruct document chunks with embeddings
                for (chunkData in indexData.chunks) {
                    val embeddingStart = chunkData.id * embeddingDimension
                    val embeddingEnd = embeddingStart + embeddingDimension
                    val embedding = embeddings.sliceArray(embeddingStart until embeddingEnd)
                    
                    val chunk = DocumentChunk(
                        id = chunkData.id,
                        content = chunkData.content,
                        source = chunkData.source,
                        chunkIndex = chunkData.chunkIndex,
                        embedding = embedding
                    )
                    documentIndex.add(chunk)
                }
                Log.d(TAG, "   ✅ Loaded ${documentIndex.size} chunks with embeddings")
            } else {
                Log.w(TAG, "   ⚠️ Vectors file not found or invalid, loading without embeddings")
                // Load without embeddings
                for (chunkData in indexData.chunks) {
                    val chunk = DocumentChunk(
                        id = chunkData.id,
                        content = chunkData.content,
                        source = chunkData.source,
                        chunkIndex = chunkData.chunkIndex,
                        embedding = null
                    )
                    documentIndex.add(chunk)
                }
                Log.d(TAG, "   ✅ Loaded ${documentIndex.size} chunks without embeddings")
            }
            
            Log.i(TAG, "   ✅ Successfully loaded RAG index with ${documentIndex.size} chunks")
            
        } catch (e: Exception) {
            Log.e(TAG, "   ❌ Failed to load RAG index", e)
            documentIndex.clear()
        }
    }

    /**
     * Get statistics about the RAG index
     */
    fun getIndexStats(): RAGStats {
        return RAGStats(
            totalChunks = documentIndex.size,
            embeddingDimension = embeddingDimension,
            hasEmbeddings = embeddingMatrix != null,
            isReady = isReady()
        )
    }

    /**
     * Clear the RAG index
     */
    suspend fun clearIndex() = withContext(Dispatchers.IO) {
        try {
            documentIndex.clear()
            embeddingMatrix = null
            embeddingDimension = 0
            
            // Delete index files
            File(context.filesDir, INDEX_FILE).delete()
            File(context.filesDir, VECTORS_FILE).delete()
            
            Log.i(TAG, "Cleared RAG index")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear RAG index", e)
        }
    }
}

/**
 * Document chunk with embedding
 */
data class DocumentChunk(
    val id: Int,
    val content: String,
    val source: String,
    val chunkIndex: Int,
    val embedding: FloatArray?
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as DocumentChunk

        if (id != other.id) return false
        if (content != other.content) return false
        if (source != other.source) return false
        if (chunkIndex != other.chunkIndex) return false
        if (embedding != null) {
            if (other.embedding == null) return false
            if (!embedding.contentEquals(other.embedding)) return false
        } else if (other.embedding != null) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + content.hashCode()
        result = 31 * result + source.hashCode()
        result = 31 * result + chunkIndex
        result = 31 * result + (embedding?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * RAG index data for serialization
 */
data class RAGIndexData(
    val chunks: List<RAGChunkData>,
    val embeddingDimension: Int,
    val totalChunks: Int
)

data class RAGChunkData(
    val id: Int,
    val content: String,
    val source: String,
    val chunkIndex: Int
)

/**
 * RAG service statistics
 */
data class RAGStats(
    val totalChunks: Int,
    val embeddingDimension: Int,
    val hasEmbeddings: Boolean,
    val isReady: Boolean
)


package com.bitchat.android.ai.rag

import android.content.Context
import android.util.Log
import com.bitchat.android.ai.AIPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Enhanced RAG service combining TF-IDF and vector similarity search.
 *
 * Retrieval strategy (hybrid):
 *   1. Vector search (when embedding model is loaded) -> top K candidates
 *   2. TF-IDF search (always available, zero-cost) -> top K candidates
 *   3. Reciprocal Rank Fusion (RRF) merges the two ranked lists
 *   4. Deduplicated context window assembled for the LLM
 *
 * When the embedding model is not available, the service degrades
 * gracefully to TF-IDF-only mode, exactly like the existing
 * [DisasterRAGService].
 */
class EnhancedRAGService(
    private val context: Context,
    private val preferences: AIPreferences
) {

    companion object {
        private const val TAG = "EnhancedRAGService"
        private const val MAX_CONTEXT_LENGTH = 2000 // chars of RAG context injected into prompt
        private const val MIN_TFIDF_SCORE = 0.5
        private const val MIN_VECTOR_SCORE = 0.3f
        private const val DEFAULT_TOP_K = 5
        private const val RRF_K = 60 // smoothing constant for reciprocal rank fusion
    }

    // ------------------------------------------------------------------ //
    // Components
    // ------------------------------------------------------------------ //

    private val simpleSearch = SimpleTextSearch()
    val embeddingService = EmbeddingService(context)
    val vectorStore = VectorStore(context)
    val documentProcessor = DocumentProcessor(embeddingService, vectorStore)

    private var isInitialized = false

    // ------------------------------------------------------------------ //
    // Result types
    // ------------------------------------------------------------------ //

    data class RetrievalResult(
        val text: String,
        val score: Float,
        val source: String,
        val method: RetrievalMethod
    )

    enum class RetrievalMethod {
        TFIDF,
        VECTOR,
        HYBRID
    }

    // ------------------------------------------------------------------ //
    // Lifecycle
    // ------------------------------------------------------------------ //

    /**
     * Initialize the RAG system.
     *
     * Builds the TF-IDF index and loads any persisted vector store.
     * Call this once at startup; it is safe to call multiple times.
     */
    suspend fun initialize() {
        if (isInitialized) return

        withContext(Dispatchers.IO) {
            // TF-IDF is always built (instant, no model needed)
            simpleSearch.buildIndex()

            // Load persisted vector store if available
            vectorStore.load()

            isInitialized = true
            Log.d(TAG, "EnhancedRAGService initialized " +
                "(tfidf entries=${DisasterKnowledgeBase.entries.size}, " +
                "vector entries=${vectorStore.size()}, " +
                "vector search=${isVectorSearchAvailable()})")
        }
    }

    /**
     * Whether vector-based retrieval is currently available.
     */
    fun isVectorSearchAvailable(): Boolean =
        embeddingService.isReady() && vectorStore.size() > 0

    // ------------------------------------------------------------------ //
    // Prompt augmentation
    // ------------------------------------------------------------------ //

    /**
     * Augment a user prompt with retrieved context.
     *
     * If RAG is disabled in preferences, the original prompt is returned
     * unchanged.
     */
    suspend fun augmentPrompt(userPrompt: String): String {
        if (!preferences.ragEnabled) return userPrompt
        if (!isInitialized) initialize()

        val results = hybridSearch(userPrompt, topK = DEFAULT_TOP_K)
        if (results.isEmpty()) {
            Log.d(TAG, "No relevant context for: ${userPrompt.take(60)}")
            return userPrompt
        }

        Log.d(TAG, "Retrieved ${results.size} results (methods: ${results.map { it.method }.distinct()})")

        val contextBuilder = StringBuilder()
        contextBuilder.append("[DISASTER KNOWLEDGE CONTEXT]\n")
        contextBuilder.append("Use the following verified safety information to help answer the user's question:\n\n")

        var totalLength = 0
        val usedTexts = mutableSetOf<String>() // deduplicate

        for (result in results) {
            if (result.text in usedTexts) continue
            val snippet = "--- (${result.source}) [${result.method.name} score=%.2f] ---\n${result.text}\n\n"
                .format(result.score)
            if (totalLength + snippet.length > MAX_CONTEXT_LENGTH) break

            contextBuilder.append(snippet)
            totalLength += snippet.length
            usedTexts.add(result.text)
        }

        contextBuilder.append("[END CONTEXT]\n\n")
        contextBuilder.append("User question: $userPrompt")

        return contextBuilder.toString()
    }

    // ------------------------------------------------------------------ //
    // Hybrid search
    // ------------------------------------------------------------------ //

    /**
     * Perform hybrid retrieval combining TF-IDF and vector search.
     *
     * Results from both methods are merged via Reciprocal Rank Fusion
     * and returned sorted by combined score.
     */
    suspend fun hybridSearch(
        query: String,
        topK: Int = DEFAULT_TOP_K
    ): List<RetrievalResult> {
        if (!isInitialized) initialize()

        // --- TF-IDF ---
        val tfidfResults = simpleSearch.search(query, maxResults = topK)
            .filter { it.score >= MIN_TFIDF_SCORE }

        // --- Vector search ---
        val vectorResults = if (isVectorSearchAvailable()) {
            val queryEmbedding = embeddingService.embed(query)
            if (queryEmbedding != null) {
                vectorStore.search(queryEmbedding, topK = topK, minScore = MIN_VECTOR_SCORE)
            } else {
                emptyList()
            }
        } else {
            emptyList()
        }

        // --- Merge ---
        return if (vectorResults.isEmpty()) {
            // TF-IDF only
            tfidfResults.map { r ->
                RetrievalResult(
                    text = r.entry.content,
                    score = r.score.toFloat(),
                    source = "${r.entry.category}/${r.entry.title}",
                    method = RetrievalMethod.TFIDF
                )
            }
        } else if (tfidfResults.isEmpty()) {
            // Vector only
            vectorResults.map { hit ->
                RetrievalResult(
                    text = hit.entry.text,
                    score = hit.score,
                    source = hit.entry.source,
                    method = RetrievalMethod.VECTOR
                )
            }
        } else {
            // Hybrid: Reciprocal Rank Fusion
            reciprocalRankFusion(tfidfResults, vectorResults, k = RRF_K)
                .take(topK)
        }
    }

    // ------------------------------------------------------------------ //
    // Knowledge management
    // ------------------------------------------------------------------ //

    /**
     * Add user-provided knowledge (mesh messages, notes, etc.)
     * to the vector store.
     */
    suspend fun addKnowledge(text: String, source: String) {
        if (!embeddingService.isReady()) {
            Log.w(TAG, "Cannot add knowledge -- embedding service not ready")
            return
        }
        val count = documentProcessor.indexDocument(text, source)
        if (count > 0) {
            vectorStore.save()
            Log.d(TAG, "Added knowledge: $count chunks from source=$source")
        }
    }

    /**
     * Remove all indexed knowledge from a given source.
     */
    fun removeKnowledge(source: String) {
        documentProcessor.removeDocument(source)
        vectorStore.save()
    }

    // ------------------------------------------------------------------ //
    // Reciprocal Rank Fusion
    // ------------------------------------------------------------------ //

    /**
     * Merge two ranked lists using Reciprocal Rank Fusion (RRF).
     *
     *   RRF_score(d) = sum over rankings r of  1 / (k + rank_r(d))
     *
     * @param tfidfResults  Ranked TF-IDF results (highest score first).
     * @param vectorResults Ranked vector results (highest similarity first).
     * @param k             Smoothing constant (default 60, per original RRF paper).
     */
    private fun reciprocalRankFusion(
        tfidfResults: List<SimpleTextSearch.SearchResult>,
        vectorResults: List<VectorStore.SearchHit>,
        k: Int = RRF_K
    ): List<RetrievalResult> {

        // Normalised text key for deduplication
        fun normaliseKey(text: String): String =
            text.lowercase().trim().replace(Regex("\\s+"), " ")

        data class FusedEntry(
            val text: String,
            val source: String,
            var rrfScore: Float = 0f,
            var methods: MutableSet<RetrievalMethod> = mutableSetOf()
        )

        val fused = LinkedHashMap<String, FusedEntry>()

        // Score TF-IDF results
        tfidfResults.forEachIndexed { rank, result ->
            val key = normaliseKey(result.entry.content)
            val entry = fused.getOrPut(key) {
                FusedEntry(
                    text = result.entry.content,
                    source = "${result.entry.category}/${result.entry.title}"
                )
            }
            entry.rrfScore += 1.0f / (k + rank + 1)
            entry.methods.add(RetrievalMethod.TFIDF)
        }

        // Score vector results
        vectorResults.forEachIndexed { rank, hit ->
            val key = normaliseKey(hit.entry.text)
            val entry = fused.getOrPut(key) {
                FusedEntry(
                    text = hit.entry.text,
                    source = hit.entry.source
                )
            }
            entry.rrfScore += 1.0f / (k + rank + 1)
            entry.methods.add(RetrievalMethod.VECTOR)
        }

        return fused.values
            .sortedByDescending { it.rrfScore }
            .map { entry ->
                val method = if (entry.methods.size > 1) {
                    RetrievalMethod.HYBRID
                } else {
                    entry.methods.first()
                }
                RetrievalResult(
                    text = entry.text,
                    score = entry.rrfScore,
                    source = entry.source,
                    method = method
                )
            }
    }

    // ------------------------------------------------------------------ //
    // Cleanup
    // ------------------------------------------------------------------ //

    /**
     * Persist vector store and release the embedding model.
     */
    fun shutdown() {
        vectorStore.save()
        embeddingService.release()
        isInitialized = false
        Log.d(TAG, "EnhancedRAGService shut down")
    }
}

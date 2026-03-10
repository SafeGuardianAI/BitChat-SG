package com.bitchat.android.ai.rag

import android.util.Log
import com.bitchat.android.ai.AIPreferences

/**
 * Disaster RAG (Retrieval-Augmented Generation) Service
 *
 * Augments AI prompts with relevant disaster preparedness data
 * retrieved from the local knowledge base. This ensures the AI
 * provides accurate, actionable safety information even without
 * internet access.
 *
 * Flow:
 *  1. User asks a question
 *  2. RAG detects if it's disaster-related
 *  3. Retrieves relevant knowledge passages
 *  4. Prepends context to the AI prompt
 *  5. AI generates an informed response
 */
class DisasterRAGService(private val preferences: AIPreferences) {

    companion object {
        private const val TAG = "DisasterRAGService"
        private const val MAX_CONTEXT_LENGTH = 1500 // Max chars of RAG context to inject
        private const val MIN_RELEVANCE_SCORE = 0.5 // Minimum score to include a result
    }

    private val searchEngine = SimpleTextSearch()
    private var isInitialized = false

    /**
     * Initialize the RAG system (build search index).
     */
    fun initialize() {
        if (isInitialized) return
        searchEngine.buildIndex()
        isInitialized = true
        Log.d(TAG, "Disaster RAG initialized with ${DisasterKnowledgeBase.entries.size} knowledge entries")
    }

    /**
     * Augment a user prompt with relevant disaster knowledge.
     *
     * @param userPrompt The original user question
     * @return An augmented prompt with prepended context, or the original if not disaster-related
     */
    fun augmentPrompt(userPrompt: String): String {
        if (!preferences.ragEnabled) return userPrompt
        if (!isInitialized) initialize()

        val results = searchEngine.search(userPrompt, maxResults = 3)
        val relevantResults = results.filter { it.score >= MIN_RELEVANCE_SCORE }

        if (relevantResults.isEmpty()) {
            Log.d(TAG, "No relevant disaster knowledge found for: ${userPrompt.take(60)}")
            return userPrompt
        }

        Log.d(TAG, "Found ${relevantResults.size} relevant entries for: ${userPrompt.take(60)}")

        val contextBuilder = StringBuilder()
        contextBuilder.append("[DISASTER KNOWLEDGE CONTEXT]\n")
        contextBuilder.append("Use the following verified safety information to help answer the user's question:\n\n")

        var totalLength = 0
        for (result in relevantResults) {
            val entry = result.entry
            val snippet = "--- ${entry.title} (${entry.category}) ---\n${entry.content}\n\n"

            if (totalLength + snippet.length > MAX_CONTEXT_LENGTH) break

            contextBuilder.append(snippet)
            totalLength += snippet.length
        }

        contextBuilder.append("[END CONTEXT]\n\n")
        contextBuilder.append("User question: $userPrompt")

        return contextBuilder.toString()
    }

    /**
     * Check if a query is disaster-related.
     */
    fun isDisasterRelated(query: String): Boolean {
        if (!isInitialized) initialize()
        return searchEngine.isDisasterRelated(query)
    }

    /**
     * Search the knowledge base directly (for function calls).
     */
    fun searchKnowledge(query: String, maxResults: Int = 3): List<SimpleTextSearch.SearchResult> {
        if (!isInitialized) initialize()
        return searchEngine.search(query, maxResults)
    }

    /**
     * Get all available categories.
     */
    fun getCategories(): Set<String> = DisasterKnowledgeBase.getCategories()

    /**
     * Get entries for a specific disaster category.
     */
    fun getCategory(category: String): List<DisasterKnowledgeBase.KnowledgeEntry> =
        DisasterKnowledgeBase.getByCategory(category)
}

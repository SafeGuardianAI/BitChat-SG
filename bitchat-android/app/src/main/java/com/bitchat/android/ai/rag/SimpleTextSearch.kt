package com.bitchat.android.ai.rag

import android.util.Log
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Simple Text Search Engine (TF-IDF)
 *
 * Lightweight keyword-based retrieval for the disaster knowledge base.
 * Uses TF-IDF scoring with keyword boosting — no embedding model needed,
 * zero battery overhead, works instantly offline.
 *
 * Can be upgraded to vector similarity search with EmbeddingGemma later.
 */
class SimpleTextSearch {

    companion object {
        private const val TAG = "SimpleTextSearch"
        private const val KEYWORD_BOOST = 2.0 // Boost for explicit keyword matches
    }

    data class SearchResult(
        val entry: DisasterKnowledgeBase.KnowledgeEntry,
        val score: Double,
        val matchedTerms: Set<String>
    )

    // Inverted index: term -> set of entry IDs
    private val invertedIndex = mutableMapOf<String, MutableSet<String>>()
    // Term frequencies per document
    private val termFrequencies = mutableMapOf<String, Map<String, Int>>()
    // Total documents
    private var totalDocs = 0

    private var isIndexed = false

    /**
     * Build the search index from the knowledge base.
     */
    fun buildIndex() {
        val entries = DisasterKnowledgeBase.entries
        totalDocs = entries.size

        for (entry in entries) {
            val tokens = tokenize(entry.title + " " + entry.content)
            val tf = mutableMapOf<String, Int>()

            for (token in tokens) {
                tf[token] = (tf[token] ?: 0) + 1
                invertedIndex.getOrPut(token) { mutableSetOf() }.add(entry.id)
            }

            // Also index explicit keywords with boosted presence
            for (keyword in entry.keywords) {
                val keyTokens = tokenize(keyword)
                for (kt in keyTokens) {
                    tf[kt] = (tf[kt] ?: 0) + 3 // Boost keyword terms
                    invertedIndex.getOrPut(kt) { mutableSetOf() }.add(entry.id)
                }
            }

            termFrequencies[entry.id] = tf
        }

        isIndexed = true
        Log.d(TAG, "Index built: $totalDocs documents, ${invertedIndex.size} unique terms")
    }

    /**
     * Search the knowledge base with a natural language query.
     *
     * @param query User's question or search terms
     * @param maxResults Maximum number of results to return
     * @return Ranked list of matching knowledge entries
     */
    fun search(query: String, maxResults: Int = 3): List<SearchResult> {
        if (!isIndexed) buildIndex()

        val queryTokens = tokenize(query)
        if (queryTokens.isEmpty()) return emptyList()

        val scores = mutableMapOf<String, Double>()
        val matchedTermsMap = mutableMapOf<String, MutableSet<String>>()

        for (token in queryTokens) {
            val docIds = invertedIndex[token] ?: continue
            // IDF: log(N / df)
            val idf = ln(totalDocs.toDouble() / docIds.size)

            for (docId in docIds) {
                val tf = termFrequencies[docId]?.get(token) ?: 0
                val tfidf = tf * idf

                // Check if this is an explicit keyword match
                val entry = DisasterKnowledgeBase.getById(docId)
                val isKeywordMatch = entry?.keywords?.any { kw ->
                    tokenize(kw).contains(token)
                } ?: false

                val boostedScore = if (isKeywordMatch) tfidf * KEYWORD_BOOST else tfidf

                scores[docId] = (scores[docId] ?: 0.0) + boostedScore
                matchedTermsMap.getOrPut(docId) { mutableSetOf() }.add(token)
            }
        }

        // Normalize by query length for fair ranking
        val queryLen = sqrt(queryTokens.size.toDouble())

        return scores.entries
            .sortedByDescending { it.value / queryLen }
            .take(maxResults)
            .mapNotNull { (docId, score) ->
                DisasterKnowledgeBase.getById(docId)?.let { entry ->
                    SearchResult(
                        entry = entry,
                        score = score / queryLen,
                        matchedTerms = matchedTermsMap[docId] ?: emptySet()
                    )
                }
            }
    }

    /**
     * Detect if a query is disaster-related.
     */
    fun isDisasterRelated(query: String): Boolean {
        val results = search(query, maxResults = 1)
        return results.isNotEmpty() && results[0].score > 1.0
    }

    /**
     * Tokenize text into lowercase terms, removing stop words.
     */
    private fun tokenize(text: String): List<String> {
        return text.lowercase()
            .replace(Regex("[^a-z0-9\\s-]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length > 2 && it !in STOP_WORDS }
    }

    private val STOP_WORDS = setOf(
        "the", "and", "for", "are", "but", "not", "you", "all",
        "can", "had", "her", "was", "one", "our", "out", "has",
        "have", "been", "from", "this", "that", "with", "they",
        "will", "what", "when", "make", "like", "time", "just",
        "know", "take", "come", "could", "than", "look", "only",
        "into", "year", "some", "them", "then", "also", "about",
        "would", "there", "their", "which", "should", "each",
        "other", "how", "more", "these", "very", "after", "most"
    )
}

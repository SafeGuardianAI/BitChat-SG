package com.bitchat.android.ai.rag

import android.content.Context
import android.util.Log
import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory vector store for RAG retrieval.
 *
 * Stores document chunks alongside their embedding vectors and performs
 * brute-force cosine similarity search.  Entries are persisted to a
 * binary file on internal storage so the index survives process restarts
 * without re-embedding.
 *
 * This is intentionally simple (flat search).  For large corpora,
 * ObjectBox HNSW indexing can replace the search loop later.
 */
class VectorStore(private val context: Context) {

    companion object {
        private const val TAG = "VectorStore"
        private const val STORE_FILENAME = "vector_store.bin"
    }

    // ------------------------------------------------------------------ //
    // Data classes
    // ------------------------------------------------------------------ //

    /**
     * A single vector entry in the store.
     *
     * Implements [Serializable] for simple Java-serialisation persistence.
     */
    data class VectorEntry(
        val id: String,
        val text: String,
        val embedding: FloatArray,
        val metadata: Map<String, String> = emptyMap(),
        val source: String = "",
        val chunkIndex: Int = 0,
        val timestamp: Long = System.currentTimeMillis()
    ) : Serializable {

        companion object {
            private const val serialVersionUID = 1L
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is VectorEntry) return false
            return id == other.id
        }

        override fun hashCode(): Int = id.hashCode()
    }

    data class SearchHit(
        val entry: VectorEntry,
        val score: Float
    )

    // ------------------------------------------------------------------ //
    // Storage
    // ------------------------------------------------------------------ //

    /** Thread-safe map keyed by entry id. */
    private val entries = ConcurrentHashMap<String, VectorEntry>()

    // ------------------------------------------------------------------ //
    // Mutators
    // ------------------------------------------------------------------ //

    /** Add a single entry (overwrites if same id exists). */
    fun add(entry: VectorEntry) {
        entries[entry.id] = entry
    }

    /** Bulk-add entries. */
    fun addAll(newEntries: List<VectorEntry>) {
        for (entry in newEntries) {
            entries[entry.id] = entry
        }
    }

    /** Remove an entry by its id. */
    fun remove(id: String) {
        entries.remove(id)
    }

    /** Remove all entries whose [VectorEntry.source] matches [source]. */
    fun removeBySource(source: String) {
        val keysToRemove = entries.values
            .filter { it.source == source }
            .map { it.id }
        for (key in keysToRemove) {
            entries.remove(key)
        }
    }

    /** Remove everything. */
    fun clear() {
        entries.clear()
    }

    // ------------------------------------------------------------------ //
    // Search
    // ------------------------------------------------------------------ //

    /**
     * Find the top-K entries most similar to [queryEmbedding].
     *
     * Uses brute-force cosine similarity (matching the cookbook pattern).
     *
     * @param queryEmbedding The embedding of the search query.
     * @param topK           Maximum number of results to return.
     * @param minScore       Minimum cosine similarity to include a hit.
     * @return Hits sorted by descending similarity score.
     */
    fun search(
        queryEmbedding: FloatArray,
        topK: Int = 5,
        minScore: Float = 0.3f
    ): List<SearchHit> {
        if (entries.isEmpty()) return emptyList()

        return entries.values
            .map { entry ->
                SearchHit(
                    entry = entry,
                    score = cosineSimilarity(queryEmbedding, entry.embedding)
                )
            }
            .filter { it.score >= minScore }
            .sortedByDescending { it.score }
            .take(topK)
    }

    // ------------------------------------------------------------------ //
    // Persistence
    // ------------------------------------------------------------------ //

    /**
     * Persist the current store to internal storage.
     *
     * The format is plain Java serialisation.  This is fine for the
     * expected index sizes (hundreds to low thousands of entries).
     */
    fun save() {
        try {
            val file = File(context.filesDir, STORE_FILENAME)
            ObjectOutputStream(file.outputStream().buffered()).use { oos ->
                oos.writeInt(entries.size)
                for (entry in entries.values) {
                    oos.writeObject(entry)
                }
            }
            Log.d(TAG, "Saved ${entries.size} entries to $STORE_FILENAME")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save vector store", e)
        }
    }

    /**
     * Load previously persisted entries.  Existing in-memory entries are
     * replaced.
     */
    fun load() {
        val file = File(context.filesDir, STORE_FILENAME)
        if (!file.exists()) {
            Log.d(TAG, "No persisted store found")
            return
        }
        try {
            ObjectInputStream(file.inputStream().buffered()).use { ois ->
                val count = ois.readInt()
                entries.clear()
                repeat(count) {
                    val entry = ois.readObject() as VectorEntry
                    entries[entry.id] = entry
                }
                Log.d(TAG, "Loaded $count entries from $STORE_FILENAME")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load vector store", e)
        }
    }

    // ------------------------------------------------------------------ //
    // Stats
    // ------------------------------------------------------------------ //

    /** Number of entries currently in the store. */
    fun size(): Int = entries.size

    /** Distinct sources present in the store. */
    fun sources(): Set<String> = entries.values.map { it.source }.toSet()

    /** Check whether the store contains any entry for [source]. */
    fun hasSource(source: String): Boolean =
        entries.values.any { it.source == source }

    // ------------------------------------------------------------------ //
    // Cosine similarity (cookbook pattern)
    // ------------------------------------------------------------------ //

    /**
     * Cosine similarity between two vectors.
     *
     * Identical to GenerateEmbedStringsUtil.computeCosineSimilarity in the
     * Nexa SDK cookbook.
     */
    private fun cosineSimilarity(a: FloatArray?, b: FloatArray?): Float {
        if (a == null || b == null) return 0.0f
        if (a.isEmpty() || b.isEmpty()) return 0.0f
        if (a.size != b.size) return 0.0f

        var dotProduct = 0.0f
        var normA = 0.0f
        var normB = 0.0f

        for (i in a.indices) {
            dotProduct += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }

        val epsilon = 1e-8f
        normA = kotlin.math.sqrt(normA + epsilon)
        normB = kotlin.math.sqrt(normB + epsilon)
        return dotProduct / (normA * normB)
    }
}

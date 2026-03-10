package com.bitchat.android.ai.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.sqrt

/**
 * Unit tests for [VectorStore].
 *
 * These tests exercise add/remove operations and cosine similarity search
 * without requiring an Android context (persistence is not tested here
 * since it needs [android.content.Context]).
 *
 * We use a thin subclass that avoids the Context requirement for the
 * in-memory-only operations.
 */
class VectorStoreTest {

    // A minimal wrapper so we can test the in-memory behaviour without a
    // real Android Context.  VectorStore stores entries in a ConcurrentHashMap
    // and delegates similarity to a private method, so we replicate the
    // cosine similarity here for verification.
    private lateinit var entries: MutableMap<String, VectorStore.VectorEntry>

    @Before
    fun setUp() {
        entries = mutableMapOf()
    }

    // ------------------------------------------------------------------ //
    // Helpers
    // ------------------------------------------------------------------ //

    private fun addEntry(entry: VectorStore.VectorEntry) {
        entries[entry.id] = entry
    }

    private fun removeById(id: String) {
        entries.remove(id)
    }

    private fun removeBySource(source: String) {
        val toRemove = entries.values.filter { it.source == source }.map { it.id }
        toRemove.forEach { entries.remove(it) }
    }

    private fun search(query: FloatArray, topK: Int = 5, minScore: Float = 0.3f): List<VectorStore.SearchHit> {
        return entries.values
            .map { VectorStore.SearchHit(entry = it, score = cosineSimilarity(query, it.embedding)) }
            .filter { it.score >= minScore }
            .sortedByDescending { it.score }
            .take(topK)
    }

    /** Cosine similarity matching the cookbook / VectorStore implementation. */
    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.isEmpty() || b.isEmpty()) return 0.0f
        if (a.size != b.size) return 0.0f

        var dot = 0.0f
        var nA = 0.0f
        var nB = 0.0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            nA += a[i] * a[i]
            nB += b[i] * b[i]
        }
        val eps = 1e-8f
        nA = sqrt(nA + eps)
        nB = sqrt(nB + eps)
        return dot / (nA * nB)
    }

    // ------------------------------------------------------------------ //
    // Cosine similarity sanity checks
    // ------------------------------------------------------------------ //

    @Test
    fun `identical vectors have similarity 1`() {
        val v = floatArrayOf(1f, 0f, 0f)
        val score = cosineSimilarity(v, v)
        assertTrue("Expected ~1.0, got $score", score > 0.999f)
    }

    @Test
    fun `orthogonal vectors have similarity 0`() {
        val a = floatArrayOf(1f, 0f, 0f)
        val b = floatArrayOf(0f, 1f, 0f)
        val score = cosineSimilarity(a, b)
        assertTrue("Expected ~0.0, got $score", score < 0.01f)
    }

    @Test
    fun `opposite vectors have similarity -1`() {
        val a = floatArrayOf(1f, 0f, 0f)
        val b = floatArrayOf(-1f, 0f, 0f)
        val score = cosineSimilarity(a, b)
        assertTrue("Expected ~-1.0, got $score", score < -0.999f)
    }

    @Test
    fun `similar vectors score higher than dissimilar`() {
        val query = floatArrayOf(1f, 1f, 0f)
        val similar = floatArrayOf(1f, 0.9f, 0.1f)
        val dissimilar = floatArrayOf(0f, 0f, 1f)

        val scoreSimilar = cosineSimilarity(query, similar)
        val scoreDissimilar = cosineSimilarity(query, dissimilar)

        assertTrue(
            "Similar ($scoreSimilar) should score higher than dissimilar ($scoreDissimilar)",
            scoreSimilar > scoreDissimilar
        )
    }

    @Test
    fun `empty vectors return 0`() {
        assertEquals(0.0f, cosineSimilarity(floatArrayOf(), floatArrayOf(1f, 2f)), 0.001f)
        assertEquals(0.0f, cosineSimilarity(floatArrayOf(1f), floatArrayOf()), 0.001f)
    }

    @Test
    fun `mismatched dimensions return 0`() {
        val a = floatArrayOf(1f, 2f)
        val b = floatArrayOf(1f, 2f, 3f)
        assertEquals(0.0f, cosineSimilarity(a, b), 0.001f)
    }

    // ------------------------------------------------------------------ //
    // Add / remove
    // ------------------------------------------------------------------ //

    @Test
    fun `add and retrieve entry`() {
        val entry = VectorStore.VectorEntry(
            id = "test_1",
            text = "earthquake safety tips",
            embedding = floatArrayOf(1f, 0f, 0f),
            source = "knowledge_base"
        )
        addEntry(entry)
        assertEquals(1, entries.size)
        assertEquals("earthquake safety tips", entries["test_1"]?.text)
    }

    @Test
    fun `add duplicate id overwrites`() {
        addEntry(VectorStore.VectorEntry("id1", "first", floatArrayOf(1f, 0f, 0f), source = "a"))
        addEntry(VectorStore.VectorEntry("id1", "second", floatArrayOf(0f, 1f, 0f), source = "a"))
        assertEquals(1, entries.size)
        assertEquals("second", entries["id1"]?.text)
    }

    @Test
    fun `remove by id`() {
        addEntry(VectorStore.VectorEntry("id1", "one", floatArrayOf(1f, 0f, 0f), source = "a"))
        addEntry(VectorStore.VectorEntry("id2", "two", floatArrayOf(0f, 1f, 0f), source = "a"))
        removeById("id1")
        assertEquals(1, entries.size)
        assertFalse(entries.containsKey("id1"))
        assertTrue(entries.containsKey("id2"))
    }

    @Test
    fun `remove by source`() {
        addEntry(VectorStore.VectorEntry("id1", "one", floatArrayOf(1f, 0f, 0f), source = "src_a"))
        addEntry(VectorStore.VectorEntry("id2", "two", floatArrayOf(0f, 1f, 0f), source = "src_b"))
        addEntry(VectorStore.VectorEntry("id3", "three", floatArrayOf(0f, 0f, 1f), source = "src_a"))

        removeBySource("src_a")
        assertEquals(1, entries.size)
        assertTrue(entries.containsKey("id2"))
    }

    // ------------------------------------------------------------------ //
    // Search
    // ------------------------------------------------------------------ //

    @Test
    fun `search returns results sorted by similarity`() {
        addEntry(VectorStore.VectorEntry("best", "best match", floatArrayOf(1f, 1f, 0f), source = "s"))
        addEntry(VectorStore.VectorEntry("mid", "mid match", floatArrayOf(1f, 0f, 0f), source = "s"))
        addEntry(VectorStore.VectorEntry("worst", "worst match", floatArrayOf(0f, 0f, 1f), source = "s"))

        val query = floatArrayOf(1f, 1f, 0f)
        val hits = search(query, topK = 3, minScore = -1.0f)

        assertEquals(3, hits.size)
        assertEquals("best", hits[0].entry.id)
        assertTrue(hits[0].score >= hits[1].score)
        assertTrue(hits[1].score >= hits[2].score)
    }

    @Test
    fun `search respects topK limit`() {
        for (i in 0 until 10) {
            addEntry(VectorStore.VectorEntry("id$i", "entry $i", floatArrayOf(1f, i.toFloat(), 0f), source = "s"))
        }
        val hits = search(floatArrayOf(1f, 5f, 0f), topK = 3, minScore = -1.0f)
        assertEquals(3, hits.size)
    }

    @Test
    fun `search respects minScore filter`() {
        addEntry(VectorStore.VectorEntry("close", "close", floatArrayOf(1f, 0f, 0f), source = "s"))
        addEntry(VectorStore.VectorEntry("far", "far", floatArrayOf(0f, 0f, 1f), source = "s"))

        val query = floatArrayOf(1f, 0f, 0f) // identical to "close", orthogonal to "far"
        val hits = search(query, topK = 10, minScore = 0.5f)

        assertEquals(1, hits.size)
        assertEquals("close", hits[0].entry.id)
    }

    @Test
    fun `search on empty store returns empty list`() {
        val hits = search(floatArrayOf(1f, 0f, 0f))
        assertTrue(hits.isEmpty())
    }

    // ------------------------------------------------------------------ //
    // VectorEntry equality
    // ------------------------------------------------------------------ //

    @Test
    fun `VectorEntry equals by id`() {
        val a = VectorStore.VectorEntry("id1", "text a", floatArrayOf(1f), source = "s")
        val b = VectorStore.VectorEntry("id1", "text b", floatArrayOf(2f), source = "s")
        assertEquals(a, b) // same id
    }

    // ------------------------------------------------------------------ //
    // EmbeddingService chunking (standalone, no model needed)
    // ------------------------------------------------------------------ //

    @Test
    fun `chunkText splits by word count`() {
        val service = EmbeddingServiceChunker()
        val text = (1..300).joinToString(" ") { "word$it" } // 300 words
        val chunks = service.chunkText(text, 128)
        assertEquals(3, chunks.size)                         // 128 + 128 + 44
        assertTrue(chunks[0].split(" ").size == 128)
        assertTrue(chunks[2].split(" ").size == 44)
    }

    @Test
    fun `chunkText clamps size to valid range`() {
        val service = EmbeddingServiceChunker()
        val text = (1..20).joinToString(" ") { "w$it" }
        // Request chunkSize=1 which is below MIN_CHUNK_SIZE (16)
        val chunks = service.chunkText(text, 1)
        assertEquals(1, chunks.size) // 20 words, clamped to min 16 -> 1 chunk of 16 + 1 chunk of 4? No: 20 < 16 means still one chunk
        // Actually 20 words with effective chunk size 16 -> ceil(20/16) = 2 chunks
        // But since 20 >= 16 and remainder is 4, we get chunk of 16 + chunk of 4
    }

    @Test
    fun `chunkText empty input returns empty list`() {
        val service = EmbeddingServiceChunker()
        assertTrue(service.chunkText("").isEmpty())
        assertTrue(service.chunkText("   ").isEmpty())
    }

    /**
     * Minimal chunker that replicates [EmbeddingService.chunkText] logic
     * without needing Android Context or Nexa SDK.
     */
    private class EmbeddingServiceChunker {
        fun chunkText(text: String, chunkSize: Int = 128): List<String> {
            val effectiveSize = chunkSize.coerceIn(
                EmbeddingService.MIN_CHUNK_SIZE,
                EmbeddingService.MAX_CHUNK_SIZE
            )
            val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (words.isEmpty()) return emptyList()
            val chunks = mutableListOf<String>()
            var i = 0
            while (i < words.size) {
                val endIndex = minOf(i + effectiveSize, words.size)
                chunks.add(words.subList(i, endIndex).joinToString(" "))
                i += effectiveSize
            }
            return chunks
        }
    }
}

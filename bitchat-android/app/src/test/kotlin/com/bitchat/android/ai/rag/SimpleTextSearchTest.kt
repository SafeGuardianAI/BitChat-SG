package com.bitchat.android.ai.rag

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for SimpleTextSearch (TF-IDF search engine).
 *
 * SimpleTextSearch operates on the static DisasterKnowledgeBase, so these
 * tests exercise real search behavior against the embedded knowledge entries.
 */
class SimpleTextSearchTest {

    private lateinit var search: SimpleTextSearch

    @Before
    fun setUp() {
        search = SimpleTextSearch()
    }

    @Test
    fun `buildIndex creates index from knowledge base`() {
        search.buildIndex()

        // After building, search should return results for known terms
        val results = search.search("earthquake")
        assertTrue("Index should enable search results", results.isNotEmpty())
    }

    @Test
    fun `search returns results for earthquake queries`() {
        val results = search.search("what to do during an earthquake")
        assertTrue("Should find earthquake-related entries", results.isNotEmpty())

        val hasEarthquakeEntry = results.any { it.entry.category == "earthquake" }
        assertTrue("Results should include earthquake category", hasEarthquakeEntry)
    }

    @Test
    fun `search returns results for flood queries`() {
        val results = search.search("how to survive a flood")
        assertTrue("Should find flood-related entries", results.isNotEmpty())

        val hasFloodEntry = results.any { it.entry.category == "flood" }
        assertTrue("Results should include flood category", hasFloodEntry)
    }

    @Test
    fun `search returns empty for irrelevant queries`() {
        search.buildIndex()
        val results = search.search("pizza recipe with mozzarella cheese")
        assertTrue(
            "Irrelevant queries should return empty or very low-scoring results",
            results.isEmpty() || results.all { it.score < 0.5 }
        )
    }

    @Test
    fun `keyword boost increases relevance score`() {
        search.buildIndex()

        // "earthquake" is an explicit keyword in earthquake entries, so it should get boosted
        val earthquakeResults = search.search("earthquake")
        assertTrue("Keyword match should produce results", earthquakeResults.isNotEmpty())

        // The top result should be from the earthquake category since "earthquake" is a keyword
        val topResult = earthquakeResults[0]
        assertEquals("Top result should be earthquake category", "earthquake", topResult.entry.category)
        assertTrue("Score should be positive from keyword boost", topResult.score > 0.0)
    }

    @Test
    fun `stop words are filtered during tokenization`() {
        search.buildIndex()

        // "the" and "and" are stop words; a query of only stop words should return nothing
        val results = search.search("the and but not")
        assertTrue("Query of only stop words should return empty results", results.isEmpty())
    }

    @Test
    fun `results are ranked by relevance score descending`() {
        val results = search.search("earthquake safety preparedness")
        if (results.size > 1) {
            for (i in 0 until results.size - 1) {
                assertTrue(
                    "Results should be sorted by score descending",
                    results[i].score >= results[i + 1].score
                )
            }
        }
    }

    @Test
    fun `maxResults parameter limits output count`() {
        val results1 = search.search("earthquake", maxResults = 1)
        assertTrue("maxResults=1 should return at most 1 result", results1.size <= 1)

        val results2 = search.search("earthquake", maxResults = 2)
        assertTrue("maxResults=2 should return at most 2 results", results2.size <= 2)

        val resultsAll = search.search("earthquake", maxResults = 100)
        assertTrue("More results available when maxResults is large", resultsAll.size >= results1.size)
    }

    @Test
    fun `isDisasterRelated returns true for disaster queries`() {
        assertTrue("'earthquake safety' should be disaster-related", search.isDisasterRelated("earthquake safety"))
        assertTrue("'flood evacuation' should be disaster-related", search.isDisasterRelated("flood evacuation"))
        assertTrue("'fire escape plan' should be disaster-related", search.isDisasterRelated("fire escape plan"))
        assertTrue("'CPR instructions' should be disaster-related", search.isDisasterRelated("CPR instructions first aid"))
    }

    @Test
    fun `isDisasterRelated returns false for non-disaster queries`() {
        assertFalse("'pizza recipe' should not be disaster-related", search.isDisasterRelated("pizza recipe"))
        assertFalse("'stock market today' should not be disaster-related", search.isDisasterRelated("stock market today"))
    }

    @Test
    fun `search auto-builds index if not already built`() {
        // Don't call buildIndex() - search should auto-build
        val results = search.search("tornado")
        assertTrue("Search should auto-build index and return results", results.isNotEmpty())
    }

    @Test
    fun `search results contain matched terms`() {
        val results = search.search("earthquake shelter")
        assertTrue("Should have results", results.isNotEmpty())

        val topResult = results[0]
        assertTrue("Matched terms should not be empty", topResult.matchedTerms.isNotEmpty())
    }

    @Test
    fun `search handles empty query gracefully`() {
        search.buildIndex()
        val results = search.search("")
        assertTrue("Empty query should return empty results", results.isEmpty())
    }
}

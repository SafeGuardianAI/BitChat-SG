package com.bitchat.android.ai.rag

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for DisasterKnowledgeBase.
 *
 * DisasterKnowledgeBase is a singleton object with static embedded data,
 * so no mocking is needed.
 */
class DisasterKnowledgeBaseTest {

    @Test
    fun `entries is not empty`() {
        assertTrue(
            "Knowledge base should contain entries",
            DisasterKnowledgeBase.entries.isNotEmpty()
        )
    }

    @Test
    fun `all entries have non-blank id`() {
        for (entry in DisasterKnowledgeBase.entries) {
            assertTrue(
                "Entry id should not be blank: ${entry.title}",
                entry.id.isNotBlank()
            )
        }
    }

    @Test
    fun `all entries have non-blank title`() {
        for (entry in DisasterKnowledgeBase.entries) {
            assertTrue(
                "Entry title should not be blank for id=${entry.id}",
                entry.title.isNotBlank()
            )
        }
    }

    @Test
    fun `all entries have non-blank content`() {
        for (entry in DisasterKnowledgeBase.entries) {
            assertTrue(
                "Entry content should not be blank for id=${entry.id}",
                entry.content.isNotBlank()
            )
        }
    }

    @Test
    fun `all entries have non-blank category`() {
        for (entry in DisasterKnowledgeBase.entries) {
            assertTrue(
                "Entry category should not be blank for id=${entry.id}",
                entry.category.isNotBlank()
            )
        }
    }

    @Test
    fun `all entries have at least one keyword`() {
        for (entry in DisasterKnowledgeBase.entries) {
            assertTrue(
                "Entry should have at least one keyword for id=${entry.id}",
                entry.keywords.isNotEmpty()
            )
        }
    }

    @Test
    fun `getById returns correct entry`() {
        val entry = DisasterKnowledgeBase.getById("eq_during")
        assertNotNull("Should find entry with id 'eq_during'", entry)
        assertEquals("earthquake", entry!!.category)
        assertEquals("During an Earthquake", entry.title)
    }

    @Test
    fun `getById returns null for unknown id`() {
        val entry = DisasterKnowledgeBase.getById("nonexistent_id_xyz")
        assertNull("Should return null for unknown id", entry)
    }

    @Test
    fun `getByCategory returns entries matching category`() {
        val earthquakeEntries = DisasterKnowledgeBase.getByCategory("earthquake")
        assertTrue("Should have earthquake entries", earthquakeEntries.isNotEmpty())
        assertTrue(
            "All returned entries should be earthquake category",
            earthquakeEntries.all { it.category == "earthquake" }
        )
    }

    @Test
    fun `getByCategory returns empty list for unknown category`() {
        val entries = DisasterKnowledgeBase.getByCategory("nonexistent_category")
        assertTrue("Should return empty for unknown category", entries.isEmpty())
    }

    @Test
    fun `getCategories returns at least 5 categories`() {
        val categories = DisasterKnowledgeBase.getCategories()
        assertTrue(
            "Should have at least 5 categories, found ${categories.size}: $categories",
            categories.size >= 5
        )
    }

    @Test
    fun `getCategories includes expected categories`() {
        val categories = DisasterKnowledgeBase.getCategories()
        assertTrue("Should include earthquake", categories.contains("earthquake"))
        assertTrue("Should include flood", categories.contains("flood"))
        assertTrue("Should include fire", categories.contains("fire"))
        assertTrue("Should include first_aid", categories.contains("first_aid"))
        assertTrue("Should include shelter", categories.contains("shelter"))
    }

    @Test
    fun `all entry IDs are unique`() {
        val ids = DisasterKnowledgeBase.entries.map { it.id }
        assertEquals(
            "All entry IDs should be unique",
            ids.size,
            ids.toSet().size
        )
    }

    @Test
    fun `earthquake category has multiple entries`() {
        val entries = DisasterKnowledgeBase.getByCategory("earthquake")
        assertTrue(
            "Earthquake should have at least 2 entries (during, after, prepare)",
            entries.size >= 2
        )
    }

    @Test
    fun `first_aid category has multiple entries`() {
        val entries = DisasterKnowledgeBase.getByCategory("first_aid")
        assertTrue(
            "First aid should have at least 2 entries",
            entries.size >= 2
        )
    }
}

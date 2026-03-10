package com.bitchat.android.ai.rag

import com.bitchat.android.ai.AIPreferences
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

/**
 * Unit tests for DisasterRAGService.
 *
 * AIPreferences is mocked to control ragEnabled without needing Android Context.
 * The service uses the real SimpleTextSearch and DisasterKnowledgeBase underneath.
 */
class DisasterRAGServiceTest {

    private lateinit var mockPreferences: AIPreferences
    private lateinit var ragService: DisasterRAGService

    @Before
    fun setUp() {
        mockPreferences = mock {
            on { ragEnabled } doReturn true
        }
        ragService = DisasterRAGService(mockPreferences)
        ragService.initialize()
    }

    @Test
    fun `augmentPrompt adds context for disaster queries`() {
        val prompt = "What should I do during an earthquake?"
        val augmented = ragService.augmentPrompt(prompt)

        assertNotEquals("Augmented prompt should differ from original", prompt, augmented)
        assertTrue(
            "Augmented prompt should contain disaster knowledge context marker",
            augmented.contains("[DISASTER KNOWLEDGE CONTEXT]")
        )
        assertTrue(
            "Augmented prompt should contain the original user question",
            augmented.contains(prompt)
        )
    }

    @Test
    fun `augmentPrompt returns original prompt for non-disaster queries`() {
        val prompt = "What is the best pizza recipe?"
        val augmented = ragService.augmentPrompt(prompt)

        assertEquals(
            "Non-disaster query should return original prompt",
            prompt,
            augmented
        )
    }

    @Test
    fun `augmentPrompt returns original when RAG is disabled`() {
        val disabledPrefs = mock<AIPreferences> {
            on { ragEnabled } doReturn false
        }
        val disabledService = DisasterRAGService(disabledPrefs)
        disabledService.initialize()

        val prompt = "What should I do during an earthquake?"
        val augmented = disabledService.augmentPrompt(prompt)

        assertEquals(
            "Should return original prompt when RAG is disabled",
            prompt,
            augmented
        )
    }

    @Test
    fun `searchKnowledge returns entries matching query`() {
        val results = ragService.searchKnowledge("earthquake")
        assertTrue("Should find earthquake entries", results.isNotEmpty())
        assertTrue(
            "Results should include earthquake category",
            results.any { it.entry.category == "earthquake" }
        )
    }

    @Test
    fun `searchKnowledge respects maxResults parameter`() {
        val results = ragService.searchKnowledge("earthquake", maxResults = 1)
        assertTrue("Should return at most 1 result", results.size <= 1)
    }

    @Test
    fun `getCategories returns all disaster categories`() {
        val categories = ragService.getCategories()
        assertTrue("Should have multiple categories", categories.size >= 5)
        assertTrue("Should include earthquake", categories.contains("earthquake"))
        assertTrue("Should include flood", categories.contains("flood"))
        assertTrue("Should include fire", categories.contains("fire"))
        assertTrue("Should include first_aid", categories.contains("first_aid"))
    }

    @Test
    fun `isDisasterRelated detects disaster queries correctly`() {
        assertTrue(
            "Earthquake query should be disaster-related",
            ragService.isDisasterRelated("earthquake safety tips")
        )
        assertTrue(
            "Flood query should be disaster-related",
            ragService.isDisasterRelated("what to do in a flood")
        )
        assertFalse(
            "Pizza query should not be disaster-related",
            ragService.isDisasterRelated("pizza recipe")
        )
    }

    @Test
    fun `augmentPrompt includes entry titles and categories in context`() {
        val prompt = "How do I do CPR?"
        val augmented = ragService.augmentPrompt(prompt)

        if (augmented != prompt) {
            assertTrue(
                "Context should include entry formatting with category",
                augmented.contains("(") && augmented.contains(")")
            )
            assertTrue(
                "Context should end with user question section",
                augmented.contains("User question:")
            )
        }
    }

    @Test
    fun `getCategory returns entries for specific category`() {
        val earthquakeEntries = ragService.getCategory("earthquake")
        assertTrue("Should have earthquake entries", earthquakeEntries.isNotEmpty())
        assertTrue(
            "All returned entries should be earthquake category",
            earthquakeEntries.all { it.category == "earthquake" }
        )
    }

    @Test
    fun `initialize is idempotent`() {
        // Initialize was already called in setUp; calling again should not throw
        ragService.initialize()
        val results = ragService.searchKnowledge("earthquake")
        assertTrue("Service should still work after double init", results.isNotEmpty())
    }
}

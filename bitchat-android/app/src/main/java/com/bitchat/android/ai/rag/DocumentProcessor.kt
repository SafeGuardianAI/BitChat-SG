package com.bitchat.android.ai.rag

import android.util.Log
import java.io.File
import java.util.UUID

/**
 * Document ingestion pipeline for the RAG system.
 *
 * Takes raw text (files, knowledge-base entries, user notes, mesh
 * messages), chunks it using the cookbook word-count strategy, generates
 * embeddings via [EmbeddingService], and stores the resulting vectors
 * in [VectorStore].
 *
 * Supported content types:
 *   - Plain text strings (notes, mesh messages)
 *   - Text files on disk (.txt, .md, etc.)
 *   - The built-in [DisasterKnowledgeBase]
 */
class DocumentProcessor(
    private val embeddingService: EmbeddingService,
    private val vectorStore: VectorStore
) {

    companion object {
        private const val TAG = "DocumentProcessor"
        const val SOURCE_KNOWLEDGE_BASE = "knowledge_base"
    }

    // ------------------------------------------------------------------ //
    // Public API
    // ------------------------------------------------------------------ //

    /**
     * Index a text document.
     *
     * The text is chunked, each chunk is embedded, and the resulting
     * vector entries are stored.
     *
     * @param text     Raw text content.
     * @param source   An identifier for the source (e.g. file path, "user_note").
     * @param metadata Optional key-value pairs attached to every chunk.
     * @param chunkSize Words per chunk (default from [EmbeddingService]).
     * @return The number of chunks successfully indexed.
     */
    suspend fun indexDocument(
        text: String,
        source: String,
        metadata: Map<String, String> = emptyMap(),
        chunkSize: Int = EmbeddingService.DEFAULT_CHUNK_SIZE
    ): Int {
        if (text.isBlank()) return 0
        if (!embeddingService.isReady()) {
            Log.w(TAG, "EmbeddingService not ready -- cannot index document")
            return 0
        }

        // Remove existing chunks for this source so re-indexing is clean
        vectorStore.removeBySource(source)

        val chunks = embeddingService.chunkText(text, chunkSize)
        var indexed = 0

        chunks.forEachIndexed { index, chunkText ->
            val embedding = embeddingService.embed(chunkText)
            if (embedding != null) {
                val entry = VectorStore.VectorEntry(
                    id = "${source}_chunk_$index",
                    text = chunkText,
                    embedding = embedding,
                    metadata = metadata,
                    source = source,
                    chunkIndex = index
                )
                vectorStore.add(entry)
                indexed++
            } else {
                Log.w(TAG, "Failed to embed chunk $index of source=$source")
            }
        }

        Log.d(TAG, "Indexed $indexed/${chunks.size} chunks from source=$source")
        return indexed
    }

    /**
     * Index a text file from disk.
     *
     * The file is read in its entirety and then passed through [indexDocument].
     *
     * @param filePath Absolute path to the text file.
     * @return Number of chunks indexed, or 0 on failure.
     */
    suspend fun indexFile(filePath: String): Int {
        val file = File(filePath)
        if (!file.exists() || !file.isFile) {
            Log.w(TAG, "File does not exist or is not a file: $filePath")
            return 0
        }
        return try {
            val text = file.readText()
            val metadata = mapOf(
                "file_name" to file.name,
                "file_path" to file.absolutePath,
                "file_size" to file.length().toString()
            )
            indexDocument(text, source = filePath, metadata = metadata)
        } catch (e: Exception) {
            Log.e(TAG, "Error reading file $filePath", e)
            0
        }
    }

    /**
     * Index the built-in [DisasterKnowledgeBase].
     *
     * Each knowledge entry is treated as a small document.  Short entries
     * (under one chunk) are embedded as-is; longer ones are chunked
     * normally.
     *
     * @return Total number of chunks indexed.
     */
    suspend fun indexKnowledgeBase(): Int {
        if (!embeddingService.isReady()) {
            Log.w(TAG, "EmbeddingService not ready -- cannot index knowledge base")
            return 0
        }

        // Remove any previous KB vectors
        vectorStore.removeBySource(SOURCE_KNOWLEDGE_BASE)

        var totalIndexed = 0

        for (entry in DisasterKnowledgeBase.entries) {
            // Combine title + content for embedding
            val fullText = "${entry.title}. ${entry.content}"
            val chunks = embeddingService.chunkText(fullText)

            chunks.forEachIndexed { index, chunkText ->
                val embedding = embeddingService.embed(chunkText)
                if (embedding != null) {
                    val vectorEntry = VectorStore.VectorEntry(
                        id = "${SOURCE_KNOWLEDGE_BASE}_${entry.id}_$index",
                        text = chunkText,
                        embedding = embedding,
                        metadata = mapOf(
                            "category" to entry.category,
                            "title" to entry.title,
                            "entry_id" to entry.id
                        ),
                        source = SOURCE_KNOWLEDGE_BASE,
                        chunkIndex = index
                    )
                    vectorStore.add(vectorEntry)
                    totalIndexed++
                }
            }
        }

        Log.d(TAG, "Indexed $totalIndexed chunks from DisasterKnowledgeBase (${DisasterKnowledgeBase.entries.size} entries)")
        return totalIndexed
    }

    /**
     * Re-index everything: knowledge base + all previously known file sources.
     *
     * @return Total number of chunks indexed.
     */
    suspend fun reindex(): Int {
        val previousSources = vectorStore.sources().filter { it != SOURCE_KNOWLEDGE_BASE }
        vectorStore.clear()

        var total = indexKnowledgeBase()

        for (source in previousSources) {
            val file = File(source)
            if (file.exists() && file.isFile) {
                total += indexFile(source)
            }
        }

        vectorStore.save()
        Log.d(TAG, "Reindex complete: $total total chunks")
        return total
    }

    /**
     * Remove all chunks belonging to [source].
     */
    fun removeDocument(source: String) {
        vectorStore.removeBySource(source)
        Log.d(TAG, "Removed document: $source")
    }
}

package com.bitchat.android.sync

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.junit.MockitoJUnitRunner

@RunWith(MockitoJUnitRunner::class)
class OfflineSyncQueueTest {

    @Mock
    private lateinit var mockContext: Context

    @Mock
    private lateinit var mockPrefs: SharedPreferences

    @Mock
    private lateinit var mockEditor: SharedPreferences.Editor

    private lateinit var queue: OfflineSyncQueue

    @Before
    fun setUp() {
        `when`(mockContext.getSharedPreferences(anyString(), anyInt())).thenReturn(mockPrefs)
        `when`(mockPrefs.getString(anyString(), anyString())).thenReturn(null)
        `when`(mockPrefs.edit()).thenReturn(mockEditor)
        `when`(mockEditor.putString(anyString(), anyString())).thenReturn(mockEditor)
        `when`(mockEditor.apply()).then { /* no-op */ }

        queue = OfflineSyncQueue(mockContext)
    }

    private fun createOperation(
        id: String = "op-${System.nanoTime()}",
        collection: String = "victims",
        documentId: String = "doc-1",
        type: OperationType = OperationType.CREATE,
        payload: String = """{"name":"test"}""",
        backend: BackendType = BackendType.BOTH
    ): SyncOperation {
        return SyncOperation(
            id = id,
            type = type,
            collection = collection,
            documentId = documentId,
            payload = payload,
            backend = backend
        )
    }

    @Test
    fun `enqueue adds operation to queue`() {
        val op = createOperation(id = "op-1")
        assertTrue(queue.enqueue(op))
        assertEquals(1, queue.size())
    }

    @Test
    fun `enqueue multiple operations increases size`() {
        queue.enqueue(createOperation(id = "op-1"))
        queue.enqueue(createOperation(id = "op-2"))
        queue.enqueue(createOperation(id = "op-3"))
        assertEquals(3, queue.size())
    }

    @Test
    fun `dequeue returns first pending operation`() {
        val op1 = createOperation(id = "op-1")
        val op2 = createOperation(id = "op-2")
        queue.enqueue(op1)
        queue.enqueue(op2)

        val dequeued = queue.dequeue()
        assertNotNull(dequeued)
        assertEquals("op-1", dequeued!!.id)
    }

    @Test
    fun `dequeue returns null when queue is empty`() {
        assertNull(queue.dequeue())
    }

    @Test
    fun `dequeue marks operation as IN_PROGRESS`() {
        queue.enqueue(createOperation(id = "op-1"))
        queue.dequeue()

        // After dequeue, op is IN_PROGRESS, so next dequeue returns null (no PENDING)
        assertNull(queue.dequeue())
        assertEquals(1, queue.size()) // Still in queue but not pending
    }

    @Test
    fun `peek returns first pending without removing`() {
        val op = createOperation(id = "op-1")
        queue.enqueue(op)

        val peeked = queue.peek()
        assertNotNull(peeked)
        assertEquals("op-1", peeked!!.id)

        // Peek again - should still be there
        assertNotNull(queue.peek())
        assertEquals(1, queue.size())
    }

    @Test
    fun `peek returns null when no pending operations`() {
        assertNull(queue.peek())
    }

    @Test
    fun `markSynced updates operation status`() {
        queue.enqueue(createOperation(id = "op-1"))
        queue.markSynced("op-1")

        val all = queue.getAll()
        assertEquals(1, all.size)
        assertEquals(SyncStatus.SYNCED, all[0].status)
        assertNotNull(all[0].syncedAt)
    }

    @Test
    fun `markFailed increments retry count and returns to PENDING`() {
        queue.enqueue(createOperation(id = "op-1"))
        queue.markFailed("op-1", "Network error")

        val all = queue.getAll()
        assertEquals(1, all.size)
        assertEquals(SyncStatus.PENDING, all[0].status) // Back to PENDING for retry
        assertEquals(1, all[0].retryCount)
        assertEquals("Network error", all[0].lastError)
    }

    @Test
    fun `markFailed sets FAILED when max retries exceeded`() {
        val op = createOperation(id = "op-1").copy(maxRetries = 2)
        queue.enqueue(op)

        queue.markFailed("op-1", "Error 1")  // retry 1 -> back to PENDING
        queue.markFailed("op-1", "Error 2")  // retry 2 >= maxRetries -> FAILED

        val all = queue.getAll()
        assertEquals(SyncStatus.FAILED, all[0].status)
        assertEquals(2, all[0].retryCount)
    }

    @Test
    fun `getPending returns only PENDING operations`() {
        queue.enqueue(createOperation(id = "op-1"))
        queue.enqueue(createOperation(id = "op-2"))
        queue.enqueue(createOperation(id = "op-3"))

        queue.markSynced("op-1")
        queue.markFailed("op-3", "error") // Goes back to PENDING (retry < max)

        val pending = queue.getPending()
        assertEquals(2, pending.size)
        assertTrue(pending.any { it.id == "op-2" })
        assertTrue(pending.any { it.id == "op-3" })
    }

    @Test
    fun `getConflicts returns only CONFLICT operations`() {
        queue.enqueue(createOperation(id = "op-1"))
        queue.enqueue(createOperation(id = "op-2"))

        queue.markConflict("op-1", "Version mismatch")

        val conflicts = queue.getConflicts()
        assertEquals(1, conflicts.size)
        assertEquals("op-1", conflicts[0].id)
    }

    @Test
    fun `clear removes all operations`() {
        queue.enqueue(createOperation(id = "op-1"))
        queue.enqueue(createOperation(id = "op-2"))
        assertEquals(2, queue.size())

        queue.clear()
        assertEquals(0, queue.size())
    }

    @Test
    fun `clearSynced removes only synced operations`() {
        queue.enqueue(createOperation(id = "op-1"))
        queue.enqueue(createOperation(id = "op-2"))
        queue.enqueue(createOperation(id = "op-3"))

        queue.markSynced("op-1")
        queue.markSynced("op-3")

        queue.clearSynced()

        assertEquals(1, queue.size())
        assertEquals("op-2", queue.getAll()[0].id)
    }

    @Test
    fun `pendingSize counts only PENDING operations`() {
        queue.enqueue(createOperation(id = "op-1"))
        queue.enqueue(createOperation(id = "op-2"))
        queue.enqueue(createOperation(id = "op-3"))

        queue.markSynced("op-1")

        assertEquals(2, queue.pendingSize())
    }

    @Test
    fun `markSynced with unknown id does nothing`() {
        queue.enqueue(createOperation(id = "op-1"))
        queue.markSynced("nonexistent")

        assertEquals(SyncStatus.PENDING, queue.getAll()[0].status)
    }

    @Test
    fun `markFailed with unknown id does nothing`() {
        queue.enqueue(createOperation(id = "op-1"))
        queue.markFailed("nonexistent", "error")

        assertEquals(SyncStatus.PENDING, queue.getAll()[0].status)
        assertEquals(0, queue.getAll()[0].retryCount)
    }

    @Test
    fun `operations maintain FIFO order`() {
        queue.enqueue(createOperation(id = "op-1", documentId = "doc-a"))
        queue.enqueue(createOperation(id = "op-2", documentId = "doc-b"))
        queue.enqueue(createOperation(id = "op-3", documentId = "doc-c"))

        val first = queue.dequeue()
        assertEquals("op-1", first!!.id)

        val second = queue.dequeue()
        assertEquals("op-2", second!!.id)

        val third = queue.dequeue()
        assertEquals("op-3", third!!.id)
    }

    @Test
    fun `update modifies operation in place`() {
        queue.enqueue(createOperation(id = "op-1", collection = "victims"))

        queue.update("op-1") { it.copy(collection = "messages") }

        val all = queue.getAll()
        assertEquals("messages", all[0].collection)
    }
}

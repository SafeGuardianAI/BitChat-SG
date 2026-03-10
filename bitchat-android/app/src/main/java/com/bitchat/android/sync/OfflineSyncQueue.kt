package com.bitchat.android.sync

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Local queue for pending sync operations.
 * Persisted in SharedPreferences (use EncryptedSharedPreferences in production).
 * Thread-safe via synchronized blocks.
 */
class OfflineSyncQueue(context: Context) {

    companion object {
        private const val TAG = "OfflineSyncQueue"
        private const val PREFS_NAME = "offline_sync_queue_prefs"
        private const val KEY_QUEUE = "offline_sync_queue_v1"
        private const val MAX_QUEUE_SIZE = 1000
    }

    private val gson = Gson()
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val operationsLock = Any()
    private var operations: MutableList<SyncOperation> = loadFromDisk()

    /**
     * Add an operation to the end of the queue.
     * If the queue is full, the oldest SYNCED or FAILED operations are evicted first.
     * Returns true if enqueued successfully.
     */
    fun enqueue(operation: SyncOperation): Boolean {
        synchronized(operationsLock) {
            if (operations.size >= MAX_QUEUE_SIZE) {
                // Evict oldest synced operations first, then failed
                val evicted = operations.firstOrNull { it.status == SyncStatus.SYNCED }
                    ?: operations.firstOrNull { it.status == SyncStatus.FAILED }

                if (evicted != null) {
                    operations.remove(evicted)
                    Log.d(TAG, "Evicted operation ${evicted.id} (${evicted.status}) to make room")
                } else {
                    Log.w(TAG, "Queue is full ($MAX_QUEUE_SIZE) and no evictable operations")
                    return false
                }
            }

            operations.add(operation)
            saveToDisk()
            Log.d(TAG, "Enqueued operation ${operation.id} for ${operation.collection}/${operation.documentId}")
            return true
        }
    }

    /**
     * Remove and return the first PENDING operation from the queue.
     */
    fun dequeue(): SyncOperation? {
        synchronized(operationsLock) {
            val index = operations.indexOfFirst { it.status == SyncStatus.PENDING }
            if (index == -1) return null

            val op = operations[index]
            // Mark as in-progress rather than removing
            operations[index] = op.copy(status = SyncStatus.IN_PROGRESS)
            saveToDisk()
            return op
        }
    }

    /**
     * Peek at the first PENDING operation without removing it.
     */
    fun peek(): SyncOperation? {
        synchronized(operationsLock) {
            return operations.firstOrNull { it.status == SyncStatus.PENDING }
        }
    }

    /**
     * Mark an operation as successfully synced.
     */
    fun markSynced(operationId: String) {
        synchronized(operationsLock) {
            val index = operations.indexOfFirst { it.id == operationId }
            if (index == -1) {
                Log.w(TAG, "Operation $operationId not found in queue")
                return
            }

            operations[index] = operations[index].copy(
                status = SyncStatus.SYNCED,
                syncedAt = System.currentTimeMillis()
            )
            saveToDisk()
            Log.d(TAG, "Marked operation $operationId as SYNCED")
        }
    }

    /**
     * Mark an operation as failed and increment retry count.
     * If max retries exceeded, status stays FAILED permanently.
     * Otherwise, status returns to PENDING for retry.
     */
    fun markFailed(operationId: String, error: String) {
        synchronized(operationsLock) {
            val index = operations.indexOfFirst { it.id == operationId }
            if (index == -1) {
                Log.w(TAG, "Operation $operationId not found in queue")
                return
            }

            val op = operations[index]
            val newRetryCount = op.retryCount + 1
            val newStatus = if (newRetryCount >= op.maxRetries) {
                SyncStatus.FAILED
            } else {
                SyncStatus.PENDING // Return to pending for retry
            }

            operations[index] = op.copy(
                status = newStatus,
                retryCount = newRetryCount,
                lastError = error
            )
            saveToDisk()
            Log.d(TAG, "Marked operation $operationId as $newStatus (retry $newRetryCount/${op.maxRetries}): $error")
        }
    }

    /**
     * Mark an operation as having a conflict.
     */
    fun markConflict(operationId: String, error: String) {
        synchronized(operationsLock) {
            val index = operations.indexOfFirst { it.id == operationId }
            if (index == -1) return

            operations[index] = operations[index].copy(
                status = SyncStatus.CONFLICT,
                lastError = error
            )
            saveToDisk()
        }
    }

    /**
     * Get all PENDING operations in queue order.
     */
    fun getPending(): List<SyncOperation> {
        synchronized(operationsLock) {
            return operations.filter { it.status == SyncStatus.PENDING }.toList()
        }
    }

    /**
     * Get all operations with CONFLICT status.
     */
    fun getConflicts(): List<SyncOperation> {
        synchronized(operationsLock) {
            return operations.filter { it.status == SyncStatus.CONFLICT }.toList()
        }
    }

    /**
     * Remove all synced and failed operations from the queue.
     */
    fun clear() {
        synchronized(operationsLock) {
            operations.clear()
            saveToDisk()
            Log.d(TAG, "Queue cleared")
        }
    }

    /**
     * Remove only synced operations (cleanup).
     */
    fun clearSynced() {
        synchronized(operationsLock) {
            operations.removeAll { it.status == SyncStatus.SYNCED }
            saveToDisk()
        }
    }

    /**
     * Total number of operations in the queue (all statuses).
     */
    fun size(): Int {
        synchronized(operationsLock) {
            return operations.size
        }
    }

    /**
     * Number of PENDING operations.
     */
    fun pendingSize(): Int {
        synchronized(operationsLock) {
            return operations.count { it.status == SyncStatus.PENDING }
        }
    }

    /**
     * Get all operations (for debugging/testing).
     */
    fun getAll(): List<SyncOperation> {
        synchronized(operationsLock) {
            return operations.toList()
        }
    }

    /**
     * Update an operation in-place.
     */
    fun update(operationId: String, transform: (SyncOperation) -> SyncOperation) {
        synchronized(operationsLock) {
            val index = operations.indexOfFirst { it.id == operationId }
            if (index != -1) {
                operations[index] = transform(operations[index])
                saveToDisk()
            }
        }
    }

    // ---- Persistence ----

    private fun loadFromDisk(): MutableList<SyncOperation> {
        val json = prefs.getString(KEY_QUEUE, null) ?: return mutableListOf()
        return try {
            val type = object : TypeToken<MutableList<SyncOperation>>() {}.type
            gson.fromJson(json, type) ?: mutableListOf()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load queue from disk", e)
            mutableListOf()
        }
    }

    private fun saveToDisk() {
        try {
            val json = gson.toJson(operations)
            prefs.edit().putString(KEY_QUEUE, json).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save queue to disk", e)
        }
    }
}

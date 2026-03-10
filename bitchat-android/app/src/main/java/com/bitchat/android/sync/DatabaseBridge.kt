package com.bitchat.android.sync

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/**
 * High-level API that ties the offline-first sync system together.
 * This is the main entry point for the app to interact with the sync system.
 *
 * Usage:
 * ```
 * val db = DatabaseBridge.getInstance(context)
 * db.setMongoDBEndpoint("https://api.example.com/v1")
 * db.setFirebaseEndpoint("https://myproject.firebaseio.com")
 *
 * // Store data (queues locally, syncs when online)
 * db.store("victims", "victim-123", """{"name":"John","status":"found"}""")
 *
 * // Read data (local cache first)
 * val data = db.read("victims", "victim-123")
 *
 * // Force sync
 * db.syncNow()
 * ```
 */
class DatabaseBridge private constructor(context: Context) {

    companion object {
        private const val TAG = "DatabaseBridge"
        private const val LOCAL_CACHE_PREFIX = "local_cache_"

        @Volatile
        private var INSTANCE: DatabaseBridge? = null

        fun getInstance(context: Context): DatabaseBridge {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DatabaseBridge(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }

        /**
         * Reset the singleton instance (for testing only).
         */
        internal fun resetInstance() {
            INSTANCE = null
        }
    }

    private val appContext: Context = context.applicationContext
    private val queue = OfflineSyncQueue(appContext)
    private val mongoDBClient = MongoDBClient()
    private val firebaseClient = FirebaseClient()
    private val syncEngine = SyncEngine(appContext, queue, mongoDBClient, firebaseClient)
    private val localCache = LocalCache(appContext)

    init {
        // Start auto-sync by default
        syncEngine.startAutoSync()
        Log.d(TAG, "DatabaseBridge initialized with auto-sync enabled")
    }

    /**
     * Store data locally and queue for sync to remote backends.
     *
     * @param collection The collection name (e.g., "victims", "messages", "vitals").
     * @param documentId The document ID. If empty, a UUID will be generated.
     * @param data The JSON data to store.
     * @param backend Which backend(s) to sync to.
     * @return The document ID.
     */
    suspend fun store(
        collection: String,
        documentId: String = UUID.randomUUID().toString(),
        data: String,
        backend: BackendType = BackendType.BOTH
    ): String {
        // 1. Save to local cache immediately
        localCache.put(collection, documentId, data)

        // 2. Queue sync operation
        val operation = SyncOperation(
            type = OperationType.UPSERT,
            collection = collection,
            documentId = documentId,
            payload = data,
            backend = backend
        )
        queue.enqueue(operation)

        Log.d(TAG, "Stored $collection/$documentId locally, queued for sync to $backend")

        // 3. If online, trigger immediate sync attempt
        if (syncEngine.isOnline()) {
            try {
                syncEngine.syncOperation(operation)
            } catch (e: Exception) {
                Log.d(TAG, "Immediate sync failed, will retry later: ${e.message}")
            }
        }

        return documentId
    }

    /**
     * Read data. Returns local cache first, falls back to remote if online and not cached.
     *
     * @param collection The collection name.
     * @param documentId The document ID.
     * @return The JSON data, or null if not found.
     */
    suspend fun read(collection: String, documentId: String): String? {
        // 1. Check local cache first
        val cached = localCache.get(collection, documentId)
        if (cached != null) {
            Log.d(TAG, "Read $collection/$documentId from local cache")
            return cached
        }

        // 2. If online, try remote
        if (syncEngine.isOnline()) {
            Log.d(TAG, "Cache miss for $collection/$documentId, trying remote")
            try {
                val result = mongoDBClient.read(collection, documentId)
                if (result.isSuccess) {
                    val data = result.getOrNull()
                    if (data != null && data != "null") {
                        localCache.put(collection, documentId, data)
                        return data
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Remote read failed: ${e.message}")
            }
        }

        Log.d(TAG, "No data found for $collection/$documentId")
        return null
    }

    /**
     * Delete data locally and queue remote deletion.
     *
     * @param collection The collection name.
     * @param documentId The document ID.
     * @param backend Which backend(s) to delete from.
     */
    suspend fun delete(
        collection: String,
        documentId: String,
        backend: BackendType = BackendType.BOTH
    ) {
        // 1. Remove from local cache
        localCache.remove(collection, documentId)

        // 2. Queue delete operation
        val operation = SyncOperation(
            type = OperationType.DELETE,
            collection = collection,
            documentId = documentId,
            payload = "",
            backend = backend
        )
        queue.enqueue(operation)

        Log.d(TAG, "Deleted $collection/$documentId locally, queued remote delete for $backend")
    }

    /**
     * Force an immediate sync of all pending operations.
     *
     * @return The final sync state.
     */
    suspend fun syncNow(): SyncState {
        Log.d(TAG, "Force sync requested (${queue.pendingSize()} pending)")
        syncEngine.sync()
        return syncEngine.syncState.value
    }

    /**
     * Get the current sync state as a StateFlow.
     */
    fun getSyncState(): StateFlow<SyncState> = syncEngine.syncState

    /**
     * Get the number of pending (unsynced) operations.
     */
    fun getPendingCount(): Int = queue.pendingSize()

    /**
     * Get total queue size (all statuses).
     */
    fun getQueueSize(): Int = queue.size()

    /**
     * Configure the MongoDB REST API endpoint.
     */
    fun setMongoDBEndpoint(url: String) {
        mongoDBClient.baseUrl = url
        Log.d(TAG, "MongoDB endpoint set to: $url")
    }

    /**
     * Configure the Firebase Realtime Database URL.
     */
    fun setFirebaseEndpoint(url: String) {
        firebaseClient.baseUrl = url
        Log.d(TAG, "Firebase endpoint set to: $url")
    }

    /**
     * Set MongoDB auth token.
     */
    fun setMongoDBAuthToken(token: String) {
        mongoDBClient.authToken = token
    }

    /**
     * Set Firebase auth token.
     */
    fun setFirebaseAuthToken(token: String) {
        firebaseClient.authToken = token
    }

    /**
     * Enable or disable auto-sync.
     */
    fun setAutoSync(enabled: Boolean, intervalMs: Long = 30_000L) {
        if (enabled) {
            syncEngine.startAutoSync(intervalMs)
        } else {
            syncEngine.stopAutoSync()
        }
    }

    /**
     * Clean up synced operations from the queue.
     */
    fun cleanupQueue() {
        queue.clearSynced()
    }

    /**
     * Release resources. Call when the app is shutting down.
     */
    fun destroy() {
        syncEngine.destroy()
        Log.d(TAG, "DatabaseBridge destroyed")
    }
}

/**
 * Simple local cache backed by SharedPreferences.
 * Provides immediate read/write for offline-first access.
 */
internal class LocalCache(context: Context) {

    companion object {
        private const val PREFS_NAME = "database_bridge_local_cache"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun put(collection: String, documentId: String, data: String) {
        prefs.edit().putString(cacheKey(collection, documentId), data).apply()
    }

    fun get(collection: String, documentId: String): String? {
        return prefs.getString(cacheKey(collection, documentId), null)
    }

    fun remove(collection: String, documentId: String) {
        prefs.edit().remove(cacheKey(collection, documentId)).apply()
    }

    private fun cacheKey(collection: String, documentId: String): String {
        return "${collection}::${documentId}"
    }
}

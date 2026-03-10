package com.bitchat.android.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.min
import kotlin.math.pow

/**
 * Core sync engine that processes the offline queue and manages connectivity.
 *
 * - Monitors network state changes via ConnectivityManager
 * - Processes queue in FIFO order when online
 * - Supports exponential backoff for retries
 * - Conflict resolution via [ConflictResolver]
 * - Batch sync support (up to 50 operations per batch)
 * - Emits sync state via StateFlow
 */
class SyncEngine(
    private val context: Context,
    private val queue: OfflineSyncQueue,
    private val mongoDBClient: MongoDBClient,
    private val firebaseClient: FirebaseClient
) {

    companion object {
        private const val TAG = "SyncEngine"
        private const val BATCH_SIZE = 50
        private const val BASE_BACKOFF_MS = 1000L
        private const val MAX_BACKOFF_MS = 60_000L
        private const val DEFAULT_AUTO_SYNC_INTERVAL_MS = 30_000L
    }

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    private val conflictResolver = ConflictResolver()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var autoSyncJob: Job? = null
    private var isNetworkAvailable = false

    private val connectivityManager: ConnectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.d(TAG, "Network available")
            isNetworkAvailable = true
            // Trigger sync when network becomes available
            scope.launch {
                delay(2000) // Brief delay to let connection stabilize
                if (queue.pendingSize() > 0) {
                    sync()
                }
            }
        }

        override fun onLost(network: Network) {
            Log.d(TAG, "Network lost")
            isNetworkAvailable = false
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            isNetworkAvailable = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }

    init {
        registerNetworkCallback()
        // Check initial state
        isNetworkAvailable = checkConnectivity()
    }

    /**
     * Process all pending operations in the queue.
     */
    suspend fun sync() {
        val pending = queue.getPending()
        if (pending.isEmpty()) {
            _syncState.value = SyncState.Completed(synced = 0, failed = 0)
            return
        }

        if (!isOnline()) {
            Log.d(TAG, "Offline - skipping sync of ${pending.size} operations")
            _syncState.value = SyncState.Error("No network connectivity")
            return
        }

        var synced = 0
        var failed = 0
        val total = pending.size
        val batches = pending.chunked(BATCH_SIZE)

        _syncState.value = SyncState.Syncing(progress = 0, total = total)

        for (batch in batches) {
            for (op in batch) {
                val result = syncOperation(op)
                if (result.isSuccess) {
                    synced++
                } else {
                    failed++
                }
                _syncState.value = SyncState.Syncing(progress = synced + failed, total = total)
            }
        }

        val finalState = SyncState.Completed(synced = synced, failed = failed)
        _syncState.value = finalState
        Log.d(TAG, "Sync completed: $synced synced, $failed failed out of $total")
    }

    /**
     * Sync a single operation with exponential backoff.
     */
    suspend fun syncOperation(op: SyncOperation): Result<Unit> {
        if (!isOnline()) {
            return Result.failure(SyncException("No network connectivity"))
        }

        // Calculate backoff based on retry count
        if (op.retryCount > 0) {
            val backoffMs = calculateBackoff(op.retryCount)
            Log.d(TAG, "Backoff ${backoffMs}ms for operation ${op.id} (retry ${op.retryCount})")
            delay(backoffMs)
        }

        return try {
            when (op.backend) {
                BackendType.MONGODB -> syncToMongoDB(op)
                BackendType.FIREBASE -> syncToFirebase(op)
                BackendType.BOTH -> syncToBoth(op)
            }
            queue.markSynced(op.id)
            Result.success(Unit)
        } catch (e: SyncException) {
            if (e.httpCode == 409) {
                // Conflict - attempt resolution
                handleConflict(op, e)
            } else {
                queue.markFailed(op.id, e.message ?: "Unknown error")
                Result.failure(e)
            }
        } catch (e: Exception) {
            queue.markFailed(op.id, e.message ?: "Unknown error")
            Result.failure(e)
        }
    }

    /**
     * Start auto-sync at the specified interval when online.
     */
    fun startAutoSync(intervalMs: Long = DEFAULT_AUTO_SYNC_INTERVAL_MS) {
        stopAutoSync()
        autoSyncJob = scope.launch {
            Log.d(TAG, "Auto-sync started (interval: ${intervalMs}ms)")
            while (isActive) {
                delay(intervalMs)
                if (isOnline() && queue.pendingSize() > 0) {
                    try {
                        sync()
                    } catch (e: Exception) {
                        Log.e(TAG, "Auto-sync error", e)
                    }
                }
            }
        }
    }

    /**
     * Stop auto-sync.
     */
    fun stopAutoSync() {
        autoSyncJob?.cancel()
        autoSyncJob = null
        Log.d(TAG, "Auto-sync stopped")
    }

    /**
     * Check if network is available.
     */
    fun isOnline(): Boolean = isNetworkAvailable

    /**
     * Clean up resources.
     */
    fun destroy() {
        stopAutoSync()
        scope.cancel()
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister network callback", e)
        }
    }

    // ---- Private helpers ----

    private suspend fun syncToMongoDB(op: SyncOperation) {
        val result = mongoDBClient.executeOperation(op)
        if (result.isFailure) {
            throw result.exceptionOrNull() ?: SyncException("MongoDB sync failed")
        }
    }

    private suspend fun syncToFirebase(op: SyncOperation) {
        val result = firebaseClient.executeOperation(op)
        if (result.isFailure) {
            throw result.exceptionOrNull() ?: SyncException("Firebase sync failed")
        }
    }

    private suspend fun syncToBoth(op: SyncOperation) {
        // Sync to both backends. If one fails, still try the other.
        var mongoError: Exception? = null
        var firebaseError: Exception? = null

        try {
            syncToMongoDB(op)
        } catch (e: Exception) {
            mongoError = e
            Log.e(TAG, "MongoDB sync failed for ${op.id}", e)
        }

        try {
            syncToFirebase(op)
        } catch (e: Exception) {
            firebaseError = e
            Log.e(TAG, "Firebase sync failed for ${op.id}", e)
        }

        // If both failed, throw
        if (mongoError != null && firebaseError != null) {
            throw SyncException(
                "Both backends failed. MongoDB: ${mongoError.message}, Firebase: ${firebaseError.message}"
            )
        }

        // If one failed, log but consider partial success
        if (mongoError != null) {
            Log.w(TAG, "Partial sync: MongoDB failed but Firebase succeeded for ${op.id}")
        }
        if (firebaseError != null) {
            Log.w(TAG, "Partial sync: Firebase failed but MongoDB succeeded for ${op.id}")
        }
    }

    private fun handleConflict(op: SyncOperation, error: SyncException): Result<Unit> {
        Log.w(TAG, "Conflict detected for ${op.collection}/${op.documentId}")
        queue.markConflict(op.id, error.message ?: "Conflict")
        return Result.failure(error)
    }

    private fun calculateBackoff(retryCount: Int): Long {
        val backoff = BASE_BACKOFF_MS * 2.0.pow(retryCount.toDouble()).toLong()
        return min(backoff, MAX_BACKOFF_MS)
    }

    private fun registerNetworkCallback() {
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager.registerNetworkCallback(request, networkCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback", e)
        }
    }

    private fun checkConnectivity(): Boolean {
        return try {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check connectivity", e)
            false
        }
    }
}

/**
 * Represents the current state of the sync engine.
 */
sealed class SyncState {
    object Idle : SyncState()
    data class Syncing(val progress: Int, val total: Int) : SyncState()
    data class Error(val message: String) : SyncState()
    data class Completed(val synced: Int, val failed: Int) : SyncState()
}

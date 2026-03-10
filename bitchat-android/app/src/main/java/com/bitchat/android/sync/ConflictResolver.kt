package com.bitchat.android.sync

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Handles sync conflicts between local and remote data.
 *
 * Strategies:
 * - LAST_WRITE_WINS: The document with the most recent timestamp wins.
 * - MERGE: Non-conflicting fields are merged; conflicting fields use the newer value.
 * - MANUAL: Conflict is queued for manual resolution.
 */
class ConflictResolver {

    companion object {
        private const val TAG = "ConflictResolver"
    }

    private val gson = Gson()

    enum class Strategy {
        LAST_WRITE_WINS,
        MERGE,
        MANUAL
    }

    var defaultStrategy: Strategy = Strategy.LAST_WRITE_WINS

    /**
     * Resolve a conflict between a local operation and remote data.
     *
     * @param localOp The local sync operation that conflicted.
     * @param remoteData The current remote data as JSON string.
     * @param remoteTimestamp The timestamp of the remote data.
     * @param strategy The resolution strategy (defaults to [defaultStrategy]).
     * @return A [ConflictResult] indicating the resolution.
     */
    fun resolve(
        localOp: SyncOperation,
        remoteData: String,
        remoteTimestamp: Long,
        strategy: Strategy = defaultStrategy
    ): ConflictResult {
        Log.d(TAG, "Resolving conflict for ${localOp.collection}/${localOp.documentId} using $strategy")

        return when (strategy) {
            Strategy.LAST_WRITE_WINS -> resolveLastWriteWins(localOp, remoteData, remoteTimestamp)
            Strategy.MERGE -> resolveMerge(localOp, remoteData, remoteTimestamp)
            Strategy.MANUAL -> ConflictResult(
                resolution = Resolution.MANUAL_REQUIRED,
                mergedData = null,
                description = "Conflict requires manual resolution for ${localOp.collection}/${localOp.documentId}"
            )
        }
    }

    /**
     * Last-write-wins: compare timestamps and keep the newer document.
     */
    private fun resolveLastWriteWins(
        localOp: SyncOperation,
        remoteData: String,
        remoteTimestamp: Long
    ): ConflictResult {
        return if (localOp.timestamp >= remoteTimestamp) {
            Log.d(TAG, "Local wins (local=${localOp.timestamp} >= remote=$remoteTimestamp)")
            ConflictResult(
                resolution = Resolution.LOCAL_WINS,
                mergedData = localOp.payload,
                description = "Local data is newer, overwriting remote"
            )
        } else {
            Log.d(TAG, "Remote wins (remote=$remoteTimestamp > local=${localOp.timestamp})")
            ConflictResult(
                resolution = Resolution.REMOTE_WINS,
                mergedData = remoteData,
                description = "Remote data is newer, keeping remote"
            )
        }
    }

    /**
     * Merge strategy: combine fields from both local and remote.
     * For fields present in both, use the newer value based on timestamp.
     * For fields present in only one, keep them.
     */
    private fun resolveMerge(
        localOp: SyncOperation,
        remoteData: String,
        remoteTimestamp: Long
    ): ConflictResult {
        return try {
            val localJson = JsonParser.parseString(localOp.payload).asJsonObject
            val remoteJson = JsonParser.parseString(remoteData).asJsonObject

            val merged = JsonObject()

            // Collect all keys from both
            val allKeys = mutableSetOf<String>()
            localJson.keySet().forEach { allKeys.add(it) }
            remoteJson.keySet().forEach { allKeys.add(it) }

            for (key in allKeys) {
                val inLocal = localJson.has(key)
                val inRemote = remoteJson.has(key)

                when {
                    inLocal && !inRemote -> {
                        // Only in local - keep it
                        merged.add(key, localJson.get(key))
                    }
                    !inLocal && inRemote -> {
                        // Only in remote - keep it
                        merged.add(key, remoteJson.get(key))
                    }
                    inLocal && inRemote -> {
                        // In both - use the newer timestamp's value
                        if (localOp.timestamp >= remoteTimestamp) {
                            merged.add(key, localJson.get(key))
                        } else {
                            merged.add(key, remoteJson.get(key))
                        }
                    }
                }
            }

            Log.d(TAG, "Merged ${allKeys.size} fields")
            ConflictResult(
                resolution = Resolution.MERGED,
                mergedData = gson.toJson(merged),
                description = "Merged ${allKeys.size} fields from local and remote"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Merge failed, falling back to last-write-wins", e)
            resolveLastWriteWins(localOp, remoteData, remoteTimestamp)
        }
    }
}

/**
 * Result of a conflict resolution.
 */
data class ConflictResult(
    val resolution: Resolution,
    val mergedData: String?,
    val description: String
)

enum class Resolution {
    LOCAL_WINS,
    REMOTE_WINS,
    MERGED,
    MANUAL_REQUIRED
}

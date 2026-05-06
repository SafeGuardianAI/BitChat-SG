package com.bitchat.android.mesh.distributed

/**
 * State machine for distributed memory lifecycle.
 */
sealed class DistMemoryState {
    /** Battery is above threshold; no offloading needed. */
    object Normal : DistMemoryState()

    /** Battery dropped below threshold; preparing to offload. */
    object LowBattery : DistMemoryState()

    /** Shards are actively being sent to peers. */
    data class Offloading(val progress: Float) : DistMemoryState()

    /** Offload completed successfully. */
    data class Offloaded(val shardCount: Int, val peerCount: Int) : DistMemoryState()

    /** Reclaiming shards from peers after battery recovery or device restart. */
    data class Reclaiming(val progress: Float) : DistMemoryState()

    /** An unrecoverable error occurred during offload or reclaim. */
    object Error : DistMemoryState()
}

/**
 * Result of an offload operation.
 */
data class OffloadResult(
    val success: Boolean,
    val shardsOffloaded: Int,
    val peersUsed: Int,
    val bytesOffloaded: Long,
    val failedShards: Int
)

/**
 * Result of a reclaim operation.
 */
data class ReclaimResult(
    val success: Boolean,
    val shardsReclaimed: Int,
    val shardsMissing: Int,
    val bytesReclaimed: Long
)

package com.bitchat.android.mesh.distributed

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Type of critical data stored in a shard.
 */
enum class ShardDataType {
    VITAL_DATA,
    PENDING_MESSAGES,
    SYNC_QUEUE,
    AI_KNOWLEDGE,
    IDENTITY_BACKUP
}

/**
 * Offload priority — higher priority shards are sent first.
 */
enum class ShardPriority {
    CRITICAL,   // Vital data, identity — offload first
    HIGH,       // Pending messages
    MEDIUM,     // Sync queue
    LOW         // AI knowledge cache
}

/**
 * A single encrypted shard of critical data that can be distributed to a BLE peer.
 *
 * Each shard is independently encrypted with AES-256-GCM so that any single
 * peer only ever sees ciphertext. The [ownerPublicKeyHex] allows the original
 * device (or an authorised delegate) to reclaim the shard later.
 */
data class MemoryShard(
    /** Unique shard identifier (UUID). */
    val id: String,
    /** Hex-encoded Noise public key of the shard owner. */
    val ownerPublicKeyHex: String,
    /** Zero-based index of this shard within its shard set. */
    val shardIndex: Int,
    /** Total number of shards in the set. */
    val totalShards: Int,
    /** Category of the data stored in this shard. */
    val dataType: ShardDataType,
    /** Base64-encoded AES-256-GCM ciphertext (IV prepended). */
    val encryptedPayload: String,
    /** SHA-256 hex digest of the plaintext for integrity verification. */
    val checksum: String,
    /** Unix epoch millis when the shard was created. */
    val timestamp: Long,
    /** Unix epoch millis after which the shard may be discarded. Defaults to 24 h from creation. */
    val expiresAt: Long,
    /** Offload priority for this shard. */
    val priority: ShardPriority
) {
    /** Serialise to JSON bytes for BLE transmission. */
    fun toBytes(): ByteArray = Gson().toJson(this).toByteArray(Charsets.UTF_8)

    companion object {
        private val gson = Gson()

        /** Deserialise from JSON bytes received over BLE. */
        fun fromBytes(bytes: ByteArray): MemoryShard =
            gson.fromJson(String(bytes, Charsets.UTF_8), MemoryShard::class.java)

        /** Deserialise a list of shards from JSON. */
        fun listFromJson(json: String): List<MemoryShard> {
            val type = object : TypeToken<List<MemoryShard>>() {}.type
            return gson.fromJson(json, type)
        }

        /** Serialise a list of shards to JSON. */
        fun listToJson(shards: List<MemoryShard>): String = gson.toJson(shards)
    }
}

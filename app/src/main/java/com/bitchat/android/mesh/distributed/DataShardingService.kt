package com.bitchat.android.mesh.distributed

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.util.UUID

/**
 * Splits critical application data into [MemoryShard]s for BLE distribution.
 *
 * Data is collected from various local stores, prioritised, chunked to fit
 * within [DistributedMemoryManager.MAX_SHARD_SIZE], encrypted with
 * [ShardEncryption], and then handed to the caller for transmission.
 *
 * The service also handles reassembly of reclaimed shards back into the
 * original data blobs.
 */
class DataShardingService(private val context: Context) {

    companion object {
        private const val TAG = "DataSharding"
        private const val PREFS_NAME = "safeguardian_data_store"
        private const val VITAL_DATA_KEY = "vital_data"
        private const val PENDING_MESSAGES_KEY = "pending_messages"
        private const val SYNC_QUEUE_KEY = "sync_queue"
        private const val AI_KNOWLEDGE_KEY = "ai_knowledge"
        private const val TWENTY_FOUR_HOURS_MS = 24L * 60 * 60 * 1000

        /** Desired number of peers each shard is replicated to. */
        const val REPLICATION_FACTOR = 2
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // ---- Public API --------------------------------------------------------

    /**
     * Collect all critical data, encrypt it, and split into shards.
     *
     * @param ownerPublicKeyHex  Owner's Noise public key hex for shard metadata.
     * @param ownerKeyMaterial   Owner's key material for AES encryption.
     * @return A list of [MemoryShard]s sorted by priority (CRITICAL first).
     */
    fun createShards(
        ownerPublicKeyHex: String,
        ownerKeyMaterial: ByteArray
    ): List<MemoryShard> {
        Log.d(TAG, "Creating shards for owner $ownerPublicKeyHex")

        val dataEntries = collectCriticalData()
        if (dataEntries.isEmpty()) {
            Log.d(TAG, "No critical data to shard")
            return emptyList()
        }

        val allShards = mutableListOf<MemoryShard>()

        for (entry in dataEntries) {
            val shards = shardDataEntry(entry, ownerPublicKeyHex, ownerKeyMaterial)
            allShards.addAll(shards)
        }

        // Sort by priority: CRITICAL first, LOW last
        allShards.sortBy { it.priority.ordinal }
        Log.d(TAG, "Created ${allShards.size} shards across ${dataEntries.size} data entries")
        return allShards
    }

    /**
     * Reassemble decrypted shard payloads back into the original data blob.
     *
     * @param shards  All shards belonging to a single data type, in order.
     * @param ownerKeyMaterial  Key material for decryption.
     * @return Reassembled plaintext bytes, or null if shards are incomplete.
     */
    fun reassembleShards(
        shards: List<MemoryShard>,
        ownerKeyMaterial: ByteArray
    ): ByteArray? {
        if (shards.isEmpty()) return null

        // Verify we have a complete set
        val totalExpected = shards.first().totalShards
        if (shards.size < totalExpected) {
            Log.w(TAG, "Incomplete shard set: have ${shards.size} of $totalExpected")
            return null
        }

        val sorted = shards.sortedBy { it.shardIndex }
        val parts = mutableListOf<ByteArray>()

        for (shard in sorted) {
            try {
                val decrypted = ShardEncryption.decrypt(
                    shard.encryptedPayload,
                    ownerKeyMaterial,
                    shard.checksum
                )
                parts.add(decrypted)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decrypt shard ${shard.id} (index ${shard.shardIndex})", e)
                return null
            }
        }

        // Concatenate all parts
        val totalSize = parts.sumOf { it.size }
        val result = ByteArray(totalSize)
        var offset = 0
        for (part in parts) {
            System.arraycopy(part, 0, result, offset, part.size)
            offset += part.size
        }

        Log.d(TAG, "Reassembled ${sorted.size} shards into $totalSize bytes")
        return result
    }

    /**
     * Distribute shards across available peers, respecting [REPLICATION_FACTOR].
     *
     * @param shards    All shards to distribute.
     * @param peerIds   Available peer IDs.
     * @return A map of peerId → list of shards to send to that peer.
     */
    fun assignShardsToPeers(
        shards: List<MemoryShard>,
        peerIds: List<String>
    ): Map<String, List<MemoryShard>> {
        if (peerIds.isEmpty()) {
            Log.w(TAG, "No peers available for shard distribution")
            return emptyMap()
        }

        val assignment = mutableMapOf<String, MutableList<MemoryShard>>()
        for (peerId in peerIds) {
            assignment[peerId] = mutableListOf()
        }

        val effectiveReplication = minOf(REPLICATION_FACTOR, peerIds.size)

        for ((shardIdx, shard) in shards.withIndex()) {
            // Round-robin with replication: each shard goes to `effectiveReplication` peers
            for (replica in 0 until effectiveReplication) {
                val peerIndex = (shardIdx + replica) % peerIds.size
                val peerId = peerIds[peerIndex]
                assignment[peerId]!!.add(shard)
            }
        }

        // Remove peers that ended up with zero shards
        return assignment.filterValues { it.isNotEmpty() }
    }

    /**
     * Persist a record of which shards were sent to which peers so we can
     * reclaim them later.
     */
    fun saveShardDistribution(
        distribution: Map<String, List<MemoryShard>>,
        ownerPublicKeyHex: String
    ) {
        val records = mutableListOf<ShardDistributionRecord>()
        for ((peerId, shards) in distribution) {
            for (shard in shards) {
                records.add(
                    ShardDistributionRecord(
                        shardId = shard.id,
                        peerId = peerId,
                        dataType = shard.dataType,
                        timestamp = shard.timestamp
                    )
                )
            }
        }

        val json = com.google.gson.Gson().toJson(records)
        prefs.edit()
            .putString("shard_distribution_$ownerPublicKeyHex", json)
            .apply()

        Log.d(TAG, "Saved ${records.size} shard distribution records")
    }

    /**
     * Load saved shard distribution records for reclaim.
     */
    fun loadShardDistribution(ownerPublicKeyHex: String): List<ShardDistributionRecord> {
        val json = prefs.getString("shard_distribution_$ownerPublicKeyHex", null)
            ?: return emptyList()
        val type = object : com.google.gson.reflect.TypeToken<List<ShardDistributionRecord>>() {}.type
        return com.google.gson.Gson().fromJson(json, type)
    }

    // ---- Internal ----------------------------------------------------------

    /**
     * Gather all critical data from local stores, ordered by priority.
     */
    private fun collectCriticalData(): List<DataEntry> {
        val entries = mutableListOf<DataEntry>()

        // 1. Vital data (CRITICAL priority)
        collectStringData(VITAL_DATA_KEY)?.let {
            entries.add(DataEntry(ShardDataType.VITAL_DATA, ShardPriority.CRITICAL, it))
        }

        // 2. Pending messages (HIGH priority)
        collectStringData(PENDING_MESSAGES_KEY)?.let {
            entries.add(DataEntry(ShardDataType.PENDING_MESSAGES, ShardPriority.HIGH, it))
        }

        // 3. Sync queue (MEDIUM priority)
        collectStringData(SYNC_QUEUE_KEY)?.let {
            entries.add(DataEntry(ShardDataType.SYNC_QUEUE, ShardPriority.MEDIUM, it))
        }

        // 4. AI knowledge cache (LOW priority)
        collectStringData(AI_KNOWLEDGE_KEY)?.let {
            entries.add(DataEntry(ShardDataType.AI_KNOWLEDGE, ShardPriority.LOW, it))
        }

        Log.d(TAG, "Collected ${entries.size} critical data entries " +
                "(${entries.sumOf { it.data.size }} bytes total)")
        return entries
    }

    private fun collectStringData(key: String): ByteArray? {
        val value = prefs.getString(key, null)
        return if (value.isNullOrEmpty()) null else value.toByteArray(Charsets.UTF_8)
    }

    /**
     * Split a single [DataEntry] into encrypted [MemoryShard]s.
     */
    private fun shardDataEntry(
        entry: DataEntry,
        ownerPublicKeyHex: String,
        ownerKeyMaterial: ByteArray
    ): List<MemoryShard> {
        val maxSize = DistributedMemoryManager.MAX_SHARD_SIZE
        val chunks = entry.data.toList().chunked(maxSize).map { it.toByteArray() }
        val now = System.currentTimeMillis()
        val expiresAt = now + TWENTY_FOUR_HOURS_MS

        return chunks.mapIndexed { index, chunk ->
            val (encryptedPayload, checksum) = ShardEncryption.encrypt(chunk, ownerKeyMaterial)

            MemoryShard(
                id = UUID.randomUUID().toString(),
                ownerPublicKeyHex = ownerPublicKeyHex,
                shardIndex = index,
                totalShards = chunks.size,
                dataType = entry.dataType,
                encryptedPayload = encryptedPayload,
                checksum = checksum,
                timestamp = now,
                expiresAt = expiresAt,
                priority = entry.priority
            )
        }
    }

    // ---- Data classes -------------------------------------------------------

    private data class DataEntry(
        val dataType: ShardDataType,
        val priority: ShardPriority,
        val data: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is DataEntry) return false
            return dataType == other.dataType && priority == other.priority && data.contentEquals(other.data)
        }

        override fun hashCode(): Int {
            var result = dataType.hashCode()
            result = 31 * result + priority.hashCode()
            result = 31 * result + data.contentHashCode()
            return result
        }
    }

    /** Tracks where each shard was sent for later reclaim. */
    data class ShardDistributionRecord(
        val shardId: String,
        val peerId: String,
        val dataType: ShardDataType,
        val timestamp: Long
    )
}

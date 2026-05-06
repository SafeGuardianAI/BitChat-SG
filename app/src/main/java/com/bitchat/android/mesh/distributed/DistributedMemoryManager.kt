package com.bitchat.android.mesh.distributed

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.BatteryManager
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Core manager for BLE distributed memory operations.
 *
 * When the device's battery drops below [BATTERY_THRESHOLD] percent, this
 * manager coordinates:
 *  1. Collecting critical data from local stores
 *  2. Splitting it into encrypted [MemoryShard]s
 *  3. Distributing shards to nearby BLE peers via [DistributedMemoryProtocol]
 *  4. Tracking which peer holds which shards
 *
 * When the battery recovers (or the device comes back online), the manager
 * reclaims shards from peers and reassembles the original data.
 */
class DistributedMemoryManager(private val context: Context) {

    companion object {
        private const val TAG = "DistMemory"

        /** Battery percentage at which offloading is triggered. */
        const val BATTERY_THRESHOLD = 10

        /** Maximum shard payload size in bytes (fits BLE MTU with fragmentation). */
        const val MAX_SHARD_SIZE = 4096

        /** SharedPreferences key for shard metadata. */
        const val STORAGE_KEY = "distributed_memory_shards_v1"

        /** SharedPreferences key for shards held on behalf of other peers. */
        const val HELD_SHARDS_KEY = "held_shards_v1"

        /** How often to poll battery level (ms). */
        private const val BATTERY_POLL_INTERVAL_MS = 60_000L

        /** SharedPreferences file name. */
        private const val PREFS_NAME = "distributed_memory_prefs"

        /** Maximum number of shards this device will hold for other peers. */
        private const val MAX_HELD_SHARDS = 50

        /** Maximum total bytes this device will hold for other peers. */
        private const val MAX_HELD_BYTES = 512L * 1024  // 512 KB
    }

    private val _state = MutableStateFlow<DistMemoryState>(DistMemoryState.Normal)

    /** Observable state of the distributed memory subsystem. */
    val state: StateFlow<DistMemoryState> = _state.asStateFlow()

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val shardingService = DataShardingService(context)
    private val gson = Gson()
    private var batteryMonitorJob: Job? = null

    // ---- Battery monitoring ------------------------------------------------

    /**
     * Query the current battery level (0–100) from the system.
     */
    fun getBatteryLevel(): Int {
        val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus: Intent? = context.registerReceiver(null, intentFilter)
        if (batteryStatus == null) {
            Log.w(TAG, "Could not read battery status")
            return 100  // Assume full if unavailable
        }

        val level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1)

        return if (level >= 0 && scale > 0) {
            (level * 100) / scale
        } else {
            Log.w(TAG, "Invalid battery level: level=$level, scale=$scale")
            100
        }
    }

    /**
     * Returns true if the battery is below [BATTERY_THRESHOLD].
     */
    fun isBatteryLow(): Boolean = getBatteryLevel() < BATTERY_THRESHOLD

    /**
     * Returns true if the device is currently charging.
     */
    fun isCharging(): Boolean {
        val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus: Intent? = context.registerReceiver(null, intentFilter)
        if (batteryStatus == null) return false

        val status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
    }

    /**
     * Start periodic battery monitoring. When battery drops below threshold
     * and the device is not charging, [onLowBattery] is invoked.
     *
     * @param scope       CoroutineScope to launch the monitor in.
     * @param onLowBattery  Callback invoked when low-battery offloading should begin.
     */
    fun startBatteryMonitor(scope: CoroutineScope, onLowBattery: suspend () -> Unit) {
        batteryMonitorJob?.cancel()
        batteryMonitorJob = scope.launch {
            Log.d(TAG, "Battery monitor started")
            var wasLow = false

            while (isActive) {
                val level = getBatteryLevel()
                val charging = isCharging()
                val isLow = level < BATTERY_THRESHOLD && !charging

                if (isLow && !wasLow) {
                    Log.w(TAG, "Battery low ($level%) — triggering distributed memory offload")
                    _state.value = DistMemoryState.LowBattery
                    try {
                        onLowBattery()
                    } catch (e: Exception) {
                        Log.e(TAG, "Low-battery callback failed", e)
                        _state.value = DistMemoryState.Error
                    }
                } else if (!isLow && wasLow) {
                    Log.i(TAG, "Battery recovered ($level%) or charging — returning to normal")
                    _state.value = DistMemoryState.Normal
                }

                wasLow = isLow
                delay(BATTERY_POLL_INTERVAL_MS)
            }
        }
    }

    /**
     * Stop the battery monitoring coroutine.
     */
    fun stopBatteryMonitor() {
        batteryMonitorJob?.cancel()
        batteryMonitorJob = null
        Log.d(TAG, "Battery monitor stopped")
    }

    // ---- Offload -----------------------------------------------------------

    /**
     * Offload critical data to BLE peers.
     *
     * @param ownerPublicKeyHex  The owner's Noise public key (hex).
     * @param ownerKeyMaterial   Key material for encrypting shards.
     * @param availablePeerIds   List of connected BLE peer IDs.
     * @param sendCallback       Function that sends bytes to a specific peer.
     *                           Returns true if the peer acknowledged receipt.
     * @return [OffloadResult] summarising the operation.
     */
    suspend fun offloadToPeers(
        ownerPublicKeyHex: String,
        ownerKeyMaterial: ByteArray,
        availablePeerIds: List<String>,
        sendCallback: suspend (peerId: String, data: ByteArray) -> Boolean
    ): OffloadResult {
        if (availablePeerIds.isEmpty()) {
            Log.w(TAG, "No peers available for offloading")
            _state.value = DistMemoryState.Error
            return OffloadResult(
                success = false,
                shardsOffloaded = 0,
                peersUsed = 0,
                bytesOffloaded = 0,
                failedShards = 0
            )
        }

        _state.value = DistMemoryState.Offloading(0f)

        // 1. Create shards
        val shards = shardingService.createShards(ownerPublicKeyHex, ownerKeyMaterial)
        if (shards.isEmpty()) {
            Log.d(TAG, "No data to offload")
            _state.value = DistMemoryState.Normal
            return OffloadResult(
                success = true,
                shardsOffloaded = 0,
                peersUsed = 0,
                bytesOffloaded = 0,
                failedShards = 0
            )
        }

        // 2. Assign shards to peers
        val distribution = shardingService.assignShardsToPeers(shards, availablePeerIds)

        // 3. Send shards
        var offloaded = 0
        var failed = 0
        var bytesTotal = 0L
        val peersUsed = mutableSetOf<String>()
        val totalSends = distribution.values.sumOf { it.size }
        var completed = 0

        for ((peerId, peerShards) in distribution) {
            for (shard in peerShards) {
                try {
                    val data = DistributedMemoryProtocol.buildShardData(shard)
                    val ack = sendCallback(peerId, data)
                    if (ack) {
                        offloaded++
                        bytesTotal += shard.encryptedPayload.length
                        peersUsed.add(peerId)
                    } else {
                        failed++
                        Log.w(TAG, "Peer $peerId did not ACK shard ${shard.id}")
                    }
                } catch (e: Exception) {
                    failed++
                    Log.e(TAG, "Failed to send shard ${shard.id} to peer $peerId", e)
                }

                completed++
                _state.value = DistMemoryState.Offloading(completed.toFloat() / totalSends)
            }
        }

        // 4. Save distribution record
        shardingService.saveShardDistribution(distribution, ownerPublicKeyHex)
        saveOffloadedShardIds(shards.map { it.id })

        val success = offloaded > 0
        if (success) {
            _state.value = DistMemoryState.Offloaded(
                shardCount = offloaded,
                peerCount = peersUsed.size
            )
        } else {
            _state.value = DistMemoryState.Error
        }

        Log.i(TAG, "Offload complete: $offloaded shards to ${peersUsed.size} peers " +
                "($bytesTotal bytes), $failed failed")

        return OffloadResult(
            success = success,
            shardsOffloaded = offloaded,
            peersUsed = peersUsed.size,
            bytesOffloaded = bytesTotal,
            failedShards = failed
        )
    }

    // ---- Store shards for other peers --------------------------------------

    /**
     * Store a shard received from another peer who is offloading.
     *
     * @param shard  The encrypted shard to store.
     * @return true if the shard was stored, false if at capacity or expired.
     */
    fun storeReceivedShard(shard: MemoryShard): Boolean {
        // Check expiration
        if (shard.expiresAt < System.currentTimeMillis()) {
            Log.d(TAG, "Rejecting expired shard ${shard.id}")
            return false
        }

        val heldShards = getHeldShardsInternal()

        // Capacity check
        if (heldShards.size >= MAX_HELD_SHARDS) {
            Log.w(TAG, "At maximum held shard capacity ($MAX_HELD_SHARDS)")
            return false
        }

        val currentBytes = heldShards.sumOf { it.encryptedPayload.length.toLong() }
        if (currentBytes + shard.encryptedPayload.length > MAX_HELD_BYTES) {
            Log.w(TAG, "Insufficient capacity for shard (current: $currentBytes, " +
                    "shard: ${shard.encryptedPayload.length}, max: $MAX_HELD_BYTES)")
            return false
        }

        // Avoid duplicates
        if (heldShards.any { it.id == shard.id }) {
            Log.d(TAG, "Shard ${shard.id} already held, skipping")
            return true
        }

        val updated = heldShards + shard
        saveHeldShards(updated)
        Log.d(TAG, "Stored shard ${shard.id} from owner ${shard.ownerPublicKeyHex} " +
                "(${shard.encryptedPayload.length} bytes)")
        return true
    }

    /**
     * Retrieve all shards held for a specific owner.
     *
     * @param ownerPublicKeyHex  Owner's Noise public key (hex).
     * @return List of held shards belonging to that owner.
     */
    fun getHeldShards(ownerPublicKeyHex: String): List<MemoryShard> {
        return getHeldShardsInternal()
            .filter { it.ownerPublicKeyHex == ownerPublicKeyHex }
            .filter { it.expiresAt >= System.currentTimeMillis() }
    }

    /**
     * Remove shards after the owner has reclaimed them.
     */
    fun removeHeldShards(shardIds: List<String>) {
        val current = getHeldShardsInternal()
        val updated = current.filter { it.id !in shardIds }
        saveHeldShards(updated)
        Log.d(TAG, "Removed ${current.size - updated.size} reclaimed shards")
    }

    /**
     * Purge expired shards from local held storage.
     */
    fun purgeExpiredShards(): Int {
        val current = getHeldShardsInternal()
        val now = System.currentTimeMillis()
        val valid = current.filter { it.expiresAt >= now }
        val purged = current.size - valid.size
        if (purged > 0) {
            saveHeldShards(valid)
            Log.d(TAG, "Purged $purged expired shards")
        }
        return purged
    }

    // ---- Reclaim -----------------------------------------------------------

    /**
     * Reclaim shards from peers after battery recovery.
     *
     * @param ownerPublicKeyHex  Owner's Noise public key (hex).
     * @param ownerKeyMaterial   Key material for decryption and auth.
     * @param requestCallback    Function that sends a reclaim request to a peer
     *                           and returns the peer's response bytes, or null on failure.
     * @return [ReclaimResult] summarising the operation.
     */
    suspend fun reclaimFromPeers(
        ownerPublicKeyHex: String,
        ownerKeyMaterial: ByteArray,
        requestCallback: suspend (peerId: String, request: ByteArray) -> ByteArray?
    ): ReclaimResult {
        _state.value = DistMemoryState.Reclaiming(0f)

        val records = shardingService.loadShardDistribution(ownerPublicKeyHex)
        if (records.isEmpty()) {
            Log.d(TAG, "No shard distribution records found; nothing to reclaim")
            _state.value = DistMemoryState.Normal
            return ReclaimResult(success = true, shardsReclaimed = 0, shardsMissing = 0, bytesReclaimed = 0)
        }

        // Group by peer
        val byPeer = records.groupBy { it.peerId }
        var reclaimed = 0
        var missing = 0
        var bytesTotal = 0L
        var completed = 0
        val totalRequests = byPeer.size

        for ((peerId, peerRecords) in byPeer) {
            val shardIds = peerRecords.map { it.shardId }
            val requestBytes = DistributedMemoryProtocol.buildShardRequest(
                ownerPublicKeyHex, shardIds, ownerKeyMaterial
            )

            try {
                val response = requestCallback(peerId, requestBytes)
                if (response != null) {
                    val message = DistributedMemoryProtocol.decode(response)
                    if (message != null && message.type == DistributedMemoryProtocol.MessageType.SHARD_RETURN) {
                        val shardReturn = DistributedMemoryProtocol
                            .decodePayload<DistributedMemoryProtocol.ShardReturn>(message)
                        if (shardReturn != null) {
                            reclaimed++
                            bytesTotal += shardReturn.shard.encryptedPayload.length
                        } else {
                            missing += shardIds.size
                        }
                    } else {
                        missing += shardIds.size
                    }
                } else {
                    missing += shardIds.size
                    Log.w(TAG, "Peer $peerId did not respond to reclaim request")
                }
            } catch (e: Exception) {
                missing += shardIds.size
                Log.e(TAG, "Failed to reclaim from peer $peerId", e)
            }

            completed++
            _state.value = DistMemoryState.Reclaiming(completed.toFloat() / totalRequests)
        }

        val success = reclaimed > 0 || missing == 0
        _state.value = if (success) DistMemoryState.Normal else DistMemoryState.Error

        Log.i(TAG, "Reclaim complete: $reclaimed shards recovered ($bytesTotal bytes), $missing missing")

        return ReclaimResult(
            success = success,
            shardsReclaimed = reclaimed,
            shardsMissing = missing,
            bytesReclaimed = bytesTotal
        )
    }

    /**
     * Available capacity (in bytes) that this device can offer to hold for others.
     */
    fun availableCapacity(): Long {
        val currentBytes = getHeldShardsInternal().sumOf { it.encryptedPayload.length.toLong() }
        return (MAX_HELD_BYTES - currentBytes).coerceAtLeast(0)
    }

    /**
     * Number of additional shards this device can accept.
     */
    fun availableShardSlots(): Int {
        return (MAX_HELD_SHARDS - getHeldShardsInternal().size).coerceAtLeast(0)
    }

    // ---- Persistence helpers -----------------------------------------------

    private fun getHeldShardsInternal(): List<MemoryShard> {
        val json = prefs.getString(HELD_SHARDS_KEY, null) ?: return emptyList()
        return try {
            MemoryShard.listFromJson(json)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deserialise held shards", e)
            emptyList()
        }
    }

    private fun saveHeldShards(shards: List<MemoryShard>) {
        prefs.edit()
            .putString(HELD_SHARDS_KEY, MemoryShard.listToJson(shards))
            .apply()
    }

    private fun saveOffloadedShardIds(ids: List<String>) {
        prefs.edit()
            .putString(STORAGE_KEY, gson.toJson(ids))
            .apply()
    }

    fun getOffloadedShardIds(): List<String> {
        val json = prefs.getString(STORAGE_KEY, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<String>>() {}.type
            gson.fromJson(json, type)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deserialise offloaded shard IDs", e)
            emptyList()
        }
    }
}

package com.bitchat.android.mesh.distributed

import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

/**
 * BLE protocol extension for distributed memory shard exchange.
 *
 * All protocol messages are serialised as JSON and transmitted through the
 * existing [BluetoothMeshService] fragmentation layer. Each message carries
 * a [MessageType] discriminator so that both sides can decode the payload.
 *
 * Typical flows:
 *
 * **Offload (low-battery device → peer)**
 * ```
 *   Owner  ──SHARD_OFFER──►  Peer
 *   Owner  ◄─SHARD_ACCEPT──  Peer   (peer has capacity)
 *   Owner  ──SHARD_DATA───►  Peer
 *   Owner  ◄─SHARD_ACK────  Peer
 * ```
 *
 * **Reclaim (owner recovers battery / comes back online)**
 * ```
 *   Owner  ──SHARD_REQUEST──► Peer
 *   Owner  ◄─SHARD_RETURN───  Peer
 *   Owner  ──SHARD_ACK──────► Peer   (peer may now delete)
 * ```
 */
object DistributedMemoryProtocol {

    @PublishedApi internal const val TAG = "DistMemProto"
    private const val PROTOCOL_VERSION = 1
    @PublishedApi internal val gson = Gson()

    // ---- Message types -----------------------------------------------------

    enum class MessageType {
        /** Owner advertises shards it wants to offload. */
        SHARD_OFFER,
        /** Peer accepts the offer (has capacity and supports dist-memory). */
        SHARD_ACCEPT,
        /** Owner sends encrypted shard data to the accepting peer. */
        SHARD_DATA,
        /** Owner requests its shards back from a peer. */
        SHARD_REQUEST,
        /** Peer returns stored shard data to the owner. */
        SHARD_RETURN,
        /** Acknowledgement (used after SHARD_DATA and SHARD_RETURN). */
        SHARD_ACK,
        /** Peer capability query / response. */
        CAPABILITY_QUERY,
        /** Peer capability response. */
        CAPABILITY_RESPONSE,
        /** Peer rejects the offer (no capacity or unsupported). */
        SHARD_REJECT
    }

    // ---- Protocol messages -------------------------------------------------

    /** Wrapper envelope for all protocol messages. */
    data class ProtocolMessage(
        @SerializedName("v") val version: Int = PROTOCOL_VERSION,
        @SerializedName("t") val type: MessageType,
        @SerializedName("p") val payload: String   // JSON-encoded inner message
    )

    /** SHARD_OFFER: owner tells a peer what shards are available. */
    data class ShardOffer(
        val ownerPublicKeyHex: String,
        val shardIds: List<String>,
        val totalBytes: Long,
        val shardCount: Int,
        val priorities: List<ShardPriority>
    )

    /** SHARD_ACCEPT: peer agrees to store offered shards. */
    data class ShardAccept(
        val peerPublicKeyHex: String,
        val acceptedShardIds: List<String>,
        val availableCapacityBytes: Long
    )

    /** SHARD_REJECT: peer declines the offer. */
    data class ShardReject(
        val peerPublicKeyHex: String,
        val reason: String
    )

    /** SHARD_DATA: the actual encrypted shard payload. */
    data class ShardData(
        val shard: MemoryShard
    )

    /** SHARD_REQUEST: owner asks a peer to return specific shards. */
    data class ShardRequest(
        val ownerPublicKeyHex: String,
        val requestedShardIds: List<String>,
        /** HMAC proof that the requester owns the key material. */
        val authProof: String
    )

    /** SHARD_RETURN: peer sends back a stored shard. */
    data class ShardReturn(
        val shard: MemoryShard
    )

    /** SHARD_ACK: generic acknowledgement. */
    data class ShardAck(
        val shardIds: List<String>,
        val success: Boolean,
        val message: String = ""
    )

    /** CAPABILITY_QUERY: ask if a peer supports distributed memory. */
    data class CapabilityQuery(
        val senderPublicKeyHex: String,
        val protocolVersion: Int = PROTOCOL_VERSION
    )

    /** CAPABILITY_RESPONSE: peer reports its distributed-memory capability. */
    data class CapabilityResponse(
        val peerPublicKeyHex: String,
        val supported: Boolean,
        val protocolVersion: Int = PROTOCOL_VERSION,
        val availableCapacityBytes: Long,
        val maxShards: Int
    )

    // ---- Encode / Decode ---------------------------------------------------

    /** Encode a protocol message into bytes for BLE transmission. */
    fun encode(type: MessageType, payload: Any): ByteArray {
        val innerJson = gson.toJson(payload)
        val envelope = ProtocolMessage(
            version = PROTOCOL_VERSION,
            type = type,
            payload = innerJson
        )
        return gson.toJson(envelope).toByteArray(Charsets.UTF_8)
    }

    /** Decode bytes received over BLE into a [ProtocolMessage] envelope. */
    fun decode(data: ByteArray): ProtocolMessage? {
        return try {
            gson.fromJson(String(data, Charsets.UTF_8), ProtocolMessage::class.java)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decode protocol message", e)
            null
        }
    }

    /** Decode the inner payload of a [ProtocolMessage] into the expected type. */
    inline fun <reified T> decodePayload(message: ProtocolMessage): T? {
        return try {
            gson.fromJson(message.payload, T::class.java)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decode payload as ${T::class.java.simpleName}", e)
            null
        }
    }

    // ---- Convenience builders ----------------------------------------------

    fun buildShardOffer(
        ownerPublicKeyHex: String,
        shards: List<MemoryShard>
    ): ByteArray {
        val offer = ShardOffer(
            ownerPublicKeyHex = ownerPublicKeyHex,
            shardIds = shards.map { it.id },
            totalBytes = shards.sumOf { it.encryptedPayload.length.toLong() },
            shardCount = shards.size,
            priorities = shards.map { it.priority }
        )
        return encode(MessageType.SHARD_OFFER, offer)
    }

    fun buildShardAccept(
        peerPublicKeyHex: String,
        acceptedIds: List<String>,
        capacity: Long
    ): ByteArray {
        val accept = ShardAccept(
            peerPublicKeyHex = peerPublicKeyHex,
            acceptedShardIds = acceptedIds,
            availableCapacityBytes = capacity
        )
        return encode(MessageType.SHARD_ACCEPT, accept)
    }

    fun buildShardReject(peerPublicKeyHex: String, reason: String): ByteArray {
        val reject = ShardReject(peerPublicKeyHex = peerPublicKeyHex, reason = reason)
        return encode(MessageType.SHARD_REJECT, reject)
    }

    fun buildShardData(shard: MemoryShard): ByteArray {
        return encode(MessageType.SHARD_DATA, ShardData(shard))
    }

    fun buildShardRequest(
        ownerPublicKeyHex: String,
        shardIds: List<String>,
        ownerKeyMaterial: ByteArray
    ): ByteArray {
        // Produce an HMAC-SHA256 proof over the shard IDs so the peer can
        // verify the requester actually owns the key material.
        val proofInput = shardIds.sorted().joinToString(",").toByteArray(Charsets.UTF_8)
        val proof = android.util.Base64.encodeToString(
            ShardEncryption.hmacSha256(proofInput, ownerKeyMaterial),
            android.util.Base64.NO_WRAP
        )
        val request = ShardRequest(
            ownerPublicKeyHex = ownerPublicKeyHex,
            requestedShardIds = shardIds,
            authProof = proof
        )
        return encode(MessageType.SHARD_REQUEST, request)
    }

    fun buildShardReturn(shard: MemoryShard): ByteArray {
        return encode(MessageType.SHARD_RETURN, ShardReturn(shard))
    }

    fun buildShardAck(shardIds: List<String>, success: Boolean, message: String = ""): ByteArray {
        return encode(MessageType.SHARD_ACK, ShardAck(shardIds, success, message))
    }

    fun buildCapabilityQuery(senderPublicKeyHex: String): ByteArray {
        return encode(MessageType.CAPABILITY_QUERY, CapabilityQuery(senderPublicKeyHex))
    }

    fun buildCapabilityResponse(
        peerPublicKeyHex: String,
        supported: Boolean,
        capacityBytes: Long,
        maxShards: Int
    ): ByteArray {
        return encode(
            MessageType.CAPABILITY_RESPONSE,
            CapabilityResponse(peerPublicKeyHex, supported, PROTOCOL_VERSION, capacityBytes, maxShards)
        )
    }
}

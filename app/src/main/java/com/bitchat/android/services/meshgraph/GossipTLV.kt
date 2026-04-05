package com.bitchat.android.services.meshgraph

object GossipTLV {
    fun encodeNeighbors(peerIDs: List<String>): ByteArray {
        return ByteArray(0)
    }
    
    fun decodeNeighborsFromAnnouncementPayload(payload: ByteArray): List<String>? {
        return null
    }
}

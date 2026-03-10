package com.bitchat.android.sync

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.util.UUID

data class SyncOperation(
    @SerializedName("id")
    val id: String = UUID.randomUUID().toString(),

    @SerializedName("timestamp")
    val timestamp: Long = System.currentTimeMillis(),

    @SerializedName("type")
    val type: OperationType,

    @SerializedName("collection")
    val collection: String,

    @SerializedName("document_id")
    val documentId: String,

    @SerializedName("payload")
    val payload: String,

    @SerializedName("status")
    val status: SyncStatus = SyncStatus.PENDING,

    @SerializedName("retry_count")
    val retryCount: Int = 0,

    @SerializedName("max_retries")
    val maxRetries: Int = 5,

    @SerializedName("last_error")
    val lastError: String? = null,

    @SerializedName("synced_at")
    val syncedAt: Long? = null,

    @SerializedName("backend")
    val backend: BackendType = BackendType.MONGODB
) {
    companion object {
        private val gson = Gson()

        fun fromJson(json: String): SyncOperation = gson.fromJson(json, SyncOperation::class.java)
    }

    fun toJson(): String = gson.toJson(this)
}

enum class OperationType {
    CREATE, UPDATE, DELETE, UPSERT
}

enum class SyncStatus {
    PENDING, IN_PROGRESS, SYNCED, FAILED, CONFLICT
}

enum class BackendType {
    MONGODB, FIREBASE, BOTH
}

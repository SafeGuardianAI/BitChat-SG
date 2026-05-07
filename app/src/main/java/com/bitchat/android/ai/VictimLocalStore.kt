package com.bitchat.android.ai

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Offline-first local queue for victim records.
 *
 * Every [postVictim] / [updateVictim] call writes here first.
 * Cloud sync (Firestore or MongoDB) happens separately; if it succeeds the
 * record is marked synced and removed from the pending queue.
 *
 * Uses SharedPreferences + JSON so no additional compile-time code generation
 * is required.
 */
class VictimLocalStore(context: Context) {

    companion object {
        private const val TAG = "VictimLocalStore"
        private const val PREFS_NAME = "victim_local_store"
        private const val KEY_PENDING = "pending_victims"
        private const val KEY_SYNCED = "synced_victims"

        private const val OP_CREATE = "create"
        private const val OP_UPDATE = "update"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ---- Write ----------------------------------------------------------------

    /**
     * Queue a new victim for creation. Returns a local UUID used for tracking
     * before a cloud ID is assigned.
     */
    fun enqueueCreate(victimJson: String, backendType: BackendType): String {
        val localId = java.util.UUID.randomUUID().toString()
        val entry = JSONObject().apply {
            put("local_id", localId)
            put("op", OP_CREATE)
            put("backend", backendType.name)
            put("data", victimJson)
            put("timestamp", System.currentTimeMillis())
            put("cloud_id", JSONObject.NULL)
        }
        appendPending(entry)
        Log.d(TAG, "Queued CREATE local_id=$localId")
        return localId
    }

    /**
     * Queue an update for an existing victim. [cloudId] is the server-assigned ID.
     */
    fun enqueueUpdate(cloudId: String, victimJson: String, backendType: BackendType) {
        val localId = java.util.UUID.randomUUID().toString()
        val entry = JSONObject().apply {
            put("local_id", localId)
            put("op", OP_UPDATE)
            put("backend", backendType.name)
            put("data", victimJson)
            put("timestamp", System.currentTimeMillis())
            put("cloud_id", cloudId)
        }
        appendPending(entry)
        Log.d(TAG, "Queued UPDATE cloud_id=$cloudId local_id=$localId")
    }

    /**
     * Record that a cloud ID was assigned to a locally-queued create operation.
     */
    fun markSynced(localId: String, cloudId: String) {
        val pending = getPending().toMutableList()
        val idx = pending.indexOfFirst { it.optString("local_id") == localId }
        if (idx >= 0) {
            pending[idx].put("cloud_id", cloudId)
            pending[idx].put("synced", true)
            savePending(pending)
        }
        Log.d(TAG, "Marked synced: local=$localId cloud=$cloudId")
    }

    /**
     * Remove a successfully synced entry from the pending queue.
     */
    fun removeSynced(localId: String) {
        val pending = getPending().filter { it.optString("local_id") != localId }
        savePending(pending)
    }

    // ---- Read -----------------------------------------------------------------

    fun getPendingCreates(backend: BackendType): List<JSONObject> =
        getPending().filter {
            it.optString("op") == OP_CREATE &&
            it.optString("backend") == backend.name &&
            !it.optBoolean("synced", false)
        }

    fun getPendingUpdates(backend: BackendType): List<JSONObject> =
        getPending().filter {
            it.optString("op") == OP_UPDATE &&
            it.optString("backend") == backend.name &&
            !it.optBoolean("synced", false)
        }

    fun hasPending(backend: BackendType): Boolean =
        getPending().any { it.optString("backend") == backend.name &&
                           !it.optBoolean("synced", false) }

    fun pendingCount(backend: BackendType): Int =
        getPending().count { it.optString("backend") == backend.name &&
                             !it.optBoolean("synced", false) }

    // ---- Persistence ---------------------------------------------------------

    private fun getPending(): List<JSONObject> {
        val json = prefs.getString(KEY_PENDING, "[]") ?: "[]"
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getJSONObject(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse pending queue", e)
            emptyList()
        }
    }

    private fun savePending(list: List<JSONObject>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        prefs.edit().putString(KEY_PENDING, arr.toString()).apply()
    }

    private fun appendPending(entry: JSONObject) {
        val current = getPending().toMutableList()
        current.add(entry)
        savePending(current)
    }
}

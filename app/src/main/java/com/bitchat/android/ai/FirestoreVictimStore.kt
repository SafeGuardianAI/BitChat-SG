package com.bitchat.android.ai

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

/**
 * Direct Firestore client — no Heroku middleman.
 *
 * Firestore SDK has built-in offline persistence: writes are committed to a
 * local LevelDB cache first and synced to the cloud whenever connectivity is
 * available.  The app never needs to be online at write time.
 *
 * Initialisation reads credentials from [CREDS_ASSET] (place
 * google-services-credentials.json in app/src/main/assets/).  If the file is
 * absent the store is disabled and all calls return null/false gracefully so
 * the MongoDB path or the local queue can take over.
 *
 * google-services-credentials.json format:
 * {
 *   "project_id":     "your-firebase-project-id",
 *   "api_key":        "AIza...",
 *   "app_id":         "1:123456789:android:abc123",
 *   "storage_bucket": "your-project.appspot.com"
 * }
 * (copy these values from Firebase Console → Project Settings → General)
 */
class FirestoreVictimStore(private val context: Context) {

    companion object {
        private const val TAG = "FirestoreVictim"
        private const val CREDS_ASSET = "google-services-credentials.json"
        private const val COLLECTION = "victims"
        private const val APP_NAME = "safeguardian"
    }

    private var db: FirebaseFirestore? = null
    private var available = false

    init {
        available = tryInitFirebase()
    }

    private fun tryInitFirebase(): Boolean {
        return try {
            val json = context.assets.open(CREDS_ASSET)
                .bufferedReader().use { it.readText() }
            val creds = JSONObject(json)

            val options = FirebaseOptions.Builder()
                .setProjectId(creds.getString("project_id"))
                .setApiKey(creds.getString("api_key"))
                .setApplicationId(creds.getString("app_id"))
                .setStorageBucket(creds.optString("storage_bucket").takeIf { it.isNotEmpty() })
                .build()

            val app = runCatching {
                FirebaseApp.getInstance(APP_NAME)
            }.getOrElse {
                FirebaseApp.initializeApp(context, options, APP_NAME)
            }

            val firestore = FirebaseFirestore.getInstance(app)
            firestore.firestoreSettings = FirebaseFirestoreSettings.Builder()
                .setPersistenceEnabled(true)
                .setCacheSizeBytes(FirebaseFirestoreSettings.CACHE_SIZE_UNLIMITED)
                .build()

            db = firestore
            Log.i(TAG, "Firestore initialised (offline persistence ON)")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Firestore unavailable: ${e.message} — place $CREDS_ASSET in assets/ to enable")
            false
        }
    }

    fun isAvailable(): Boolean = available

    /**
     * Create a new victim document. Returns the Firestore-assigned document ID,
     * or null on failure (data is already in [VictimLocalStore]).
     */
    suspend fun create(victimJson: String): String? {
        val firestore = db ?: return null
        return try {
            val data = JSONObject(victimJson)
            val map = jsonToMap(data)
            val ref = firestore.collection(COLLECTION).add(map).await()
            Log.d(TAG, "Victim created in Firestore: ${ref.id}")
            ref.id
        } catch (e: Exception) {
            Log.w(TAG, "Firestore create failed (offline?): ${e.message}")
            null
        }
    }

    /**
     * Update an existing victim document.
     */
    suspend fun update(cloudId: String, victimJson: String): Boolean {
        val firestore = db ?: return false
        return try {
            val data = JSONObject(victimJson)
            val map = jsonToMap(data)
            firestore.collection(COLLECTION).document(cloudId).set(map).await()
            Log.d(TAG, "Victim updated in Firestore: $cloudId")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Firestore update failed (offline?): ${e.message}")
            false
        }
    }

    /**
     * Flush all pending local records for Firebase backend from [VictimLocalStore].
     * Called by [VictimSyncWorker] when connectivity is restored.
     */
    suspend fun flushLocalQueue(localStore: VictimLocalStore): Int {
        if (!available) return 0
        var flushed = 0

        for (entry in localStore.getPendingCreates(BackendType.FIREBASE)) {
            val localId = entry.optString("local_id")
            val data = entry.optString("data")
            val cloudId = create(data)
            if (cloudId != null) {
                localStore.markSynced(localId, cloudId)
                localStore.removeSynced(localId)
                flushed++
            }
        }

        for (entry in localStore.getPendingUpdates(BackendType.FIREBASE)) {
            val localId = entry.optString("local_id")
            val cloudId = entry.optString("cloud_id")
            val data = entry.optString("data")
            if (update(cloudId, data)) {
                localStore.removeSynced(localId)
                flushed++
            }
        }

        Log.d(TAG, "Flushed $flushed Firebase records from local queue")
        return flushed
    }

    @Suppress("UNCHECKED_CAST")
    private fun jsonToMap(obj: JSONObject): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            map[key] = when (val v = obj.get(key)) {
                is JSONObject -> jsonToMap(v)
                is org.json.JSONArray -> (0 until v.length()).map { jsonToMap(v.getJSONObject(it)) }
                JSONObject.NULL -> null
                else -> v
            }
        }
        return map
    }
}

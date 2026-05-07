package com.bitchat.android.ai

import android.content.Context
import android.util.Log
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.io.OutputStreamWriter

/**
 * Victim information data class
 */
data class VictimInfo(
    val id: String? = null,
    val emergency_status: String? = null,
    val location: LocationInfo? = null,
    val personal_info: PersonalInfo? = null,
    val medical_info: MedicalInfo? = null,
    val situation: SituationInfo? = null,
    val contact_info: ContactInfo? = null,
    val resources: ResourcesInfo? = null,
    val rescue_info: RescueInfo? = null,
    val environmental_data: EnvironmentalData? = null,
    val device_data: DeviceData? = null,
    val social_info: SocialInfo? = null,
    val psychological_status: PsychologicalStatus? = null
)

data class LocationInfo(val lat: Double, val lon: Double, val details: String?, val nearest_landmark: String?)
data class PersonalInfo(val name: String?, val age: Int?, val gender: String?, val language: String?, val physical_description: String?)
data class MedicalInfo(val injuries: List<String>?, val pain_level: Int?, val medical_conditions: List<String>?, val medications: List<String>?, val allergies: List<String>?, val blood_type: String?)
data class SituationInfo(val disaster_type: String?, val immediate_needs: List<String>?, val trapped: Boolean?, val mobility: String?, val nearby_hazards: List<String>?)
data class ContactInfo(val phone: String?, val email: String?, val emergency_contact: EmergencyContact?)
data class EmergencyContact(val name: String?, val relationship: String?, val phone: String?)
data class ResourcesInfo(val food_status: String?, val water_status: String?, val shelter_status: String?, val communication_devices: List<String>?)
data class RescueInfo(val last_contact: String?, val rescue_team_eta: String?, val special_rescue_needs: String?)
data class EnvironmentalData(val temperature: Double?, val humidity: Double?, val air_quality: String?, val weather: String?)
data class DeviceData(val battery_level: Double?, val network_status: String?)
data class SocialInfo(val group_size: Int?, val dependents: Int?, val nearby_victims_count: Int?, val can_communicate_verbally: Boolean?)
data class PsychologicalStatus(val stress_level: String?, val special_needs: String?)

enum class BackendType {
    FIREBASE, MONGODB
}

enum class RescueAPISettings {
    Enabled,
    Endpoint,
    AutoReport,
    LastVictimNumber,
    FirebaseEnabled,
    MongoDBEnabled
}

/**
 * RescueAPIService for submitting victim information to rescue backends
 * Supports MongoDB and Firebase with automatic failover and mesh propagation
 */
class RescueAPIService(private val context: Context) {
    companion object {
        private const val TAG = "RescueAPIService"
        private var instance: RescueAPIService? = null
        
        fun getInstance(context: Context): RescueAPIService {
            if (instance == null) {
                instance = RescueAPIService(context)
            }
            return instance!!
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences("rescue_api", Context.MODE_PRIVATE)
    private var currentBackend: BackendType = BackendType.MONGODB
    private var initialized = false

    suspend fun initialize() = withContext(Dispatchers.IO) {
        if (initialized) return@withContext
        
        // Set defaults if not already set
        if (!prefs.contains(RescueAPISettings.Endpoint.name)) {
            prefs.edit().putString(RescueAPISettings.Endpoint.name, "https://safeguardian-33b94228882a.herokuapp.com/").apply()
        }
        if (!prefs.contains(RescueAPISettings.Enabled.name)) {
            prefs.edit().putBoolean(RescueAPISettings.Enabled.name, false).apply()
        }
        if (!prefs.contains(RescueAPISettings.AutoReport.name)) {
            prefs.edit().putBoolean(RescueAPISettings.AutoReport.name, true).apply()
        }
        if (!prefs.contains(RescueAPISettings.MongoDBEnabled.name)) {
            prefs.edit().putBoolean(RescueAPISettings.MongoDBEnabled.name, true).apply()
        }
        if (!prefs.contains(RescueAPISettings.FirebaseEnabled.name)) {
            prefs.edit().putBoolean(RescueAPISettings.FirebaseEnabled.name, false).apply()
        }
        
        updateBackendType()
        initialized = true
        Log.d(TAG, "RescueAPIService initialized with backend: $currentBackend")
    }

    private fun updateBackendType() {
        currentBackend = if (prefs.getBoolean(RescueAPISettings.FirebaseEnabled.name, false)) {
            BackendType.FIREBASE
        } else {
            BackendType.MONGODB
        }
    }

    fun setBackendType(type: BackendType) {
        currentBackend = type
        prefs.edit().putBoolean(RescueAPISettings.FirebaseEnabled.name, type == BackendType.FIREBASE).apply()
        prefs.edit().putBoolean(RescueAPISettings.MongoDBEnabled.name, type == BackendType.MONGODB).apply()
        Log.d(TAG, "Backend switched to: $type")
    }

    fun getBackendType(): BackendType = currentBackend

    suspend fun postVictim(
        victimInfo: VictimInfo,
        onNetworkError: (suspend (victimData: String) -> Unit)? = null
    ): String? = withContext(Dispatchers.IO) {
        try {
            initialize()
            val victimJson = victimInfoToJson(victimInfo).toString()

            // 1. Always write locally first
            val localStore = VictimLocalStore(context)
            val localId = localStore.enqueueCreate(victimJson, currentBackend)
            Log.d(TAG, "Victim written locally: $localId")

            // 2a. Firebase — Firestore SDK handles offline persistence natively
            if (currentBackend == BackendType.FIREBASE) {
                val firestoreStore = FirestoreVictimStore(context)
                if (firestoreStore.isAvailable()) {
                    val cloudId = firestoreStore.create(victimJson)
                    if (cloudId != null) {
                        localStore.markSynced(localId, cloudId)
                        localStore.removeSynced(localId)
                        prefs.edit().putString(RescueAPISettings.LastVictimNumber.name, cloudId).apply()
                        return@withContext cloudId
                    }
                }
                // Firestore unavailable or creds missing — enqueue WorkManager sync
                VictimSyncWorker.enqueue(context)
                return@withContext localId   // caller gets local UUID until synced
            }

            // 2b. MongoDB — try HTTP; on failure the WorkManager job will retry
            val endpoint = prefs.getString(RescueAPISettings.Endpoint.name, "") ?: ""
            if (endpoint.isNotEmpty()) {
                val base = endpoint.trimEnd('/')
                val result = makeRequest("POST", "$base/victim/report", victimJson)
                if (result != null) {
                    val cloudId = extractVictimId(result)
                    if (cloudId != null) {
                        localStore.markSynced(localId, cloudId)
                        localStore.removeSynced(localId)
                        prefs.edit().putString(RescueAPISettings.LastVictimNumber.name, cloudId).apply()
                        return@withContext cloudId
                    }
                }
            }
            // MongoDB offline — enqueue for retry
            Log.w(TAG, "MongoDB offline, victim queued for sync")
            onNetworkError?.invoke(victimJson)
            VictimSyncWorker.enqueue(context)
            return@withContext null
        } catch (e: Exception) {
            Log.e(TAG, "Error posting victim", e)
            return@withContext null
        }
    }

    suspend fun updateVictim(
        victimId: String,
        victimInfo: VictimInfo,
        onNetworkError: (suspend (victimData: String) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            initialize()
            val victimJson = victimInfoToJson(victimInfo).toString()

            // 1. Write locally first
            val localStore = VictimLocalStore(context)
            localStore.enqueueUpdate(victimId, victimJson, currentBackend)

            // 2a. Firebase
            if (currentBackend == BackendType.FIREBASE) {
                val firestoreStore = FirestoreVictimStore(context)
                if (firestoreStore.isAvailable()) {
                    return@withContext firestoreStore.update(victimId, victimJson)
                }
                VictimSyncWorker.enqueue(context)
                return@withContext true   // stored locally, will sync
            }

            // 2b. MongoDB
            val endpoint = prefs.getString(RescueAPISettings.Endpoint.name, "") ?: ""
            if (endpoint.isNotEmpty()) {
                val base = endpoint.trimEnd('/')
                val result = makeRequest("POST", "$base/victim/update/$victimId", victimJson)
                if (result != null) {
                    Log.d(TAG, "Victim updated in MongoDB: $victimId")
                    return@withContext true
                }
            }
            Log.w(TAG, "MongoDB offline, update queued for sync")
            onNetworkError?.invoke(victimJson)
            VictimSyncWorker.enqueue(context)
            return@withContext false
        } catch (e: Exception) {
            Log.e(TAG, "Error updating victim", e)
            return@withContext false
        }
    }

    suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) {
        try {
            initialize()
            
            val endpoint = prefs.getString(RescueAPISettings.Endpoint.name, "") ?: ""
            if (endpoint.isEmpty()) return@withContext false

            val url = if (currentBackend == BackendType.MONGODB) {
                "$endpoint/victims/all"
            } else {
                "$endpoint/victims"
            }

            val response = makeRequest("GET", url, null)
            val success = response != null
            Log.d(TAG, "Connection test to $currentBackend backend: ${if (success) "SUCCESS" else "FAILED"}")
            return@withContext success
        } catch (e: Exception) {
            Log.e(TAG, "Connection test failed", e)
            return@withContext false
        }
    }

    private suspend fun makeRequest(
        method: String,
        url: String,
        body: String?
    ): String? = withContext(Dispatchers.IO) {
        return@withContext try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = method
            connection.setRequestProperty("Content-Type", "application/json")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            if (body != null) {
                connection.doOutput = true
                OutputStreamWriter(connection.outputStream).use { it.write(body) }
            }

            val responseCode = connection.responseCode
            Log.d(TAG, "Response code: $responseCode")

            if (responseCode in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                Log.w(TAG, "HTTP error: $responseCode")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Request failed: ${e.message}", e)
            null
        }
    }

    private fun victimInfoToJson(victimInfo: VictimInfo): JSONObject {
        return when (currentBackend) {
            BackendType.MONGODB -> JSONObject().apply {
                put("victim_data", JSONObject().apply {
                    victimInfo.id?.let { put("id", it) }
                    victimInfo.emergency_status?.let { put("emergency_status", it) }
                    victimInfo.personal_info?.let {
                        put("personal_info", JSONObject().apply {
                            it.name?.let { n -> put("name", n) }
                            it.age?.let { a -> put("age", a) }
                            it.gender?.let { g -> put("gender", g) }
                            it.language?.let { l -> put("language", l) }
                            it.physical_description?.let { pd -> put("physical_description", pd) }
                        })
                    }
                    victimInfo.location?.let {
                        put("location", JSONObject().apply {
                            it.lat.let { lat -> put("lat", lat) }
                            it.lon.let { lon -> put("lon", lon) }
                            it.details?.let { d -> put("details", d) }
                            it.nearest_landmark?.let { nl -> put("nearest_landmark", nl) }
                        })
                    }
                    victimInfo.medical_info?.let {
                        put("medical_info", JSONObject().apply {
                            it.injuries?.let { inj -> put("injuries", JSONArray(inj)) }
                            it.pain_level?.let { pl -> put("pain_level", pl) }
                            it.medical_conditions?.let { mc -> put("medical_conditions", JSONArray(mc)) }
                            it.medications?.let { med -> put("medications", JSONArray(med)) }
                            it.allergies?.let { all -> put("allergies", JSONArray(all)) }
                            it.blood_type?.let { bt -> put("blood_type", bt) }
                        })
                    }
                })
            }
            BackendType.FIREBASE -> JSONObject().apply {
                put("victim_info", JSONObject().apply {
                    victimInfo.id?.let { put("id", it) }
                    victimInfo.emergency_status?.let { put("emergency_status", it) }
                    victimInfo.personal_info?.let {
                        put("personal_info", JSONObject().apply {
                            it.name?.let { n -> put("name", n) }
                            it.age?.let { a -> put("age", a) }
                            it.gender?.let { g -> put("gender", g) }
                            it.language?.let { l -> put("language", l) }
                            it.physical_description?.let { pd -> put("physical_description", pd) }
                        })
                    }
                    victimInfo.location?.let {
                        put("location", JSONObject().apply {
                            it.lat.let { lat -> put("lat", lat) }
                            it.lon.let { lon -> put("lon", lon) }
                            it.details?.let { d -> put("details", d) }
                            it.nearest_landmark?.let { nl -> put("nearest_landmark", nl) }
                        })
                    }
                    victimInfo.medical_info?.let {
                        put("medical_info", JSONObject().apply {
                            it.injuries?.let { inj -> put("injuries", JSONArray(inj)) }
                            it.pain_level?.let { pl -> put("pain_level", pl) }
                            it.medical_conditions?.let { mc -> put("medical_conditions", JSONArray(mc)) }
                            it.medications?.let { med -> put("medications", JSONArray(med)) }
                            it.allergies?.let { all -> put("allergies", JSONArray(all)) }
                            it.blood_type?.let { bt -> put("blood_type", bt) }
                        })
                    }
                })
            }
        }
    }

    private fun extractVictimId(response: String): String? {
        return try {
            val json = JSONObject(response)
            json.optString("_id").takeIf { it.isNotEmpty() }
                ?: json.optString("id").takeIf { it.isNotEmpty() }
                ?: json.optString("victim_id").takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            response.trim().takeIf { it.length == 24 || it.startsWith("-") }
        }
    }

    /**
     * Parse a [VictimInfo] out of a raw AI response string.
     *
     * Accepts both backend root keys:
     * - MongoDB: `{"victim_data": {...}}`
     * - Firebase: `{"victim_info": {...}}`
     *
     * Returns null if no valid victim object is found.
     */
    fun parseVictimFromResponse(response: String): VictimInfo? {
        val jsonText = extractJsonSubstring(response) ?: return null
        return try {
            val root = JSONObject(jsonText)
            val obj = root.optJSONObject("victim_data")
                ?: root.optJSONObject("victim_info")
                ?: return null

            val locObj = obj.optJSONObject("location")
            val perObj = obj.optJSONObject("personal_info")
            val medObj = obj.optJSONObject("medical_info")
            val sitObj = obj.optJSONObject("situation")

            VictimInfo(
                id = obj.optString("id").takeIf { it.isNotEmpty() },
                emergency_status = obj.optString("emergency_status").takeIf { it.isNotEmpty() },
                location = locObj?.let {
                    LocationInfo(
                        lat = it.optDouble("lat", 0.0),
                        lon = it.optDouble("lon", 0.0),
                        details = it.optString("details").takeIf { s -> s.isNotEmpty() },
                        nearest_landmark = it.optString("nearest_landmark").takeIf { s -> s.isNotEmpty() }
                    )
                },
                personal_info = perObj?.let {
                    PersonalInfo(
                        name = it.optString("name").takeIf { s -> s.isNotEmpty() },
                        age = if (it.has("age")) it.optInt("age") else null,
                        gender = it.optString("gender").takeIf { s -> s.isNotEmpty() },
                        language = it.optString("language").takeIf { s -> s.isNotEmpty() },
                        physical_description = it.optString("physical_description").takeIf { s -> s.isNotEmpty() }
                    )
                },
                medical_info = medObj?.let {
                    MedicalInfo(
                        injuries = it.optJSONArray("injuries")?.let { a ->
                            (0 until a.length()).map { i -> a.getString(i) }
                        },
                        pain_level = if (it.has("pain_level")) it.optInt("pain_level") else null,
                        medical_conditions = it.optJSONArray("medical_conditions")?.let { a ->
                            (0 until a.length()).map { i -> a.getString(i) }
                        },
                        medications = it.optJSONArray("medications")?.let { a ->
                            (0 until a.length()).map { i -> a.getString(i) }
                        },
                        allergies = it.optJSONArray("allergies")?.let { a ->
                            (0 until a.length()).map { i -> a.getString(i) }
                        },
                        blood_type = it.optString("blood_type").takeIf { s -> s.isNotEmpty() }
                    )
                },
                situation = sitObj?.let {
                    SituationInfo(
                        disaster_type = it.optString("disaster_type").takeIf { s -> s.isNotEmpty() },
                        immediate_needs = it.optJSONArray("immediate_needs")?.let { a ->
                            (0 until a.length()).map { i -> a.getString(i) }
                        },
                        trapped = if (it.has("trapped")) it.optBoolean("trapped") else null,
                        mobility = it.optString("mobility").takeIf { s -> s.isNotEmpty() },
                        nearby_hazards = it.optJSONArray("nearby_hazards")?.let { a ->
                            (0 until a.length()).map { i -> a.getString(i) }
                        }
                    )
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse victim JSON: ${e.message}")
            null
        }
    }

    private fun extractJsonSubstring(text: String): String? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        return if (start >= 0 && end > start) text.substring(start, end + 1) else null
    }
}









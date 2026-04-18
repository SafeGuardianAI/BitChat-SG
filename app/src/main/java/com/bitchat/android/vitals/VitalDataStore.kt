package com.bitchat.android.vitals

import android.content.Context
import android.util.Log
import com.bitchat.android.identity.SecureIdentityStateManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Local encrypted persistence for vital data.
 * Uses SecureIdentityStateManager for EncryptedSharedPreferences storage.
 * Thread-safe singleton following SeenMessageStore pattern.
 */
class VitalDataStore private constructor(private val context: Context) {

    companion object {
        private const val TAG = "VitalDataStore"
        private const val STORAGE_KEY = "vital_data_store_v1"

        @Volatile
        private var INSTANCE: VitalDataStore? = null

        fun getInstance(appContext: Context): VitalDataStore {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: VitalDataStore(appContext.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val gson = Gson()
    private val secure = SecureIdentityStateManager(context)
    private val dataMap = LinkedHashMap<String, VitalData>()

    init {
        load()
    }

    /**
     * Save or update a vital data entry.
     */
    @Synchronized
    fun saveVitalData(data: VitalData) {
        dataMap[data.id] = data
        persist()
        Log.d(TAG, "Saved vital data id=${data.id}, source=${data.source}")
    }

    /**
     * Retrieve a specific vital data entry by ID.
     */
    @Synchronized
    fun getVitalData(id: String): VitalData? {
        return dataMap[id]
    }

    /**
     * Get all stored vital data entries, ordered by timestamp (newest first).
     */
    @Synchronized
    fun getAllVitalData(): List<VitalData> {
        return dataMap.values.sortedByDescending { it.timestamp }
    }

    /**
     * Delete a vital data entry by ID.
     */
    @Synchronized
    fun deleteVitalData(id: String) {
        dataMap.remove(id)
        persist()
        Log.d(TAG, "Deleted vital data id=$id")
    }

    /**
     * Merge all stored entries into a single consolidated vital profile.
     * Later entries take priority over earlier ones for non-list fields.
     * List fields (allergies, medications, etc.) are merged and deduplicated.
     */
    @Synchronized
    fun getLatestVitalProfile(): VitalData? {
        if (dataMap.isEmpty()) return null

        val sorted = dataMap.values.sortedBy { it.timestamp }
        var merged = sorted.first()

        for (entry in sorted.drop(1)) {
            merged = merged.copy(
                timestamp = entry.timestamp,
                fullName = entry.fullName ?: merged.fullName,
                dateOfBirth = entry.dateOfBirth ?: merged.dateOfBirth,
                gender = entry.gender ?: merged.gender,
                bloodType = entry.bloodType ?: merged.bloodType,
                allergies = (merged.allergies + entry.allergies).distinct(),
                medications = (merged.medications + entry.medications).distinct(),
                medicalConditions = (merged.medicalConditions + entry.medicalConditions).distinct(),
                emergencyContacts = mergeContacts(merged.emergencyContacts, entry.emergencyContacts),
                insuranceProvider = entry.insuranceProvider ?: merged.insuranceProvider,
                insurancePolicyNumber = entry.insurancePolicyNumber ?: merged.insurancePolicyNumber,
                insuranceGroupNumber = entry.insuranceGroupNumber ?: merged.insuranceGroupNumber,
                heightCm = entry.heightCm ?: merged.heightCm,
                weightKg = entry.weightKg ?: merged.weightKg
            )
        }

        return merged
    }

    /**
     * Export all vital data as a JSON string for sharing via mesh network.
     */
    @Synchronized
    fun exportAsJson(): String {
        val allData = getAllVitalData()
        return gson.toJson(allData)
    }

    /**
     * Import vital data from a JSON string received via mesh network.
     * Does not overwrite existing entries with the same ID.
     */
    @Synchronized
    fun importFromJson(json: String) {
        try {
            val type = object : TypeToken<List<VitalData>>() {}.type
            val importedList: List<VitalData> = gson.fromJson(json, type)
            var importCount = 0
            for (data in importedList) {
                if (!dataMap.containsKey(data.id)) {
                    dataMap[data.id] = data
                    importCount++
                }
            }
            if (importCount > 0) {
                persist()
            }
            Log.d(TAG, "Imported $importCount new vital data entries (${importedList.size} total in payload)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import vital data from JSON: ${e.message}")
        }
    }

    /**
     * Get the number of stored vital data entries.
     */
    @Synchronized
    fun count(): Int = dataMap.size

    /**
     * Clear all vital data (for emergency wipe).
     */
    @Synchronized
    fun clearAll() {
        dataMap.clear()
        secure.removeSecureValue(STORAGE_KEY)
        Log.w(TAG, "All vital data cleared")
    }

    // -- Private helpers --

    private fun mergeContacts(
        existing: List<EmergencyContact>,
        incoming: List<EmergencyContact>
    ): List<EmergencyContact> {
        val byPhone = LinkedHashMap<String, EmergencyContact>()
        for (contact in existing) {
            byPhone[contact.phone] = contact
        }
        for (contact in incoming) {
            byPhone[contact.phone] = contact
        }
        return byPhone.values.toList()
    }

    private fun load() {
        try {
            val json = secure.getSecureValue(STORAGE_KEY) ?: return
            val type = object : TypeToken<List<VitalData>>() {}.type
            val list: List<VitalData> = gson.fromJson(json, type) ?: return
            dataMap.clear()
            for (data in list) {
                dataMap[data.id] = data
            }
            Log.d(TAG, "Loaded ${dataMap.size} vital data entries")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load VitalDataStore: ${e.message}")
        }
    }

    private fun persist() {
        try {
            val list = dataMap.values.toList()
            val json = gson.toJson(list)
            secure.storeSecureValue(STORAGE_KEY, json)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist VitalDataStore: ${e.message}")
        }
    }
}

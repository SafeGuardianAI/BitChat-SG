package com.bitchat.android.vitals

import kotlinx.serialization.Serializable

/**
 * Vital data extracted from photos or entered manually.
 * Stored locally in encrypted storage for emergency use.
 */
@Serializable
data class VitalData(
    val id: String,
    val timestamp: Long = System.currentTimeMillis(),
    val source: VitalDataSource = VitalDataSource.MANUAL,

    // Personal identification
    val fullName: String? = null,
    val dateOfBirth: String? = null,  // ISO 8601
    val gender: String? = null,
    val bloodType: String? = null,

    // Medical
    val allergies: List<String> = emptyList(),
    val medications: List<String> = emptyList(),
    val medicalConditions: List<String> = emptyList(),
    val emergencyContacts: List<EmergencyContact> = emptyList(),

    // Insurance
    val insuranceProvider: String? = null,
    val insurancePolicyNumber: String? = null,
    val insuranceGroupNumber: String? = null,

    // Physical
    val heightCm: Int? = null,
    val weightKg: Int? = null,

    // Document info
    val documentType: DocumentType? = null,
    val rawExtractedText: String? = null,
    val photoPath: String? = null,  // Local encrypted path
    val confidence: Float = 0f  // 0-1 confidence of extraction
)

@Serializable
enum class VitalDataSource {
    PHOTO_SCAN, MANUAL, AI_EXTRACTED
}

@Serializable
enum class DocumentType {
    ID_CARD, MEDICAL_RECORD, INSURANCE_CARD, ALLERGY_CARD,
    PRESCRIPTION, VACCINATION_RECORD, EMERGENCY_BRACELET, OTHER
}

@Serializable
data class EmergencyContact(
    val name: String,
    val phone: String,
    val relationship: String? = null
)

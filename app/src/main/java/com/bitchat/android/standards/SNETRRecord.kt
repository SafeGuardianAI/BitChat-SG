package com.bitchat.android.standards

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * SAFE-NET Emergency Triage Record (SNETR)
 *
 * Bridges CAP v1.2 alerting, EDXL-TEP patient tracking, and HL7 FHIR R4 clinical data.
 * Designed for OASIS standardization submission (Series A milestone).
 *
 * Maps:
 * - START/SALT triage codes to FHIR Observation resources
 * - BLE survivor proximity data to EDXL-TEP locations
 * - Audio transcripts to FHIR Clinical Notes
 * - Device sensor data to environmental observations
 */
object SNETRGenerator {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    private val compactJson = Json {
        prettyPrint = false
        encodeDefaults = false
    }

    /**
     * Generate a complete SNETR record from all available data.
     */
    fun generateRecord(
        survivor: SurvivorData,
        deviceState: DeviceStateSnapshot,
        meshContext: MeshContext? = null
    ): SNETRRecord {
        val triageAssessment = TriageAssessment()
        val triageLevel = TriageProtocol.assessSTART(triageAssessment)

        return SNETRRecord(
            recordId = "snetr-${System.currentTimeMillis()}",
            incidentId = "inc-${System.currentTimeMillis()}",
            timestamp = java.time.Instant.now().toString(),
            triageLevel = triageLevel,
            triageAssessment = triageAssessment,
            survivor = survivor,
            environment = null,
            deviceId = "device-${deviceState.hashCode()}",
            meshPeers = meshContext?.peerCount ?: 0,
            batteryLevel = deviceState.batteryLevel
        )
    }

    /**
     * Generate a SNETR record with explicit triage data.
     */
    fun generateRecordWithTriage(
        survivor: SurvivorData,
        deviceState: DeviceStateSnapshot,
        triageAssessment: TriageAssessment,
        incidentId: String,
        meshContext: MeshContext? = null
    ): SNETRRecord {
        val triageLevel = TriageProtocol.assessSTART(triageAssessment)

        return SNETRRecord(
            recordId = "snetr-${System.currentTimeMillis()}",
            incidentId = incidentId,
            timestamp = java.time.Instant.now().toString(),
            triageLevel = triageLevel,
            triageAssessment = triageAssessment,
            survivor = survivor,
            environment = null,
            deviceId = "device-${deviceState.hashCode()}",
            meshPeers = meshContext?.peerCount ?: 0,
            batteryLevel = deviceState.batteryLevel,
            collectionMethod = if (triageAssessment.notes != null) "manual" else "automated"
        )
    }

    /**
     * Export SNETR as CAP alert (for broadcasting to nearby devices and agencies).
     */
    fun toCAPAlert(record: SNETRRecord): CAPAlert {
        val lat = record.survivor.location?.latitude ?: 0.0
        val lon = record.survivor.location?.longitude ?: 0.0

        val severity = when (record.triageLevel) {
            TriageLevel.IMMEDIATE -> CAPSeverity.EXTREME
            TriageLevel.DELAYED -> CAPSeverity.SEVERE
            TriageLevel.MINIMAL -> CAPSeverity.MODERATE
            TriageLevel.EXPECTANT -> CAPSeverity.EXTREME
            TriageLevel.DEAD -> CAPSeverity.EXTREME
        }

        val urgency = when (record.triageLevel) {
            TriageLevel.IMMEDIATE -> CAPUrgency.IMMEDIATE
            TriageLevel.DELAYED -> CAPUrgency.EXPECTED
            TriageLevel.MINIMAL -> CAPUrgency.FUTURE
            TriageLevel.EXPECTANT -> CAPUrgency.IMMEDIATE
            TriageLevel.DEAD -> CAPUrgency.PAST
        }

        val injuries = record.survivor.injuries.joinToString(", ").ifEmpty { "Unknown" }
        val description = buildString {
            append("Triage: ${record.triageLevel.code} (${record.triageLevel.color}). ")
            append("Injuries: $injuries. ")
            if (record.survivor.medicalConditions.isNotEmpty()) {
                append("Conditions: ${record.survivor.medicalConditions.joinToString(", ")}. ")
            }
            if (record.meshPeers > 0) {
                append("Mesh peers: ${record.meshPeers}. ")
            }
        }

        return CAPAlert(
            identifier = record.recordId,
            sender = record.collectedBy,
            sent = record.timestamp,
            status = CAPStatus.ACTUAL,
            msgType = CAPMsgType.ALERT,
            scope = CAPScope.RESTRICTED,
            info = listOf(
                CAPInfo(
                    category = CAPCategory.RESCUE,
                    event = "Emergency Triage - ${record.triageLevel.color.uppercase()}",
                    urgency = urgency,
                    severity = severity,
                    certainty = CAPCertainty.OBSERVED,
                    headline = "Survivor ${record.triageLevel.code} (${record.triageLevel.color}) - ${record.incidentId}",
                    description = description,
                    instruction = when (record.triageLevel) {
                        TriageLevel.IMMEDIATE -> "Immediate medical attention required"
                        TriageLevel.DELAYED -> "Medical attention needed within 1-2 hours"
                        TriageLevel.MINIMAL -> "Minor injuries, can wait for treatment"
                        TriageLevel.EXPECTANT -> "Comfort care only"
                        TriageLevel.DEAD -> "No intervention needed"
                    },
                    area = if (lat != 0.0 || lon != 0.0) {
                        listOf(
                            CAPArea(
                                areaDesc = record.survivor.location?.description ?: "Survivor location",
                                circle = "$lat,$lon 0.5"
                            )
                        )
                    } else {
                        emptyList()
                    }
                )
            )
        )
    }

    /**
     * Export SNETR as FHIR Bundle (for hospital handoff).
     */
    fun toFHIRBundle(record: SNETRRecord): String {
        val patient = FHIRPatient(
            id = "patient-${record.recordId}",
            name = record.survivor.name,
            gender = record.survivor.gender,
            birthDate = null,
            identifier = record.recordId,
            bloodType = record.survivor.bloodType,
            allergies = record.survivor.allergies,
            language = "en"
        )

        val encounter = FHIREncounter(
            id = "encounter-${record.recordId}",
            status = "in-progress",
            classCode = "EMER",
            period = record.timestamp,
            locationLatitude = record.survivor.location?.latitude,
            locationLongitude = record.survivor.location?.longitude,
            triageLevel = record.triageLevel,
            disasterType = null
        )

        val conditions = record.survivor.injuries.mapIndexed { index, injury ->
            FHIRCondition(
                id = "condition-${record.recordId}-$index",
                code = "282291009",  // SNOMED CT: Diagnosis
                displayName = injury,
                severity = when (record.triageLevel) {
                    TriageLevel.IMMEDIATE, TriageLevel.EXPECTANT -> "severe"
                    TriageLevel.DELAYED -> "moderate"
                    TriageLevel.MINIMAL -> "mild"
                    TriageLevel.DEAD -> "severe"
                }
            )
        }

        val observations = mutableListOf<FHIRObservation>()

        // Triage observation
        observations.add(
            FHIRObservation(
                id = "obs-triage-${record.recordId}",
                code = "56839-4",
                displayName = "Triage category",
                value = "${record.triageLevel.code} (${record.triageLevel.color})",
                unit = null,
                timestamp = record.timestamp
            )
        )

        // Respiratory rate
        if (record.triageAssessment.respiratoryRate != null) {
            observations.add(
                FHIRObservation(
                    id = "obs-resp-${record.recordId}",
                    code = "9279-1",
                    displayName = "Respiratory rate",
                    value = record.triageAssessment.respiratoryRate.toString(),
                    unit = "/min",
                    timestamp = record.timestamp
                )
            )
        }

        // Consciousness / AVPU
        if (record.triageAssessment.consciousness != null) {
            observations.add(
                FHIRObservation(
                    id = "obs-avpu-${record.recordId}",
                    code = "11454-6",
                    displayName = "Level of consciousness (AVPU)",
                    value = record.triageAssessment.consciousness,
                    unit = null,
                    timestamp = record.timestamp
                )
            )
        }

        // Environment observations
        if (record.environment != null) {
            if (record.environment.temperature != null) {
                observations.add(
                    FHIRObservation(
                        id = "obs-temp-${record.recordId}",
                        code = "60832-3",
                        displayName = "Ambient temperature",
                        value = record.environment.temperature.toString(),
                        unit = "Cel",
                        timestamp = record.timestamp
                    )
                )
            }
            if (record.environment.noiseLevel != null) {
                observations.add(
                    FHIRObservation(
                        id = "obs-noise-${record.recordId}",
                        code = "89022-9",
                        displayName = "Ambient noise level",
                        value = record.environment.noiseLevel.toString(),
                        unit = "dB",
                        timestamp = record.timestamp
                    )
                )
            }
        }

        return FHIRResourceGenerator.generateEmergencyBundle(
            patient = patient,
            encounter = encounter,
            conditions = conditions,
            observations = observations
        )
    }

    /**
     * Export SNETR as EDXL-TEP (for field tracking).
     */
    fun toEDXLPatient(record: SNETRRecord): EDXLPatient {
        val location = if (record.survivor.location != null) {
            EDXLLocation(
                latitude = record.survivor.location.latitude,
                longitude = record.survivor.location.longitude,
                altitude = record.survivor.location.altitude,
                description = record.survivor.location.description
            )
        } else {
            null
        }

        val vitals = EDXLVitalSigns(
            respiratoryRate = record.triageAssessment.respiratoryRate,
            consciousness = record.triageAssessment.consciousness
        )

        return EDXLPatient(
            patientId = record.recordId,
            incidentId = record.incidentId,
            triageStatus = record.triageLevel,
            transportStatus = TransportStatus.NOT_TRANSPORTED,
            lastKnownLocation = location,
            patientName = record.survivor.name,
            gender = record.survivor.gender,
            estimatedAge = record.survivor.age?.toString(),
            chiefComplaint = record.survivor.injuries.firstOrNull(),
            injuries = record.survivor.injuries,
            vitalSigns = vitals,
            timestamp = record.timestamp
        )
    }

    /**
     * Export as compact JSON for BLE mesh transmission.
     * Minimizes payload size for low-bandwidth mesh networks.
     */
    fun toMeshPayload(record: SNETRRecord): String {
        val payload = MeshPayload(
            id = record.recordId,
            inc = record.incidentId,
            t = record.triageLevel.code,
            lat = record.survivor.location?.latitude,
            lon = record.survivor.location?.longitude,
            acc = record.survivor.location?.accuracy,
            bat = record.batteryLevel,
            mp = record.meshPeers,
            ts = record.timestamp,
            inj = record.survivor.injuries.size,
            rr = record.triageAssessment.respiratoryRate,
            w = record.triageAssessment.canWalk,
            b = record.triageAssessment.isBreathing
        )
        return compactJson.encodeToString(payload)
    }

    /**
     * Reconstruct a partial SNETR record from a mesh payload.
     */
    fun fromMeshPayload(payloadJson: String): SNETRRecord {
        val payload = compactJson.decodeFromString<MeshPayload>(payloadJson)

        val location = if (payload.lat != null && payload.lon != null) {
            LocationData(
                latitude = payload.lat,
                longitude = payload.lon,
                accuracy = payload.acc ?: 0f
            )
        } else {
            null
        }

        val triageLevel = TriageLevel.entries.find { it.code == payload.t } ?: TriageLevel.DELAYED

        return SNETRRecord(
            recordId = payload.id,
            incidentId = payload.inc,
            timestamp = payload.ts,
            triageLevel = triageLevel,
            triageAssessment = TriageAssessment(
                canWalk = payload.w,
                isBreathing = payload.b,
                respiratoryRate = payload.rr
            ),
            survivor = SurvivorData(
                location = location
            ),
            deviceId = "mesh-relay",
            meshPeers = payload.mp ?: 0,
            batteryLevel = payload.bat ?: -1,
            collectionMethod = "mesh-relay"
        )
    }

    /**
     * Serialize a full SNETR record to JSON.
     */
    fun toJson(record: SNETRRecord): String {
        return json.encodeToString(record)
    }

    /**
     * Deserialize a SNETR record from JSON.
     */
    fun fromJson(jsonString: String): SNETRRecord {
        return json.decodeFromString(jsonString)
    }
}

/**
 * Compact mesh payload for BLE transmission.
 * Field names are abbreviated to minimize payload size.
 */
@Serializable
data class MeshPayload(
    val id: String,        // recordId
    val inc: String,       // incidentId
    val t: String,         // triage code (T1/T2/T3/T4/T0)
    val lat: Double? = null,
    val lon: Double? = null,
    val acc: Float? = null, // GPS accuracy
    val bat: Int? = null,  // battery level
    val mp: Int? = null,   // mesh peers
    val ts: String,        // timestamp
    val inj: Int? = null,  // injury count
    val rr: Int? = null,   // respiratory rate
    val w: Boolean? = null, // can walk
    val b: Boolean? = null  // is breathing
)

@Serializable
data class SNETRRecord(
    val version: String = "1.0",
    val recordId: String,
    val incidentId: String,
    val timestamp: String,

    // Triage (START/SALT)
    val triageLevel: TriageLevel,
    val triageAssessment: TriageAssessment,

    // Survivor
    val survivor: SurvivorData,

    // Environment
    val environment: EnvironmentData? = null,

    // Device
    val deviceId: String,
    val meshPeers: Int = 0,
    val batteryLevel: Int = -1,

    // Provenance
    val collectedBy: String = "SafeGuardian",
    val collectionMethod: String = "automated"
)

@Serializable
data class TriageAssessment(
    val canWalk: Boolean? = null,
    val isBreathing: Boolean? = null,
    val respiratoryRate: Int? = null,
    val hasPulse: Boolean? = null,
    val capillaryRefill: Int? = null,
    val followsCommands: Boolean? = null,
    val consciousness: String? = null,
    val notes: String? = null
)

@Serializable
data class SurvivorData(
    val name: String? = null,
    val age: Int? = null,
    val gender: String? = null,
    val bloodType: String? = null,
    val injuries: List<String> = emptyList(),
    val medicalConditions: List<String> = emptyList(),
    val allergies: List<String> = emptyList(),
    val medications: List<String> = emptyList(),
    val location: LocationData? = null,
    val contactInfo: String? = null
)

@Serializable
data class LocationData(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float = 0f,
    val altitude: Double? = null,
    val floor: Int? = null,
    val description: String? = null,
    val geohash: String? = null
)

@Serializable
data class EnvironmentData(
    val lightLevel: Float? = null,
    val isTrapped: Boolean? = null,
    val temperature: Float? = null,
    val noiseLevel: Float? = null,
    val airQuality: String? = null
)

@Serializable
data class DeviceStateSnapshot(
    val batteryLevel: Int,
    val isCharging: Boolean,
    val networkType: String,
    val meshPeerCount: Int,
    val gpsAccuracy: Float?
)

@Serializable
data class MeshContext(
    val peerCount: Int,
    val channelName: String,
    val relayHops: Int = 0
)

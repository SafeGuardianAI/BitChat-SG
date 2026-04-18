package com.bitchat.android.standards

import kotlinx.serialization.Serializable

/**
 * EDXL-TEP v1.1 (Emergency Data Exchange Language - Tracking of Emergency Patients)
 *
 * Generates EDXL-TEP compliant records for pre-hospital patient tracking.
 * Enables handoff between field teams, transport, and hospitals.
 *
 * Reference: https://docs.oasis-open.org/emergency/edxl-tep/v1.1/
 */
object EDXLPatientTracker {

    private const val EDXL_TEP_NAMESPACE = "urn:oasis:names:tc:emergency:edxl:tep:1.1"

    /**
     * Generate an EDXL-TEP v1.1 XML patient record.
     */
    fun generatePatientRecord(patient: EDXLPatient): String {
        return buildString {
            appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            appendLine("<TEPReport xmlns=\"$EDXL_TEP_NAMESPACE\">")
            appendLine("  <PatientRecord>")
            appendLine("    <PatientID>${escapeXml(patient.patientId)}</PatientID>")
            appendLine("    <IncidentID>${escapeXml(patient.incidentId)}</IncidentID>")
            appendLine("    <Timestamp>${escapeXml(patient.timestamp)}</Timestamp>")
            appendLine("    <TriageStatus>")
            appendLine("      <Code>${patient.triageStatus.code}</Code>")
            appendLine("      <Color>${patient.triageStatus.color}</Color>")
            appendLine("      <Description>${escapeXml(patient.triageStatus.description)}</Description>")
            appendLine("    </TriageStatus>")
            appendLine("    <TransportStatus>${patient.transportStatus.name}</TransportStatus>")

            if (patient.patientName != null) {
                appendLine("    <PatientName>${escapeXml(patient.patientName)}</PatientName>")
            }
            if (patient.gender != null) {
                appendLine("    <Gender>${escapeXml(patient.gender)}</Gender>")
            }
            if (patient.estimatedAge != null) {
                appendLine("    <EstimatedAge>${escapeXml(patient.estimatedAge)}</EstimatedAge>")
            }
            if (patient.chiefComplaint != null) {
                appendLine("    <ChiefComplaint>${escapeXml(patient.chiefComplaint)}</ChiefComplaint>")
            }

            if (patient.injuries.isNotEmpty()) {
                appendLine("    <Injuries>")
                for (injury in patient.injuries) {
                    appendLine("      <Injury>${escapeXml(injury)}</Injury>")
                }
                appendLine("    </Injuries>")
            }

            if (patient.lastKnownLocation != null) {
                appendLine("    <LastKnownLocation>")
                appendLine("      <Latitude>${patient.lastKnownLocation.latitude}</Latitude>")
                appendLine("      <Longitude>${patient.lastKnownLocation.longitude}</Longitude>")
                if (patient.lastKnownLocation.altitude != null) {
                    appendLine("      <Altitude>${patient.lastKnownLocation.altitude}</Altitude>")
                }
                if (patient.lastKnownLocation.description != null) {
                    appendLine("      <Description>${escapeXml(patient.lastKnownLocation.description)}</Description>")
                }
                appendLine("    </LastKnownLocation>")
            }

            if (patient.destinationFacility != null) {
                appendLine("    <DestinationFacility>${escapeXml(patient.destinationFacility)}</DestinationFacility>")
            }

            if (patient.vitalSigns != null) {
                appendLine("    <VitalSigns>")
                if (patient.vitalSigns.pulse != null) {
                    appendLine("      <Pulse>${patient.vitalSigns.pulse}</Pulse>")
                }
                if (patient.vitalSigns.respiratoryRate != null) {
                    appendLine("      <RespiratoryRate>${patient.vitalSigns.respiratoryRate}</RespiratoryRate>")
                }
                if (patient.vitalSigns.bloodPressure != null) {
                    appendLine("      <BloodPressure>${escapeXml(patient.vitalSigns.bloodPressure)}</BloodPressure>")
                }
                if (patient.vitalSigns.consciousness != null) {
                    appendLine("      <Consciousness>${escapeXml(patient.vitalSigns.consciousness)}</Consciousness>")
                }
                appendLine("    </VitalSigns>")
            }

            appendLine("  </PatientRecord>")
            appendLine("</TEPReport>")
        }
    }

    /**
     * Convert an EDXL-TEP patient record to a FHIR Patient resource.
     */
    fun toFHIR(patient: EDXLPatient): FHIRPatient {
        return FHIRPatient(
            id = patient.patientId,
            name = patient.patientName,
            gender = mapGenderToFHIR(patient.gender),
            birthDate = null,
            identifier = patient.patientId,
            bloodType = null,
            allergies = emptyList(),
            language = "en"
        )
    }

    /**
     * Convert an EDXL-TEP patient to a FHIR Encounter resource.
     */
    fun toFHIREncounter(patient: EDXLPatient): FHIREncounter {
        return FHIREncounter(
            id = "enc-${patient.patientId}",
            status = mapTransportStatusToFHIR(patient.transportStatus),
            classCode = "EMER",
            period = patient.timestamp,
            locationLatitude = patient.lastKnownLocation?.latitude,
            locationLongitude = patient.lastKnownLocation?.longitude,
            triageLevel = patient.triageStatus,
            disasterType = null
        )
    }

    /**
     * Convert FHIR Patient and Encounter back to an EDXL-TEP patient record.
     */
    fun fromFHIR(fhirPatient: FHIRPatient, encounter: FHIREncounter): EDXLPatient {
        return EDXLPatient(
            patientId = fhirPatient.id,
            incidentId = "inc-${encounter.id}",
            triageStatus = encounter.triageLevel ?: TriageLevel.DELAYED,
            transportStatus = mapFHIRStatusToTransport(encounter.status),
            lastKnownLocation = if (encounter.locationLatitude != null && encounter.locationLongitude != null) {
                EDXLLocation(
                    latitude = encounter.locationLatitude,
                    longitude = encounter.locationLongitude
                )
            } else {
                null
            },
            destinationFacility = null,
            patientName = fhirPatient.name,
            gender = fhirPatient.gender,
            estimatedAge = null,
            chiefComplaint = null,
            injuries = emptyList(),
            vitalSigns = null,
            timestamp = encounter.period
        )
    }

    /**
     * Generate a list of FHIR Observations from EDXL vital signs.
     */
    fun vitalSignsToFHIR(
        patientId: String,
        vitals: EDXLVitalSigns,
        timestamp: String
    ): List<FHIRObservation> {
        val observations = mutableListOf<FHIRObservation>()

        if (vitals.pulse != null) {
            observations.add(
                FHIRObservation(
                    id = "obs-pulse-$patientId",
                    code = "8867-4",
                    displayName = "Heart rate",
                    value = vitals.pulse.toString(),
                    unit = "/min",
                    timestamp = timestamp
                )
            )
        }
        if (vitals.respiratoryRate != null) {
            observations.add(
                FHIRObservation(
                    id = "obs-resp-$patientId",
                    code = "9279-1",
                    displayName = "Respiratory rate",
                    value = vitals.respiratoryRate.toString(),
                    unit = "/min",
                    timestamp = timestamp
                )
            )
        }
        if (vitals.bloodPressure != null) {
            observations.add(
                FHIRObservation(
                    id = "obs-bp-$patientId",
                    code = "85354-9",
                    displayName = "Blood pressure",
                    value = vitals.bloodPressure,
                    unit = "mmHg",
                    timestamp = timestamp
                )
            )
        }
        if (vitals.consciousness != null) {
            observations.add(
                FHIRObservation(
                    id = "obs-avpu-$patientId",
                    code = "11454-6",
                    displayName = "Level of consciousness (AVPU)",
                    value = vitals.consciousness,
                    unit = null,
                    timestamp = timestamp
                )
            )
        }

        return observations
    }

    private fun mapGenderToFHIR(gender: String?): String? {
        if (gender == null) return null
        return when (gender.lowercase()) {
            "m", "male" -> "male"
            "f", "female" -> "female"
            "o", "other" -> "other"
            else -> "unknown"
        }
    }

    private fun mapTransportStatusToFHIR(status: TransportStatus): String {
        return when (status) {
            TransportStatus.NOT_TRANSPORTED -> "triaged"
            TransportStatus.IN_TRANSIT -> "in-progress"
            TransportStatus.ARRIVED -> "arrived"
            TransportStatus.DISCHARGED -> "finished"
        }
    }

    private fun mapFHIRStatusToTransport(status: String): TransportStatus {
        return when (status.lowercase()) {
            "planned", "triaged" -> TransportStatus.NOT_TRANSPORTED
            "in-progress" -> TransportStatus.IN_TRANSIT
            "arrived" -> TransportStatus.ARRIVED
            "finished", "cancelled" -> TransportStatus.DISCHARGED
            else -> TransportStatus.NOT_TRANSPORTED
        }
    }

    private fun escapeXml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}

@Serializable
data class EDXLPatient(
    val patientId: String,
    val incidentId: String,
    val triageStatus: TriageLevel,
    val transportStatus: TransportStatus = TransportStatus.NOT_TRANSPORTED,
    val lastKnownLocation: EDXLLocation? = null,
    val destinationFacility: String? = null,
    val patientName: String? = null,
    val gender: String? = null,
    val estimatedAge: String? = null,
    val chiefComplaint: String? = null,
    val injuries: List<String> = emptyList(),
    val vitalSigns: EDXLVitalSigns? = null,
    val timestamp: String
)

@Serializable
data class EDXLLocation(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double? = null,
    val description: String? = null
)

@Serializable
data class EDXLVitalSigns(
    val pulse: Int? = null,
    val respiratoryRate: Int? = null,
    val bloodPressure: String? = null,
    val consciousness: String? = null
)

enum class TransportStatus {
    NOT_TRANSPORTED,
    IN_TRANSIT,
    ARRIVED,
    DISCHARGED
}

package com.bitchat.android.standards

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * HL7 FHIR R4 Emergency Resources
 *
 * Generates FHIR-compliant JSON for clinical handoff.
 * Based on US-SAFR (Situational Awareness for Emergency Response) IG.
 *
 * Resources generated:
 * - Patient: survivor identification
 * - Encounter: emergency encounter
 * - Condition: injuries and medical conditions
 * - Observation: vital signs and triage score
 * - Location: GPS coordinates
 */
object FHIRResourceGenerator {

    private const val FHIR_VERSION = "4.0.1"

    /**
     * Generate a FHIR R4 Patient resource as JSON string.
     */
    fun generatePatient(patient: FHIRPatient): String {
        val json = buildPatientJson(patient)
        return json.toString()
    }

    /**
     * Generate a FHIR R4 Encounter resource as JSON string.
     */
    fun generateEncounter(encounter: FHIREncounter): String {
        val json = buildEncounterJson(encounter)
        return json.toString()
    }

    /**
     * Generate a FHIR R4 Condition resource as JSON string.
     */
    fun generateCondition(condition: FHIRCondition): String {
        val json = buildConditionJson(condition)
        return json.toString()
    }

    /**
     * Generate a FHIR R4 Observation resource as JSON string.
     */
    fun generateObservation(observation: FHIRObservation): String {
        val json = buildObservationJson(observation)
        return json.toString()
    }

    /**
     * Generate a FHIR Bundle containing multiple resources.
     */
    fun generateBundle(resources: List<String>): String {
        val entries = buildJsonArray {
            for (resource in resources) {
                add(buildJsonObject {
                    put("resource", JsonPrimitive(resource))
                })
            }
        }
        val bundle = buildJsonObject {
            put("resourceType", "Bundle")
            put("type", "collection")
            put("entry", entries)
        }
        return bundle.toString()
    }

    /**
     * Generate a FHIR Bundle wrapping proper JsonObject resources.
     */
    private fun generateBundleFromJsonObjects(resources: List<JsonObject>): String {
        val entries = buildJsonArray {
            for (resource in resources) {
                add(buildJsonObject {
                    put("fullUrl", "urn:uuid:${resource["id"]}")
                    put("resource", resource)
                })
            }
        }
        val bundle = buildJsonObject {
            put("resourceType", "Bundle")
            put("type", "collection")
            put("timestamp", currentTimestamp())
            put("entry", entries)
        }
        return bundle.toString()
    }

    /**
     * Generate a complete emergency bundle for a survivor.
     * Includes Patient, Encounter, all Conditions, and all Observations.
     */
    fun generateEmergencyBundle(
        patient: FHIRPatient,
        encounter: FHIREncounter,
        conditions: List<FHIRCondition>,
        observations: List<FHIRObservation>
    ): String {
        val resources = mutableListOf<JsonObject>()
        resources.add(buildPatientJson(patient))
        resources.add(buildEncounterJson(encounter))
        for (condition in conditions) {
            resources.add(buildConditionJson(condition))
        }
        for (observation in observations) {
            resources.add(buildObservationJson(observation))
        }
        return generateBundleFromJsonObjects(resources)
    }

    /**
     * Generate a FHIR Observation for a triage assessment.
     */
    fun generateTriageObservation(
        id: String,
        triageLevel: TriageLevel,
        timestamp: String,
        patientId: String
    ): String {
        val json = buildJsonObject {
            put("resourceType", "Observation")
            put("id", id)
            put("status", "final")
            put("category", buildJsonArray {
                add(buildJsonObject {
                    put("coding", buildJsonArray {
                        add(buildJsonObject {
                            put("system", "http://terminology.hl7.org/CodeSystem/observation-category")
                            put("code", "survey")
                            put("display", "Survey")
                        })
                    })
                })
            })
            put("code", buildJsonObject {
                put("coding", buildJsonArray {
                    add(buildJsonObject {
                        put("system", "http://loinc.org")
                        put("code", "56839-4")
                        put("display", "Triage category")
                    })
                })
                put("text", "START Triage Level")
            })
            put("subject", buildJsonObject {
                put("reference", "Patient/$patientId")
            })
            put("effectiveDateTime", timestamp)
            put("valueCodeableConcept", buildJsonObject {
                put("coding", buildJsonArray {
                    add(buildJsonObject {
                        put("system", "urn:safeguardian:triage:start")
                        put("code", triageLevel.code)
                        put("display", "${triageLevel.color} - ${triageLevel.description}")
                    })
                })
                put("text", "${triageLevel.code} (${triageLevel.color})")
            })
        }
        return json.toString()
    }

    // --- Internal JSON builders ---

    internal fun buildPatientJson(patient: FHIRPatient): JsonObject {
        return buildJsonObject {
            put("resourceType", "Patient")
            put("id", patient.id)
            put("meta", buildJsonObject {
                put("profile", buildJsonArray {
                    add(JsonPrimitive("http://hl7.org/fhir/us/core/StructureDefinition/us-core-patient"))
                })
            })

            if (patient.identifier != null) {
                put("identifier", buildJsonArray {
                    add(buildJsonObject {
                        put("system", "urn:safeguardian:patient-id")
                        put("value", patient.identifier)
                    })
                })
            }

            if (patient.name != null) {
                put("name", buildJsonArray {
                    add(buildJsonObject {
                        put("use", "usual")
                        put("text", patient.name)
                    })
                })
            }

            if (patient.gender != null) {
                put("gender", patient.gender)
            }

            if (patient.birthDate != null) {
                put("birthDate", patient.birthDate)
            }

            put("communication", buildJsonArray {
                add(buildJsonObject {
                    put("language", buildJsonObject {
                        put("coding", buildJsonArray {
                            add(buildJsonObject {
                                put("system", "urn:ietf:bcp:47")
                                put("code", patient.language)
                            })
                        })
                    })
                    put("preferred", true)
                })
            })

            if (patient.bloodType != null) {
                put("extension", buildJsonArray {
                    add(buildJsonObject {
                        put("url", "urn:safeguardian:blood-type")
                        put("valueString", patient.bloodType)
                    })
                })
            }
        }
    }

    internal fun buildEncounterJson(encounter: FHIREncounter): JsonObject {
        return buildJsonObject {
            put("resourceType", "Encounter")
            put("id", encounter.id)
            put("status", encounter.status)
            put("class", buildJsonObject {
                put("system", "http://terminology.hl7.org/CodeSystem/v3-ActCode")
                put("code", encounter.classCode)
                put("display", "Emergency")
            })
            put("type", buildJsonArray {
                add(buildJsonObject {
                    put("coding", buildJsonArray {
                        add(buildJsonObject {
                            put("system", "http://snomed.info/sct")
                            put("code", "50849002")
                            put("display", "Emergency room admission")
                        })
                    })
                })
            })
            put("period", buildJsonObject {
                put("start", encounter.period)
            })

            if (encounter.triageLevel != null) {
                put("priority", buildJsonObject {
                    put("coding", buildJsonArray {
                        add(buildJsonObject {
                            put("system", "urn:safeguardian:triage:start")
                            put("code", encounter.triageLevel.code)
                            put("display", "${encounter.triageLevel.color} - ${encounter.triageLevel.description}")
                        })
                    })
                })
            }

            if (encounter.locationLatitude != null && encounter.locationLongitude != null) {
                put("location", buildJsonArray {
                    add(buildJsonObject {
                        put("location", buildJsonObject {
                            put("display", "${encounter.locationLatitude},${encounter.locationLongitude}")
                        })
                        put("extension", buildJsonArray {
                            add(buildJsonObject {
                                put("url", "urn:safeguardian:gps")
                                put("valueString", "${encounter.locationLatitude},${encounter.locationLongitude}")
                            })
                        })
                    })
                })
            }

            if (encounter.disasterType != null) {
                put("reasonCode", buildJsonArray {
                    add(buildJsonObject {
                        put("text", encounter.disasterType)
                    })
                })
            }
        }
    }

    internal fun buildConditionJson(condition: FHIRCondition): JsonObject {
        return buildJsonObject {
            put("resourceType", "Condition")
            put("id", condition.id)
            put("clinicalStatus", buildJsonObject {
                put("coding", buildJsonArray {
                    add(buildJsonObject {
                        put("system", "http://terminology.hl7.org/CodeSystem/condition-clinical")
                        put("code", "active")
                    })
                })
            })
            put("code", buildJsonObject {
                put("coding", buildJsonArray {
                    add(buildJsonObject {
                        put("system", "http://snomed.info/sct")
                        put("code", condition.code)
                        put("display", condition.displayName)
                    })
                })
                put("text", condition.displayName)
            })

            if (condition.severity != null) {
                val severityCode = when (condition.severity.lowercase()) {
                    "mild" -> "255604002"
                    "moderate" -> "6736007"
                    "severe" -> "24484000"
                    else -> condition.severity
                }
                val severityDisplay = condition.severity.replaceFirstChar { it.uppercase() }
                put("severity", buildJsonObject {
                    put("coding", buildJsonArray {
                        add(buildJsonObject {
                            put("system", "http://snomed.info/sct")
                            put("code", severityCode)
                            put("display", severityDisplay)
                        })
                    })
                })
            }

            if (condition.bodySite != null) {
                put("bodySite", buildJsonArray {
                    add(buildJsonObject {
                        put("text", condition.bodySite)
                    })
                })
            }
        }
    }

    internal fun buildObservationJson(observation: FHIRObservation): JsonObject {
        return buildJsonObject {
            put("resourceType", "Observation")
            put("id", observation.id)
            put("status", "final")
            put("code", buildJsonObject {
                put("coding", buildJsonArray {
                    add(buildJsonObject {
                        put("system", "http://loinc.org")
                        put("code", observation.code)
                        put("display", observation.displayName)
                    })
                })
                put("text", observation.displayName)
            })
            put("effectiveDateTime", observation.timestamp)

            if (observation.unit != null) {
                put("valueQuantity", buildJsonObject {
                    put("value", JsonPrimitive(observation.value.toDoubleOrNull() ?: 0.0))
                    put("unit", observation.unit)
                    put("system", "http://unitsofmeasure.org")
                })
            } else {
                put("valueString", observation.value)
            }
        }
    }

    private fun currentTimestamp(): String {
        // Return current time in ISO 8601 format
        return java.time.Instant.now().toString()
    }
}

@Serializable
data class FHIRPatient(
    val id: String,
    val name: String? = null,
    val gender: String? = null,
    val birthDate: String? = null,
    val identifier: String? = null,
    val bloodType: String? = null,
    val allergies: List<String> = emptyList(),
    val language: String = "en"
)

@Serializable
data class FHIREncounter(
    val id: String,
    val status: String = "in-progress",
    val classCode: String = "EMER",
    val period: String,
    val locationLatitude: Double? = null,
    val locationLongitude: Double? = null,
    val triageLevel: TriageLevel? = null,
    val disasterType: String? = null
)

@Serializable
data class FHIRCondition(
    val id: String,
    val code: String,
    val displayName: String,
    val severity: String? = null,
    val bodySite: String? = null
)

@Serializable
data class FHIRObservation(
    val id: String,
    val code: String,
    val displayName: String,
    val value: String,
    val unit: String? = null,
    val timestamp: String
)

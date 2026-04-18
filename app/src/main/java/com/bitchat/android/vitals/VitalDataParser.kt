package com.bitchat.android.vitals

import android.util.Log
import java.util.UUID

/**
 * On-device parser that extracts vital data from OCR text output.
 * Uses regex patterns and keyword detection. No cloud API required.
 *
 * Designed to handle noisy OCR output from photos of:
 * - ID cards
 * - Medical records
 * - Insurance cards
 * - Allergy cards / emergency bracelets
 * - Prescriptions
 * - Vaccination records
 */
class VitalDataParser {

    companion object {
        private const val TAG = "VitalDataParser"

        // Blood type patterns (case-insensitive, handles OCR noise)
        private val BLOOD_TYPE_PATTERN = Regex(
            """(?i)\b(A|B|AB|O)\s*([+\-]|pos(?:itive)?|neg(?:ative)?)\b"""
        )

        // Date patterns
        private val DATE_MM_DD_YYYY = Regex(
            """(?<!\d)(0?[1-9]|1[0-2])[/\-](0?[1-9]|[12]\d|3[01])[/\-](19|20)\d{2}(?!\d)"""
        )
        private val DATE_DD_MM_YYYY = Regex(
            """(?<!\d)(0?[1-9]|[12]\d|3[01])[/\-](0?[1-9]|1[0-2])[/\-](19|20)\d{2}(?!\d)"""
        )
        private val DATE_YYYY_MM_DD = Regex(
            """(?<!\d)(19|20)\d{2}[/\-](0?[1-9]|1[0-2])[/\-](0?[1-9]|[12]\d|3[01])(?!\d)"""
        )
        private val DATE_WRITTEN = Regex(
            """(?i)(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:tember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)\s+(\d{1,2}),?\s+(19|20)\d{2}"""
        )

        // Phone number pattern (US-centric but flexible)
        private val PHONE_PATTERN = Regex(
            """(?<!\d)(?:\+?1[\s\-]?)?(?:\(?\d{3}\)?[\s\-]?)?\d{3}[\s\-]?\d{4}(?!\d)"""
        )

        // Name label patterns
        private val NAME_PATTERNS = listOf(
            Regex("""(?i)(?:patient|member|name|insured|subscriber|holder)\s*(?:name)?\s*[:=]\s*(.+)"""),
            Regex("""(?i)(?:name)\s*[:=]\s*(.+)""")
        )

        // Allergy label patterns
        private val ALLERGY_PATTERNS = listOf(
            Regex("""(?i)(?:allergies|allergic\s+to|allergy|known\s+allergies)\s*[:=]\s*(.+)"""),
            Regex("""(?i)(?:drug\s+allergies|food\s+allergies|medication\s+allergies)\s*[:=]\s*(.+)""")
        )

        // Medication label patterns
        private val MEDICATION_PATTERNS = listOf(
            Regex("""(?i)(?:medications?|current\s+meds?|prescriptions?|rx|medicines?)\s*[:=]\s*(.+)"""),
            Regex("""(?i)(?:taking|prescribed)\s*[:=]?\s*(.+)""")
        )

        // Medical condition label patterns
        private val CONDITION_PATTERNS = listOf(
            Regex("""(?i)(?:diagnosis|diagnoses|conditions?|medical\s+history|medical\s+conditions?|chronic\s+conditions?)\s*[:=]\s*(.+)"""),
            Regex("""(?i)(?:dx|hx)\s*[:=]\s*(.+)""")
        )

        // Insurance patterns
        private val INSURANCE_PROVIDER_PATTERNS = listOf(
            Regex("""(?i)(?:insurance|insurer|carrier|plan|provider)\s*(?:company|name)?\s*[:=]\s*(.+)""")
        )
        private val POLICY_NUMBER_PATTERNS = listOf(
            Regex("""(?i)(?:policy|member\s+id|member\s+#|id\s+number|id\s*#|subscriber\s+id)\s*[:=]?\s*([A-Z0-9\-]+)""")
        )
        private val GROUP_NUMBER_PATTERNS = listOf(
            Regex("""(?i)(?:group|grp)\s*(?:number|no|#|id)?\s*[:=]?\s*([A-Z0-9\-]+)""")
        )

        // Emergency contact patterns
        private val EMERGENCY_CONTACT_PATTERNS = listOf(
            Regex("""(?i)(?:emergency|ice|in\s+case\s+of\s+emergency)\s*(?:contact)?\s*[:=]\s*(.+)"""),
            Regex("""(?i)(?:contact)\s*[:=]\s*(.+)""")
        )

        // Gender patterns
        private val GENDER_PATTERNS = listOf(
            Regex("""(?i)(?:sex|gender)\s*[:=]\s*(male|female|m|f|non[- ]?binary|other)""")
        )

        // Height/weight patterns
        private val HEIGHT_CM_PATTERN = Regex("""(?i)(\d{2,3})\s*cm""")
        private val HEIGHT_FT_IN_PATTERN = Regex("""(?i)(\d)'[\s]?(\d{1,2})(?:"|'')?""")
        private val WEIGHT_KG_PATTERN = Regex("""(?i)(\d{2,3})\s*kg""")
        private val WEIGHT_LBS_PATTERN = Regex("""(?i)(\d{2,3})\s*(?:lbs?|pounds?)""")
    }

    /**
     * Parse raw text (from OCR or VLM description) into a VitalData object.
     * Attempts to detect the document type and extract relevant fields.
     *
     * @param text The raw text to parse
     * @param documentType Optional hint about document type
     * @param source The source of this data (default PHOTO_SCAN)
     * @return Parsed VitalData with extracted fields
     */
    fun parse(
        text: String,
        documentType: DocumentType? = null,
        source: VitalDataSource = VitalDataSource.PHOTO_SCAN
    ): VitalData {
        Log.d(TAG, "Parsing text (${text.length} chars), docType=$documentType")

        val detectedType = documentType ?: detectDocumentType(text)
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }

        val bloodType = extractBloodType(text)
        val name = extractName(lines)
        val dob = extractDateOfBirth(lines, text)
        val gender = extractGender(text)
        val allergies = extractListField(lines, ALLERGY_PATTERNS)
        val medications = extractListField(lines, MEDICATION_PATTERNS)
        val conditions = extractListField(lines, CONDITION_PATTERNS)
        val emergencyContacts = extractEmergencyContacts(lines, text)
        val insuranceProvider = extractSingleField(lines, INSURANCE_PROVIDER_PATTERNS)
        val policyNumber = extractSingleField(lines, POLICY_NUMBER_PATTERNS)
        val groupNumber = extractSingleField(lines, GROUP_NUMBER_PATTERNS)
        val heightCm = extractHeight(text)
        val weightKg = extractWeight(text)

        // Calculate extraction confidence based on how many fields were found
        val fieldCount = listOfNotNull(
            bloodType, name, dob, gender,
            allergies.takeIf { it.isNotEmpty() },
            medications.takeIf { it.isNotEmpty() },
            conditions.takeIf { it.isNotEmpty() },
            emergencyContacts.takeIf { it.isNotEmpty() },
            insuranceProvider, policyNumber, groupNumber,
            heightCm, weightKg
        ).size
        val confidence = (fieldCount.toFloat() / 13f).coerceIn(0f, 1f)

        return VitalData(
            id = UUID.randomUUID().toString(),
            source = source,
            fullName = name,
            dateOfBirth = dob,
            gender = gender,
            bloodType = bloodType,
            allergies = allergies,
            medications = medications,
            medicalConditions = conditions,
            emergencyContacts = emergencyContacts,
            insuranceProvider = insuranceProvider,
            insurancePolicyNumber = policyNumber,
            insuranceGroupNumber = groupNumber,
            heightCm = heightCm,
            weightKg = weightKg,
            documentType = detectedType,
            rawExtractedText = text,
            confidence = confidence
        )
    }

    /**
     * Detect the type of document from its text content.
     */
    fun detectDocumentType(text: String): DocumentType {
        val lower = text.lowercase()
        return when {
            lower.containsAny("insurance", "policy", "group number", "copay", "deductible", "coverage") ->
                DocumentType.INSURANCE_CARD
            lower.containsAny("allergy", "allergies", "allergic", "anaphylaxis", "epipen") ->
                DocumentType.ALLERGY_CARD
            lower.containsAny("prescription", "rx", "refill", "pharmacy", "dispense", "dosage") ->
                DocumentType.PRESCRIPTION
            lower.containsAny("vaccination", "vaccine", "immunization", "dose 1", "dose 2", "booster") ->
                DocumentType.VACCINATION_RECORD
            lower.containsAny("ice", "in case of emergency", "emergency contact", "medical alert", "medic alert") ->
                DocumentType.EMERGENCY_BRACELET
            lower.containsAny("diagnosis", "medical record", "patient record", "medical history", "discharge") ->
                DocumentType.MEDICAL_RECORD
            lower.containsAny("driver", "license", "passport", "identification", "state of", "date of birth", "dob") ->
                DocumentType.ID_CARD
            else -> DocumentType.OTHER
        }
    }

    // -- Extraction methods --

    internal fun extractBloodType(text: String): String? {
        val match = BLOOD_TYPE_PATTERN.find(text) ?: return null
        val group = match.groupValues[1].uppercase()
        val rhRaw = match.groupValues[2].lowercase()
        val rh = when {
            rhRaw == "+" || rhRaw.startsWith("pos") -> "+"
            rhRaw == "-" || rhRaw.startsWith("neg") -> "-"
            else -> return null
        }
        return "$group$rh"
    }

    internal fun extractName(lines: List<String>): String? {
        for (pattern in NAME_PATTERNS) {
            for (line in lines) {
                val match = pattern.find(line)
                if (match != null) {
                    val name = match.groupValues[1].trim()
                        .replace(Regex("""[^a-zA-Z\s'\-.]"""), "")
                        .trim()
                    if (name.length >= 2 && name.contains(" ")) {
                        return name
                    }
                }
            }
        }
        return null
    }

    internal fun extractDateOfBirth(lines: List<String>, fullText: String): String? {
        // First look for labeled DOB
        val dobLabelPattern = Regex("""(?i)(?:date\s+of\s+birth|dob|birth\s*date|born)\s*[:=]?\s*(.+)""")
        for (line in lines) {
            val match = dobLabelPattern.find(line)
            if (match != null) {
                val dateText = match.groupValues[1].trim()
                val parsed = parseDate(dateText)
                if (parsed != null) return parsed
            }
        }

        // Fall back to any date in the text (less reliable)
        return null
    }

    internal fun parseDate(text: String): String? {
        // Try YYYY-MM-DD first (already ISO)
        DATE_YYYY_MM_DD.find(text)?.let { match ->
            return match.value
        }

        // Try MM/DD/YYYY
        DATE_MM_DD_YYYY.find(text)?.let { match ->
            val parts = match.value.split(Regex("[/\\-]"))
            if (parts.size == 3) {
                val month = parts[0].padStart(2, '0')
                val day = parts[1].padStart(2, '0')
                val year = parts[2]
                return "$year-$month-$day"
            }
        }

        // Try written dates
        DATE_WRITTEN.find(text)?.let { match ->
            val monthStr = match.groupValues[1].lowercase().take(3)
            val day = match.groupValues[2].padStart(2, '0')
            val yearPrefix = match.groupValues[3]
            val fullYear = text.substring(match.range).let {
                Regex("""(19|20)\d{2}""").find(it)?.value ?: return null
            }
            val monthNum = monthToNumber(monthStr) ?: return null
            return "$fullYear-$monthNum-$day"
        }

        return null
    }

    private fun monthToNumber(month: String): String? {
        return when (month.take(3).lowercase()) {
            "jan" -> "01"; "feb" -> "02"; "mar" -> "03"; "apr" -> "04"
            "may" -> "05"; "jun" -> "06"; "jul" -> "07"; "aug" -> "08"
            "sep" -> "09"; "oct" -> "10"; "nov" -> "11"; "dec" -> "12"
            else -> null
        }
    }

    internal fun extractGender(text: String): String? {
        for (pattern in GENDER_PATTERNS) {
            val match = pattern.find(text)
            if (match != null) {
                val raw = match.groupValues[1].trim().lowercase()
                return when (raw) {
                    "m", "male" -> "Male"
                    "f", "female" -> "Female"
                    else -> raw.replaceFirstChar { it.uppercase() }
                }
            }
        }
        return null
    }

    internal fun extractListField(
        lines: List<String>,
        patterns: List<Regex>
    ): List<String> {
        val results = mutableListOf<String>()
        for (pattern in patterns) {
            for (line in lines) {
                val match = pattern.find(line)
                if (match != null) {
                    val raw = match.groupValues[1].trim()
                    // Split by common delimiters
                    val items = raw.split(Regex("""[,;/]|\band\b"""))
                        .map { it.trim() }
                        .filter { it.length >= 2 }
                    results.addAll(items)
                }
            }
        }
        return results.distinct()
    }

    internal fun extractSingleField(
        lines: List<String>,
        patterns: List<Regex>
    ): String? {
        for (pattern in patterns) {
            for (line in lines) {
                val match = pattern.find(line)
                if (match != null) {
                    val value = match.groupValues[1].trim()
                    if (value.isNotEmpty()) return value
                }
            }
        }
        return null
    }

    internal fun extractEmergencyContacts(
        lines: List<String>,
        fullText: String
    ): List<EmergencyContact> {
        val contacts = mutableListOf<EmergencyContact>()

        for (pattern in EMERGENCY_CONTACT_PATTERNS) {
            for (line in lines) {
                val match = pattern.find(line)
                if (match != null) {
                    val contactText = match.groupValues[1].trim()
                    val phone = PHONE_PATTERN.find(contactText)?.value?.trim()
                    if (phone != null) {
                        val name = contactText.replace(phone, "")
                            .replace(Regex("""[,\-:()]"""), " ")
                            .trim()
                            .takeIf { it.length >= 2 }
                            ?: "Emergency Contact"
                        contacts.add(EmergencyContact(name = name, phone = phone))
                    }
                }
            }
        }

        return contacts.distinctBy { it.phone }
    }

    internal fun extractHeight(text: String): Int? {
        // Try cm first
        HEIGHT_CM_PATTERN.find(text)?.let { match ->
            val cm = match.groupValues[1].toIntOrNull()
            if (cm != null && cm in 50..250) return cm
        }
        // Try feet/inches
        HEIGHT_FT_IN_PATTERN.find(text)?.let { match ->
            val ft = match.groupValues[1].toIntOrNull() ?: return null
            val inches = match.groupValues[2].toIntOrNull() ?: return null
            if (ft in 1..8 && inches in 0..11) {
                return ((ft * 12 + inches) * 2.54).toInt()
            }
        }
        return null
    }

    internal fun extractWeight(text: String): Int? {
        // Try kg first
        WEIGHT_KG_PATTERN.find(text)?.let { match ->
            val kg = match.groupValues[1].toIntOrNull()
            if (kg != null && kg in 10..300) return kg
        }
        // Try lbs
        WEIGHT_LBS_PATTERN.find(text)?.let { match ->
            val lbs = match.groupValues[1].toIntOrNull()
            if (lbs != null && lbs in 20..700) {
                return (lbs * 0.453592).toInt()
            }
        }
        return null
    }

    private fun String.containsAny(vararg keywords: String): Boolean {
        return keywords.any { this.contains(it) }
    }
}

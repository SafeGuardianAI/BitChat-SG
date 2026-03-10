package com.bitchat.android.vitals

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for VitalDataParser regex patterns and extraction logic.
 * These tests do not require Android framework (no Context needed).
 */
class VitalDataParserTest {

    private lateinit var parser: VitalDataParser

    @Before
    fun setUp() {
        parser = VitalDataParser()
    }

    // ---- Blood Type Tests ----

    @Test
    fun `extractBloodType - standard positive types`() {
        assertEquals("A+", parser.extractBloodType("Blood Type: A+"))
        assertEquals("B+", parser.extractBloodType("Blood Type: B+"))
        assertEquals("AB+", parser.extractBloodType("Blood Type: AB+"))
        assertEquals("O+", parser.extractBloodType("Blood type O+"))
    }

    @Test
    fun `extractBloodType - standard negative types`() {
        assertEquals("A-", parser.extractBloodType("Blood Type: A-"))
        assertEquals("B-", parser.extractBloodType("Blood Type: B-"))
        assertEquals("AB-", parser.extractBloodType("Blood Type: AB-"))
        assertEquals("O-", parser.extractBloodType("Blood Type: O-"))
    }

    @Test
    fun `extractBloodType - written positive and negative`() {
        assertEquals("A+", parser.extractBloodType("Blood Type: A positive"))
        assertEquals("O-", parser.extractBloodType("Blood Type: O negative"))
        assertEquals("AB+", parser.extractBloodType("Type AB pos"))
    }

    @Test
    fun `extractBloodType - with spaces`() {
        assertEquals("A+", parser.extractBloodType("A +"))
        assertEquals("O-", parser.extractBloodType("O -"))
    }

    @Test
    fun `extractBloodType - no match returns null`() {
        assertNull(parser.extractBloodType("No blood type info here"))
        assertNull(parser.extractBloodType(""))
    }

    // ---- Name Extraction Tests ----

    @Test
    fun `extractName - standard labels`() {
        assertEquals("John Doe", parser.extractName(listOf("Name: John Doe")))
        assertEquals("Jane Smith", parser.extractName(listOf("Patient: Jane Smith")))
        assertEquals("Bob Johnson", parser.extractName(listOf("Member Name: Bob Johnson")))
    }

    @Test
    fun `extractName - no match returns null`() {
        assertNull(parser.extractName(listOf("No name here")))
        assertNull(parser.extractName(listOf("")))
        assertNull(parser.extractName(emptyList()))
    }

    @Test
    fun `extractName - requires first and last name`() {
        // Single word should not match (requires space in name)
        assertNull(parser.extractName(listOf("Name: John")))
    }

    // ---- Date of Birth Tests ----

    @Test
    fun `parseDate - ISO format`() {
        assertEquals("2000-01-15", parser.parseDate("2000-01-15"))
        assertEquals("1985-12-31", parser.parseDate("1985-12-31"))
    }

    @Test
    fun `parseDate - MM slash DD slash YYYY`() {
        assertEquals("1990-03-25", parser.parseDate("03/25/1990"))
        assertEquals("1985-12-01", parser.parseDate("12/01/1985"))
    }

    @Test
    fun `parseDate - written month format`() {
        assertEquals("1990-03-25", parser.parseDate("March 25, 1990"))
        assertEquals("1985-01-15", parser.parseDate("Jan 15, 1985"))
    }

    @Test
    fun `parseDate - no match returns null`() {
        assertNull(parser.parseDate("no date here"))
        assertNull(parser.parseDate(""))
    }

    @Test
    fun `extractDateOfBirth - labeled DOB`() {
        val lines = listOf("DOB: 03/25/1990")
        assertEquals("1990-03-25", parser.extractDateOfBirth(lines, "DOB: 03/25/1990"))
    }

    @Test
    fun `extractDateOfBirth - date of birth label`() {
        val lines = listOf("Date of Birth: 1985-12-31")
        assertEquals("1985-12-31", parser.extractDateOfBirth(lines, "Date of Birth: 1985-12-31"))
    }

    // ---- Gender Tests ----

    @Test
    fun `extractGender - standard values`() {
        assertEquals("Male", parser.extractGender("Sex: M"))
        assertEquals("Female", parser.extractGender("Sex: F"))
        assertEquals("Male", parser.extractGender("Gender: Male"))
        assertEquals("Female", parser.extractGender("Gender: Female"))
    }

    @Test
    fun `extractGender - no match returns null`() {
        assertNull(parser.extractGender("No gender info"))
    }

    // ---- Allergy Extraction Tests ----

    @Test
    fun `extractListField - allergies comma separated`() {
        val lines = listOf("Allergies: Penicillin, Peanuts, Latex")
        val allergies = parser.extractListField(lines, listOf(
            Regex("""(?i)(?:allergies|allergic\s+to|allergy|known\s+allergies)\s*[:=]\s*(.+)""")
        ))
        assertEquals(3, allergies.size)
        assertTrue(allergies.contains("Penicillin"))
        assertTrue(allergies.contains("Peanuts"))
        assertTrue(allergies.contains("Latex"))
    }

    @Test
    fun `extractListField - allergies semicolon separated`() {
        val lines = listOf("Allergies: Penicillin; Sulfa drugs; Shellfish")
        val allergies = parser.extractListField(lines, listOf(
            Regex("""(?i)(?:allergies|allergic\s+to|allergy)\s*[:=]\s*(.+)""")
        ))
        assertEquals(3, allergies.size)
    }

    @Test
    fun `extractListField - medications`() {
        val lines = listOf("Medications: Metformin 500mg, Lisinopril 10mg, Aspirin 81mg")
        val meds = parser.extractListField(lines, listOf(
            Regex("""(?i)(?:medications?|current\s+meds?|prescriptions?)\s*[:=]\s*(.+)""")
        ))
        assertEquals(3, meds.size)
        assertTrue(meds.any { it.contains("Metformin") })
    }

    @Test
    fun `extractListField - empty when no match`() {
        val lines = listOf("No relevant info")
        val result = parser.extractListField(lines, listOf(
            Regex("""(?i)(?:allergies)\s*[:=]\s*(.+)""")
        ))
        assertTrue(result.isEmpty())
    }

    // ---- Insurance Field Tests ----

    @Test
    fun `extractSingleField - policy number`() {
        val lines = listOf("Policy Number: ABC123456")
        val result = parser.extractSingleField(lines, listOf(
            Regex("""(?i)(?:policy|member\s+id|member\s+#|id\s+number)\s*[:=]?\s*([A-Z0-9\-]+)""")
        ))
        assertEquals("ABC123456", result)
    }

    @Test
    fun `extractSingleField - group number`() {
        val lines = listOf("Group Number: GRP-7890")
        val result = parser.extractSingleField(lines, listOf(
            Regex("""(?i)(?:group|grp)\s*(?:number|no|#|id)?\s*[:=]?\s*([A-Z0-9\-]+)""")
        ))
        assertEquals("GRP-7890", result)
    }

    // ---- Height and Weight Tests ----

    @Test
    fun `extractHeight - centimeters`() {
        assertEquals(175, parser.extractHeight("Height: 175 cm"))
        assertEquals(160, parser.extractHeight("160cm"))
    }

    @Test
    fun `extractHeight - feet and inches`() {
        // 5'10" = 70 inches * 2.54 = 177.8 -> 177
        assertEquals(177, parser.extractHeight("Height: 5'10\""))
        // 6'0" = 72 inches * 2.54 = 182.88 -> 182
        assertEquals(182, parser.extractHeight("6'0\""))
    }

    @Test
    fun `extractHeight - no match returns null`() {
        assertNull(parser.extractHeight("no height info"))
    }

    @Test
    fun `extractWeight - kilograms`() {
        assertEquals(70, parser.extractWeight("Weight: 70 kg"))
        assertEquals(85, parser.extractWeight("85kg"))
    }

    @Test
    fun `extractWeight - pounds converted to kg`() {
        // 150 lbs * 0.453592 = 68.0388 -> 68
        assertEquals(68, parser.extractWeight("Weight: 150 lbs"))
        // 200 lbs * 0.453592 = 90.7184 -> 90
        assertEquals(90, parser.extractWeight("200 pounds"))
    }

    @Test
    fun `extractWeight - no match returns null`() {
        assertNull(parser.extractWeight("no weight info"))
    }

    // ---- Document Type Detection Tests ----

    @Test
    fun `detectDocumentType - insurance card`() {
        val text = "Blue Cross Blue Shield\nPolicy Number: ABC123\nGroup Number: GRP456\nCopay: \$25"
        assertEquals(DocumentType.INSURANCE_CARD, parser.detectDocumentType(text))
    }

    @Test
    fun `detectDocumentType - allergy card`() {
        val text = "MEDICAL ALERT\nAllergies: Penicillin, Peanuts\nAnaphylaxis risk"
        assertEquals(DocumentType.ALLERGY_CARD, parser.detectDocumentType(text))
    }

    @Test
    fun `detectDocumentType - prescription`() {
        val text = "Rx: Metformin 500mg\nRefill: 3\nPharmacy: CVS"
        assertEquals(DocumentType.PRESCRIPTION, parser.detectDocumentType(text))
    }

    @Test
    fun `detectDocumentType - vaccination record`() {
        val text = "COVID-19 Vaccination Record\nDose 1: Pfizer\nDate: 01/15/2021"
        assertEquals(DocumentType.VACCINATION_RECORD, parser.detectDocumentType(text))
    }

    @Test
    fun `detectDocumentType - ID card`() {
        val text = "State of California\nDriver License\nDate of Birth: 01/15/1990"
        assertEquals(DocumentType.ID_CARD, parser.detectDocumentType(text))
    }

    @Test
    fun `detectDocumentType - medical record`() {
        val text = "Patient Record\nDiagnosis: Type 2 Diabetes\nMedical History"
        assertEquals(DocumentType.MEDICAL_RECORD, parser.detectDocumentType(text))
    }

    @Test
    fun `detectDocumentType - unknown returns OTHER`() {
        val text = "Some random text with no medical keywords"
        assertEquals(DocumentType.OTHER, parser.detectDocumentType(text))
    }

    // ---- Full Parse Integration Tests ----

    @Test
    fun `parse - insurance card text`() {
        val text = """
            Blue Cross Blue Shield
            Member Name: John A. Doe
            Member ID: XYZ789012
            Group Number: GRP-456
            Date of Birth: 03/25/1990
        """.trimIndent()

        val result = parser.parse(text)

        assertEquals(DocumentType.INSURANCE_CARD, result.documentType)
        assertEquals("John A. Doe", result.fullName)
        assertEquals("1990-03-25", result.dateOfBirth)
        assertNotNull(result.insurancePolicyNumber)
        assertNotNull(result.insuranceGroupNumber)
        assertTrue(result.confidence > 0f)
    }

    @Test
    fun `parse - medical record text`() {
        val text = """
            Patient Medical Record
            Patient: Jane Smith
            DOB: 1985-06-15
            Sex: F
            Blood Type: AB+
            Allergies: Penicillin, Sulfa, Latex
            Medications: Metformin 500mg, Lisinopril 10mg
            Diagnosis: Type 2 Diabetes, Hypertension
            Height: 165 cm
            Weight: 70 kg
            Emergency Contact: Bob Smith 555-123-4567
        """.trimIndent()

        val result = parser.parse(text)

        assertEquals(DocumentType.MEDICAL_RECORD, result.documentType)
        assertEquals("Jane Smith", result.fullName)
        assertEquals("1985-06-15", result.dateOfBirth)
        assertEquals("Female", result.gender)
        assertEquals("AB+", result.bloodType)
        assertEquals(3, result.allergies.size)
        assertEquals(2, result.medications.size)
        assertTrue(result.medicalConditions.isNotEmpty())
        assertEquals(165, result.heightCm)
        assertEquals(70, result.weightKg)
        assertTrue(result.confidence > 0.3f)
    }

    @Test
    fun `parse - empty text`() {
        val result = parser.parse("")
        assertEquals(0f, result.confidence, 0.001f)
        assertNull(result.fullName)
        assertNull(result.bloodType)
        assertTrue(result.allergies.isEmpty())
    }

    @Test
    fun `parse - assigns source correctly`() {
        val result = parser.parse("Name: John Doe", source = VitalDataSource.MANUAL)
        assertEquals(VitalDataSource.MANUAL, result.source)
    }

    @Test
    fun `parse - generates unique IDs`() {
        val r1 = parser.parse("test 1")
        val r2 = parser.parse("test 2")
        assertTrue(r1.id != r2.id)
    }
}

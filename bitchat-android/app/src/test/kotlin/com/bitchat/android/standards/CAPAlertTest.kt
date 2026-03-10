package com.bitchat.android.standards

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for CAP v1.2 XML generation.
 *
 * Validates that generated XML conforms to the OASIS CAP v1.2 schema,
 * including required elements, correct enum values, XML escaping,
 * and area/circle formatting.
 */
class CAPAlertTest {

    private fun createTestAlert(
        info: List<CAPInfo> = listOf(
            CAPInfo(
                category = CAPCategory.RESCUE,
                event = "Earthquake Survivor Located",
                urgency = CAPUrgency.IMMEDIATE,
                severity = CAPSeverity.EXTREME,
                certainty = CAPCertainty.OBSERVED,
                senderName = "SafeGuardian Device",
                headline = "Survivor Needs Immediate Help",
                description = "Survivor located via BLE mesh network",
                instruction = "Dispatch rescue team to coordinates",
                area = listOf(
                    CAPArea(
                        areaDesc = "Survivor location",
                        circle = "34.0522,-118.2437 0.5"
                    )
                )
            )
        )
    ): CAPAlert {
        return CAPAlert(
            identifier = "SG-2024-001",
            sender = "safeguardian@example.com",
            sent = "2024-01-15T10:30:00-08:00",
            status = CAPStatus.ACTUAL,
            msgType = CAPMsgType.ALERT,
            scope = CAPScope.PUBLIC,
            info = info
        )
    }

    // --- XML Structure Tests ---

    @Test
    fun `generated XML should have correct XML declaration`() {
        val alert = createTestAlert()
        val xml = CAPAlertGenerator.generateAlert(alert)
        assertTrue(xml.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"))
    }

    @Test
    fun `generated XML should have CAP namespace`() {
        val alert = createTestAlert()
        val xml = CAPAlertGenerator.generateAlert(alert)
        assertTrue(xml.contains("xmlns=\"urn:oasis:names:tc:emergency:cap:1.2\""))
    }

    @Test
    fun `generated XML should contain all required elements`() {
        val alert = createTestAlert()
        val xml = CAPAlertGenerator.generateAlert(alert)

        assertTrue("Missing identifier", xml.contains("<identifier>SG-2024-001</identifier>"))
        assertTrue("Missing sender", xml.contains("<sender>safeguardian@example.com</sender>"))
        assertTrue("Missing sent", xml.contains("<sent>2024-01-15T10:30:00-08:00</sent>"))
        assertTrue("Missing status", xml.contains("<status>Actual</status>"))
        assertTrue("Missing msgType", xml.contains("<msgType>Alert</msgType>"))
        assertTrue("Missing scope", xml.contains("<scope>Public</scope>"))
    }

    @Test
    fun `generated XML should contain info block elements`() {
        val alert = createTestAlert()
        val xml = CAPAlertGenerator.generateAlert(alert)

        assertTrue("Missing info block", xml.contains("<info>"))
        assertTrue("Missing category", xml.contains("<category>Rescue</category>"))
        assertTrue("Missing event", xml.contains("<event>Earthquake Survivor Located</event>"))
        assertTrue("Missing urgency", xml.contains("<urgency>Immediate</urgency>"))
        assertTrue("Missing severity", xml.contains("<severity>Extreme</severity>"))
        assertTrue("Missing certainty", xml.contains("<certainty>Observed</certainty>"))
        assertTrue("Missing headline", xml.contains("<headline>Survivor Needs Immediate Help</headline>"))
        assertTrue("Missing description", xml.contains("<description>Survivor located via BLE mesh network</description>"))
        assertTrue("Missing instruction", xml.contains("<instruction>Dispatch rescue team to coordinates</instruction>"))
    }

    @Test
    fun `generated XML should contain area with circle`() {
        val alert = createTestAlert()
        val xml = CAPAlertGenerator.generateAlert(alert)

        assertTrue("Missing area block", xml.contains("<area>"))
        assertTrue("Missing areaDesc", xml.contains("<areaDesc>Survivor location</areaDesc>"))
        assertTrue("Missing circle", xml.contains("<circle>34.0522,-118.2437 0.5</circle>"))
    }

    // --- Enum Value Tests ---

    @Test
    fun `all CAPStatus values should serialize correctly`() {
        for (status in CAPStatus.entries) {
            val alert = CAPAlert(
                identifier = "test",
                sender = "test",
                sent = "2024-01-01T00:00:00Z",
                status = status,
                msgType = CAPMsgType.ALERT,
                scope = CAPScope.PUBLIC
            )
            val xml = CAPAlertGenerator.generateAlert(alert)
            assertTrue("Status ${status.value} not found", xml.contains("<status>${status.value}</status>"))
        }
    }

    @Test
    fun `all CAPMsgType values should serialize correctly`() {
        for (msgType in CAPMsgType.entries) {
            val alert = CAPAlert(
                identifier = "test",
                sender = "test",
                sent = "2024-01-01T00:00:00Z",
                status = CAPStatus.ACTUAL,
                msgType = msgType,
                scope = CAPScope.PUBLIC
            )
            val xml = CAPAlertGenerator.generateAlert(alert)
            assertTrue("MsgType ${msgType.value} not found", xml.contains("<msgType>${msgType.value}</msgType>"))
        }
    }

    @Test
    fun `all CAPScope values should serialize correctly`() {
        for (scope in CAPScope.entries) {
            val alert = CAPAlert(
                identifier = "test",
                sender = "test",
                sent = "2024-01-01T00:00:00Z",
                status = CAPStatus.ACTUAL,
                msgType = CAPMsgType.ALERT,
                scope = scope
            )
            val xml = CAPAlertGenerator.generateAlert(alert)
            assertTrue("Scope ${scope.value} not found", xml.contains("<scope>${scope.value}</scope>"))
        }
    }

    @Test
    fun `all severity levels should serialize correctly`() {
        for (severity in CAPSeverity.entries) {
            val info = CAPInfo(
                category = CAPCategory.RESCUE,
                event = "Test",
                urgency = CAPUrgency.IMMEDIATE,
                severity = severity,
                certainty = CAPCertainty.OBSERVED
            )
            val alert = CAPAlert(
                identifier = "test",
                sender = "test",
                sent = "2024-01-01T00:00:00Z",
                status = CAPStatus.ACTUAL,
                msgType = CAPMsgType.ALERT,
                scope = CAPScope.PUBLIC,
                info = listOf(info)
            )
            val xml = CAPAlertGenerator.generateAlert(alert)
            assertTrue("Severity ${severity.value} not found", xml.contains("<severity>${severity.value}</severity>"))
        }
    }

    // --- XML Escaping Tests ---

    @Test
    fun `special characters should be escaped in identifier`() {
        val alert = CAPAlert(
            identifier = "test<id>&\"'value",
            sender = "test",
            sent = "2024-01-01T00:00:00Z",
            status = CAPStatus.ACTUAL,
            msgType = CAPMsgType.ALERT,
            scope = CAPScope.PUBLIC
        )
        val xml = CAPAlertGenerator.generateAlert(alert)
        assertTrue("Ampersand not escaped", xml.contains("&amp;"))
        assertTrue("Less-than not escaped", xml.contains("&lt;"))
        assertTrue("Quote not escaped", xml.contains("&quot;"))
        assertTrue("Apostrophe not escaped", xml.contains("&apos;"))
        assertFalse("Raw < found in output", xml.contains("<id>"))
    }

    @Test
    fun `special characters should be escaped in description`() {
        val info = CAPInfo(
            category = CAPCategory.RESCUE,
            event = "Test",
            urgency = CAPUrgency.IMMEDIATE,
            severity = CAPSeverity.EXTREME,
            certainty = CAPCertainty.OBSERVED,
            description = "Patient has <critical> injuries & needs O'Brien protocol"
        )
        val alert = CAPAlert(
            identifier = "test",
            sender = "test",
            sent = "2024-01-01T00:00:00Z",
            status = CAPStatus.ACTUAL,
            msgType = CAPMsgType.ALERT,
            scope = CAPScope.PUBLIC,
            info = listOf(info)
        )
        val xml = CAPAlertGenerator.generateAlert(alert)
        assertTrue(xml.contains("&lt;critical&gt;"))
        assertTrue(xml.contains("&amp;"))
        assertTrue(xml.contains("O&apos;Brien"))
    }

    @Test
    fun `escapeXml should handle all five XML entities`() {
        val input = "a&b<c>d\"e'f"
        val escaped = CAPAlertGenerator.escapeXml(input)
        assertEquals("a&amp;b&lt;c&gt;d&quot;e&apos;f", escaped)
    }

    // --- Area Tests ---

    @Test
    fun `area with geocode should be formatted correctly`() {
        val info = CAPInfo(
            category = CAPCategory.GEO,
            event = "Test",
            urgency = CAPUrgency.IMMEDIATE,
            severity = CAPSeverity.EXTREME,
            certainty = CAPCertainty.OBSERVED,
            area = listOf(
                CAPArea(
                    areaDesc = "FIPS area",
                    geocode = mapOf("FIPS6" to "006037", "UGC" to "CAZ041")
                )
            )
        )
        val alert = CAPAlert(
            identifier = "test",
            sender = "test",
            sent = "2024-01-01T00:00:00Z",
            status = CAPStatus.ACTUAL,
            msgType = CAPMsgType.ALERT,
            scope = CAPScope.PUBLIC,
            info = listOf(info)
        )
        val xml = CAPAlertGenerator.generateAlert(alert)
        assertTrue(xml.contains("<geocode>"))
        assertTrue(xml.contains("<valueName>FIPS6</valueName>"))
        assertTrue(xml.contains("<value>006037</value>"))
        assertTrue(xml.contains("<valueName>UGC</valueName>"))
        assertTrue(xml.contains("<value>CAZ041</value>"))
    }

    @Test
    fun `area with polygon should be included`() {
        val info = CAPInfo(
            category = CAPCategory.GEO,
            event = "Test",
            urgency = CAPUrgency.IMMEDIATE,
            severity = CAPSeverity.EXTREME,
            certainty = CAPCertainty.OBSERVED,
            area = listOf(
                CAPArea(
                    areaDesc = "Polygon area",
                    polygon = "34.0,-118.0 34.1,-118.0 34.1,-118.1 34.0,-118.1 34.0,-118.0"
                )
            )
        )
        val alert = CAPAlert(
            identifier = "test",
            sender = "test",
            sent = "2024-01-01T00:00:00Z",
            status = CAPStatus.ACTUAL,
            msgType = CAPMsgType.ALERT,
            scope = CAPScope.PUBLIC,
            info = listOf(info)
        )
        val xml = CAPAlertGenerator.generateAlert(alert)
        assertTrue(xml.contains("<polygon>"))
    }

    // --- Convenience Generator Test ---

    @Test
    fun `generateEmergencyAlert should produce valid CAP XML`() {
        val xml = CAPAlertGenerator.generateEmergencyAlert(
            identifier = "EM-001",
            sender = "safeguardian",
            sent = "2024-06-01T12:00:00Z",
            event = "Building Collapse",
            headline = "Survivors detected",
            description = "Multiple survivors detected via mesh network",
            latitude = 34.0522,
            longitude = -118.2437,
            radiusKm = 2.0,
            severity = CAPSeverity.EXTREME,
            urgency = CAPUrgency.IMMEDIATE,
            category = CAPCategory.RESCUE
        )

        assertTrue(xml.contains("<identifier>EM-001</identifier>"))
        assertTrue(xml.contains("<status>Actual</status>"))
        assertTrue(xml.contains("<category>Rescue</category>"))
        assertTrue(xml.contains("<circle>34.0522,-118.2437 2.0</circle>"))
    }

    // --- Empty Info List Test ---

    @Test
    fun `alert with no info blocks should still be valid`() {
        val alert = CAPAlert(
            identifier = "test",
            sender = "test",
            sent = "2024-01-01T00:00:00Z",
            status = CAPStatus.ACTUAL,
            msgType = CAPMsgType.ALERT,
            scope = CAPScope.PUBLIC,
            info = emptyList()
        )
        val xml = CAPAlertGenerator.generateAlert(alert)
        assertTrue(xml.contains("<alert"))
        assertTrue(xml.contains("</alert>"))
        assertFalse(xml.contains("<info>"))
    }

    // --- Multiple Info Blocks Test ---

    @Test
    fun `alert with multiple info blocks should include all`() {
        val info1 = CAPInfo(
            category = CAPCategory.RESCUE,
            event = "Event 1",
            urgency = CAPUrgency.IMMEDIATE,
            severity = CAPSeverity.EXTREME,
            certainty = CAPCertainty.OBSERVED
        )
        val info2 = CAPInfo(
            category = CAPCategory.HEALTH,
            event = "Event 2",
            urgency = CAPUrgency.EXPECTED,
            severity = CAPSeverity.SEVERE,
            certainty = CAPCertainty.LIKELY,
            language = "es-US"
        )
        val alert = CAPAlert(
            identifier = "multi-test",
            sender = "test",
            sent = "2024-01-01T00:00:00Z",
            status = CAPStatus.ACTUAL,
            msgType = CAPMsgType.ALERT,
            scope = CAPScope.PUBLIC,
            info = listOf(info1, info2)
        )
        val xml = CAPAlertGenerator.generateAlert(alert)
        assertTrue(xml.contains("Event 1"))
        assertTrue(xml.contains("Event 2"))
        assertTrue(xml.contains("<category>Rescue</category>"))
        assertTrue(xml.contains("<category>Health</category>"))
        assertTrue(xml.contains("<language>es-US</language>"))
    }

    // --- Optional Fields ---

    @Test
    fun `optional source and note should be included when present`() {
        val alert = CAPAlert(
            identifier = "test",
            sender = "test",
            sent = "2024-01-01T00:00:00Z",
            status = CAPStatus.ACTUAL,
            msgType = CAPMsgType.ALERT,
            scope = CAPScope.RESTRICTED,
            source = "SafeGuardian v2.0",
            restriction = "First Responders Only",
            note = "Test alert for exercise"
        )
        val xml = CAPAlertGenerator.generateAlert(alert)
        assertTrue(xml.contains("<source>SafeGuardian v2.0</source>"))
        assertTrue(xml.contains("<restriction>First Responders Only</restriction>"))
        assertTrue(xml.contains("<note>Test alert for exercise</note>"))
    }
}

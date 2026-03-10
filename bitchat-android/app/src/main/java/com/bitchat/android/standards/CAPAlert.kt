package com.bitchat.android.standards

import kotlinx.serialization.Serializable

/**
 * Common Alerting Protocol v1.2 (ITU-T X.1303)
 *
 * Generates CAP-compliant XML alerts for emergency broadcasting.
 * CAP is deployed in 200+ countries covering 91% of global population.
 *
 * Reference: http://docs.oasis-open.org/emergency/cap/v1.2/CAP-v1.2.html
 */
object CAPAlertGenerator {

    private const val CAP_NAMESPACE = "urn:oasis:names:tc:emergency:cap:1.2"

    /**
     * Generate a CAP v1.2 XML alert message.
     * The output conforms to the OASIS CAP v1.2 schema.
     */
    fun generateAlert(alert: CAPAlert): String {
        return buildString {
            appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            appendLine("<alert xmlns=\"$CAP_NAMESPACE\">")
            appendLine("  <identifier>${escapeXml(alert.identifier)}</identifier>")
            appendLine("  <sender>${escapeXml(alert.sender)}</sender>")
            appendLine("  <sent>${escapeXml(alert.sent)}</sent>")
            appendLine("  <status>${alert.status.value}</status>")
            appendLine("  <msgType>${alert.msgType.value}</msgType>")
            appendLine("  <scope>${alert.scope.value}</scope>")

            if (alert.source != null) {
                appendLine("  <source>${escapeXml(alert.source)}</source>")
            }
            if (alert.restriction != null) {
                appendLine("  <restriction>${escapeXml(alert.restriction)}</restriction>")
            }
            if (alert.note != null) {
                appendLine("  <note>${escapeXml(alert.note)}</note>")
            }

            for (info in alert.info) {
                appendInfoBlock(this, info)
            }

            appendLine("</alert>")
        }
    }

    /**
     * Generate a CAP alert for a SafeGuardian emergency with sensible defaults.
     */
    fun generateEmergencyAlert(
        identifier: String,
        sender: String,
        sent: String,
        event: String,
        headline: String,
        description: String,
        latitude: Double,
        longitude: Double,
        radiusKm: Double = 1.0,
        severity: CAPSeverity = CAPSeverity.SEVERE,
        urgency: CAPUrgency = CAPUrgency.IMMEDIATE,
        category: CAPCategory = CAPCategory.RESCUE
    ): String {
        val alert = CAPAlert(
            identifier = identifier,
            sender = sender,
            sent = sent,
            status = CAPStatus.ACTUAL,
            msgType = CAPMsgType.ALERT,
            scope = CAPScope.PUBLIC,
            info = listOf(
                CAPInfo(
                    category = category,
                    event = event,
                    urgency = urgency,
                    severity = severity,
                    certainty = CAPCertainty.OBSERVED,
                    headline = headline,
                    description = description,
                    area = listOf(
                        CAPArea(
                            areaDesc = "Emergency area",
                            circle = "$latitude,$longitude $radiusKm"
                        )
                    )
                )
            )
        )
        return generateAlert(alert)
    }

    private fun appendInfoBlock(sb: StringBuilder, info: CAPInfo) {
        sb.appendLine("  <info>")
        sb.appendLine("    <language>${escapeXml(info.language)}</language>")
        sb.appendLine("    <category>${info.category.value}</category>")
        sb.appendLine("    <event>${escapeXml(info.event)}</event>")
        sb.appendLine("    <urgency>${info.urgency.value}</urgency>")
        sb.appendLine("    <severity>${info.severity.value}</severity>")
        sb.appendLine("    <certainty>${info.certainty.value}</certainty>")

        if (info.senderName != null) {
            sb.appendLine("    <senderName>${escapeXml(info.senderName)}</senderName>")
        }
        if (info.headline != null) {
            sb.appendLine("    <headline>${escapeXml(info.headline)}</headline>")
        }
        if (info.description != null) {
            sb.appendLine("    <description>${escapeXml(info.description)}</description>")
        }
        if (info.instruction != null) {
            sb.appendLine("    <instruction>${escapeXml(info.instruction)}</instruction>")
        }
        if (info.expires != null) {
            sb.appendLine("    <expires>${escapeXml(info.expires)}</expires>")
        }

        for (area in info.area) {
            appendAreaBlock(sb, area)
        }

        sb.appendLine("  </info>")
    }

    private fun appendAreaBlock(sb: StringBuilder, area: CAPArea) {
        sb.appendLine("    <area>")
        sb.appendLine("      <areaDesc>${escapeXml(area.areaDesc)}</areaDesc>")

        if (area.circle != null) {
            sb.appendLine("      <circle>${escapeXml(area.circle)}</circle>")
        }

        if (area.polygon != null) {
            sb.appendLine("      <polygon>${escapeXml(area.polygon)}</polygon>")
        }

        if (area.geocode != null) {
            for ((name, value) in area.geocode) {
                sb.appendLine("      <geocode>")
                sb.appendLine("        <valueName>${escapeXml(name)}</valueName>")
                sb.appendLine("        <value>${escapeXml(value)}</value>")
                sb.appendLine("      </geocode>")
            }
        }

        sb.appendLine("    </area>")
    }

    internal fun escapeXml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}

@Serializable
data class CAPAlert(
    val identifier: String,
    val sender: String,
    val sent: String,
    val status: CAPStatus,
    val msgType: CAPMsgType,
    val scope: CAPScope,
    val source: String? = null,
    val restriction: String? = null,
    val note: String? = null,
    val info: List<CAPInfo> = emptyList()
)

@Serializable
data class CAPInfo(
    val category: CAPCategory,
    val event: String,
    val urgency: CAPUrgency,
    val severity: CAPSeverity,
    val certainty: CAPCertainty,
    val senderName: String? = null,
    val headline: String? = null,
    val description: String? = null,
    val instruction: String? = null,
    val expires: String? = null,
    val language: String = "en-US",
    val area: List<CAPArea> = emptyList()
)

@Serializable
data class CAPArea(
    val areaDesc: String,
    val circle: String? = null,
    val polygon: String? = null,
    val geocode: Map<String, String>? = null
)

enum class CAPStatus(val value: String) {
    ACTUAL("Actual"),
    EXERCISE("Exercise"),
    SYSTEM("System"),
    TEST("Test"),
    DRAFT("Draft")
}

enum class CAPMsgType(val value: String) {
    ALERT("Alert"),
    UPDATE("Update"),
    CANCEL("Cancel"),
    ACK("Ack"),
    ERROR("Error")
}

enum class CAPScope(val value: String) {
    PUBLIC("Public"),
    RESTRICTED("Restricted"),
    PRIVATE("Private")
}

enum class CAPCategory(val value: String) {
    GEO("Geo"),
    MET("Met"),
    SAFETY("Safety"),
    SECURITY("Security"),
    RESCUE("Rescue"),
    FIRE("Fire"),
    HEALTH("Health"),
    ENV("Env"),
    TRANSPORT("Transport"),
    INFRA("Infra"),
    CBRNE("CBRNE"),
    OTHER("Other")
}

enum class CAPUrgency(val value: String) {
    IMMEDIATE("Immediate"),
    EXPECTED("Expected"),
    FUTURE("Future"),
    PAST("Past"),
    UNKNOWN("Unknown")
}

enum class CAPSeverity(val value: String) {
    EXTREME("Extreme"),
    SEVERE("Severe"),
    MODERATE("Moderate"),
    MINOR("Minor"),
    UNKNOWN("Unknown")
}

enum class CAPCertainty(val value: String) {
    OBSERVED("Observed"),
    LIKELY("Likely"),
    POSSIBLE("Possible"),
    UNLIKELY("Unlikely"),
    UNKNOWN("Unknown")
}

package com.bitchat.android.ui.lite

import android.util.Log
import com.bitchat.android.standards.CAPSeverity
import com.bitchat.android.standards.CAPUrgency
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory inbox of CAP v1.2 alerts received over the BLE mesh.
 *
 * The receive path is: mesh `[CAP]<xml>` payload → [tryIngest] → parsed
 * [Entry] pushed onto [alerts]. UI observes the [StateFlow]; older entries
 * are evicted when [MAX_ENTRIES] is exceeded.
 */
object CapAlertInbox {

    private const val TAG = "CapAlertInbox"
    private const val PREFIX = "[CAP]"
    private const val MAX_ENTRIES = 50

    data class Entry(
        val identifier: String,
        val sender: String,
        val event: String,
        val headline: String,
        val description: String,
        val severity: CAPSeverity,
        val urgency: CAPUrgency,
        val latitude: Double?,
        val longitude: Double?,
        val receivedAt: Long
    )

    private val _alerts = MutableStateFlow<List<Entry>>(emptyList())
    val alerts: StateFlow<List<Entry>> = _alerts.asStateFlow()

    /**
     * Inspect a received message payload. Returns true if this was a CAP
     * alert and was ingested; false otherwise (caller should keep treating
     * it as a normal chat message).
     */
    fun tryIngest(content: String, senderFallback: String): Boolean {
        if (!content.startsWith(PREFIX)) return false
        val xml = content.substring(PREFIX.length)
        val entry = CapAlertParser.parse(xml, senderFallback) ?: run {
            Log.w(TAG, "Failed to parse CAP alert from ${senderFallback.take(8)}")
            return true
        }
        if (_alerts.value.any { it.identifier == entry.identifier }) return true
        _alerts.value = (listOf(entry) + _alerts.value).take(MAX_ENTRIES)
        Log.d(TAG, "Ingested CAP alert ${entry.identifier} severity=${entry.severity}")
        return true
    }

    fun clear() {
        _alerts.value = emptyList()
    }
}

internal object CapAlertParser {

    private val identifierRx = Regex("<identifier>(.*?)</identifier>")
    private val senderRx = Regex("<sender>(.*?)</sender>")
    private val eventRx = Regex("<event>(.*?)</event>")
    private val headlineRx = Regex("<headline>(.*?)</headline>")
    private val descriptionRx = Regex("<description>(.*?)</description>", RegexOption.DOT_MATCHES_ALL)
    private val severityRx = Regex("<severity>(.*?)</severity>")
    private val urgencyRx = Regex("<urgency>(.*?)</urgency>")
    private val circleRx = Regex("<circle>(.*?)</circle>")

    fun parse(xml: String, senderFallback: String): CapAlertInbox.Entry? {
        val identifier = identifierRx.find(xml)?.groupValues?.get(1) ?: return null
        val sender = senderRx.find(xml)?.groupValues?.get(1) ?: senderFallback
        val event = eventRx.find(xml)?.groupValues?.get(1) ?: "Alert"
        val headline = headlineRx.find(xml)?.groupValues?.get(1) ?: event
        val description = descriptionRx.find(xml)?.groupValues?.get(1).orEmpty().trim()

        val severity = severityRx.find(xml)?.groupValues?.get(1)
            ?.let { v -> runCatching { CAPSeverity.valueOf(v.uppercase()) }.getOrNull() }
            ?: CAPSeverity.UNKNOWN
        val urgency = urgencyRx.find(xml)?.groupValues?.get(1)
            ?.let { v -> runCatching { CAPUrgency.valueOf(v.uppercase()) }.getOrNull() }
            ?: CAPUrgency.UNKNOWN

        val (lat, lon) = circleRx.find(xml)?.groupValues?.get(1)?.let { circle ->
            val coords = circle.substringBefore(' ').split(',')
            if (coords.size == 2) {
                coords[0].toDoubleOrNull() to coords[1].toDoubleOrNull()
            } else null to null
        } ?: (null to null)

        return CapAlertInbox.Entry(
            identifier = identifier,
            sender = sender,
            event = event,
            headline = unescape(headline),
            description = unescape(description),
            severity = severity,
            urgency = urgency,
            latitude = lat,
            longitude = lon,
            receivedAt = System.currentTimeMillis()
        )
    }

    private fun unescape(s: String): String = s
        .replace("&apos;", "'")
        .replace("&quot;", "\"")
        .replace("&gt;", ">")
        .replace("&lt;", "<")
        .replace("&amp;", "&")
}

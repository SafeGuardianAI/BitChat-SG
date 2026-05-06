package com.bitchat.android.ui.lite

import android.content.Context
import android.util.Log
import com.bitchat.android.device.LocationService
import com.bitchat.android.mesh.BluetoothMeshService
import com.bitchat.android.standards.CAPAlertGenerator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Turns a completed [LiteNode.Outcome] into a CAP v1.2 alert and broadcasts
 * it over the BLE mesh. Lite tier devices never run an LLM — this is the only
 * outbound emergency path from the rule tree.
 */
class LiteAlertEmitter(
    private val context: Context,
    private val mesh: BluetoothMeshService,
    private val senderId: String
) {

    private val locationService = LocationService(context)
    private val isoFormat: SimpleDateFormat =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

    fun emit(outcome: LiteNode.Outcome): String {
        val loc = locationService.getLastKnownLocation()
        val identifier = "sg-${UUID.randomUUID()}"
        val now = isoFormat.format(Date())

        val xml = CAPAlertGenerator.generateEmergencyAlert(
            identifier = identifier,
            sender = senderId,
            sent = now,
            event = outcome.event,
            headline = outcome.headline,
            description = "${outcome.description}\n${outcome.instruction}",
            latitude = loc?.latitude ?: 0.0,
            longitude = loc?.longitude ?: 0.0,
            radiusKm = 1.0,
            severity = outcome.severity,
            urgency = outcome.urgency,
            category = outcome.category
        )

        val payload = "[CAP]$xml"
        Log.d(TAG, "Emitting Lite CAP alert id=$identifier event=${outcome.event}")
        mesh.sendMessage(payload)
        return identifier
    }

    companion object {
        private const val TAG = "LiteAlertEmitter"
    }
}

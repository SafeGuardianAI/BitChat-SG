package com.bitchat.android.mesh

import java.io.DataInputStream

/**
 * Parses the raw telemetry bytes broadcast inside ANNOUNCE packets and formats
 * them as a one-line human-readable status string for display in the chat UI.
 *
 * Two wire formats are handled transparently:
 *
 * ① Connectivity-snapshot format (ConnectivityTestViewModel.packConnectivitySnapshot):
 *     [catCount: 1B] [catId: 1B, testCount: 1B, [testId: 1B, status: 1B, detailLen: 1B, detail...]*]*
 *     Known testIds:  0 = BLE,  1 = GPS,  2 = Internet
 *     status:  1 = PASS,  0 = FAIL
 *
 * ② Telemeter.packed() format (Telemeter.packMap):
 *     [count: 4B big-endian int]
 *     [sid: 4B, dataSize: 4B, data: dataSize B]*
 *     Known SIDs (see SensorID):
 *       0x04 BATTERY  → float chargePercent + bool charging + float temp
 *       0x02 LOCATION → int lat*1e6 + int lon*1e6 + int alt*100 + int speed*100 + int bearing*100 + short accuracy*100 + long lastUpdate
 *       0x1B CONNECTIVITY → varies (ConnectivitySensor)
 */
object TelemetryFormatter {

    // SID constants (mirrors SensorID object)
    private const val SID_BATTERY      = 0x04
    private const val SID_LOCATION     = 0x02
    private const val SID_CONNECTIVITY = 0x1B

    /**
     * Returns a compact, plain-text status line, e.g.:
     *   "battery 78% · GPS ok · radio BLE ok WiFi down"
     * Returns null if the bytes can't be parsed at all.
     */
    fun format(raw: ByteArray): String? {
        if (raw.size < 2) return null
        val firstByte = raw[0].toInt() and 0xFF
        return if (firstByte in 1..20) {
            formatConnectivitySnapshot(raw)
        } else {
            formatTelemeterPacked(raw)
        }
    }

    // ─── Connectivity snapshot ────────────────────────────────────────────────

    private fun formatConnectivitySnapshot(raw: ByteArray): String? {
        return try {
            val catCount = raw[0].toInt() and 0xFF
            var off = 1
            var bleOk: Boolean? = null
            var gpsOk: Boolean? = null
            var internetOk: Boolean? = null

            for (c in 0 until catCount) {
                if (off + 2 > raw.size) break
                @Suppress("UNUSED_VARIABLE") val catId = raw[off].toInt() and 0xFF
                val testCount = raw[off + 1].toInt() and 0xFF
                off += 2
                for (t in 0 until testCount) {
                    if (off + 3 > raw.size) break
                    val testId = raw[off].toInt() and 0xFF
                    val status  = raw[off + 1].toInt() and 0xFF
                    val detLen  = raw[off + 2].toInt() and 0xFF
                    off += 3 + detLen
                    val pass = status == 1
                    when (testId) {
                        0 -> bleOk = pass
                        1 -> gpsOk = pass
                        2 -> internetOk = pass
                    }
                }
            }

            buildStatusLine(
                battery = null, charging = null,
                hasGps = gpsOk,
                bleOk = bleOk, wifiOk = null, internetOk = internetOk
            )
        } catch (_: Exception) { null }
    }

    // ─── Telemeter.packed() ───────────────────────────────────────────────────

    private fun formatTelemeterPacked(raw: ByteArray): String? {
        return try {
            val dis = DataInputStream(raw.inputStream())
            val count = dis.readInt()
            if (count <= 0 || count > 50) return null   // sanity

            var battery: Float? = null
            var charging: Boolean? = null
            var hasGps = false

            repeat(count) {
                val sid      = dis.readInt()
                val dataSize = dis.readInt()
                val data     = ByteArray(dataSize).also { dis.readFully(it) }
                val inner    = DataInputStream(data.inputStream())
                when (sid) {
                    SID_BATTERY -> {
                        battery  = inner.readFloat()
                        charging = inner.readBoolean()
                        // temperature float ignored
                    }
                    SID_LOCATION -> {
                        // Any non-zero lat/lon means we have a GPS fix
                        val lat = inner.readInt()
                        val lon = inner.readInt()
                        hasGps  = (lat != 0 || lon != 0)
                    }
                    // Other sensors ignored for the status line
                }
            }

            buildStatusLine(
                battery = battery, charging = charging,
                hasGps = if (hasGps) true else null,
                bleOk = null, wifiOk = null, internetOk = null
            )
        } catch (_: Exception) { null }
    }

    // ─── Formatter ────────────────────────────────────────────────────────────

    private fun buildStatusLine(
        battery: Float?,
        charging: Boolean?,
        hasGps: Boolean?,
        bleOk: Boolean?,
        wifiOk: Boolean?,
        internetOk: Boolean?
    ): String {
        val parts = mutableListOf<String>()

        if (battery != null) {
            val label = when {
                charging == true   -> "charging"
                battery < 20f      -> "battery low"
                else               -> "battery"
            }
            parts += "$label ${battery.toInt()}%"
        }

        if (hasGps != null) {
            parts += if (hasGps) "GPS ok" else "GPS none"
        }

        val radio = buildList<String> {
            if (bleOk != null)      add("BLE ${if (bleOk) "ok" else "down"}")
            if (wifiOk != null)     add("WiFi ${if (wifiOk) "ok" else "down"}")
            if (internetOk != null) add("Net ${if (internetOk) "ok" else "down"}")
        }
        if (radio.isNotEmpty()) parts += "radio ${radio.joinToString(" ")}"

        if (parts.isEmpty()) return "status received"
        return parts.joinToString(" · ")
    }
}

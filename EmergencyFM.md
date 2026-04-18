# Emergency FM Radio

BitChat-SG (Android) — `com.bitchat.android.radio`

---

## Overview

The Emergency FM feature finds the nearest government emergency broadcast station to the user, tunes the device's FM hardware, and passively monitors the audio for EAS (Emergency Alert System) signals. It works without internet — everything is local.

Regions covered: TR, US, GB, DE, JP, AU, FR (196 stations across 65 cities).

---

## Architecture

```
User taps "Emergency FM"
        │
        ▼
EmergencyFmRepository.findNearest()
  └── resolveLocation()          GPS → cached → locale fallback
  └── haversineKm()              distance to each of 65 city centers
  └── returns List<NearestResult> sorted by distance

        │
        ▼
FmHardwareController.tune(station)
  Layer 0: JNI (libqcomfmjni)           Qualcomm rooted/system devices
  Layer 1: RadioManager (reflection)    @SystemApi, OEM devices
  Layer 2: OEM FM Intent                opens device FM app
  Layer 3: Manual                       display frequency, user tunes

        │
        ▼
EasMonitorService (foreground)
  AudioRecord (FM_TUNER source or DEFAULT fallback)
  GoertzelToneDetector.detect() per 512-sample chunk
  → EasAlertState.AlertDetected if dual-tone confirmed
  auto-shutoff after 15 minutes
```

---

## Package Structure

| File | Responsibility |
|---|---|
| `EmergencyFmStation.kt` | Data model + 196 stations + 65 city coordinates |
| `EmergencyFmRepository.kt` | Location resolution + Haversine nearest-station search |
| `FmHardwareController.kt` | 4-layer FM tuner, `FmState` sealed class |
| `GoertzelToneDetector.kt` | EAS dual-tone detector (853 Hz + 960 Hz) |
| `EasMonitorService.kt` | Foreground audio monitoring service |
| `jni/FmNativeWrapper.java` | JNI bridge to `libqcomfmjni.so` (Qualcomm only) |
| `ui/EmergencyFmScreen.kt` | Compose UI — all 5 states + EAS alert chip |

---

## Components

### EmergencyFmStation

Immutable data class. Frequency stored in MHz throughout — only `FmHardwareController` converts to Hz for the RadioManager API.

```kotlin
data class EmergencyFmStation(
    val name: String,
    val frequencyMHz: Float,
    val city: String,
    val country: String,
    val region: EmergencyFmRegion
)
```

Regions: `TR`, `US`, `GB`, `DE`, `JP`, `AU`, `FR`

### EmergencyFmRepository

Location resolution priority:

1. **Last known GPS** — if cached fix is < 30 minutes old, use it immediately (no battery drain)
2. **Fresh GPS fix** — requests one-shot `PRIORITY_BALANCED_POWER_ACCURACY` with 10-second timeout
3. **Locale fallback** — reads `context.resources.configuration.locales[0]` and maps country code to a default city

After resolving location, Haversine distance is computed against all 65 city center coordinates. Returns the top N results sorted by distance, each tagged with its `LocationSource`.

```kotlin
enum class LocationSource { GPS_FRESH, GPS_CACHED, LOCALE_FALLBACK }
```

The UI shows this source so the user knows how accurate the station suggestion is.

### FmHardwareController

4-layer progressive enhancement. Tries each layer in order, stops at first success.

**Layer 0 — JNI (`libqcomfmjni`)**
Qualcomm hardware only. Requires root or system-level permissions. Calls `openDev()` → `powerUp(frequencyMHz)`. If the `.so` isn't present, `FmNativeWrapper.loadNative()` returns false and the layer is skipped instantly.

**Layer 1 — RadioManager (reflection)**
`android.hardware.radio.RadioManager` is `@SystemApi` — not in the public SDK stubs but accessible at runtime on OEM devices via reflection. Resolves the class, finds an FM/FM-HD band module, calls `openTuner()` and `tune(freqHz, 0)`. Works on many Samsung, Sony, and LG devices.

**Layer 2 — OEM FM Intent**
Sends `android.intent.action.FM` with `frequency` extra. If any app on the device can handle it (Samsung Music, HTC FM, etc.), it opens and tunes. No direct hardware control.

**Layer 3 — Manual**
Displays the target frequency and a copy button. The user tunes their own FM app. Always succeeds as a last resort.

State is exposed as a `StateFlow<FmState>`:

```kotlin
sealed class FmState {
    object Idle
    object Connecting
    data class Tuned(station, layer, signalStrengthDbm, isMuted, locationSource)
    data class AppLaunched(station)
    data class ManualInstruction(station)
    data class Error(message)
}
```

### GoertzelToneDetector

Detects the SAME/EAS attention signal: **853 Hz + 960 Hz simultaneously** (ANSI S42.502-2010).

The Goertzel algorithm computes DFT magnitude at a single target frequency without a full FFT — optimal for detecting a small set of known frequencies in real time.

Detection logic:
1. Each 512-sample chunk: compute Goertzel magnitude at 853 Hz and 960 Hz
2. Normalize both against chunk RMS energy (volume-invariant)
3. Both must exceed `DETECTION_THRESHOLD = 0.35`
4. Must hold for `CONSECUTIVE_CHUNKS_REQUIRED = 5` chunks (~160 ms at 8 kHz) to suppress false positives

```kotlin
data class DetectionResult(
    val magnitude853: Double,
    val magnitude960: Double,
    val chunkEnergy: Double,
    val isEasAlert: Boolean
)
```

### EasMonitorService

Foreground service. Runs a daemon thread that reads from `AudioRecord` in a tight loop.

Audio source priority:
- `AudioSource.FM_TUNER` (constant `1998`, `@hide`) — taps directly into FM audio pipeline on supported devices
- Falls back to `AudioSource.DEFAULT` if FM_TUNER is not exposed

Configuration:
- Sample rate: 8000 Hz
- Format: PCM 16-bit mono
- Chunk size: 512 samples

Auto-shutoff: `Handler.postDelayed()` calls `stopSelf()` after 15 minutes.

Alert state is shared via a companion object `StateFlow` so it survives service restarts within the process:

```kotlin
sealed class EasAlertState {
    object Monitoring
    data class AlertDetected(detectedAt, stationName, frequencyMHz)
    object Stopped
}
```

When `AlertDetected` fires, the service updates its foreground notification to high-priority with the station name and frequency, and continues monitoring (does not stop).

---

## UI States

`ui/EmergencyFmScreen.kt` handles all states from `FmHardwareController.state`:

| State | What the user sees |
|---|---|
| `Idle` | Station list + "Find Nearest" button |
| `Connecting` | Loading indicator |
| `Tuned` | Station name, frequency, signal strength, mute toggle, EAS chip if alert active |
| `AppLaunched` | Confirmation that OEM FM app was opened |
| `ManualInstruction` | Frequency displayed + copy button + instructions |

EAS alert chip appears over the tuned state and persists until `EasMonitorService.clearAlert()` is called.

---

## Permissions Required

Add to `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
```

Register the service:

```xml
<service
    android:name="com.bitchat.android.radio.EasMonitorService"
    android:foregroundServiceType="microphone"
    android:exported="false" />
```

---

## Porting to Another App

Copy these 7 files wholesale — no internal dependencies outside the `radio` package:

```
radio/EmergencyFmStation.kt
radio/EmergencyFmRepository.kt
radio/FmHardwareController.kt
radio/GoertzelToneDetector.kt
radio/EasMonitorService.kt
radio/jni/FmNativeWrapper.java
ui/EmergencyFmScreen.kt
```

Modify `EasMonitorService.kt` line 18 to point to your `MainActivity`:
```kotlin
import com.yourpackage.MainActivity
```

Wire the entry point (a button or nav route) to call:
```kotlin
val repo = EmergencyFmRepository(context)
val stations = repo.findNearest(count = 5)
val controller = FmHardwareController(context)
controller.tune(stations.first().station)
```

Start EAS monitoring after tuning:
```kotlin
EasMonitorService.start(context, station.name, station.frequencyMHz)
```

Observe state in your ViewModel:
```kotlin
controller.state.collect { fmState -> ... }
EasMonitorService.alertState.collect { alertState -> ... }
```

---

## Adding Stations

Edit `EMERGENCY_FM_STATIONS` in `EmergencyFmStation.kt`. Add the city coordinates to `CITY_COORDINATES` if the city is not already listed. The key must match `station.city.lowercase()` exactly.

```kotlin
EmergencyFmStation("KQED 88.5", 88.5f, "San Francisco", "US", EmergencyFmRegion.US)

// Add to CITY_COORDINATES if missing:
"san francisco" to Pair(37.7749, -122.4194)
```

# SafeGuardian Rescue API Integration

## Overview

SafeGuardian now includes comprehensive Rescue API integration for emergency victim reporting. The system supports multiple backend providers (MongoDB and Firebase) with automatic failover and mesh channel propagation for offline scenarios.

## Features

### 1. **Multi-Backend Support**
- **MongoDB**: Primary backend using ObjectIds for victim identification
- **Firebase**: Alternative backend with Firebase-style IDs
- **Easy Switching**: Change backends in settings without code changes
- **Flexible ID Handling**: Automatically handles both ID formats

### 2. **Victim Information Capture**
The system captures comprehensive victim data including:
- **Personal Info**: Name, age, gender, language, physical description
- **Location**: GPS coordinates with landmarks
- **Medical Info**: Injuries, pain level, conditions, medications, allergies, blood type
- **Situation**: Disaster type, immediate needs, mobility status, nearby hazards
- **Contact**: Phone, email, emergency contacts
- **Resources**: Food, water, shelter, communication devices status
- **Rescue Info**: Last contact time, ETA, special rescue needs
- **Environmental Data**: Temperature, humidity, air quality, weather
- **Device Data**: Battery level, network status
- **Social Info**: Group size, dependents, nearby victims
- **Psychological Status**: Stress level, special needs

### 3. **Network Error Handling**
When the API endpoint is unreachable:
- Victim data is captured locally
- Can be propagated to mesh channels for peer-to-peer distribution
- Automatic retry on network recovery
- Queue management for offline scenarios

### 4. **Structured Output Integration**
Works seamlessly with the AI-powered victim data generation:
- Uses **GBNF Grammar** constraints for accurate data capture
- Supports emergency survival schema (survival_v2.gbnf)
- Validates parsed JSON before submission

## Usage

### Chat Commands

```
/rescue [on|off]           - Enable/disable rescue API
/rescue-backend [type]     - Switch backend (mongodb|firebase)
/rescue-endpoint [url]     - Set API endpoint
/report [victim-data]      - Submit victim report
/test-rescue              - Test connection to rescue backend
```

### New Structured Output Commands

```
/structured [on|off]              - Toggle structured output enforcement
/structured-type [mode]           - Set mode: off|prompt|grammar
                                    - OFF: Normal response (no constraints)
                                    - PROMPT: JSON format via system prompt
                                    - GRAMMAR: Strict GBNF grammar enforcement
```

### Code Integration

#### Initialize RescueAPI Service

```kotlin
val rescueAPI = RescueAPIService.getInstance(context)
rescueAPI.initialize()
```

#### Post Victim Information

```kotlin
runBlocking {
    val victimInfo = VictimInfo(
        id = "victim-001",
        emergency_status = "critical",
        personal_info = PersonalInfo(
            name = "John Doe",
            age = 35,
            gender = "male",
            language = "English"
        ),
        location = LocationInfo(
            lat = 37.7749,
            lon = -122.4194,
            details = "Collapsed building",
            nearest_landmark = "Golden Gate Bridge"
        ),
        medical_info = MedicalInfo(
            injuries = listOf("broken leg", "head trauma"),
            pain_level = 8,
            medical_conditions = listOf("diabetes"),
            allergies = listOf("penicillin"),
            blood_type = "O+"
        )
    )
    
    // Post with mesh fallback on network error
    val victimId = rescueAPI.postVictim(victimInfo) { victimJson ->
        // Send to mesh channel on network error
        meshService.sendToChannel(victimJson, "#rescue-coordination")
    }
}
```

#### Switch Backends

```kotlin
val rescueAPI = RescueAPIService.getInstance(context)

// Switch to Firebase
rescueAPI.setBackendType(BackendType.FIREBASE)

// Switch back to MongoDB
rescueAPI.setBackendType(BackendType.MONGODB)
```

#### Test Connection

```kotlin
val isConnected = rescueAPI.testConnection()
if (isConnected) {
    Log.d("Rescue", "Connected to backend successfully")
} else {
    Log.w("Rescue", "Backend unreachable - will use mesh propagation")
}
```

## Architecture

### Data Flow

```
AI Response (GBNF Grammar)
    ↓
JSON Parsing (JSONToGBNFConverter)
    ↓
Victim Info Extraction
    ↓
RescueAPIService
    ├─→ Try Primary Backend
    │   ├─→ Success: Save victim ID
    │   └─→ Failure: Invoke mesh callback
    └─→ Network Error Callback
        ↓
    Send to Mesh Channel
        ↓
    Peer-to-Peer Distribution
```

### Backend-Specific Formatting

#### MongoDB Format
```json
{
  "victim_data": {
    "id": "...",
    "emergency_status": "...",
    "personal_info": { ... },
    "location": { ... }
  }
}
```

#### Firebase Format
```json
{
  "victim_info": {
    "id": "...",
    "emergency_status": "...",
    "personal_info": { ... },
    "location": { ... }
  }
}
```

## Settings Integration

### SharedPreferences Keys

```kotlin
RescueAPISettings.Enabled          // Boolean: API enabled
RescueAPISettings.Endpoint         // String: API endpoint URL
RescueAPISettings.AutoReport       // Boolean: Auto-submit reports
RescueAPISettings.LastVictimNumber // String: Last reported victim ID
RescueAPISettings.FirebaseEnabled  // Boolean: Use Firebase backend
RescueAPISettings.MongoDBEnabled   // Boolean: Use MongoDB backend
```

### Settings UI

The app includes a settings panel for:
- Enabling/disabling rescue API
- Switching between backends (MongoDB ↔️ Firebase)
- Setting custom endpoint URL
- Testing backend connection
- Viewing last reported victim ID

## Structured Output Modes

### OFF Mode
- AI responses are unconstrained
- Full natural language responses
- No JSON formatting required

### PROMPT Mode
- JSON formatting enforced via system prompt injection
- Less reliable than grammar mode
- Good for older models

### GRAMMAR Mode (Recommended)
- GBNF grammar constraints enforce valid JSON
- 100% guaranteed valid JSON output
- Uses `survival_v2.gbnf` schema by default
- Most reliable for critical victim data capture

## Mesh Propagation (Offline Sync)

When the API endpoint is unreachable, victim data is automatically sent to a designated mesh channel:

1. **Detection**: Network request fails → triggers mesh callback
2. **Packaging**: Victim JSON is formatted for mesh transmission
3. **Distribution**: Sent to all peers in current channel
4. **Relay**: Peers relay to other mesh participants
5. **Eventual Consistency**: Data reaches rescue coordination nodes

### Channel Examples

```
#rescue-coordination     - Main rescue operations channel
#emergency-reports      - Critical incident reports
#survivor-registry      - Long-term victim tracking
```

## Error Handling

### Network Errors
- Connection timeout → try mesh propagation
- HTTP error codes → log details, try mesh
- Invalid response → attempt parsing recovery

### Data Validation
- Missing required fields → request user clarification
- Invalid coordinates → request new location
- Inconsistent timestamps → use current time

### Backend Errors
- Authentication failures → fall back to mesh
- Rate limiting → queue and retry later
- Service unavailable → mesh propagation

## Security Considerations

1. **Data Encryption**: All data transmitted over HTTPS
2. **ID Anonymization**: Victim IDs are backend-generated
3. **Mesh Encryption**: Mesh channel data uses app's mesh encryption
4. **Rate Limiting**: API enforces request rate limits
5. **Validation**: Server-side validation of all fields

## Performance

- **API Latency**: 500ms - 2s typical
- **Mesh Fallback**: <100ms to queue for propagation
- **Data Storage**: ~1KB per victim report
- **Concurrent Reports**: Unlimited (handled by queue)

## Troubleshooting

### Connection Test Fails
1. Check network connectivity
2. Verify endpoint URL is correct
3. Test in browser: `curl {endpoint}/victims`
4. Check server logs

### Backend Switching Issues
1. Clear app cache
2. Restart app
3. Re-initialize RescueAPI
4. Test connection

### Mesh Propagation Not Working
1. Verify mesh channel name format (#channel)
2. Check mesh service is initialized
3. Confirm peers are connected
4. Check mesh logs

## Future Enhancements

- [ ] Automatic endpoint failover (primary + backup)
- [ ] Partial data sync (sync partial reports)
- [ ] Encryption key distribution via mesh
- [ ] Real-time sync status UI
- [ ] Victim data aggregation from multiple reporters
- [ ] Conflict resolution for duplicate reports
- [ ] Integration with emergency services APIs

## References

- **survival_v2.gbnf**: Emergency victim schema
- **JSONToGBNFConverter**: JSON to GBNF conversion utility
- **RescueAPIService**: Multi-backend victim reporting
- **StructuredOutputMode**: AI output formatting modes

## Support

For issues or questions about the Rescue API integration, refer to:
- In-app `/test-rescue` command for diagnostics
- Mesh channel #rescue-coordination for peer support
- Application logs with TAG="RescueAPIService"





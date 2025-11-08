# SafeGuardian Testing and Debugging Guide

## Quick Test Commands

Run these commands in the chat to test features:

### Rescue API Testing

```
/test-rescue               # Test connection to rescue backend
/test-rescue-submit        # Submit test victim report to API
/test-backend-switch       # Test MongoDB ↔ Firebase switching
/test-diagnostics          # Run full system diagnostics
```

### TTS Testing

```
/test-tts                                    # Test with default message
/test-tts SafeGuardian emergency activated   # Test with custom text
```

### Debug Logging

```
/debug-logs               # Show all debug logs
/debug-clear              # Clear debug logs
```

## RescueAPIDebugger Features

### Comprehensive Logging

The `RescueAPIDebugger` automatically logs all operations with timestamps:

```
10:45:32.123 [INFO] RescueAPIService: ✅ Rescue API connection SUCCESSFUL
10:45:32.456 [DEBUG] RescueAPIService: Backend: MONGODB
10:45:33.789 [INFO] RescueAPIService: Test victim data: Test Victim
```

### Test Functions

#### 1. Test Connection
```
/test-rescue
```
- Verifies API endpoint is reachable
- Reports current backend (MongoDB/Firebase)
- Shows connection status (✅ success or ❌ failure)

#### 2. Test Victim Submission
```
/test-rescue-submit
```
- Creates test victim with sample data
- Submits to configured backend
- Logs victim ID on success
- Triggers mesh fallback on network error

#### 3. Test Backend Switching
```
/test-backend-switch
```
- Switches to MongoDB
- Tests connection
- Switches to Firebase
- Tests connection
- Switches back to MongoDB

#### 4. Test TTS Playback
```
/test-tts "your text here"
```
- Initiates text-to-speech
- Logs "Audio should play shortly"
- Check device volume/speakers

#### 5. Full Diagnostics
```
/test-diagnostics
```
Runs comprehensive system check including:
- Device info (manufacturer, OS version, SDK)
- Available processors
- AI/TTS/ASR/RAG settings
- API connection test
- Backend switching test
- TTS test
- Memory usage

## Understanding Log Output

### Log Levels

- **INFO** (ℹ️) - Important operations completed successfully
- **DEBUG** (🐞) - Detailed information for debugging
- **WARN** (⚠️) - Warning conditions, operation may have issues
- **ERROR** (❌) - Error conditions, operation failed

### Example Log Analysis

```
10:45:00.000 [INFO] RescueAPIService: Starting Rescue API connection test...
10:45:01.234 [INFO] RescueAPIService: ✅ Rescue API connection SUCCESSFUL
10:45:01.235 [INFO] RescueAPIService: Backend: MONGODB
```
**Meaning**: API is connected and using MongoDB backend.

```
10:46:00.000 [INFO] RescueAPIService: Starting victim report submission test...
10:46:02.345 [INFO] RescueAPIService: ✅ Victim report submitted successfully
10:46:02.346 [INFO] RescueAPIService: Victim ID: test-1702814400345
```
**Meaning**: Victim report was successfully submitted and received an ID.

```
10:47:00.000 [WARN] RescueAPIService: ⚠️ Rescue API connection FAILED
10:47:00.001 [WARN] RescueAPIService: Mesh fallback triggered for network error
```
**Meaning**: API unreachable, data will be sent to mesh channel instead.

## Accessing Debug Information

### Via Chat Commands

1. **View Recent Logs**
   ```
   /debug-logs
   ```
   Shows last 100 log entries in the chat

2. **Export Debug Info**
   ```
   RescueAPIDebugger.getInstance(context).getDebugInfo()
   ```
   Gets formatted debug report with:
   - Recent logs
   - Device info
   - Memory usage

### Via adb Logcat

Monitor all system logs:
```bash
adb logcat | grep -E "(RescueAPIService|RescueAPIDebugger|AIService|TTSService)"
```

Filter by tag:
```bash
adb logcat | grep "RescueAPIDebugger"
```

Save logs to file:
```bash
adb logcat > rescue_debug.log
```

## Testing Workflows

### 1. Verify Rescue API Setup

```
1. /test-rescue                  # Check connection
2. /test-backend-switch          # Verify both backends work
3. /test-rescue-submit           # Submit test report
4. /debug-logs                   # Review operation logs
```

### 2. Test TTS Functionality

```
1. /test-tts                     # Test default audio
2. /test-tts Emergency alert     # Test custom text
3. Verify audio plays on device
4. Check /debug-logs for TTS errors
```

### 3. Full System Diagnostic

```
1. /test-diagnostics            # Run all tests
2. /debug-logs                   # Review comprehensive logs
3. Check each section:
   - Device capabilities
   - API connectivity
   - Backend switching
   - TTS audio output
```

### 4. Network Error Simulation

```
1. Disable network (airplane mode)
2. /test-rescue-submit           # Should trigger mesh fallback
3. /debug-logs                   # Verify mesh callback triggered
4. Re-enable network
5. /test-rescue                  # Verify reconnection
```

## Troubleshooting with Logs

### Problem: API Connection Always Fails

**Log to check:**
```
RescueAPIService: Connection test failed
```

**Debug steps:**
```
1. Verify endpoint is correct: Check settings
2. Check network connectivity: /test-diagnostics
3. Verify backend is configured: /test-backend-switch
4. Check firewall/proxy: Try different network
```

### Problem: TTS No Audio Output

**Log to check:**
```
RescueAPIDebugger: TTS playback initiated
```

**But no sound heard, check:**
```
1. Device volume is not muted
2. Speaker/headphone connected
3. TTS enabled in settings
4. Check system audio routing
5. Run /test-tts again and monitor logs
```

**Additional logging:**
```bash
adb logcat | grep TTSService
```

### Problem: Victim Report Fails to Submit

**Log to check:**
```
RescueAPIService: Victim report submission failed
```

**Verify:**
```
1. /test-rescue              # API connection working?
2. Check victim data: Valid JSON?
3. /test-backend-switch      # Backend responsive?
4. /debug-logs               # See exact error message
```

### Problem: Mesh Fallback Not Triggering

**Log to check:**
```
RescueAPIService: Mesh fallback triggered
```

**If not appearing when network down:**
```
1. Verify mesh service initialized
2. Check mesh channel configuration
3. Ensure peers are connected
4. Verify onNetworkError callback is set
```

## Memory and Performance

Monitor from logs:
```
MEMORY INFO:
Total: 3.50 GB
Used: 1.20 GB (34%)
Free: 2.30 GB
```

**Optimization tips:**
- If memory usage > 70%, check for memory leaks
- Monitor token generation for slow TTS
- Check processor count for AI performance

## Automating Tests

### Schedule Regular Diagnostics

Create a background job that runs `/test-diagnostics` periodically:

```kotlin
// Run diagnostics every 1 hour
val diagnosticsJob = scope.launch {
    while (isActive) {
        debugger.runFullDiagnostics(rescueAPI, aiService, preferences)
        delay(60 * 60 * 1000)  // 1 hour
    }
}
```

### Real-Time Log Monitoring

```bash
# Watch logs in real-time
adb logcat -s "RescueAPIDebugger" | while read line; do
    echo "[$(date +'%H:%M:%S')] $line"
done
```

## Export and Analysis

### Generate Debug Report

```kotlin
val debugInfo = debugger.getDebugInfo()
// Share or analyze:
// - Recent logs
// - Device info
// - Memory stats
```

### Parse Logs for Analysis

```bash
# Count errors
grep "\[ERROR\]" rescue_debug.log | wc -l

# Show only API tests
grep "test" rescue_debug.log

# Timeline of operations
grep "SUCCESSFUL\|FAILED" rescue_debug.log
```

## Best Practices

1. **Always check logs after testing**
   ```
   Run command → Run /debug-logs → Review output
   ```

2. **Clear logs before major tests**
   ```
   /debug-clear → Run test suite → /debug-logs
   ```

3. **Monitor during network changes**
   - Toggle airplane mode
   - Switch networks
   - Check log reactions

4. **Document issues with logs**
   - Take screenshot of /debug-logs
   - Save adb logcat output
   - Include in bug reports

5. **Use test-diagnostics before deployment**
   ```
   /test-diagnostics → Fix any issues → Deploy
   ```

## Available Test Data

### Test Victim Profile

Used in `/test-rescue-submit`:

```json
{
  "id": "test-<timestamp>",
  "emergency_status": "critical",
  "personal_info": {
    "name": "Test Victim",
    "age": 35,
    "gender": "unknown",
    "language": "English"
  },
  "location": {
    "lat": 37.7749,
    "lon": -122.4194,
    "details": "Test Location",
    "nearest_landmark": "Test Landmark"
  },
  "medical_info": {
    "injuries": ["test injury"],
    "pain_level": 5,
    "blood_type": "O+"
  }
}
```

## Support Resources

- Check `/debug-logs` for detailed error messages
- Run `/test-diagnostics` for comprehensive system info
- Monitor `adb logcat` for real-time updates
- Review `RESCUE_API_INTEGRATION.md` for API details
- Check `NEXA_SDK_INTEGRATION_GUIDE.md` for AI/TTS info

## Quick Reference

| Command | Purpose | Check Logs For |
|---------|---------|-----------------|
| `/test-rescue` | API connection | "SUCCESSFUL" or "FAILED" |
| `/test-rescue-submit` | Submit victim | "Victim ID:" or error message |
| `/test-tts <text>` | Audio output | "Audio should play shortly" |
| `/test-backend-switch` | Both backends | MongoDB and Firebase status |
| `/test-diagnostics` | Full system | All subsystems status |
| `/debug-logs` | View logs | Last 100 operations |
| `/debug-clear` | Clear logs | Fresh start for testing |





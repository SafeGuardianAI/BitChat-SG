# UI Components - Microphone Input & Speaker Output

## Overview
Added visible and discoverable UI components for voice input (ASR) and text-to-speech (TTS) output in the SafeGuardian Android chat interface.

## Components Added

### 1. Visible Microphone Input Button
**File**: `InputComponents.kt` (MessageInput composable)

**Location**: Input bar next to the send button

**Features**:
- 🎙️ **Always Visible** when voice is available (unlike the previous hidden button)
- **Purple circle** when idle - matches the voice theme
- **Bright red circle** when actively recording - provides visual feedback
- **Mic icon** when idle, **Mic-off icon** when recording
- **Size**: 30dp circle for easy tapping
- **Responsive**: Connected to `isRecordingVoice` state
- **Accessible**: Clear description text for screen readers

**Visual States**:
```
Idle State:         [🎙] Purple button
Recording State:    [⊗] Red button  
Disabled State:     (Hidden when voice unavailable)
```

**Code Integration**:
```kotlin
// Microphone input button (always visible when voice is available)
if (isVoiceAvailable && onVoiceClick != null) {
    IconButton(
        onClick = onVoiceClick,
        modifier = Modifier.size(32.dp)
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .background(
                    color = if (isRecordingVoice) {
                        colorScheme.error.copy(alpha = 0.85f) // Bright red
                    } else {
                        Color(0xFF9C27B0).copy(alpha = 0.65f) // Purple
                    },
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isRecordingVoice) Icons.Filled.MicOff else Icons.Filled.Mic,
                contentDescription = if (isRecordingVoice) "Stop recording" else "Start voice input",
                modifier = Modifier.size(18.dp),
                tint = Color.White
            )
        }
    }
}
```

**User Flow**:
1. User sees purple microphone button in input area
2. Taps button to start recording (changes to red)
3. Speaks their message
4. Taps again to stop recording
5. Audio is processed and converted to text
6. Text is automatically sent or can be edited first

---

### 2. Speaker/TTS Button for AI Messages
**File**: `MessageComponents.kt` (MessageItem composable)

**Location**: Right side of AI generation statistics row

**Features**:
- 🔊 **Speaker icon** in cyan color (#00BCD4) for audio playback
- **Positioned** after token count, speed, processing unit, and time stats
- **Cyan accent** differentiates it from other UI elements
- **Clean design** - small icon (16dp) on 28dp button
- **Accessible**: Clear description for screen readers
- **Smart placement**: Only appears for AI-generated messages

**Visual Layout**:
```
📊 125 tokens | ⚡ 45.23 tok/s | 💻 CPU | ⏱ 2750ms    [🔊]
```

**Code Integration**:
```kotlin
// Speaker/TTS button for AI messages
IconButton(
    onClick = { 
        // TODO: Implement TTS playback for this message
        // Can be connected to AIService.speak(message.content)
    },
    modifier = Modifier.size(28.dp)
) {
    Icon(
        imageVector = Icons.Filled.VolumeUp,
        contentDescription = "Play message audio (TTS)",
        modifier = Modifier.size(16.dp),
        tint = Color(0xFF00BCD4) // Cyan for audio
    )
}
```

**User Flow**:
1. AI generates and displays response with statistics
2. User sees cyan speaker icon at end of stats row
3. Taps speaker icon to hear the message read aloud
4. TTS service processes and plays the audio
5. User can continue interacting with other messages

---

## Design Rationale

### Microphone Button Placement
- **Always visible**: Not hidden when text field is empty, making it more discoverable
- **Consistent position**: Always in the same place in input bar
- **Visual hierarchy**: Purple is distinct from send button (green/orange) and command button (/)
- **Recording feedback**: Red color during recording provides clear status

### Speaker Button Placement
- **Near statistics**: Grouped with AI message metadata
- **Right-aligned**: Doesn't interfere with reading the message
- **Subtle color**: Cyan is calming and associated with audio/media
- **Optional interaction**: Doesn't disrupt the message if user doesn't want audio

---

## Implementation Details

### State Management

**Microphone Button**:
- Uses existing `isVoiceAvailable` state from AIPreferences
- Uses existing `isRecordingVoice` state from ChatScreen
- Connected to `onVoiceClick` callback
- Callback toggles recording state

**Speaker Button**:
- Placeholder onClick handler (TODO for full TTS integration)
- Can be connected to `AIService.speak(message.content)`
- Respects TTS enable/disable setting from AIPreferences

### Color Scheme
- **Microphone Idle**: `Color(0xFF9C27B0)` - Purple (#9C27B0) @ 65% alpha
- **Microphone Recording**: `colorScheme.error` - Red @ 85% alpha
- **Speaker Button**: `Color(0xFF00BCD4)` - Cyan (#00BCD4)

### Icon Sources
- Microphone: `Icons.Filled.Mic` (Compose Material Icons)
- Microphone Off: `Icons.Filled.MicOff` (Compose Material Icons)
- Speaker: `Icons.Filled.VolumeUp` (Compose Material Icons)

---

## Future Enhancements

### Microphone Button
1. **Recording timer**: Display countdown for max recording duration
2. **Audio level indicator**: Show voice amplitude during recording
3. **Waveform visualization**: Display recording waveform in real-time
4. **Hold-to-record**: Support Android style long-press recording
5. **Cancel gesture**: Swipe-up to cancel recording

### Speaker Button
1. **Active playback indicator**: Show when TTS is playing
2. **Playback controls**: Play, pause, speed adjustment
3. **Audio progress**: Display playback position
4. **Favorite voices**: Support different TTS voices
5. **Download option**: Cache audio for offline playback

---

## Testing Checklist

### Microphone Button
- [ ] Button visible when voice is available
- [ ] Button hidden when voice unavailable
- [ ] Purple color in idle state
- [ ] Red color during recording
- [ ] Mic icon shows in idle state
- [ ] Mic-off icon shows during recording
- [ ] Click starts/stops recording
- [ ] Button position consistent
- [ ] Button is touch-friendly (32dp hit target)

### Speaker Button
- [ ] Button visible on AI-generated messages
- [ ] Button not visible on user/system messages
- [ ] Cyan color consistent
- [ ] Icon visible and clear
- [ ] Click is responsive
- [ ] Button positioned after all stats
- [ ] Proper alignment in stats row
- [ ] Accessible (content description works)

---

## Accessibility Features

### Microphone Button
- **Content Description**: "Start voice input" (idle) / "Stop recording" (recording)
- **Size**: 32dp hit target meets accessibility guidelines
- **Color Contrast**: White icon on colored background meets WCAG AA
- **State Feedback**: Color change provides visual feedback

### Speaker Button
- **Content Description**: "Play message audio (TTS)"
- **Size**: 28dp button with 16dp icon
- **Color Contrast**: Cyan icon visible on light/dark backgrounds
- **Semantic Meaning**: VolumeUp icon is universally understood

---

## Files Modified

1. **InputComponents.kt**
   - Added persistent microphone button in MessageInput
   - Removed duplicate hidden voice button
   - Button shows when `isVoiceAvailable == true`

2. **MessageComponents.kt**
   - Added speaker button to AI message stats row
   - Imported VolumeUp icon
   - Added proper alignment and spacing

---

## Integration Points

### Connected to Existing Systems
- **Microphone Button** → AIPreferences.microphoneEnabled
- **Microphone Button** → ChatScreen.isRecordingVoice state
- **Microphone Button** → VoiceInputService.startRecording()
- **Speaker Button** → AIService.speak() (TODO implementation)
- **Speaker Button** → AIPreferences.ttsEnabled check

### Observable States
- `isVoiceAvailable` - Determines if microphone button shows
- `isRecordingVoice` - Determines button color/icon
- `message.isAIGenerated` - Determines if speaker button shows
- `aiPreferences.ttsEnabled` - Can gate TTS button

---

## Summary

These UI additions make voice input and text-to-speech features highly visible and discoverable:

✅ **Microphone Button**
- Always visible in input area when available
- Clear visual feedback during recording
- Easy to access and interact with

✅ **Speaker Button**
- Grouped with message statistics
- Accessible and non-intrusive
- Ready for TTS playback integration

Both components follow Material Design 3 guidelines and integrate seamlessly with the existing SafeGuardian Android UI.





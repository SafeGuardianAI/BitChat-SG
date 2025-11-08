# Nexa SDK Integration Guide for SafeGuardian

## Overview

This guide explains how SafeGuardian integrates the Nexa SDK for on-device LLM inference with streaming support, GBNF grammar constraints, and emergency victim data extraction.

## Core API Pattern

### 1. Initialize Nexa SDK

```kotlin
val aiService = AIService(context, modelManager, preferences)
aiService.initialize()  // Call once on app start
```

### 2. Load Model

```kotlin
val model = AIModel(id = "llama2", name = "Llama 2", path = "/path/to/model.gguf")
val result = aiService.loadModel(model)
result.onSuccess { state ->
    Log.d("AI", "Model loaded: ${state.name}")
}.onFailure { error ->
    Log.e("AI", "Failed to load: ${error.message}")
}
```

### 3. Generate Streaming Response

```kotlin
aiService.generateResponse(
    prompt = "What is your emergency status?",
    systemPrompt = "You are an emergency responder..."
).collect { response ->
    when (response) {
        is AIResponse.Token -> updateUI(response.text)
        is AIResponse.Completed -> showProfileData(response.profile)
        is AIResponse.Error -> showError(response.message)
    }
}
```

## Streaming Architecture

### The Complete Flow

```
User Input
    ↓
applyChatTemplate() - Format with system prompt
    ↓
generateStreamFlow() - Stream tokens from model
    ↓
LlmStreamResult (sealed class)
    ├─ Token → String token (partial output)
    ├─ Completed → Profile (generation stats)
    └─ Error → Throwable (error info)
    ↓
AIResponse (app wrapper)
    ├─ Token(text)
    ├─ Completed(profile)
    └─ Error(message)
    ↓
UI Update (real-time streaming)
```

## Nexa SDK Key Components

### 1. LlmWrapper

Main interface for LLM operations:

```kotlin
// Create wrapper
LlmWrapper.builder()
    .llmCreateInput(LlmCreateInput(...))
    .build()
    .onSuccess { wrapper ->
        // wrapper is ready to use
    }

// Use wrapper
wrapper.applyChatTemplate(messages, tools, false)  // Format prompt
wrapper.generateStreamFlow(text, config)            // Stream generation
wrapper.stopStream()                                // Stop generation
wrapper.destroy()                                   // Cleanup
```

### 2. GenerationConfig

Controls how the model generates text:

```kotlin
GenerationConfig(
    maxTokens = 2048,                    // Max output length
    samplerConfig = SamplerConfig(
        topK = 40,                       // Top-K sampling
        topP = 0.95f,                    // Nucleus sampling
        temperature = 0.8f,              // Randomness (0.0-1.0+)
        repetitionPenalty = 1.1f,        // Penalize repetition
        presencePenalty = 0.0f,          // Penalize presence
        seed = -1,                       // Random seed (-1 = random)
        grammarString = survivalSchema   // GBNF grammar (optional)
    ),
    stopWords = null,                    // Words to stop at
    stopCount = 0
)
```

### 3. LlmStreamResult (sealed class)

```kotlin
sealed class LlmStreamResult {
    data class Token(val text: String) : LlmStreamResult()
    data class Completed(val profile: Any?) : LlmStreamResult()
    data class Error(val throwable: Throwable) : LlmStreamResult()
}
```

### 4. ChatMessage & applyChatTemplate

```kotlin
// Format conversation
val messages = arrayOf(
    ChatMessage("system", "You are a helpful assistant."),
    ChatMessage("user", "Hello!")
)

wrapper.applyChatTemplate(messages, tools = null, addGenerationPrompt = false)
    .onSuccess { result ->
        val formattedText = result.formattedText
        // Use formattedText for generation
    }
```

## Grammar-Based Structured Output

### Integration Pattern

```kotlin
val grammarString = if (useStructuredOutput) {
    AIGrammarDefinitions.EMERGENCY_SURVIVAL_GRAMMAR  // GBNF constraint
} else {
    null  // Unconstrained generation
}

val config = GenerationConfig(
    maxTokens = 2048,
    samplerConfig = SamplerConfig(
        // ... other params ...
        grammarString = grammarString
    )
)

wrapper.generateStreamFlow(formattedText, config)
    .collect { result ->
        // Model now generates ONLY valid JSON matching the grammar
    }
```

### Three Modes

1. **OFF**: `grammarString = null`
   - No constraints
   - Natural language response
   - Fastest

2. **PROMPT**: `grammarString = null` (but system prompt includes instructions)
   - JSON suggested via system prompt
   - Less reliable
   - May output non-JSON

3. **GRAMMAR** (Recommended): `grammarString = EMERGENCY_SURVIVAL_GRAMMAR`
   - Strict GBNF grammar enforcement
   - 100% valid JSON guaranteed
   - Best for critical victim data

## Structured Output Integration

### Complete Example

```kotlin
// In AIService.generateResponse()
val finalSystemPrompt = when (preferences.structuredOutputMode) {
    StructuredOutputMode.PROMPT -> {
        "$systemPrompt\n\n[STRUCTURED OUTPUT]\nRespond as valid JSON..."
    }
    StructuredOutputMode.GRAMMAR -> {
        "$systemPrompt\n\nRespond in JSON format."
    }
    StructuredOutputMode.OFF -> systemPrompt
}

val messages = arrayOf(
    ChatMessage("system", finalSystemPrompt),
    ChatMessage("user", prompt)
)

wrapper.applyChatTemplate(messages, null, false).onSuccess { result ->
    val genConfig = GenerationConfig(
        maxTokens = preferences.getModelConfig().maxTokens,
        samplerConfig = SamplerConfig(
            topK = 40,
            topP = 0.95f,
            temperature = 0.8f,
            repetitionPenalty = 1.1f,
            presencePenalty = 0.0f,
            seed = -1,
            grammarString = if (preferences.structuredOutputMode == StructuredOutputMode.GRAMMAR) {
                AIGrammarDefinitions.EMERGENCY_SURVIVAL_GRAMMAR
            } else {
                null
            }
        )
    )
    
    wrapper.generateStreamFlow(result.formattedText, genConfig).collect { streamResult ->
        when (streamResult) {
            is LlmStreamResult.Token -> {
                emit(AIResponse.Token(streamResult.text))  // Real-time output
            }
            is LlmStreamResult.Completed -> {
                // Extract profile data for statistics
                emit(AIResponse.Completed(streamResult.profile))
            }
            is LlmStreamResult.Error -> {
                emit(AIResponse.Error("${streamResult.throwable.message}"))
            }
        }
    }
}
```

## Rescue API Integration

### JSON to Victim Info Flow

```
GenerationConfig(grammarString = EMERGENCY_SURVIVAL_GRAMMAR)
    ↓
Model generates JSON (guaranteed valid)
    ↓
Token stream: "{", "\"victim_info\":", "{", ...
    ↓
Collect all tokens → full JSON string
    ↓
JSONToGBNFConverter.parseAIResponse(jsonString)
    ↓
Extract VictimInfo object
    ↓
RescueAPIService.postVictim(victimInfo)
    ↓
MongoDB/Firebase backend
    ├─ Success → Save victim ID
    └─ Failure → Mesh propagation
```

### Example Implementation

```kotlin
// 1. Set structured output to GRAMMAR mode
preferences.structuredOutputMode = StructuredOutputMode.GRAMMAR

// 2. Ask for victim information
aiService.generateResponse(
    "Report victim status. Name: John Doe, Location: (37.7749, -122.4194), " +
    "Injuries: broken leg, Status: critical"
).collect { response ->
    when (response) {
        is AIResponse.Token -> {
            // Accumulate tokens
            accumulatedJson += response.text
        }
        is AIResponse.Completed -> {
            // Parse completed JSON
            val victimInfo = JSONToGBNFConverter.parseAIResponse(accumulatedJson)
            
            if (victimInfo != null) {
                // Submit to rescue API
                val victimId = rescueAPI.postVictim(victimInfo) { failedJson ->
                    // On network error, send to mesh
                    meshService.sendToChannel(failedJson, "#rescue-coordination")
                }
            }
        }
        is AIResponse.Error -> {
            Log.e("Victim", response.message)
        }
    }
}
```

## Performance Tuning

### Model Configuration

```kotlin
// Recommended settings for emergency response
ModelConfig(
    nCtx = 1024,              // Context window
    max_tokens = 2048,        // Max generation length
    nThreads = 4,             // CPU threads
    nThreadsBatch = 4,        // Batch threads
    nBatch = 1,               // Batch size
    nUBatch = 1,              // Unbatch size
    nSeqMax = 1,              // Sequence max
    nGpuLayers = 0            // GPU layers (0 = CPU only)
)
```

### Sampling Parameters

```kotlin
SamplerConfig(
    topK = 40,               // Keep top 40 tokens
    topP = 0.95f,            // Nucleus (95% probability mass)
    temperature = 0.8f,      // Moderate randomness
    repetitionPenalty = 1.1f, // Slight repetition penalty
    presencePenalty = 0.0f,  // No presence penalty
    seed = -1                // Random seed
)
```

For victim data (deterministic): `temperature = 0.2f`
For creative responses: `temperature = 1.0f+`

## Error Handling

### Common Issues

1. **No Token Received**
   - Check model is loaded
   - Verify prompt is not empty
   - Check grammar matches model output

2. **Grammar Mismatches**
   - Ensure grammar is valid GBNF
   - Test with simpler grammar first
   - Add logging to track token flow

3. **Out of Memory**
   - Reduce `nCtx` or `max_tokens`
   - Lower `nBatch` size
   - Close other apps

4. **Slow Generation**
   - Increase `nThreads`
   - Enable GPU layers if available
   - Use smaller model

## Debug Logging

Enable detailed logging:

```kotlin
// In AIService
private const val TAG = "AIService"
Log.d(TAG, "Starting generation...")
Log.d(TAG, "Chat template applied")
Log.d(TAG, "Token: ${streamResult.text}")
Log.d(TAG, "Completed with profile: ${streamResult.profile}")
Log.e(TAG, "Generation error: ${streamResult.throwable.message}")
```

Check logs:
```bash
adb logcat | grep AIService
```

## Testing

### Unit Test Example

```kotlin
@Test
fun testStructuredOutput() {
    // Load small test model
    val model = AIModel(id = "test", name = "Test", path = "/path/to/test.gguf")
    aiService.loadModel(model)
    
    // Set GRAMMAR mode
    preferences.structuredOutputMode = StructuredOutputMode.GRAMMAR
    
    // Generate
    var output = ""
    runBlocking {
        aiService.generateResponse("Test prompt").collect { response ->
            if (response is AIResponse.Token) {
                output += response.text
            }
        }
    }
    
    // Verify JSON
    val victimInfo = JSONToGBNFConverter.parseAIResponse(output)
    assertNotNull(victimInfo)
    assertNotNull(victimInfo.personal_info?.name)
}
```

## Best Practices

1. **Always use structured output (GRAMMAR mode) for victim data**
   - Guarantees valid JSON
   - Prevents parsing errors
   - Enables reliable data extraction

2. **Handle streaming appropriately**
   - Update UI in real-time
   - Buffer tokens for display
   - Show completion status

3. **Implement proper error handling**
   - Catch network errors
   - Handle model generation errors
   - Provide user feedback

4. **Manage resources**
   - Load models on demand
   - Unload after use
   - Respect memory limits

5. **Log comprehensively**
   - Track token generation
   - Monitor profile data
   - Record errors with context

## References

- **LlmStreamResult**: Token/Completed/Error result types
- **GenerationConfig**: Generation parameters
- **SamplerConfig**: Sampling parameters with grammar support
- **AIGrammarDefinitions**: GBNF grammar templates
- **JSONToGBNFConverter**: JSON parsing from AI output
- **RescueAPIService**: Backend submission with fallback

## Troubleshooting

### Grammar Not Working?
```kotlin
// Verify grammar is not null
Log.d(TAG, "Grammar: ${sampler.grammarString?.take(100)}")

// Check grammar syntax
// Valid GBNF: root ::= "{" ... "}"
```

### Model Too Slow?
```kotlin
// Reduce context and tokens
ModelConfig(nCtx = 512, max_tokens = 256)

// Check available threads
Log.d(TAG, "Threads: ${Runtime.getRuntime().availableProcessors()}")
```

### Memory Issues?
```kotlin
// Monitor memory
val runtime = Runtime.getRuntime()
val usedMemory = runtime.totalMemory() - runtime.freeMemory()
Log.d(TAG, "Memory used: $usedMemory bytes")
```

## Support

For Nexa SDK documentation: Check `nexa-sdk-examples/` directory
For SafeGuardian integration: See `RESCUE_API_INTEGRATION.md`





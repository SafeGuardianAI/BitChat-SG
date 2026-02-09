# Battery & Memory Efficient UI Architecture

## Design Principles for SafeGuardian

### Core Goals
1. **Minimal Recomposition**: Reduce Compose recompositions
2. **Lazy Loading**: Load UI elements on demand
3. **Efficient State**: Use derivedStateOf and remember wisely
4. **Background Processing**: Keep AI inference off main thread
5. **Visual Clarity**: Clean, readable UI without heavy animations

---

## Battery Optimization Strategies

### 1. **Reduce CPU/GPU Usage**

#### Minimize Recompositions
```kotlin
// ❌ BAD: Every state change triggers full recomposition
@Composable
fun ChatScreen(viewModel: ChatViewModel) {
    val messages by viewModel.messages.observeAsState(emptyList())
    // Entire screen recomposes on every message
}

// ✅ GOOD: Only affected parts recompose
@Composable
fun ChatScreen(viewModel: ChatViewModel) {
    val messages by viewModel.messages.observeAsState(emptyList())

    LazyColumn {  // Only new items added, not full redraw
        items(
            items = messages,
            key = { it.id }  // Stable keys prevent unnecessary recomposition
        ) { message ->
            MessageItem(message)  // Isolated recomposition
        }
    }
}
```

#### Avoid Expensive Operations in Composition
```kotlin
// ❌ BAD: Calculation runs every recomposition
@Composable
fun AIStatusBadge(aiService: AIService) {
    val status = aiService.getStatus()  // Called repeatedly!
}

// ✅ GOOD: Cached calculation
@Composable
fun AIStatusBadge(aiService: AIService) {
    val status by remember {
        derivedStateOf { aiService.getStatus() }
    }
}
```

### 2. **Lazy Loading**

```kotlin
// Only load AI features when user opens AI sheet
@Composable
fun ChatScreen(viewModel: ChatViewModel) {
    var showAISheet by remember { mutableStateOf(false) }

    // Main UI (always rendered)
    ChatContent()

    // AI UI (loaded on demand)
    if (showAISheet) {
        AIAssistantSheet(
            onDismiss = { showAISheet = false }
        )
    }
}
```

### 3. **Avoid Heavy Animations**

```kotlin
// ✅ Simple, battery-friendly animations
@Composable
fun AIThinkingIndicator() {
    val alpha by animateFloatAsState(
        targetValue = if (isThinking) 1f else 0f,
        animationSpec = tween(300)  // Short, simple fade
    )

    Text(
        text = "AI is thinking...",
        modifier = Modifier.alpha(alpha)
    )
}

// ❌ Avoid: Complex particle effects, 3D transforms, shaders
```

### 4. **Efficient List Rendering**

```kotlin
@Composable
fun MessageList(messages: List<BitchatMessage>) {
    LazyColumn(
        // Use stable keys
        key = { message -> message.id },
        // Use content type for different layouts
        contentType = { message -> message.type }
    ) {
        items(messages) { message ->
            // Each item is independently composable
            when (message.type) {
                MessageType.TEXT -> TextMessage(message)
                MessageType.AI_RESPONSE -> AIMessage(message)
            }
        }
    }
}
```

---

## Memory Optimization Strategies

### 1. **Limit Context Window**

```kotlin
class ConversationContext {
    private val maxMessages = 50  // Keep only recent messages
    private val chatHistory = ArrayDeque<ChatMessage>(maxMessages)

    fun addMessage(message: ChatMessage) {
        if (chatHistory.size >= maxMessages) {
            chatHistory.removeFirst()  // Drop oldest
        }
        chatHistory.addLast(message)
    }
}
```

### 2. **Model Unloading**

```kotlin
class AIService {
    private var llmWrapper: LlmWrapper? = null
    private val unloadTimer = Timer()

    // Auto-unload after 5 minutes of inactivity
    fun scheduleUnload() {
        unloadTimer.schedule(300_000) {  // 5 minutes
            unloadModel()
        }
    }

    fun unloadModel() {
        llmWrapper?.destroy()
        llmWrapper = null
        System.gc()  // Suggest garbage collection
    }
}
```

### 3. **Efficient Image Loading**

```kotlin
// Use Coil with memory caching
@Composable
fun UserAvatar(userId: String) {
    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current)
            .data(getAvatarUrl(userId))
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .size(48)  // Resize to actual display size
            .build(),
        contentDescription = "Avatar"
    )
}
```

### 4. **Limit Vector DB Size**

```kotlin
class RAGService {
    private val maxEmbeddings = 10_000  // Limit total embeddings

    suspend fun indexMessage(message: BitchatMessage) {
        val currentCount = messageEmbeddingBox.count()

        if (currentCount >= maxEmbeddings) {
            // Prune oldest 10%
            pruneOldEmbeddings(maxEmbeddings / 10)
        }

        // Add new embedding
        addEmbedding(message)
    }
}
```

---

## Visual Clarity without Performance Cost

### 1. **Clean Typography**

```kotlin
// Use Material3 typography (no custom fonts = less memory)
@Composable
fun ChatMessage(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,  // Standard font
        color = MaterialTheme.colorScheme.onSurface
    )
}
```

### 2. **Simple Color Scheme**

```kotlin
// Minimal color palette reduces GPU overdraw
@Composable
fun SafeGuardianTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF00E676),      // Green
            secondary = Color(0xFF00BFA5),    // Teal
            background = Color(0xFF121212),   // Dark gray
            surface = Color(0xFF1E1E1E)       // Slightly lighter
        ),
        content = content
    )
}
```

### 3. **Icon-Based UI**

```kotlin
// Material Icons (vector) = sharp, scalable, low memory
@Composable
fun QuickActions() {
    Row {
        IconButton(onClick = { /* ... */ }) {
            Icon(Icons.Default.Mic, "Voice input")  // Vector icon
        }
        IconButton(onClick = { /* ... */ }) {
            Icon(Icons.Default.SmartToy, "AI assistant")
        }
    }
}
```

### 4. **Subtle Indicators**

```kotlin
// Simple status indicators
@Composable
fun AIStatus(isActive: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (isActive) Color.Green else Color.Gray)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = if (isActive) "AI Ready" else "AI Offline",
            style = MaterialTheme.typography.bodySmall
        )
    }
}
```

---

## Compose Best Practices

### 1. **Stable Parameters**

```kotlin
// Mark data classes as @Stable or @Immutable
@Immutable
data class AIMessage(
    val id: String,
    val content: String,
    val timestamp: Long,
    val isProcessing: Boolean
)

// Compose can skip recomposition if parameters haven't changed
@Composable
fun AIMessageItem(message: AIMessage) {
    // Only recomposes when 'message' actually changes
}
```

### 2. **Remember Expensive Calculations**

```kotlin
@Composable
fun ModelInfoSheet(model: AIModel) {
    // Calculate once, remember result
    val formattedSize = remember(model.fileSizeMB) {
        "${model.fileSizeMB / 1024f} GB"
    }

    val estimatedDownloadTime = remember(model.fileSizeMB) {
        calculateDownloadTime(model.fileSizeMB)
    }
}
```

### 3. **Key Lambdas Properly**

```kotlin
@Composable
fun ChatInput(onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }

    // ❌ BAD: Creates new lambda every recomposition
    Button(onClick = { onSend(text) })

    // ✅ GOOD: Stable lambda
    val handleSend = remember {
        { message: String -> onSend(message) }
    }
    Button(onClick = { handleSend(text) })
}
```

### 4. **Use LaunchedEffect Wisely**

```kotlin
@Composable
fun AIResponseStream(aiService: AIService, query: String) {
    var response by remember { mutableStateOf("") }

    // LaunchedEffect runs only when 'query' changes
    LaunchedEffect(query) {
        aiService.generateResponse(query).collect { token ->
            response += token
        }
    }

    Text(response)
}
```

---

## Background Processing

### 1. **Coroutine Dispatchers**

```kotlin
class AIService {
    private val ioScope = CoroutineScope(Dispatchers.IO)
    private val defaultScope = CoroutineScope(Dispatchers.Default)

    // Heavy computation on background thread
    suspend fun loadModel(modelPath: String) = withContext(Dispatchers.IO) {
        LlmWrapper.builder()
            .llmCreateInput(/* ... */)
            .build()
    }

    // Inference on compute thread
    suspend fun generateResponse(prompt: String) = flow {
        withContext(Dispatchers.Default) {
            llm.generateStreamFlow(prompt, config).collect { token ->
                emit(token)  // Emits to UI thread
            }
        }
    }
}
```

### 2. **Debounce User Input**

```kotlin
@Composable
fun SmartSearchBar(onSearch: (String) -> Unit) {
    var query by remember { mutableStateOf("") }

    // Debounce: Only search after 300ms pause
    LaunchedEffect(query) {
        delay(300)
        if (query.isNotEmpty()) {
            onSearch(query)
        }
    }

    TextField(
        value = query,
        onValueChange = { query = it }
    )
}
```

---

## Power Mode Adaptation

```kotlin
/**
 * Adapt AI behavior based on battery level
 */
class PowerManager(private val context: Context) {
    fun getCurrentPowerMode(): PowerMode {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val batteryLevel = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

        return when {
            batteryLevel < 15 -> PowerMode.ULTRA_SAVER
            batteryLevel < 30 -> PowerMode.SAVER
            batteryLevel < 60 -> PowerMode.BALANCED
            else -> PowerMode.PERFORMANCE
        }
    }
}

enum class PowerMode {
    ULTRA_SAVER,  // Minimal AI, 1 thread, 512 tokens
    SAVER,        // Basic AI, 2 threads, 1024 tokens
    BALANCED,     // Normal AI, 2 threads, 2048 tokens
    PERFORMANCE   // Full AI, 4 threads, 4096 tokens
}

// Apply power mode to model config
fun ModelConfig.Companion.forPowerMode(mode: PowerMode): ModelConfig {
    return when (mode) {
        PowerMode.ULTRA_SAVER -> powerSaver()
        PowerMode.SAVER -> balanced().copy(maxTokens = 1024)
        PowerMode.BALANCED -> balanced()
        PowerMode.PERFORMANCE -> performance()
    }
}
```

---

## UI Component Guidelines

### ✅ DO
- Use `LazyColumn` for lists
- Use `remember` for expensive calculations
- Use `derivedStateOf` for derived state
- Use stable keys in `items()`
- Use `Modifier.drawWithCache` for custom drawing
- Keep composable functions small and focused
- Use `Spacer` instead of empty `Box`
- Use Material3 components (optimized)

### ❌ DON'T
- Use `Column` with hundreds of items (use `LazyColumn`)
- Create new lambdas in `@Composable` body
- Perform I/O in `@Composable` functions
- Use complex animations (particles, 3D effects)
- Load high-res images without downscaling
- Use custom fonts (stick to system fonts)
- Add unnecessary elevation/shadows
- Nest `LazyColumn` inside `LazyColumn`

---

## Performance Monitoring

```kotlin
/**
 * Monitor app performance
 */
class PerformanceMonitor {
    fun logMemoryUsage() {
        val runtime = Runtime.getRuntime()
        val usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
        Log.d("Performance", "Memory used: ${usedMemory}MB")
    }

    fun logBatteryDrain() {
        // Track battery % over time
    }

    fun logFPS() {
        // Monitor frame rate (aim for 60fps)
    }
}
```

---

## Summary

### Memory Budget
- **Mesh Service**: ~50MB
- **UI (Compose)**: ~100MB
- **LLM Model**: ~800MB (Granite Q4)
- **Embedding Model**: ~200MB (EmbeddingGemma)
- **Vector DB**: ~50MB (10k embeddings)
- **Overhead**: ~200MB
- **Total**: ~1.4GB

### Battery Budget (per hour active use)
- **Mesh Networking**: ~3%
- **UI Rendering**: ~2%
- **LLM Inference**: ~5-10% (depends on usage)
- **ASR**: ~2%
- **TTS**: ~1%
- **Total**: ~13-18% per hour (balanced mode)

### Target Devices
- **Minimum**: Android 8.0, 3GB RAM, 2GB storage
- **Recommended**: Android 10+, 4GB+ RAM, 4GB storage
- **Optimal**: Android 12+, 6GB+ RAM, SSD storage

---

## Visual Design: Clean & Efficient

### Color Scheme
```
Background: #121212 (Dark)
Surface: #1E1E1E
Primary: #00E676 (Green)
Secondary: #00BFA5 (Teal)
Text: #FFFFFF / #B0B0B0
Accent: #FF5722 (Error/Alert)
```

### Typography
```
Title: Roboto Bold 20sp
Body: Roboto Regular 14sp
Caption: Roboto Light 12sp
```

### Spacing
```
Compact: 4dp
Normal: 8dp
Medium: 16dp
Large: 24dp
```

### Result
- Sharp, readable text
- Clear visual hierarchy
- No distracting animations
- Instant responsiveness
- <5% additional battery drain from UI

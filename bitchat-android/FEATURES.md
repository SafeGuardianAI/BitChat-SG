# SafeGuardian Features Changelog

Features implemented on the `claude_features` branch, ordered by complexity.

---

## Feature 1: Share AI Messages with Peers
**Status**: Implemented
**Files**:
- `ai/AIMessageSharing.kt` (new)
- `ai/AIChatService.kt` (modified)
- `ai/AIPreferences.kt` (modified — added `aiMessageSharingEnabled`)

When the local AI generates a response, it is automatically broadcast to nearby BLE mesh peers with an `[AI]` prefix. This lets everyone in range benefit from AI-generated safety information during disasters.

- Rate-limited to 1 share per 5 seconds
- Truncated to 2000 chars for BLE-friendly size
- Includes the original query for context
- Toggle via `aiMessageSharingEnabled` preference
- Parser: `AIMessageSharing.parseAISharedMessage()` extracts Q&A from received messages

---

## Feature 2: Host AI Model for Peers
**Status**: Implemented
**Files**:
- `ai/AIHostService.kt` (new)
- `ai/AIPreferences.kt` (modified — added `aiHostingEnabled`)

Allows a device with a loaded AI model to serve as an inference host for nearby peers. Uses the existing MESSAGE packet type with prefix-based protocol:

- Request: `[AI_REQ]<requestId>:<prompt>`
- Response: `[AI_RES]<requestId>:<response>`

Safety controls:
- Rate limiting: 1 request per peer per 30 seconds
- Queue capacity: max 3 concurrent requests
- Prompt max length: 500 chars
- Response max length: 1500 chars
- System prompt tuned for concise, actionable disaster advice

Client API: `AIHostService.requestRemoteInference(prompt)` returns response via `CompletableDeferred` with 60-second timeout.

---

## Feature 3: ASR & TTS with Disaster Alerts
**Status**: Implemented
**Files**:
- `ai/SpeechRecognitionService.kt` (new)
- `ai/DisasterTTSService.kt` (new)
- `ai/AIPreferences.kt` (modified — added `disasterModeEnabled`)

### Speech Recognition (ASR)
`SpeechRecognitionService` provides unified ASR through two backends:
1. **Android SpeechRecognizer** (primary) — works on most devices, no model download
2. **Offline Sherpa-ONNX** (fallback) — for fully offline disaster scenarios

Features: partial results streaming, language selection, offline-first preference.

### Disaster TTS
`DisasterTTSService` automatically reads incoming messages aloud when disaster mode is enabled:
- AI-generated messages (`isAIGenerated` flag)
- Shared AI messages (`[AI]` prefix)
- AI host responses (`[AI_RES]` prefix)
- Messages containing emergency keywords (earthquake, flood, fire, evacuation, tsunami, shelter, rescue, etc.)

Rate-limited to 1 TTS per 3 seconds. Messages truncated to 500 chars for TTS. Sender attribution included.

---

## Feature 4: Local RAG with Disaster Data
**Status**: Implemented
**Files**:
- `ai/rag/DisasterKnowledgeBase.kt` (new)
- `ai/rag/SimpleTextSearch.kt` (new)
- `ai/rag/DisasterRAGService.kt` (new)
- `ai/AIChatService.kt` (modified — RAG integration)

### Knowledge Base
Embedded disaster preparedness data (FEMA/Red Cross/WHO guidelines) covering:
- Earthquake (during, after, preparation)
- Flood (during, after)
- Fire (escape, wildfire)
- First aid (bleeding, CPR, fractures)
- Shelter (improvised, warmth)
- Water (purification, finding sources)
- Communication (mesh networking, signaling)
- Tsunami, tornado, hurricane safety

### Search Engine
TF-IDF keyword-based retrieval with:
- Inverted index for fast lookup
- Keyword boosting (2x weight for explicit category keywords)
- Stop word filtering
- Relevance scoring with minimum threshold

### RAG Integration
- `DisasterRAGService.augmentPrompt()` detects disaster-related queries
- Prepends up to 1500 chars of relevant knowledge context to the AI prompt
- Both `processMessage()` and `streamResponse()` paths augmented
- Zero battery overhead (keyword matching, no embedding model required)
- Upgradeable to vector embeddings with EmbeddingGemma model

---

## Feature 5: On-Device Function Calls
**Status**: Implemented
**Files**:
- `ai/functions/FunctionRegistry.kt` (new)
- `ai/functions/FunctionExecutor.kt` (new)
- `ai/functions/DisasterFunctions.kt` (new)
- `ai/AIService.kt` (modified — function call system prompt injection)
- `ai/AIPreferences.kt` (modified — added `functionCallsEnabled`)

### Available Functions
| Function | Description | Confirmation |
|----------|-------------|-------------|
| `broadcast_emergency(message, severity)` | Send emergency broadcast to all peers | Required |
| `search_disaster_info(query)` | Search local disaster knowledge base | No |
| `set_disaster_mode(enabled)` | Toggle disaster mode (TTS + sharing) | Required |
| `request_help(type, description)` | Send typed help request to peers | Required |
| `share_ai_response(summary)` | Share current AI output with peers | No |

### How It Works
1. When `functionCallsEnabled` is true, `FunctionRegistry.generateToolPrompt()` is appended to the system prompt
2. AI responds with JSON: `{"function": "<name>", "params": {<parameters>}}`
3. `FunctionExecutor.tryExecute()` parses JSON, validates parameters against registry schema
4. Functions marked `requiresConfirmation` return `PendingConfirmation` for UI approval
5. `DisasterFunctions` implements the actual actions (mesh broadcast, settings toggle, RAG search)

### Safety
- All broadcast/destructive actions require user confirmation
- Parameter validation against enum values and required fields
- JSON extraction is defensive (finds first valid `{...}` with "function" key)

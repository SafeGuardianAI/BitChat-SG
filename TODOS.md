# TODOs

Tracked follow-ups from reviews and design decisions. Each entry carries enough context that a contributor picking it up later understands the motivation without rereading the review.

## AI module (from /plan-eng-review on 2026-04-17, branch `new_UI`)

### [AI] Wire or delete the ~4,400 LOC of floating code in `ai/`
**Why:** agent/, rag/, functions/, AIHostService, AIMessageSharing have zero imports from outside ai/. Leaving them adds maintenance tax and invites subtle bugs (someone edits the wrong RAGService; branches diverge).
**State:** Deferred for a follow-up decision after the AIModel unification + test-coverage work lands. Deciding in isolation would thrash.
**Start at:** AIManager.kt:36 (wired RAGService) vs ai/rag/EnhancedRAGService.kt (unwired). Grep confirms only ai/ internals reference the new trees.
**Blocks:** TODOs "agent/AutomationRules duplicate sensor registration" and "peerRateLimits cleanup" below \u2014 both become moot if the parent trees are deleted.

### [AI] Benchmark and tune `nGpuLayers` in AIService.ModelConfig per-device
**Why:** `nGpuLayers = 0` is forced on every load. When the plugin is `cpu_gpu`, offloading layers to an Adreno/Mali GPU typically cuts inference latency 2\u20134\u00d7. Current value is probably a conservative guess.
**Cost of doing it now:** Needs per-device benchmarking on actual hardware (low-end MediaTek, flagship Qualcomm, Tensor). Not a single-session task.
**Cost of not doing it:** Leaving latency on the floor; disaster-mode responses slower than needed.
**Start at:** AIService.kt:104 (loadModel) and :208 (loadAIModel) \u2014 both hardcode nGpuLayers=0. Feed from `DeviceCapabilityService.DeviceTier` (new field `recommendedGpuLayers`).

### [AI] Tier-2 and Tier-3 test coverage for ai/
**Why:** This PR lands only Tier 1 regression tests (GgufHeader + friendlyLoadError). Still zero coverage on: DeviceCapabilityService (pure logic: classifySmNumber, paramBudget, estimateModelRamGB, compatibilityLabel), ModelCatalog.resolveDependencies transitive walk, TaskConfig stop-word behavior, DisasterTTSService keyword matching, AIService load/generate/unload integration.
**Cost of doing it now:** ~3h human / ~45 min CC for Tier 2 (pure JVM). Tier 3 (AIService integration) needs a fake LlmWrapper; ~1\u20132 days.
**Cost of not doing it:** Every refactor risks silent regressions in wired code. Pattern matching + classification logic in DeviceCapabilityService is exactly the kind of code that breaks subtly.
**Start at:** app/src/test/kotlin/com/bitchat/android/ai/ \u2014 create package matching existing test layout.

### [AI] Remove duplicate SensorEventListener in agent/AutomationRules; route through telemetry/AccelerationSensor
**Why:** AutomationRules.kt registers its own accelerometer listener in parallel to the Tier-1 + FIFO-batched AccelerationSensor we just built in telemetry. If AutomationRules gets wired as-is, we'll double-subscribe the sensor and double the battery drain.
**Gate:** Blocked on the wire-or-delete decision above. If agent/ gets deleted, this is moot. If wired, it must be fixed as a prerequisite.
**Start at:** ai/agent/AutomationRules.kt (the full file registers SensorEventListener for TYPE_ACCELEROMETER). Compare with telemetry/sensors/AndroidSensors.kt AccelerationSensor.

### [AI] Implement peerRateLimits cleanup in AIHostService (gated)
**Why:** ConcurrentHashMap<String, Long> grows per unique peer ever seen. Small leak (~50 bytes/peer) but unbounded over a long-running mesh session.
**Gate:** Blocked on AIHostService wire-or-delete. If deleted, moot. If wired, pairs with the three other bugs (auth, timeout leak, race on pendingRequests counter) as a single AIHostService-hardening task.
**Start at:** AIHostService.kt:46. Fix would be a periodic coroutine that prunes entries where `now - timestamp > RATE_LIMIT_MS * 4`.

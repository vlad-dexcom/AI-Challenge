# Modules

| Module | Plugin | Depends on | Purpose |
|---|---|---|---|
| `:core:common` | kotlin-jvm | kotlinx-io, ktor-core | `io/Files.kt` (`Path` helpers: `readText`, `writeText` (creates parents), `walkFiles`, `atomicMoveTo`, `div`…), `time/Time.kt` (`nowMillis()`, `newId()`), `text/TextNormalizer`, `platform/` seams |
| `:core:llm` | kotlin-jvm | common | `LlmClient`, `ToolCallingLlmClient`, `GeminiApiClient` (Ktor, Interactions API), `GeminiModels`, `EmbeddingClient`/`GeminiEmbeddingClient`/`HashingEmbeddingClient`, `TextGenerator`, Ollama: `OllamaChatClient` (`/api/chat` + `/api/tags`), `OllamaTextGenerator`, `OllamaEmbeddingClient` |
| `:rag:core` | kotlin-jvm + testFixtures | llm, common | `Chunker`s, `VectorIndex`/`IndexStore`/`Indexer`, `Retriever`, `RagPipeline`, rerank/filter/rewrite, `Citations`, `chat/` (`ChatEngine`, `SessionStore`, `TaskMemory`, `MemoryExtractor`). Fixtures: `ChatFixture`, `ScriptedGenerator` |
| `:rag:tools` | application | rag:core | `LlmProvider` (`--provider gemini|ollama`), CLI `./gradlew :rag:tools:run --args="index|eval|compare|ask|chat|rag-eval|sweep|modes-eval"`, evals, scenarios |
| `:web-console` | application | rag:tools | `./gradlew :web-console:run [--args="--port 8080"]`: single Chat tab (RAG, task memory; "Answer with" Cloud / Local / Compare via `LocalRag`) + chunk visualiser (`resources/ui`, JS tests in `src/test/js`) |
| `:agent` | kotlin-jvm | rag:core, llm, common | Everything agent-related (see below) |
| `:app` | android-application | agent | Android shell and UI |

`rag/corpus`, `rag/eval`, `rag/index*` (`index` Gemini, `index-local` Ollama, `index-offline` hashing), `rag/sessions` are data directories, not modules. `rag/eval/local-rag/` holds the Day 28 Python benchmark scripts and results.
`mcp-server/` is a separate Firebase/TypeScript project exposing `get_exercise_info` and `suggest_workout`.

## `:agent` packages (`com.example.geminichat`)

| Package | Contents |
|---|---|
| `agent/` | `Agent`, `AgentConfig`/`AgentCatalog`, `AgentRequest`/`AgentResponse`/`TokenUsage`, `LlmAgent`, `TokenEstimator` |
| `agent/memory/` | short/working/long-term layers: `MemoryRouter` (LLM classifier), `MemoryAssembler`, stores |
| `agent/profile/` | `UserProfile`, `ProfileRenderer`, `PreferenceAdvisor`, `UserProfileStore` |
| `agent/task/` | `TaskState`, `TaskStateMachine`, `TaskTransitionTable`, `TaskStageGuard`, `TaskStateAdvisor`, stores/log |
| `agent/invariant/` | `Invariant(Set)`, `InvariantGuard`, `InvariantRules`, `InvariantRenderer`, `InvariantStore` |
| `agent/mcp/` | `McpToolCallingAgent` (tool loop, `maxToolRounds`) |
| `agent/rag/` | `RagAgent` + `RagAgentMode` (RAG / NO_RAG / COMPARE) |
| `agent/planner/`, `agent/workout/` | workout planning data, log/summary stores, digest aggregator |
| `mcp/` | `McpGateway` (+ `KotlinSdkMcpGateway`, `LocalWorkoutMcpGateway`, `LocalWorkoutPlannerMcpGateway`, `CompositeMcpGateway`), `McpCallLog`, `McpConfig` |
| root | `ChatController`, `ChatUiState`, `ChatUiSlices`, `ChatMessage`, `ChatHistoryStore` |

## `:app`

`MainActivity` → `AppNavigation(graph)`. `GeminiChatApplication` owns the lazy `AppGraph` and
schedules the WorkManager digest job. `ui/<feature>/` per screen, `mcp/McpScreen.kt`,
`agent/workout/WorkoutDigest{Worker,Scheduler,DebugReceiver}` (Android-only).
Package names were intentionally kept (`com.example.geminichat.*`, `com.example.rag.*`) when code
moved between modules; only `core:llm` (`com.example.core.llm`) and `web-console`
(`com.example.webconsole`) got new ones.

## Where does new code go?

- Needs `java.*`/Android? → `:app`, or behind a `platform/` package in `core/common`.
- LLM transport/DTOs → `:core:llm`. Retrieval/indexing → `:rag:core`. CLI/eval-only → `:rag:tools`.
- Agent behaviour, stores, guards, controller logic → `:agent`.
- Composables, ViewModels, navigation → `:app/ui`.

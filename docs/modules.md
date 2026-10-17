# Modules

| Module | Plugin | Depends on | Purpose |
|---|---|---|---|
| `:core:common` | kotlin-jvm | kotlinx-io, ktor-core | `io/Files.kt` (`Path` helpers: `readText`, `writeText` (creates parents), `walkFiles`, `atomicMoveTo`, `div`…), `time/Time.kt` (`nowMillis()`, `newId()`), `text/TextNormalizer`, `platform/` seams |
| `:core:llm` | kotlin-jvm | common | `LlmClient`, `ToolCallingLlmClient`, `GeminiApiClient` (Ktor, Interactions API), `GeminiModels`, `EmbeddingClient`/`GeminiEmbeddingClient`/`HashingEmbeddingClient`, `TextGenerator` (+ `CallPurpose`), Ollama: `OllamaChatClient` (`/api/chat` + `/api/tags`), `OllamaTextGenerator`, `OllamaEmbeddingClient`, `OllamaTuning`/`LlmCallStats` (per-purpose parameters and token statistics) |
| `:rag:core` | kotlin-jvm + testFixtures | llm, common | `Chunker`s, `VectorIndex`/`IndexStore`/`Indexer`, `Retriever`, `RagPipeline`, rerank/filter/rewrite, `Citations`, `chat/` (`ChatEngine`, `SessionStore`, `TaskMemory`, `MemoryExtractor`), `PromptProfile` (prompt wording variants). Fixtures: `ChatFixture`, `ScriptedGenerator` |
| `:rag:tools` | application | rag:core | `LlmProvider` (`--provider gemini|ollama`), CLI `./gradlew :rag:tools:run --args="index|eval|compare|ask|chat|rag-eval|sweep|modes-eval"`, evals, scenarios |
| `:ai-gateway` | application | core:llm | `scripts/private-ai.sh start`: loopback Ktor CIO gateway behind `scripts/lan_forward.py` (Day 30) — OpenAI-style `/v1/chat/completions` (SSE), `/v1/models`, `/healthz`, `/stats`, mobile chat page; Bearer keys (SHA-256), rate limit, context/size limits, queue, upstream cancel. Uses `OllamaStreamingClient` |
| `:web-console` | application | rag:tools, dev-assistant | `./gradlew :web-console:run [--args="--port 8080"]`: single Chat tab (RAG, task memory; "Answer with" Cloud / Local / Compare via `LocalRag`) + chunk visualiser (`resources/ui`, JS tests in `src/test/js`) + "Dev assistant" tab (`HelpApi`, `POST /api/help`) |
| `:reviewer` | application | dev-assistant | Day 32 AI review of a pull request: `Reviewer` (git MCP `git_changed_files`/`git_diff` → docs + code RAG → one Gemini call → validated findings), `DiffParser` (line-numbered diffs), `ReviewReport` (markdown, `en`/`ru`), `CodeCorpus`/`ReviewerFactory`, CLI `./gradlew :reviewer:run --args="--base main"`; CI: `.github/workflows/ai-review.yml` + `scripts/post-review-comment.sh` |
| `:dev-assistant` | application | agent, rag:tools | Day 31 `/help`: `GitMcpGateway` (read-only git MCP tools over `GitRunner`, incl. `git_changed_files`/`git_diff` used by `:reviewer`), `ProjectDocs`/`DocsIndex` (README + AGENTS.md + docs → `rag/index-docs`), `HelpAssistant` (docs RAG + MCP excerpts, verified quotes), CLI `./gradlew :dev-assistant:run` |
| `:agent` | kotlin-jvm | rag:core, llm, common | Everything agent-related (see below) |
| `:app` | android-application | agent | Android shell and UI |

`rag/corpus`, `rag/eval`, `rag/index*` (`index` Gemini, `index-local` Ollama, `index-offline` hashing, `index-docs`/`index-code` git-ignored indexes of the dev assistant and the reviewer), `rag/sessions` are data directories, not modules. `rag/eval/local-rag/` holds the Day 28 Python benchmark scripts and results.
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

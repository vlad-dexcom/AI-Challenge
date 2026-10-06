# Agent patterns (conventions for future work)

Read with `docs/architecture.md`. These are the patterns the code already follows; keep to them.

## 1. Agents are interfaces; UI never calls Gemini

`interface Agent { val config: AgentConfig; suspend fun handle(AgentRequest): Result<AgentResponse> }`.
Implementations: `LlmAgent` (plain), `McpToolCallingAgent` (tool loop), `RagAgent`.
`ChatController.buildAgent(config)` picks the implementation by `AgentConfig.id`. Always return
`Result`, never throw for expected failures (empty input, network, context overflow); rethrow
`CancellationException`.

### Adding a persona
1. Add an `AgentConfig` to `AgentCatalog` and to `ALL` (id, name, description, system instruction,
   optional model/temperature/maxOutputTokens).
2. If it needs tools/RAG, branch in `ChatController.buildAgent` and build the right agent.
3. Add tests with a fake `LlmClient` (see `LlmAgentTest`).

## 2. Prompt context is rendered, not passed as objects

State → renderer → string block on `AgentRequest` (`longTermMemory`, `workingMemory`,
`userProfile`, `taskState`, `taskStageRules`, `invariants`). Standing *instructions* (profile,
invariants) go to the system instruction; changing *context* (memory, task state, history) goes
into the user turn. Empty block → `null` so a feature that is unused adds zero tokens and keeps
old behaviour. Every block is token-counted in `TokenUsage`; `LlmAgent` fails fast with
`ContextWindowExceededException` rather than sending an oversize prompt.
New context block = renderer + field on `AgentRequest` + `TokenUsage` counter + test.

## 3. Deterministic guards before LLM calls

`InvariantGuard` and `TaskStageGuard` are pure functions (`check(state, prompt)`), run in
`ChatController.sendMessage` before spending any LLM call and again inside `LlmAgent`. A refusal is
an `AgentResponse` with `refusedByInvariantIds` / `blockedByStage`, shown with a badge. Rules that
must always hold belong in guards, not in prompt text. Locked invariants are repaired by
`InvariantStore.load()` even if the file was hand-edited.

## 4. Advisors suggest, humans apply

`MemoryRouter`, `PreferenceAdvisor`, `TaskStateAdvisor` are separate LLM calls (run concurrently
in `prepareRequestContext`). They fail soft: on error keep previous state. Memory routing updates
memory automatically (user can edit); profile and task suggestions become
`pending*Suggestion` and only change state via an explicit Apply, which goes through the same
validated path as a button press (`TaskStateMachine`, `applyFieldValue`).

## 5. State machines return results

`TaskStateMachine.*` returns `TransitionResult.Applied | Rejected(reason)`; legality comes from
`TaskTransitionTable`. The UI renders only allowed events. Applied and rejected attempts are both
journaled in `TaskTransitionLogStore`. Follow this for new stateful features: pure transition
function + table + log + store.

## 6. Tools go through `McpGateway`

`connect → listTools → callTool → close`. Remote (`KotlinSdkMcpGateway`), local on-device
(`LocalWorkout*McpGateway`) and `CompositeMcpGateway` (prefixes tools per `NamedMcpGateway`)
share one contract, so `McpToolCallingAgent` is transport-agnostic. The agent loops
`requires_action` → execute calls → send results, up to `maxToolRounds`; tool errors go back to
the model as results. Every call is recorded in `McpCallLog` (shown on the MCP screen). A new local tool
= extend/add a gateway, register it in `buildAgent`, add a persona.

## 7. RAG

`rag:core` pipeline: `Chunker` → `Indexer` (embeddings) → `VectorIndex`; query → `Retriever` →
threshold filter → rerank (heuristic/LLM) → `RagPromptBuilder` → `TextGenerator` → `Citations`
validation; weak evidence yields a structured "I don't know" with closest-but-unused sources.
`RagAgentMode` compares with/without RAG. Change retrieval behaviour only with eval evidence
(`:rag:tools:run --args="eval|rag-eval|sweep|modes-eval"`, data in `rag/eval`).

## 8. Persistence

One JSON file per store, constructor takes a `Path`, `const val FILE_NAME`, `load()` tolerant of
missing/corrupt data, `save()` overwrites (parents created by `writeText`). Per-branch data is
keyed by branch id; the pending checkpoint uses the reserved key `__checkpoint__`. Wire files in
`AppGraph` only. Use `nowMillis()` / `newId()` from `core.time`, never `System`/`UUID`.

## 9. Testing

- JUnit4, `runTest`, `TemporaryFolder` (`tmp.newFolder("x").toKxPath()`), fake `LlmClient`/
  `ScriptedGenerator`/`HashingEmbeddingClient`; no network in tests.
- Pure logic (guards, machines, renderers, routers) gets unit tests; controllers are tested through
  fakes of the LLM client. MCP gateways: `KotlinSdkMcpGatewayIntegrationTest` uses an embedded server.
- Run `./gradlew check` (includes `checkKmpReadiness`) and `:app:assembleDebug` before finishing.
  Compose UI is not covered; smoke-test Chat, Settings, Profile, Invariants, Memory, Task, MCP,
  Branch sheet and system Back by hand.

## 10. Pitfalls

- Never print or commit `GEMINI_API_KEY` (`local.properties`, git-ignored; the key is compiled
  into the app via `BuildConfig` — fine for a learning project, not for production).
- Cross-module smart casts on public properties fail; use `?.let {}`.
- `kotlin.concurrent.atomics` needs `@OptIn(ExperimentalAtomicApi::class)`.
- Don't add `java.*`/`android.*` imports to shared modules; use/extend `core/common` seams.

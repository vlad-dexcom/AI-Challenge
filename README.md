# Personal Trainer

A minimal Android app with a single `ChatScreen` (Jetpack Compose) where a user's message is
handled by an **agent** (not a bare API call) that talks to the Gemini API and returns a reply.

- **No Gemini SDK** — plain REST calls via [Ktor](https://ktor.io) client (`GeminiApiClient.kt`).
- **UI**: Jetpack Compose. Main chat screen (`ChatScreen.kt`) plus a dedicated Settings screen
  (model + profile) and a Branch bottom sheet, reached via the chat screen's app bar; state held
  in `ChatViewModel`.
- **Model**: selectable per-message, defaults to `gemini-3.5-flash`.
- **Endpoint**: `POST https://generativelanguage.googleapis.com/v1beta/interactions`
  (Google's current recommended [Interactions API](https://ai.google.dev/api/interactions-api),
  which supersedes the legacy `generateContent` endpoint), with the API key sent via the
  `x-goog-api-key` header and the model name in the request body.

## Agent architecture (Day 6)

The `ChatViewModel` never talks to Gemini directly — it depends only on an `Agent`. The
request/response logic (persona, model, generation params, error handling) lives entirely
inside the agent, not scattered across the UI layer:

```
ChatScreen ──▶ ChatViewModel ──▶ Agent (LlmAgent + AgentConfig)
                                     │  (LlmClient interface)
                                     ▼
                              GeminiApiClient (Ktor / REST)
```

- **`agent/AgentConfig.kt`** — an agent's persona: id, display name, description, system
  instruction, model, generation params. `AgentCatalog` holds ready-to-use configs
  (`PERSONAL_TRAINER` is the default; `GENERAL_ASSISTANT` shows the catalog is not hardcoded
  to one persona).
- **`agent/Agent.kt`** — `interface Agent { val config: AgentConfig; suspend fun handle(...) }`.
  This is the "agent as a separate entity" contract callers use.
- **`agent/LlmAgent.kt`** — the default `Agent` implementation. Validates input, assembles the
  LLM request from `AgentConfig` + `AgentRequest`, calls an `LlmClient`, times the call, trims
  and validates the answer, and maps failures into a single user-facing `Result`.
- **`agent/LlmClient.kt`** — transport-agnostic `interface LlmClient { suspend fun complete(spec) }`.
  `GeminiApiClient` implements it, so `LlmAgent` can be unit-tested with a fake client and no
  network/Android dependency (see `app/src/test/.../agent/LlmAgentTest.kt`).
- **`agent/AgentContracts.kt`** — `AgentRequest`/`AgentResponse`/`AgentMessage`.

**In-memory chat history, mixed into every request**: the agent is not stateless anymore —
`ChatViewModel.sendMessage()` snapshots the whole visible conversation so far and passes it as
`AgentRequest.history`; `LlmAgent.renderPrompt` folds it into the prompt as a
"User: ...\n\<Agent\>: ..." transcript before the new user message. This history lives only in
memory for the current app process/session (it resets on process death or app restart — no
database/file persistence), is not capped in length (a very long chat grows the prompt
accordingly), and is shared across agents (switching Personal Trainer ↔ General Assistant mid
chat keeps prior turns in context for the new persona).

**Tool-calling is a documented extension point, not implemented yet**: the natural place for
a "LLM → tool call → tool result → second LLM call" loop is inside `LlmAgent.handle`, after the
first `client.complete(spec)` — see the comment on `LlmAgent`.

The UI lets you switch both the **agent** (top-left dropdown, e.g. Personal Trainer ↔ General
Assistant) and the **model** (top-right dropdown) independently; the agent's one-line
description is shown under the top bar.

## Token accounting (Day 8)

Every `LlmAgent.handle` call now counts tokens and can refuse to send an over-budget request:

- **`agent/TokenEstimator.kt`** — the Gemini Interactions API used here doesn't return token
  usage, and no Gemini tokenizer runs on-device, so token counts are an **offline heuristic**:
  `max(word/punctuation count, chars / 4)`. It's approximate (not a billed count), but stable
  enough to show growth trends and to unit-test overflow deterministically.
- **`agent/AgentContracts.kt`** — `AgentResponse.tokenUsage` (a `TokenUsage`) breaks a call
  down into `requestTokens` (just the new message), `historyTokens` (prior turns folded into
  the prompt), `systemInstructionTokens`, `promptTokens` (everything sent to the model), and
  `completionTokens` (the reply); `totalTokens` is the sum.
- **`agent/LlmClient.kt`** / **`GeminiApiClient.kt`** — `LlmClient.contextWindowTokens(model)`
  lets `LlmAgent` ask the transport for a model's context window (Gemini's real per-model
  windows in `GeminiApiClient`, a large default elsewhere) without depending on Gemini directly.
- **Overflow guard** — before calling the client, `LlmAgent` reserves
  `AgentConfig.maxOutputTokens` (or a default) for the reply and compares
  `promptTokens + reserved` against the window. If it doesn't fit, `handle` returns
  `Result.failure(ContextWindowExceededException(...))` **without ever calling the model** —
  the failure is immediate and explains itself, instead of sending a request the API would
  truncate, reject, or answer with a request cut short.
- **UI** — `ChatScreen` shows a running "Tokens in dialog" total under the agent description,
  and each agent reply shows its own `prompt N (history M) · reply K · total T` breakdown.
- **Tests** (`app/src/test/.../agent/TokenBudgetTest.kt`) compare a **short dialog** (few
  tokens, succeeds), a **long dialog** (prompt tokens grow turn over turn as history
  accumulates — asserted to be monotonic and >4x by the 8th turn), and a **dialog that exceeds
  the model's context window** (a fake small window makes a realistic multi-turn history fail
  with `ContextWindowExceededException` and zero calls to the client), plus a sanity check that
  a short dialog still succeeds against that same tiny window.
- **Manually triggering the overflow in the running app** — `GeminiApiClient` and
  `ChatViewModel` both accept an optional `debugContextWindowOverrideTokens` constructor
  parameter (default `null`, no effect on real usage). Passing a small value (e.g. `200`)
  forces every model to report that tiny context window, so even a short chat overflows and
  you can see the "conversation is too long" error surface for real in `ChatScreen`'s error
  banner — e.g. temporarily change `MainActivity`'s `ChatViewModel(apiKey = ...)` call to
  `ChatViewModel(apiKey = ..., debugContextWindowOverrideTokens = 200)`, run the app, send a
  message, and revert the change afterward. See `GeminiApiClientTest.kt` for the unit-level
  proof that the override takes effect.

## Memory model (Day 11)

`agent/memory/` splits the agent's memory into three independently-stored layers instead of one
growing blob — see `docs/day11-memory-model.md` for the full write-up (routing rules, a worked
example, and automated proof of the effect on the agent's prompt/answers). This is the only
context-management mechanism in the app; there is no strategy switcher.

- **Short-term** — the raw dialog, unchanged, still in `ChatHistoryStore`; only the last
  `MemoryRouter.RECENT_CONTEXT_SIZE` messages are sent to the model verbatim.
- **Working** (`MemoryStore.kt`'s `WorkingMemoryStore`) — current-task data (goal, steps, open
  questions), one file per app, keyed by branch id; cleared by "End task".
- **Long-term** (`LongTermMemoryStore`) — durable user profile/decisions/knowledge, one global
  file, shared across every branch; cleared only by explicitly deleting an item.

`MemoryRouter` classifies each user turn into working/long-term via one LLM call, then a
deterministic guard-rules pass (`applyGuardRules`) protects anything the user pinned manually in
the UI's memory panel from ever being overwritten or dropped.

## Personalization (Day 12)

`agent/profile/` adds a single, global, user-editable **profile** on top of the Day 11 memory
model — see `docs/day12-personalization.md` for the full write-up (field table, the
profile-vs-memory boundary, the hybrid update flow, worked prompt comparisons). This is *not*
another memory layer: memory holds facts the agent *learned*; the profile holds preferences the
user *declared* (tone, format, expertise, hard constraints). There is exactly one profile per
app — like Day 11's long-term memory — editable but not switchable; no catalog of profiles to
pick between.

- **`agent/profile/UserProfile.kt`** — the profile's fields (`displayName`, `about`, `language`,
  `expertise`, `tone`, `format`, `maxAnswerSentences`, `constraints`, `notes`); `UserProfile.EMPTY`
  renders to nothing (agent behaves exactly as before Day 12); `UserProfile.STARTER` seeds a
  realistic first-run example instead of a blank form.
- **`agent/profile/ProfileRenderer.kt`** — deterministically renders the profile into a single
  labeled block. `agent/LlmAgent.kt` appends this block to the agent's system instruction (not
  the user-turn prompt, where Day 11's memory blocks live) on *every* request, since it's an
  instruction about how to answer, not conversational context — see `AgentRequest.userProfile`
  and `TokenUsage.profileTokens` (counted into the Day 8 context-window budget).
- **`agent/profile/UserProfileStore.kt`** — persists the single profile to its own
  `user_profile.json`, independent of every memory-layer file.
- **`agent/profile/PreferenceAdvisor.kt`** — the hybrid update path: the profile is edited by
  hand in the UI, but after a turn that sounds like a stated preference ("keep it shorter", "no
  barbell"), one LLM call proposes a single field change. It is *never* applied automatically —
  `ChatScreen` shows it as an Apply/Dismiss banner (`ChatViewModel.onApplySuggestion`/
  `onDismissSuggestion`); the profile only ever changes by hand or by explicit approval.
- **UI** — the model picker and `ProfilePanel` (every field, the constraint list, a "Reset
  profile" action, and a preset row that loads one of `UserProfile.PRESETS` in one tap) now live
  on a dedicated **Settings** screen, reached via the ⚙️ button in the chat screen's app bar; the
  suggestion banner still appears on the chat screen itself, above the input row, whenever
  `PreferenceAdvisor` has a pending proposal.

## Task state machine (Day 13)

`agent/task/` adds a formalized **task state machine** on top of Day 11/12's memory and profile —
see `docs/day13-task-state.md` for the full write-up (transition table, the task-state-vs-working-
memory boundary, why pause is a flag not a stage) and `docs/day13-task-state-test-scenario.md` for
a manual walkthrough that explicitly proves pause survives a full app restart. It answers a
different question than memory or profile: not *what do we know* or *how should we answer*, but
*where are we in the task and whose turn is it*.

- **`agent/task/TaskState.kt`** — `TaskStage` (`PLANNING` → `EXECUTION` → `VALIDATION` → `DONE`),
  the step list/index, `expectedAction`/`expectedActor` (`USER`/`AGENT`), and a `paused` flag that
  overlays any non-`DONE` stage instead of being a fifth stage — so `resume()` restores the exact
  prior stage/step. `TaskState.NONE` renders to nothing, same convention as `UserProfile.EMPTY`.
- **`agent/task/TaskStateMachine.kt`** — the only code allowed to mutate `TaskState`; every
  operation returns `Applied`/`Rejected(reason)` instead of throwing or silently no-op'ing, so
  invalid transitions (e.g. approving a plan mid-`EXECUTION`) surface a clear error in the UI.
- **`agent/task/TaskStateRenderer.kt`** — deterministically renders two blocks: `render()` (task
  context — stage/step/expected action — appended to the prompt body like Day 11 memory) and
  `stageRules()` (a short behavioral rule per stage, appended to the system instruction like
  Day 12's profile) — see `AgentRequest.taskState`/`taskStageRules` and the matching
  `TokenUsage` fields.
- **`agent/task/TaskStateStore.kt`** — persists one `TaskState` per branch to `task_state.json`,
  mirroring `WorkingMemoryStore`'s per-branch model; a paused task survives a full app restart.
- **`agent/task/TaskStateAdvisor.kt`** — optional hybrid update path, modeled on Day 12's
  `PreferenceAdvisor`: after a turn that sounds like the expected action was completed, one LLM
  call proposes a transition as an Apply/Dismiss banner; it is never applied automatically, and
  the applied transition still goes through `TaskStateMachine`, so a stale suggestion is rejected
  if the task already moved on.
- **UI** — a "Task" panel in the chat screen's App Bar (next to the Day 11 memory panel) shows the
  current stage/step/expected action and only the buttons the machine actually allows from that
  state (e.g. no "Next" while in `VALIDATION`); "End task" (Day 11) now also resets task state,
  while "Clear dialog" leaves it untouched.

## Setup

1. Get a Gemini API key from [Google AI Studio](https://aistudio.google.com/apikey).
2. Provide the key to the build without committing it to source control, either:
   - Add to `local.properties` (already git-ignored):
     ```
     GEMINI_API_KEY=your_key_here
     ```
     and read it in `app/build.gradle.kts` (already wired up if present), **or**
   - Pass it on the command line:
     ```
     ./gradlew assembleDebug -PGEMINI_API_KEY=your_key_here
     ```
3. Build and install:
   ```
   ./gradlew installDebug
   ```
4. Run the agent unit tests:
   ```
   ./gradlew testDebugUnitTest
   ```

## Project layout

- `agent/` — the agent abstraction: `Agent`, `AgentConfig`/`AgentCatalog`, `AgentRequest`/
  `AgentResponse`/`AgentMessage`, `LlmClient`/`LlmRequestSpec`, `TokenUsage`/`TokenEstimator`,
  `ContextWindowExceededException`, and the `LlmAgent` implementation.
- `agent/memory/` — the Day 11 memory layers (`MemoryLayer`, `MemoryItem`/`MemorySnapshot`,
  `MemoryRouter`, `MemoryAssembler`, `LongTermMemoryStore`/`WorkingMemoryStore`).
- `agent/profile/` — the Day 12 personalization profile (`UserProfile`, `ProfileRenderer`,
  `UserProfileStore`, `PreferenceAdvisor`).
- `GeminiModels.kt` — kotlinx.serialization request/response DTOs for the Gemini Interactions
  API, including `system_instruction` and `generation_config`.
- `GeminiApiClient.kt` — Ktor `HttpClient` wrapper implementing `LlmClient`; POSTs the request
  and parses the `model_output` step's text.
- `ChatViewModel.kt` — holds chat messages/input/loading state, delegates to the current `Agent`.
- `ChatScreen.kt` — the chat screen (message list + input + send button, memory panel, branch
  bottom sheet trigger) plus the Settings screen (model picker, personalization profile).
- `MainActivity.kt` — hosts `ChatScreen`.

## Adding a new agent persona

Add an `AgentConfig` entry to `AgentCatalog` (id, display name, description, system
instruction, optional model/generation overrides) and list it in `AgentCatalog.ALL`. The app is
trainer-only by default with no agent picker in the UI (see the UI-cleanup note above), so
switching personas currently requires code (e.g. changing `AgentCatalog.DEFAULT` or restoring an
in-app selector).

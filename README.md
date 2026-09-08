# Personal Trainer

A minimal Android app with a single `ChatScreen` (Jetpack Compose) where a user's message is
handled by an **agent** (not a bare API call) that talks to the Gemini API and returns a reply.

- **No Gemini SDK** — plain REST calls via [Ktor](https://ktor.io) client (`GeminiApiClient.kt`).
- **UI**: Jetpack Compose, single screen (`ChatScreen.kt`), state held in `ChatViewModel`.
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
  `AgentResponse`/`AgentMessage`, `LlmClient`/`LlmRequestSpec`, and the `LlmAgent` implementation.
- `GeminiModels.kt` — kotlinx.serialization request/response DTOs for the Gemini Interactions
  API, including `system_instruction` and `generation_config`.
- `GeminiApiClient.kt` — Ktor `HttpClient` wrapper implementing `LlmClient`; POSTs the request
  and parses the `model_output` step's text.
- `ChatViewModel.kt` — holds chat messages/input/loading state, delegates to the current `Agent`.
- `ChatScreen.kt` — the app's only screen: message list + input field + send button + agent/model
  selectors.
- `MainActivity.kt` — hosts `ChatScreen`.

## Adding a new agent persona

Add an `AgentConfig` entry to `AgentCatalog` (id, display name, description, system
instruction, optional model/generation overrides) and list it in `AgentCatalog.ALL` — it will
automatically show up in the agent selector, with no other code changes required.

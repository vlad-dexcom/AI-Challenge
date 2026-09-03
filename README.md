# GeminiChat

A minimal Android app with a single `ChatScreen` (Jetpack Compose) that sends a
user's message to the Gemini API and displays the reply.

- **No Gemini SDK** — plain REST calls via [Ktor](https://ktor.io) client (`GeminiApiClient.kt`).
- **UI**: Jetpack Compose, single screen (`ChatScreen.kt`), state held in `ChatViewModel`.
- **Model**: `gemini-3.7-flash`.
- **Endpoint**: `POST https://generativelanguage.googleapis.com/v1beta/interactions`
  (Google's current recommended [Interactions API](https://ai.google.dev/api/interactions-api),
  which supersedes the legacy `generateContent` endpoint), with the API key sent via the
  `x-goog-api-key` header and the model name in the request body.

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

## Project layout

- `GeminiModels.kt` — kotlinx.serialization request/response DTOs for the Gemini Interactions API.
- `GeminiApiClient.kt` — Ktor `HttpClient` wrapper that POSTs the prompt and parses the `model_output` step's text.
- `ThinkingMode.kt` — the four prompting strategies (Direct, Step by step, Meta-prompt, Expert panel) and their prompt templates.
- `ComparisonViewModel.kt` — holds the task input and per-mode results, runs all four modes concurrently, calls the API client.
- `ComparisonScreen.kt` — the app's only screen: task input + Run all/Stop/Clear + one result tile per mode.
- `MainActivity.kt` — hosts `ComparisonScreen`.

## Day 3 — Thinking Variations

One task (a logical/algorithmic/analytical question, entered once) is solved four different
ways, all sent to Gemini concurrently and shown as four tiles so the approaches can be
compared side by side:

| Mode | Approach | API calls |
|---|---|---|
| **Direct** | The task is sent as-is, no extra instructions — the baseline. | 1 |
| **Step by step** | Adds a "solve step by step" instruction: show reasoning steps, then a final answer. | 1 |
| **Meta-prompt** | First call asks the model to design the best prompt for the task; that generated prompt is then sent as the actual request. The tile shows both the generated prompt and its answer. | 2 (sequential) |
| **Expert panel** | A single call where the model role-plays three experts — Analyst, Engineer, Critic — each contributing, followed by a consensus answer. | 1 |

### Example task

> A man has to take a wolf, a goat, and a cabbage across a river. His boat can only carry
> himself and one of the three at a time. Left unsupervised, the wolf will eat the goat, and
> the goat will eat the cabbage. How does he get everything across safely?

### What to compare

- **Correctness** — does the final answer actually solve the task?
- **Reasoning quality** — is intermediate reasoning shown, and is it sound (Step by step,
  Expert panel), or is verification/self-checking present (Expert panel's Critic step)?
- **Latency/cost** — Meta-prompt is inherently slower/costlier since it makes two sequential
  calls per run; the duration shown on each tile makes this visible directly.
- **Prompt quality** — for Meta-prompt, is the model-generated prompt actually better than a
  naive direct question, and does it change the answer's quality?
- **Robustness** — running four calls concurrently can occasionally hit the API's rate limit
  (HTTP 429); a failing tile is isolated and doesn't affect the other three.


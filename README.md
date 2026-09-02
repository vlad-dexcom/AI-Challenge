# Personal Trainer

A minimal Android app with a single `ChatScreen` (Jetpack Compose) that sends a
user's message to the Gemini API and displays the reply.

- **No Gemini SDK** — plain REST calls via [Ktor](https://ktor.io) client (`GeminiApiClient.kt`).
- **UI**: Jetpack Compose, single screen (`ChatScreen.kt`), state held in `ChatViewModel`.
- **Model selector**: choose between several Gemini models in the top bar (useful if one
  is temporarily overloaded/rate-limited).
- **Markdown rendering**: model replies are rendered as Markdown (bold, lists, code, links)
  via [compose-markdown](https://github.com/jeziellago/compose-markdown).
- **Restricted mode** (optional, toggled just below the top bar): when enabled, the reply is
  always requested as structured JSON (`response_format` with a `message` + `trainings` schema)
  so it can be parsed and reused elsewhere in the app later — for now the raw JSON is shown
  as-is in the answer bubble for verification. On top of that, three chips are independently
  selectable: topic scoping to physical-training only, capped output length, and stop
  sequences. The master switch disables everything (including the JSON format) at once.
  Off by default — a plain toggle-off request behaves exactly like the unrestricted chat.
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

- `GeminiModels.kt` — kotlinx.serialization request/response DTOs for the Gemini Interactions
  API, including the optional `system_instruction`/`generation_config`/`response_format`
  fields used by restricted mode.
- `GeminiApiClient.kt` — Ktor `HttpClient` wrapper; `Limitation` enum (topic restriction, max
  tokens, stop sequences) drives which request fields get built for a given call, independently
  of each other; structured JSON `response_format` is always requested whenever restricted mode
  is on, regardless of which `Limitation`s are selected. Parses the `model_output` step's text;
  currently returns the raw JSON string as-is when restricted mode is on (see TODO in
  `parseResponse`) rather than extracting the `answer` field, for end-to-end verification.
- `ChatViewModel.kt` — holds chat messages/input/loading/model state plus the restricted-mode
  master switch and the set of individually enabled `Limitation`s; calls the API client with
  only the limitations that are both switched on and individually selected.
- `ChatScreen.kt` — the app's only screen: top bar (title + model selector), a restricted-mode
  switch + one selectable `FilterChip` per limitation above the content, message list
  (Markdown-rendered replies) + input field + send button.
- `MainActivity.kt` — hosts `ChatScreen`.

## Restricted mode notes

- Structured JSON output (`response_format` + `message`/`trainings` schema) is always requested
  when restricted mode is on — it's not a selectable chip, since it's meant to make replies
  parseable elsewhere in the app rather than being a user-facing option. The schema is
  `{ "message": string, "trainings": [{ "day_of_week", "focus", "exercises": [{ "name", "sets",
  "reps", "rest_seconds" }], "notes" }] }`, matching `RestrictedAnswer`/`Training`/`Exercise` in
  `GeminiModels.kt`. `parseResponse` currently returns that raw JSON string verbatim (visible in
  the answer bubble) instead of deserializing it, so the schema/format can be verified before
  wiring up real parsing/UI consumption.
- The three remaining limitations (topic restriction, max output tokens, stop sequences) are
  each independently selectable via their chip; the master switch turns everything off at once
  regardless of individual chip selection, and turning it back on restores the previously
  selected combination.
- Topic scope is enforced via a `system_instruction` persona ("Physical Training Assistant")
  rather than client-side keyword filtering — Gemini has no native topic allowlist. The
  instruction always also tells the model to emit schema-conforming JSON, since that format is
  mandatory whenever restricted mode is on.
- `generation_config.max_output_tokens` (256) and `stop_sequences` are only included in the
  request when their respective chip is selected; `thinking_level` is set to `"minimal"`
  whenever the max-tokens limitation is on, so the budget goes to the visible answer rather
  than internal reasoning tokens.
- A short output cap can still truncate long answers into invalid JSON (API reports
  `status: "incomplete"`); this is treated as a soft signal, not an error, and the app
  regex-extracts the partial `answer` text so the user still sees readable (if cut short)
  content instead of a raw JSON blob or a generic error.

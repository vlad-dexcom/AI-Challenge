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
- `ChatViewModel.kt` — holds chat messages/input/loading state, calls the API client.
- `ChatScreen.kt` — the app's only screen: message list + input field + send button.
- `MainActivity.kt` — hosts `ChatScreen`.

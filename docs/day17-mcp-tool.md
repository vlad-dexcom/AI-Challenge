# Day 17 — Первый инструмент MCP (Custom MCP tool)

Day 16 made the app an MCP *client* that can connect to a remote server and list its tools. Day 17
closes the loop: **the app's own agent calls a real MCP tool during a conversation and uses the
result to answer** — the exercise's "агент делает вызов к MCP-инструменту и получает результат".

Two halves:

1. **A brand-new MCP server** (`mcp-server/`), built from scratch and deployed as a Firebase Cloud
   Function, wrapping the public [wger.de](https://wger.de) fitness/exercise REST API. It exposes
   two tools: `get_exercise_info` and `suggest_workout`.
2. **Client-side function-calling** in the Android app: a new `McpToolCallingAgent` gives Gemini
   those two tools via the Interactions API's function-calling mechanism, executes any tool call
   the model requests against the real MCP server, and resubmits the result so the model can
   finish its answer.

---

## Part 1 — The MCP server (`mcp-server/`)

```
mcp-server/functions/src/
  wgerClient.ts        # thin fetch client for https://wger.de/api/v2
  tools/
    getExerciseInfo.ts # tool: get_exercise_info(name)
    suggestWorkout.ts  # tool: suggest_workout(goal, level, minutes)
  index.ts             # Cloud Function `mcp`: builds a stateless McpServer per HTTP request
```

- Built with the official `@modelcontextprotocol/sdk` (TypeScript), registering tools with a
  `zod` input schema — the SDK derives the JSON-Schema `inputSchema` (including `enum`s) each tool
  advertises via `tools/list` from that schema automatically.
- **`get_exercise_info(name: string)`** searches wger.de's `exerciseinfo` endpoint
  (`name__search` + `language__code=en` — the only filter combination that actually does a
  full-text search; see the commit history for the wger API quirks this uncovered) and returns
  category, equipment, primary/secondary muscles and a description, or `isError: true` if nothing
  matches.
- **`suggest_workout(goal, level, minutes)`** (`goal` ∈ abs/arms/back/calves/cardio/chest/legs/
  shoulders/full_body, `level` ∈ beginner/intermediate/advanced) fetches exercises for that
  category from wger.de, filters to bodyweight-only equipment for beginners, and builds a
  sets/reps plan sized to fit `minutes`.
- Deployed as a single Cloud Function `mcp` (`onRequest`, `us-central1`, public invoker), using
  `StreamableHTTPServerTransport({ sessionIdGenerator: undefined })` — **stateless mode**: a fresh
  `McpServer` + transport per HTTP request, since Cloud Functions instances aren't guaranteed to
  persist between invocations.
- **Live URL:** `https://us-central1-ai-challenge-mcp.cloudfunctions.net/mcp`

Run/redeploy it yourself:

```bash
cd mcp-server/functions && npm install && npm run build
firebase deploy --only functions --project ai-challenge-mcp   # requires Blaze plan
# or, for local iteration:
firebase emulators:start --only functions
```

---

## Part 2 — Wiring the tool into the Android agent

### Why the Interactions API's client-side function calling (not `type: "mcp_server"`)

Gemini's Interactions API (`https://ai.google.dev/api/interactions-api`) supports two ways to use
an MCP server: a server-side `type: "mcp_server"` tool (Google calls the MCP server directly) or
declaring `type: "function"` tools and handling the resulting `function_call` steps yourself. The
exercise explicitly asks for the *app* to call the tool and use the result, so this uses the
client-side function-calling loop.

### The wire format (confirmed against the official REST reference)

- Request: `tools: [{ "type": "function", "name", "description", "parameters": <JSON Schema> }]`.
- When the model wants to call a tool, the response has `status: "requires_action"` and a step
  `{ "type": "function_call", "id", "name", "arguments": {...} }` (`FunctionCallStep`).
- To answer it, send a new request with `previous_interaction_id` = the prior response's `id`, and
  `input` = an array containing `{ "type": "function_result", "call_id", "name", "result",
  "is_error"? }` (`FunctionResultStep` — `call_id` must match the `function_call` step's `id`).

### New/changed code

```
GeminiModels.kt              InteractionRequest.input is now JsonElement (string OR an array of
                              function_result steps); +tools, +previous_interaction_id;
                              InteractionStep gained id/name/arguments; new GeminiFunctionTool
                              and FunctionResultInput models.
GeminiApiClient.kt            new createInteraction(...) — like complete() but returns the raw
                              InteractionResponse (so "requires_action" + its steps are visible)
                              and accepts tools/previousInteractionId.
agent/mcp/
  ToolCallingLlmClient.kt     the slice of GeminiApiClient the loop needs, as its own interface
                              (so McpToolCallingAgent is unit-testable against a fake).
  McpToolCallingAgent.kt      new Agent: connects (lazily, cached) to a McpGateway, maps its
                              McpToolInfo list to GeminiFunctionTool (using the tool's raw JSON
                              Schema — see McpToolInfo.rawInputSchema below), then loops:
                              createInteraction → if requires_action, call the tool(s) via
                              McpGateway.callTool → resubmit function_result(s) → repeat (capped
                              at 4 rounds) → extract final text.
mcp/McpGateway.kt              +callTool(name, arguments): McpToolCallResult
mcp/KotlinSdkMcpGateway.kt     implements callTool via the SDK's Client.callTool(...)
mcp/McpModels.kt               McpToolInfo +rawInputSchema (the tool's original JSON Schema,
                              preserved instead of only the flattened McpToolParam list — an LLM
                              needs enum values etc. to call a tool accurately); new
                              McpToolCallResult / McpToolCallException.
mcp/McpConfig.kt               +FITNESS_SERVER_URL (the deployed Cloud Function above).
agent/AgentConfig.kt           +FITNESS_MCP_COACH persona ("Fitness Coach (MCP tools)").
agent/AgentContracts.kt        AgentResponse +toolCalls: List<ToolInvocation> — every tool call
                              made while answering, so the UI/logs can show which tool ran and
                              what it returned, not just the final text.
ChatViewModel.kt               owns one long-lived KotlinSdkMcpGateway; buildAgent(config) picks
                              McpToolCallingAgent for the FITNESS_MCP_COACH persona and plain
                              LlmAgent for every other persona.
```

Selecting **"Fitness Coach (MCP tools)"** in the existing agent picker (same UI Days 1–16 already
built) is the only user-facing change needed to try it: ask it something like *"What muscles does
a deadlift work?"* or *"Suggest a 20-minute beginner leg workout"* and it calls `get_exercise_info`
/ `suggest_workout` on the live wger.de-backed MCP server instead of only recalling facts from the
model's own training data.

---

## Tests

- **`McpToolCallingAgentTest`** (new) — drives the full loop against fakes: answers directly when
  no tool is needed; calls the tool and resubmits its result to produce the final answer; surfaces
  a tool failure to the model instead of crashing; fails cleanly when the MCP server is
  unreachable; rejects a blank message without ever calling the model or connecting; reuses the
  cached MCP connection across multiple `handle()` calls.
- **`McpConnectionControllerTest`** — updated `FakeMcpGateway` for the new `callTool` interface
  member (unrelated to its existing assertions).

```bash
./gradlew :app:testDebugUnitTest --tests "com.example.geminichat.agent.mcp.*"
./gradlew :app:testDebugUnitTest   # full suite, confirms nothing else regressed
```

See [`day17-mcp-tool-test-scenario.md`](day17-mcp-tool-test-scenario.md) for the manual QA script
(including live `curl` calls against the deployed MCP server).

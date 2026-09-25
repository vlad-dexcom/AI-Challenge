# Day 16 — MCP connection (Подключение MCP)

Introduces the app as a **Model Context Protocol (MCP) client/host**: it can open a connection to
an external MCP server and retrieve the list of tools that server exposes. This is a foundational
step for later days, where the assistant will actually *call* MCP tools during a conversation; Day
16 focuses only on establishing the connection and listing tools, per the exercise's acceptance
criteria.

- MCP client built on the official Kotlin SDK (`io.modelcontextprotocol:kotlin-sdk-client:0.15.0`),
  connecting over **Streamable HTTP** to a remote MCP server.
- Default target server: the public [DeepWiki MCP server](https://mcp.deepwiki.com/mcp)
  (`read_wiki_structure`, `read_wiki_contents`, `ask_question` tools for any public GitHub repo) —
  no API key required, ideal for verifying the connection end-to-end. A custom/local server is
  planned for Day 17.
- Connection lifecycle and tool list are surfaced both in a new in-app **MCP** screen and in
  Logcat (tag `MCP`), satisfying the "verify connection establishes" and "verify tool list returns
  correctly" checks from two independent places.

---

## Why the toolchain was upgraded

`kotlin-sdk-client:0.15.0`'s POM requires `kotlin-stdlib 2.4.0` and `ktor-client-core 3.5.1`. The
project was previously on Kotlin `1.9.24` / Ktor `2.3.12`, which are binary-incompatible with the
SDK. Rather than shipping a hand-rolled JSON-RPC client (against the exercise's intent to *use* an
MCP SDK/client) or isolating the SDK in a separate JVM module (extra complexity for a single
feature), the whole app was upgraded:

- Kotlin Gradle plugin `1.9.24` → `2.4.0`, plus the now-separate Compose compiler plugin
  (`org.jetbrains.kotlin.plugin.compose`) versioned in lockstep.
- `kotlinOptions.jvmTarget` (deprecated/hard error under Kotlin 2.x) migrated to the
  `kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }` DSL.
- Ktor `2.3.12` → `3.5.1` (client-core/okhttp/content-negotiation/serialization-kotlinx-json), plus
  the new `io.ktor:ktor-sse` artifact needed by `StreamableHttpClientTransport`.
- `kotlinx-serialization-json` → `1.7.3`, `kotlinx-coroutines-test` → `1.9.0`.

AGP (`8.5.2`) and the Compose BOM (`2024.06.00`) did **not** need to change; the existing Gemini
chat feature continues to build and run unmodified on the new toolchain.

---

## Architecture

```
McpScreen (Compose UI)
    │  observes
    ▼
McpViewModel (AndroidX ViewModel)
    │  owns + drives                     Log.i / Log.e (Logcat, tag "MCP")
    ▼                                        ▲
McpConnectionController  ──────────── on every state change ┘
    │  (plain Kotlin state machine, no Android imports — unit-testable)
    │  calls
    ▼
McpGateway (interface: connect / listTools / close)
    │
    ▼
KotlinSdkMcpGateway (real implementation)
    │  StreamableHttpClientTransport + Client (kotlin-sdk-client)
    ▼
Remote MCP server (default: https://mcp.deepwiki.com/mcp)
```

- **`McpGateway`** keeps every MCP SDK type out of the rest of the app; only
  `KotlinSdkMcpGateway.kt` imports `io.modelcontextprotocol.kotlin.sdk.*`.
- **`KotlinSdkMcpGateway`** builds an `HttpClient(OkHttp) { install(SSE); install(HttpTimeout) }`,
  wraps it in a `StreamableHttpClientTransport(client, url)`, and drives a
  `Client(clientInfo = Implementation(name = "PersonalTrainerApp", version = ...))`:
  - `connect(url)` calls `client.connect(transport)` (performs the MCP `initialize` handshake) and
    maps the resulting `serverVersion` / `serverCapabilities` / `serverInstructions` into a plain
    `McpServerInfo`.
  - `listTools()` calls `client.listTools()`, paginating via `ListToolsResult.nextCursor` until
    exhausted, and maps each SDK `Tool` (JSON Schema `inputSchema`) into `McpToolInfo` /
    `McpToolParam` via `McpToolMapper`.
  - `close()` calls `client.close()`.
  - Any handshake/listing failure (`IllegalStateException`, `McpException`, `StreamableHttpError`,
    `SerializationException`, or generic `IOException`) is wrapped into a single
    `McpConnectionException` so the rest of the app deals with one exception type.
- **`McpConnectionController`** is a plain class (no `android.*`, no `ViewModel`) that owns a
  `McpUiState` (`Idle` / `Connecting` / `Connected(serverInfo, tools)` / `Error(message)`) and
  drives `connect()` / `disconnect()` against an injected `McpGateway`, reporting every transition
  through `onLog` / `onLogError` callbacks. Because it has no Android or `viewModelScope`
  dependency it runs directly under plain JUnit.
- **`McpViewModel`** is a thin `ViewModel` that constructs a `McpConnectionController` wired to
  `Log.i(TAG, ...)` / `Log.e(TAG, ...)`, exposes its `StateFlow<McpUiState>`, and launches
  `connect()` / `disconnect()` on `viewModelScope`.
- **`McpScreen`** is a full-screen Compose screen (same `TopAppBar` + back-arrow pattern as
  `SettingsScreen`) with a server-URL field (defaulting to the DeepWiki URL), Connect/Reconnect/
  Disconnect buttons, and renders the current `McpUiState`: an error banner, a "Connecting…"
  spinner, or on success a server-info card plus the full tool list (name, description, and each
  parameter's name/type/required flag). It is reachable from the chat screen via a new
  wrench/build icon in `ChatScreen`'s app bar, and is constructed in `MainActivity` together with
  a `KotlinSdkMcpGateway()` via a `ViewModelProvider.Factory`.

---

## Tests

- **`McpToolMapperTest`** — pure unit tests of the JSON Schema → `McpToolInfo`/`McpToolParam`
  mapping: empty schema, fully populated properties (types, descriptions, required list), title
  fallback to `annotations.title`, and defensive handling of non-primitive `type` fields (e.g.
  JSON Schema union-type arrays) without throwing.
- **`McpConnectionControllerTest`** — drives the state machine against a `FakeMcpGateway`: initial
  `Idle` state, successful connect → `Connected`, gateway failure → `Error`, blank-URL rejection,
  reconnecting closes the previous connection first, and `disconnect()` returns to `Idle`.
- **`KotlinSdkMcpGatewayIntegrationTest`** — the most end-to-end check: it starts a **real**
  embedded MCP server (`kotlin-sdk-server` + an embedded Ktor **CIO** engine) on a random free
  local port, registers two tools (`health_check`, `get_exercise_info`), and connects to it with
  the real `KotlinSdkMcpGateway` (no fakes) — asserting the actual server identity returned by the
  `initialize` handshake and that `listTools()` returns exactly the registered tools with correct
  parameter metadata. This exercises the genuine Streamable HTTP request/response cycle instead of
  depending on network access to the public DeepWiki server during CI/local test runs.
  - Note: this test uses `runBlocking`, not `kotlinx-coroutines-test`'s `runTest` — `runTest`'s
    virtual-time scheduler is not compatible with tests that perform real socket I/O against an
    embedded server, and caused spurious "Request timed out" failures when first tried.

Run just the MCP tests with:

```bash
./gradlew :app:testDebugUnitTest --tests "com.example.geminichat.mcp.*"
```

---

## Manual verification

See [`day16-mcp-connection-test-scenario.md`](day16-mcp-connection-test-scenario.md) for the full
manual QA script (happy path, bad URL, and confirming the existing Gemini chat still works after
the toolchain upgrade).

# Day 18 — Планировщик и фоновые задачи (Scheduled MCP-style tool)

Day 17 gave the app's agent a real MCP tool talking to a remote server. Day 18 adds the exercise's
other half: a tool with **deferred/periodic execution** that persists data, runs on a schedule,
and returns an **aggregated** result — "агент, который работает 24/7 и периодически выдаёт
сводку".

Since this app has no always-on server component of its own (Day 17's MCP server is a stateless
Cloud Function, not a long-running process), the periodic execution lives on-device via
**Android's `WorkManager`**, which persists scheduled work across app restarts and even device
reboots without requiring the app to be in the foreground.

## What it does: "Workout Digest"

- **`log_workout(goal, minutes)`** — records a completed workout (as `WorkoutLogEntry`) to a JSON
  file on disk.
- A periodic background job re-aggregates every logged workout over the trailing 7 days into a
  single `WorkoutSummary` (totals + breakdown by goal), overwriting the previous summary.
- **`get_workout_summary()`** — returns that saved summary. It never recomputes on the spot; it
  just reads whatever the background job most recently produced.

```
app/src/main/java/com/example/geminichat/
  agent/workout/
    WorkoutLogEntry.kt        # one logged workout (id, goal, minutes, timestamp)
    WorkoutLogStore.kt        # JSON-file persistence for the log (append-only)
    WorkoutSummary.kt         # the aggregated result (totals + per-goal breakdown)
    WorkoutSummaryStore.kt    # JSON-file persistence for the single latest summary
    WorkoutDigestAggregator.kt# pure aggregation logic (plain JVM, unit-tested)
    WorkoutDigestWorker.kt    # CoroutineWorker: reads the log, aggregates, saves the summary
    WorkoutDigestScheduler.kt # registers WorkoutDigestWorker as periodic WorkManager work
  mcp/
    LocalWorkoutMcpGateway.kt # McpGateway implementation with no network — tools read/write
                              # the stores above instead of calling a remote server
```

### Why this reuses `McpGateway`/`McpToolCallingAgent` unchanged

[`McpGateway`](../app/src/main/java/com/example/geminichat/mcp/McpGateway.kt) is a
transport-agnostic contract (`connect` / `listTools` / `callTool` / `close`). Day 17's
`KotlinSdkMcpGateway` implements it over a real MCP server; `LocalWorkoutMcpGateway` implements the
exact same contract purely with local JSON storage — `connect` is a no-op handshake,
`listTools` returns two `McpToolInfo`s (with JSON-Schema `inputSchema`s, same as Day 17's tools),
and `callTool` dispatches to the stores. Because the contract is identical,
[`McpToolCallingAgent`](../app/src/main/java/com/example/geminichat/agent/mcp/McpToolCallingAgent.kt)
— the same function-calling loop from Day 17 (declare tools to Gemini, execute whichever one it
calls, resubmit the result, repeat) — needs **no changes at all** to drive this "local MCP tool".
Only the transport backing the tools differs.

### The schedule

`WorkoutDigestScheduler.schedule(context)` is called once from `MainActivity.onCreate` and
registers `WorkoutDigestWorker` as a `PeriodicWorkRequest` with `enqueueUniquePeriodicWork(...,
ExistingPeriodicWorkPolicy.KEEP, ...)`:

- `KEEP` means re-running `onCreate` (config change, relaunch) never schedules a duplicate job.
- The repeat interval defaults to **15 minutes** — WorkManager's own minimum for periodic work;
  Android does not allow shorter periodic intervals regardless of what's requested.
- WorkManager persists the schedule in its own database, independent of the app process, which is
  what gives the "24/7" behavior without a dedicated server.

`WorkoutDigestWorker.doWork()` reads every entry via `WorkoutLogStore`, calls the pure
`WorkoutDigestAggregator.aggregate(logs, now, periodDays = 7)`, and saves the result via
`WorkoutSummaryStore` — overwriting, not appending, since only the latest summary matters.

### New agent persona

`AgentCatalog.WORKOUT_DIGEST_COACH` ("Workout Digest (scheduled)") is wired up in
`ChatViewModel.buildAgent` exactly like `FITNESS_MCP_COACH`, just pointed at
`LocalWorkoutMcpGateway` instead of the remote fitness server. Its system instruction tells the
model to call `log_workout` when the user mentions completing a workout, and
`get_workout_summary` when they ask for a recap — noting that the summary is a periodic snapshot,
not a live recalculation.

## Tests

- **`WorkoutDigestAggregatorTest`** — pure aggregation: empty log, sums/counts per goal, and
  entries outside the trailing window are ignored entirely.
- **`WorkoutStoresTest`** — `WorkoutLogStore`/`WorkoutSummaryStore` round-trip persistence
  (append without dropping earlier entries; summary overwrite semantics).
- **`LocalWorkoutMcpGatewayTest`** — the tool contract: `listTools` advertises both tools,
  `log_workout` writes an entry (and rejects missing/invalid input), `get_workout_summary`
  reports "no data yet" before the first digest run and otherwise returns the saved summary.

```bash
./gradlew :app:testDebugUnitTest --tests "com.example.geminichat.agent.workout.*"
./gradlew :app:testDebugUnitTest --tests "com.example.geminichat.mcp.LocalWorkoutMcpGatewayTest"
./gradlew :app:testDebugUnitTest   # full suite, confirms nothing else regressed
```

`WorkoutDigestWorker` and `WorkoutDigestScheduler` themselves are thin Android/WorkManager
wrappers (constructor injection isn't possible for a `CoroutineWorker`, which WorkManager
constructs via reflection) — their logic is deliberately kept in the plain-JVM
`WorkoutDigestAggregator`, which is what's unit-tested; see
[`day18-scheduled-mcp-tool-test-scenario.md`](day18-scheduled-mcp-tool-test-scenario.md) for the
manual QA script that exercises the real scheduled job end-to-end on a device/emulator.

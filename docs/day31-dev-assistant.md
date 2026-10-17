# Day 31 — developer assistant (`/help`)

Goal: an assistant that understands this project. It answers questions about its structure from the documentation (RAG) and
knows the live repository state through MCP (current git branch, working tree).

## What is built

| Piece | Where | Role |
|---|---|---|
| Git MCP server | `dev-assistant/.../GitMcpGateway.kt` | Read-only tools `git_branch`, `git_status`, `git_diff_stat`, `list_files` behind the app's `McpGateway` contract (same as `LocalWorkoutMcpGateway`: in-process, no network). `git` runs through a `GitRunner` (arguments as a list, no shell, 10 s timeout, output capped to 60 lines). Refs and paths are validated (no options, no `..`). |
| Docs corpus + index | `ProjectDocs.kt`, `DocsIndex` | README.md, AGENTS.md and the markdown files in `docs/` (historical `dayNN-*.md` only with `--history`), structure chunker, Gemini embeddings, stored in `rag/index-docs/structure.json` (git-ignored). The index carries a fingerprint of the docs and is rebuilt automatically when they change. |
| `HelpAssistant` | `HelpAssistant.kt` | question → git MCP calls → retrieval (top 5) → JSON answer with quotes → verification in code. |
| CLI | `Main.kt` | `./gradlew :dev-assistant:run`, `/help <question>`, `--once`, `--history`, `--reindex`. |
| Web | `web-console/.../HelpApi.kt`, tab "Dev assistant (Day 31)" | `POST /api/help {question}`; created lazily on the first question. |

## How an answer is made

```
/help <question>
   ├─ MCP: git_branch + git_status (+ git_diff_stat when the question is about changes)
   ├─ RAG: embed question → top 5 chunks of README/AGENTS.md/docs
   ├─ prompt: documentation excerpts + MCP results as extra excerpts (file = "git (MCP)")
   ├─ Gemini → {"answerable", "answer", "sources", "quotes"}  (temperature 0)
   └─ CitationVerifier: every quote must be a verbatim substring of an excerpt, every source one of the excerpts;
      otherwise one retry, then "I don't know" (no invented answer)
```

MCP results are ordinary excerpts, so "what branch am I on?" is answered *and cited* from `git_branch`, and a quote from the
git output is verified exactly like a quote from a document. Reuses `CitationParser`/`CitationVerifier` from `:rag:core`
(Day 24); only the persona differs from the trainer's `CitedAnswerer`, so `HelpAssistant` has its own prompt.

If git fails (not a repository, no `git`), the tool result is an error entry and the answer still comes from the docs.

## Decisions

- **MCP in-process, not a separate server process.** The week's goal is the flow (assistant ↔ MCP tools), and the app already
  has the `McpGateway` abstraction; a stdio/HTTP transport can replace `GitMcpGateway` without touching `HelpAssistant`.
- **Tools are called deterministically, not chosen by the model.** Branch and status are cheap and relevant to almost every
  question about "the project right now"; only `git_diff_stat` is conditional (keyword match). This keeps answers reproducible
  and works with the plain `TextGenerator`. A model-driven tool loop (`McpToolCallingAgent`) is the next step if more tools appear.
- **Own module `:dev-assistant`.** It needs `java.io`/`ProcessBuilder`, so it cannot live in the KMP-ready `:agent`; Days 32 and
  34 reuse the git tools from here.
- **Day notes excluded by default.** They are historical and would outweigh the architecture docs in retrieval.

## Manual scenario

See `docs/day31-dev-assistant-test-scenario.md` (needs `GEMINI_API_KEY`).

## Tests (no network)

`./gradlew :dev-assistant:test :web-console:test` — fake `GitRunner` and scripted generator: tool output and truncation, rejection
of option-like refs and path traversal, git failure does not break doc answers, MCP excerpt is citable, fabricated quotes end in
"I don't know", index cache reuse and invalidation, `/api/help` validation and responses, page rendering (node test).

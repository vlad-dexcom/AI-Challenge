# Day 31 — test scenario

Needs `GEMINI_API_KEY` (env or `local.properties`) for sections 2–5; section 1 needs no key or network.

## 1. Without a key (fake git, scripted model)
- `./gradlew :dev-assistant:test :web-console:test` is green (tool output, validation, citations, index cache, `/api/help`, page rendering).
- `./gradlew check` is green (KMP guard: `:dev-assistant` is a JVM-only module, nothing leaks into `core/*`, `rag/core`, `agent`).
- `./gradlew :dev-assistant:run` without a key → "GEMINI_API_KEY is not set", exit code 2.

## 2. Index
- `./gradlew :dev-assistant:run` → first start prints `Indexing 5 project documents with gemini-embedding-001…` and `Index: N chunks -> ./rag/index-docs/structure.json`; the second start prints nothing (cache hit).
- Edit any line in `docs/modules.md`, restart → `Indexing …` again (fingerprint changed). `--history` adds the `dayNN-*.md` notes (more documents), `--reindex` forces a rebuild.
- `git status` does not list `rag/index-docs/` (git-ignored).

## 3. CLI `/help` (interactive or `--args="--once '<question>'"`)
1. `/help какой модуль отвечает за RAG и от каких модулей он зависит?` → `:rag:core`, depends on `:core:llm` and `:core:common`; sources `docs/modules.md`, README.
2. `/help где должна жить логика агента, а где ViewModel?` → `:agent` vs `:app/ui`; source `AGENTS.md` or `docs/modules.md`.
3. `/help как добавить новую MCP-тулзу?` → steps from `docs/agent-patterns.md`.
4. `/help какие зависимости разрешены между модулями?` → `:app → :agent → :rag:core → :core:llm → :core:common`.
5. `/help на какой я ветке?` → the current branch; sources list `git (MCP) > git_branch`, the "MCP (git)" block shows the raw lines.
6. `/help что у меня изменено?` → changed files from `git_status`; `git_diff_stat` appears among the MCP calls (the question is about changes).
7. `/help как приготовить борщ?` → "Не знаю: …" (nothing verifiable in the docs), no sources.
8. Question without `/help` (plain text) works the same; `/help` alone prints the usage; `/exit` quits.

## 4. MCP tools directly
- `--repo /tmp` (no docs) → "No documentation found …", exit code 2. A folder with docs but without `.git` → the "MCP (git)" block shows `[error]` entries and the answer still comes from the docs.
- Detached HEAD (`git checkout --detach`) → "Detached HEAD" instead of a branch name. Switch back afterwards.
- Safety (covered by unit tests): a ref like `--output=/tmp/x` and a path like `../secrets` are rejected before git runs; there are no write tools.

## 5. Web console
`./gradlew :web-console:run` → http://localhost:8080 → tab *Dev assistant (Day 31)*.
- Click an example chip, send: answer, "Sources" list, collapsible "MCP calls (N)" with the raw tool output.
- First question after start takes longer (index is built); later ones are fast.
- Question 7 above → blue "I don't know" box. Empty input → nothing is sent; `/help ` + empty → "Question is empty".
- Without a key: the tab shows "GEMINI_API_KEY is not set …".
- `curl -s -X POST localhost:8080/api/help -d '{"question":"где живёт логика агента?"}'` → JSON with `answer`, `idk`, `sources`, `mcpCalls`.

### Questions for the video
1. Какой модуль отвечает за RAG и от каких модулей он зависит?
2. Где должна жить логика агента, а где ViewModel?
3. Как добавить новую MCP-тулзу и что для неё написать в тестах?
4. На какой я ветке и какие файлы изменены?
5. Какие правила проверяет `checkKmpReadiness`?
6. Как приготовить борщ? (вне проекта: «Не знаю»)

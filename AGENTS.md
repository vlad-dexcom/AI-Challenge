# Instructions for AI coding agents

Educational Android + JVM project (Gemini chat agent, MCP, RAG). Read first:
`docs/architecture.md` → `docs/modules.md` → `docs/agent-patterns.md`. `docs/dayNN-*.md` are
historical notes; don't update them.

## Commands
- All tests + KMP guard: `./gradlew check`; Android build: `./gradlew :app:assembleDebug`
- RAG CLI: `./gradlew :rag:tools:run --args="…"`; web UI: `./gradlew :web-console:run`
- Project Q&A (docs + git MCP): `./gradlew :dev-assistant:run` → `/help <question>`
- AI review of a branch (commit first): `./gradlew :reviewer:run --args="--base main"`; runs on PRs via `.github/workflows/ai-review.yml`
- Gemini key: `GEMINI_API_KEY` in `local.properties` or env. Never print/commit it.

## Rules
- Dependencies flow `:app → :agent → :rag:core → :core:llm → :core:common`. Only `:app` may use
  Android; shared modules must not import `java.*`/`android.*` outside `platform/` packages.
- Agent logic belongs in `:agent`, UI/ViewModels/navigation in `:app/ui`.
- Keep package names; stores take kotlinx-io `Path`; use `nowMillis()`/`newId()`.
- New behaviour needs tests (JUnit4, fakes, no network). Update README/docs when structure changes.
- Don't commit or push unless asked.

# Day 32 — AI code review of pull requests

Goal: a pipeline in which an assistant analyses every new pull request: it gets the diff and the changed files, uses RAG
(documentation + code) and writes a review with potential bugs, architecture problems and recommendations.

## What is built

| Piece | Where | Role |
|---|---|---|
| Git MCP tools for a PR | `dev-assistant/.../GitMcpGateway.kt` | New read-only tools `git_changed_files(base, head)` and `git_diff(base, path, head)`: changes between the **merge base** of `base` and `head` and `head` (`git diff base...head`, what GitHub shows). Refs and paths are validated (no options, no `..`), `git_diff` has its own larger line cap. |
| Diff parsing | `reviewer/.../Diff.kt` | Rewrites a patch so that every visible line carries its **new-file line number** (`L12+ code` added, `L12  code` unchanged, `    - code` removed); remembers which line numbers exist. `ReviewScope` skips deleted, generated, binary and vendored files. |
| Context (RAG) | `Reviewer.kt`, `ReviewerFactory.kt` | Docs index (README, AGENTS.md, docs/, shared with `/help`) and a **code index** (all tracked non-test `.kt` files, `rag/index-code/`, git-ignored). |
| Reviewer | `Reviewer.kt` | changed files + diffs via MCP → retrieval → one Gemini call (JSON) → validation in code → `ReviewReport`. |
| Report | `ReviewReport.kt` | Markdown with a hidden marker, three sections, verified rule quotes, skipped files, consulted context. `en` and `ru`. |
| CLI | `reviewer/.../Main.kt` | `./gradlew :reviewer:run --args="--base main"`. |
| GitHub Action | `.github/workflows/ai-review.yml`, `scripts/post-review-comment.sh` | Runs the CLI on every PR and keeps **one comment** up to date. |

## How a review is made

```
PR opened / pushed
   │  GitHub Action (fetch-depth 0)
   ▼
MCP git_changed_files + git_diff ──► annotated diff per file (largest production files first, size budget)
   │
   ├─ RAG docs: query = file path + added lines, per changed file ──► conventions   (AGENTS.md is always included)
   ├─ RAG code: same query ──► related existing code (chunks of the changed files themselves are left out)
   ▼
Gemini (temperature 0, JSON): summary, bugs, architecture, recommendations
   ▼
validation in code ──► markdown ──► PR comment (updated on every push)
```

Retrieval uses the path and the *added lines* of the biggest changed files (up to 8) as the query, not the raw diff, which
embeds poorly. Per file 3 documentation chunks and 4 code chunks are fetched; the best 6 + 5 reach the prompt next to the always-present
AGENTS.md rules.

## The noise filter

LLM reviewers are noisy, so the answer is not trusted as is. A finding is kept only if:

| Check | Applies to | Why |
|---|---|---|
| the file is part of the reviewed diff | all | no comments about code that was not changed |
| the line is a line of the diff (added or unchanged) | bugs (others lose the line number instead) | a bug that cannot be located is not actionable |
| `confidence` ≥ 6 (of 10) | bugs, architecture | the model rates its own certainty; `--min-confidence` changes the threshold |
| a **verbatim quote** from a documentation excerpt supports it | architecture | same verification as Day 24 citations (`CitationVerifier`): a "rule" the model invented is dropped. Without a rule it can still be a recommendation. |

Dropped findings are counted in the report footer and listed with the reason by `--verbose`. Per category at most 8 findings, highest confidence first.

## Security and limits

- **Untrusted input.** The diff, file contents and documents are data: the system prompt says so, the diff sits in a `<pr_diff>` block and the
  model has no tools and no write access. A test checks that text such as "IGNORE ALL PREVIOUS INSTRUCTIONS" in a diff stays inside the block.
- **Secrets.** `GEMINI_API_KEY` is a repository secret, never printed. Pull requests from forks get no secrets from GitHub, so the job skips with a notice;
  `pull_request_target` is deliberately **not** used (it would run untrusted code with secrets).
- **Token.** `contents: read`, `pull-requests: write`; the only write is the PR comment.
- **Known limit:** the workflow runs the reviewer code from the PR's own branch, so anyone who can push a branch to this repository can change what runs with the key. That is the usual trust level of repository CI; for a larger team run the reviewer from the base branch.
- **Budget.** 60 000 characters of diff in total, 12 000 per file, 25 files; the rest is listed under "Not reviewed" with the reason. `concurrency` cancels an outdated run when a new commit arrives; the job has a 20 minute timeout.

## Decisions

- **Own module `:reviewer`** on top of `:dev-assistant`: it reuses the git MCP server and the docs index, and keeps the JVM-only CLI out of the KMP-ready modules.
- **MCP, not `git` calls in the reviewer.** The reviewer only knows the `McpGateway` interface, so the same code reviews a PR through any server that offers the two tools.
- **Model**: `gemini-3.8-flash` by default (`--model` overrides); the app and `/help` keep the general default. The seeded-PR results below were produced with `gemini-3.5-flash` before the switch.
- **One model call per PR**, not one per file: the model sees the whole change, so it can notice cross-file problems, and the cost and latency stay predictable.
- **Code index with an embedding cache** (`rag/index-code/embed-cache`, per chunk text): after a PR changes a few files only the new chunks are embedded. The Action caches the indexes between runs.
- **Committed changes only.** The review compares `base...HEAD`; commit before running it locally.

## Result on a seeded pull request

A sample PR (`docs/day32-ai-review-test-scenario.md`) adds `WorkoutExport.kt` (a `java.io.File` import in `:agent`, an off-by-one loop,
a division by zero) and `QuickAskViewModel.kt` (a ViewModel that calls `GeminiApiClient` directly and does `getOrNull()!!`). Real run with Gemini:

| Seeded problem | Found as |
|---|---|
| `0..count` loop / empty list → `IndexOutOfBounds` | bug, confidence 10 |
| division by `all.size` = 0 | bug, confidence 10 |
| `getOrNull()!!` on a failed LLM call | bug, confidence 9 |
| `java.io.File` in a shared module | architecture, with the verified quote from AGENTS.md "Rules" |
| ViewModel bypasses the `Agent` layer | recommendation (the model found no quotable rule for it) |
| no tests for the new class | recommendation |

No findings were dropped in that run. The first run took about 1.5 minutes including indexing 67 documentation and 737 code chunks; a repeat run took about 80 seconds, most of it the model call.

## Not verified yet

The workflow itself runs for the first time on the pull request that adds it: that needs the `GEMINI_API_KEY` secret and a runner with the Android SDK (Gradle configures `:app`, which ubuntu runners provide). The YAML is validated and the posting script is tested against a stub `gh`.

## Manual scenario

See `docs/day32-ai-review-test-scenario.md`.

## Tests (no network)

`./gradlew :reviewer:test :dev-assistant:test` — diff annotation and truncation, file scope, validation (bad line, unknown file, low confidence,
invented rule quote), caps and ordering, prompt contents (diff, pinned rules, related code without the changed files), prompt-injection placement,
no model call without reviewable changes, file limit, JSON retry, git and model failures, `ru` labels, the new git tools and their argument checks, and the comment script
(post vs. update vs. nothing) against a stub `gh`.

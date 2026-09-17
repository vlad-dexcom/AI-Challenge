# Day 14 — Invariants and state constraints

Adds a fourth layer on top of Day 11 (memory), Day 12 (profile), and Day 13 (task state):
**invariants** — hard rules the agent is never allowed to violate, no matter what the user asks,
what the profile says, or what stage the task is in. It answers a different question than the
three earlier layers: not *what do we know*, *how should we answer*, or *where are we in the
task*, but **what is off the table entirely**.

## Why this is not another memory/profile field

Memory (Day 11) and the profile (Day 12) are both things the agent is expected to *use to answer
better* — they can be wrong, stale, or overridden by a more specific user request. Invariants are
the opposite: they are rules the *user themselves* should not be able to talk the agent out of in
a single message, because they encode either a safety boundary (never suggest steroids), a
technical constraint the project has committed to (Compose only, no new dependencies), or a
business rule (a trainer persona always includes a warm-up). Mixing them into the profile would
make them just another editable preference — exactly what they must not be.

## The four example categories asked for by the assignment

| Category (assignment) | This app's example invariant(s) |
|---|---|
| Chosen architecture | `home-equipment-only` (methodology), `ui-compose-only` / `rest-ktor-no-sdk` (Tech stack preset) |
| Accepted technical decisions | `no-new-deps` (Tech stack preset — "no new Gradle dependency without asking") |
| Stack constraints | `ui-compose-only`, `rest-ktor-no-sdk` |
| Business rules | `medical-scope`, `no-ped`, `no-pain-training`, `min-calories` (locked core); `max-3-sessions`, `warmup-cooldown`, `progression-10`, `fitness-scope` (editable) |

The "Tech stack" preset intentionally demonstrates that invariants aren't fitness-specific — the
same mechanism can pin an architecture decision ("Compose only, no XML views") just as well as a
training rule.

## Layer boundary

| Layer | Question it answers | Who can change it | Where it lives |
|---|---|---|---|
| Memory (Day 11) | What do we know? | The agent (routed automatically) | `MemoryStore` files |
| Profile (Day 12) | How should we answer? | The user, freely | `user_profile.json` |
| Task state (Day 13) | Where are we in the task? | The state machine only | `task_state.json` |
| **Invariants (Day 14)** | **What must never happen?** | The user, but locked entries can't be disabled/removed | `invariants.json` |

## Data model — `agent/invariant/`

- **`Invariant.kt`** — one rule: `id`, `category` (`InvariantCategory`: `SAFETY`, `MEDICAL`,
  `EQUIPMENT`, `SCHEDULE`, `NUTRITION`, `METHODOLOGY`, `SCOPE`, `TECH`), `statement` (the rule
  itself), `rationale` (why it exists), `alternative` (what to suggest instead of just refusing),
  `triggers` (regexes matched against the user's message), `enabled`, and `locked`. `locked`
  invariants can be turned off or deleted by nobody, including the user, from the UI.
- **`InvariantSet.kt`** — an ordered list of `Invariant`s plus lookup helpers.
  `InvariantSet.DEFAULTS` ships 4 locked core rules (medical scope, no PEDs, no train-through-pain,
  a hard calorie floor) and 5 editable ones (home-equipment-only, ≤3 sessions/week, always
  warm up/cool down, ≤10% weekly load progression, fitness-only scope). `InvariantSet.PRESETS`
  offers swappable bundles ("Домашние тренировки", "Реабилитация", "Tech stack"). `InvariantRules`
  is the *only* code allowed to mutate a set (`add`/`remove`/`setEnabled`/`applyPreset`/`reset`),
  always returning `InvariantChangeResult.Applied`/`Rejected(reason)` — the same
  explicit-result-instead-of-throwing pattern as Day 13's `TaskStateMachine`. Every mutation that
  would touch a `locked` entry is rejected here, before it ever reaches storage or the UI.
- **`InvariantRenderer.kt`** — deterministically renders the enabled invariants into one
  "hard invariants, non-negotiable" system-instruction block, with a footer telling the model
  this block overrides the profile, memory, task state, and the user's own request. No LLM call
  is involved in rendering.
- **`InvariantGuard.kt`** — the enforcement engine. `check(set, message)` normalizes the newest
  user message (lowercase, `ё`→`е`) and matches it against every *enabled* invariant's trigger
  regexes, returning every conflict found (a request can violate more than one rule at once).
  `refusalText(conflicts)` builds the Markdown refusal shown to the user.
- **`InvariantStore.kt`** — persists the set to `invariants.json`. `load()` **sanitizes**, not
  just deserializes: a missing/corrupt file yields `DEFAULTS`; any locked invariant missing from
  a hand-edited file is re-inserted; any locked invariant found `enabled = false` is forced back
  to `true`; duplicate ids collapse to one entry. This is what makes "locked" actually mean
  locked, rather than just a UI affordance.

## Enforcement pipeline (the "medium" depth)

Three enforcement depths were considered; this app implements the middle one deliberately:

1. **Prompt-only** (rejected) — just tell the model about the rules and hope it complies. No
   hard guarantee; an LLM can still be talked into breaking a stated rule.
2. **Medium (implemented)** — a **deterministic, code-only pre-check** of the user's newest
   message runs in `LlmAgent.handle()` *before* any prompt is assembled or the model is called.
   If it matches an enabled invariant's trigger, the agent returns a refusal
   (`Result.success`, not `failure` — see below) with **zero LLM calls and zero token cost**,
   and the model never even sees the request. If there's no match, the request proceeds
   normally, but the rendered invariants block is still appended to the system instruction (as
   the *last* block, after the profile and task-stage rules) so the model has the rules in view
   for every turn, as a second line of defense for wording the guard's regexes don't anticipate.
3. **Deep** (rejected, out of scope for this app) — an additional LLM-based post-check that
   re-reads the model's own answer for invariant violations before showing it to the user. Not
   implemented: it doubles the API cost of every turn, and turning the pre-check off (disabling
   a rule) already fully un-blocks that behavior — there is no gap that only a post-check would
   catch, given every default trigger is checked against the same normalized user message.

```
user message
     │
     ▼
InvariantGuard.check(set, message)      ← deterministic, no network call
     │
     ├─ conflict found ──► refusal (Result.success, 0 tokens, model never called)
     │
     └─ no conflict ──► prompt assembled, invariants block appended last to
                          system instruction ──► LlmClient.complete(...)
```

Why the refusal is `Result.success`, not `Result.failure`: the app's error banner
(`ContextWindowExceededException`, network failures) is for things that went *wrong*. A refusal
because of an invariant is the agent working *correctly* — it should render as a normal (if
visually flagged) assistant chat bubble, not an error toast. `AgentResponse.refusedByInvariantIds`
is what the UI checks to badge that bubble.

## Wiring

- **`AgentContracts.kt`** — `AgentRequest.invariants: String?` (the rendered block, appended to
  the system instruction); `AgentResponse.refusedByInvariantIds: List<String>`;
  `TokenUsage.invariantTokens`, folded into `promptTokens` like every other prompt component.
- **`LlmAgent.kt`** — constructor takes an `InvariantSet` (default: empty, so every pre-Day-14
  caller/test is unaffected); the guard runs first thing in `handle()`.
- **`ChatViewModel.kt`** — owns the loaded `InvariantSet`, persists it via `InvariantStore`, and
  exposes `onToggleInvariant`/`onAddInvariant`/`onDeleteInvariant`/`onApplyInvariantPreset`/
  `onResetInvariants`, all routed through `applyInvariantChange` (updates state + persists +
  rebuilds the `LlmAgent` with the new set on `Applied`, surfaces `Rejected`'s reason as an error
  otherwise). Unlike "Clear dialog"/"End task" (Day 11/13), neither touches the invariant set —
  invariants outlive both the dialog and the current task, by design.
- **UI (`ChatScreen.kt`)** — an `InvariantPanel` on the Settings screen: enabled/total count,
  preset buttons, invariants grouped by category, an "Add invariant" form, "Reset to defaults".
  Locked rows show a 🔒 instead of a switch/delete button — there's no UI path to attempt the
  mutation at all. `MessageBubble` tints refused turns (`errorContainer`) and shows
  "⛔ Инвариант: <ids>"; the token caption includes `inv N`.

## What happens on a conflict (verified by `InvariantEnforcementTest`)

- The client (`LlmClient.complete`) is called **zero times**.
- The returned `AgentResponse.tokenUsage.totalTokens == 0` (only `requestTokens` is non-zero, for
  UI display of what was estimated for the rejected message itself).
- `refusedByInvariantIds` lists every conflicting rule's id, in the order the guard found them.
- The refusal text names the rule id, its category, the exact `statement`, the `rationale`, the
  suggested `alternative`, and whether it's locked (non-negotiable) or editable (can be turned off
  in Settings) — see "How the assistant explains a refusal" below.
- A profile constraint that contradicts a locked invariant does **not** override it — the
  invariant still wins, because the guard runs before the profile is even read.

## How the assistant explains a refusal

The refusal is always structured the same way, so it reads as a rule lookup rather than an
improvised "no": rule id → category → the exact statement → why it exists → what to ask for
instead → whether the user themselves could turn this rule off in Settings. Example (for a
gym/barbell request against the default `home-equipment-only`):

> ⛔ Не могу выполнить это — конфликт с инвариантом **`home-equipment-only`** (methodology).
>
> **Правило:** Тренировки проектируются только с оборудованием, доступным дома...
> **Почему:** Пользователь тренируется дома и не имеет доступа в зал...
> **Вместо этого:** Предложи вариант с гантелями/эспандерами/весом тела...
>
> Это правило можно отключить в Настройках → Инварианты, если оно тебе больше не подходит.
>
> Если я неправильно понял твой запрос, переформулируй его.

For a locked rule (e.g. `no-ped`), the "can be turned off" line is replaced with a statement that
the rule is locked and non-negotiable in this app.

## Known limitations

- Enforcement is regex-based on the newest message only; it will miss paraphrases the trigger
  list didn't anticipate (a real system would likely add semantic/LLM-based detection as a
  second pass — deliberately out of scope here, see "medium" depth above).
- Only the user's newest message is checked, not the full history — a multi-turn conversation
  that gradually steers into a violation without a single triggering message would not be caught.
- `InvariantStore.load()`'s "re-insert a locked default" recovery only knows about the ids that
  ship in `InvariantSet.DEFAULTS`; a locked invariant *added* at runtime (there is no UI path for
  this, but the model allows it) would not be recovered the same way if hand-deleted from the
  file.

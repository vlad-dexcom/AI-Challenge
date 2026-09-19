# Day 15 — Controlled state transitions (Контролируемые переходы состояний)

Builds upon Day 13's task state machine (`TaskStateMachine`) and Day 14's deterministic pre-check
enforcement (`InvariantGuard`) to establish **strict, controlled lifecycle transitions**:

- Tasks have formal, valid states (`PLANNING`, `EXECUTION`, `VALIDATION`, `DONE`, `CANCELLED`).
- Transitions between stages are governed by a single declarative transition matrix
  (`TaskTransitionTable`) with explicit precondition guards (`NotPaused`, `PlanHasSteps`,
  `HasNextStep`, `HasPreviousStep`, `ValidationPassed`, `NotTerminal`).
- The assistant is strictly prevented from jumping stages (e.g. executing before plan approval, or
  finalizing without passed validation), enforced both deterministically in code via
  `TaskStageGuard` (with 0 tokens and 0 API calls) and behaviorally in system prompts via
  `TaskStateRenderer.stageRules()`.
- Pause/resume state freezes progress without loss of stage, step index, or validation outcome,
  surviving app restarts and branch switches.
- Every attempted state transition (both applied and rejected) is logged into a persistent
  transition journal (`TaskTransitionLogStore`), viewable directly in the UI.

---

## State Transition Matrix

The table below summarizes all legal state transitions defined in `TaskTransitionTable`:

| From Stage | Event | To Stage | Precondition Guards | Description |
|---|---|---|---|---|
| `PLANNING` | `APPROVE_PLAN` | `EXECUTION` | `NotPaused`, `PlanHasSteps` | Plan must have ≥1 step; moves to step 0 |
| `PLANNING` | `CANCEL` | `CANCELLED` | `NotTerminal` | Abandons planning |
| `EXECUTION` | `NEXT_STEP` | `EXECUTION` | `NotPaused`, `HasNextStep` | Advances to the next step |
| `EXECUTION` | `PREVIOUS_STEP` | `EXECUTION` | `NotPaused`, `HasPreviousStep` | Returns to previous step |
| `EXECUTION` | `REQUEST_VALIDATION` | `VALIDATION` | `NotPaused` | Moves execution results to review |
| `EXECUTION` | `CANCEL` | `CANCELLED` | `NotTerminal` | Abandons in-progress execution |
| `VALIDATION` | `RECORD_VALIDATION` | `VALIDATION` | `NotPaused` | Records outcome (`PASSED` or `FAILED`) |
| `VALIDATION` | `SEND_BACK_TO_EXECUTION` | `EXECUTION` | `NotPaused` | Returns to step with revision note |
| `VALIDATION` | `COMPLETE` | `DONE` | `NotPaused`, `ValidationPassed` | **Requires** `PASSED` outcome |
| `VALIDATION` | `CANCEL` | `CANCELLED` | `NotTerminal` | Abandons validation |
| Any active | `PAUSE` | (Same) | `NotTerminal`, not paused | Freezes task without losing state |
| Any paused | `RESUME` | (Same) | `NotTerminal`, paused | Unfreezes task at exact stage/step |
| Any | `RESET` | `NONE` | (None) | Resets task state completely |

### Prohibited Jumps

- ❌ **PLANNING ➔ EXECUTION** without `APPROVE_PLAN` (cannot jump directly to doing exercises before agreeing on steps).
- ❌ **PLANNING ➔ DONE** (cannot mark done without execution and validation; cancel goes to `CANCELLED`).
- ❌ **EXECUTION ➔ DONE** (cannot bypass `VALIDATION`; skipping verification is strictly forbidden).
- ❌ **VALIDATION ➔ DONE** when outcome is `NOT_RUN` or `FAILED` (cannot complete task unless validation actually passed).
- ❌ **Work while paused** (cannot advance steps or request workouts while task is paused; must resume first).
- ❌ **Work after terminal** (cannot continue on a task that is `DONE` or `CANCELLED`).

---

## Two-Tier Enforcement Architecture

To guarantee the assistant cannot "skip" a stage or perform out-of-turn actions, Day 15 employs
two synchronized tiers:

```
User Message
    │
    ▼
[ Tier 1: Deterministic Code Guard (TaskStageGuard) ]
    │──▶ Violation detected?
    │       ├── YES: Return immediate refusal (0 LLM tokens, 0 network calls)
    │       └── NO: Pass through
    ▼
[ Tier 2: System Instruction Behavioral Rules (TaskStateRenderer) ]
    │──▶ "CRITICAL STAGE RULES: You are in stage X. NEVER do Y..."
    ▼
Gemini API / LLM Client
```

1. **Tier 1 — Deterministic Code Pre-Check (`TaskStageGuard.kt`)**:
   Runs inside `LlmAgent.handle()` before prompt assembly or network invocation. Inspects user
   intent against the current stage. If the user asks for immediate workouts during `PLANNING`,
   or asks to close the task during `EXECUTION`, `TaskStageGuard` detects the violation and returns
   a structured refusal immediately with 0 tokens spent.
2. **Tier 2 — Prompt Behavioral Constraints (`TaskStateRenderer.kt`)**:
   If the user's message is conversational or ambiguous, the prompt explicitly instructs the LLM
   what is forbidden for the active stage. For example, during `PLANNING`, the assistant is
   instructed never to provide full training sessions or mark steps completed until the user
   confirms the plan.

---

## Data Model & Files

- **`agent/task/TaskState.kt`**:
  - Added `TaskStage.CANCELLED`.
  - Added `ValidationOutcome` (`NOT_RUN`, `PASSED`, `FAILED`).
  - Added audit metadata: `validationOutcome`, `validationNote`, `planApproved`, `stageEnteredAt`,
    `cancellationReason`.
- **`agent/task/TaskTransitionTable.kt`**:
  - Declarative transition matrix with events (`TaskEvent`) and guards (`TransitionGuard`).
  - `check(state, event)` returns structured `TransitionRejection` if illegal.
  - `allowedEvents(state)` returns the exact set of legal operations for the UI.
- **`agent/task/TaskStageGuard.kt`**:
  - Unicode-aware regex classifier detecting premature execution, validation, or completion requests.
  - Generates clear, actionable refusal text explaining the current stage, why the request was
    blocked, and what action to take next.
- **`agent/task/TaskTransitionLog.kt`**:
  - `TaskTransitionRecord` storing timestamp, event, from/to stages, applied status, and notes.
  - `TaskTransitionLogStore` providing JSON persistence per branch (`task_transition_log.json`).
- **`agent/task/TaskStateMachine.kt`**:
  - Rewired to delegate transition legality and preconditions to `TaskTransitionTable`.
  - Added `recordValidation(state, outcome, note)` and `cancel(state, reason)`.
  - Added validation gate to `complete(state)`.
- **`agent/task/TaskStateRenderer.kt`**:
  - Expanded `stageRules()` with explicit stage-by-stage prohibitions.
  - Rendered `validationOutcome` in prompt context block.
- **`ChatScreen.kt` & `ChatViewModel.kt`**:
  - Dynamic button rendering based on `TaskTransitionTable.allowedEvents(state)`.
  - Forbidden action hints informing the user of the active gate.
  - Expandable Transition Journal showing full audit history of transitions and rejections.
  - Refused messages badged with `⛔ Переход заблокирован: <STAGE>` and tinted container.

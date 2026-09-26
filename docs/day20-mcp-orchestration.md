# Day 20 — Orchestration MCP (несколько серверов)

Day 16 дал агенту подключение к одному MCP-серверу, Day 17 — один реальный вызов инструмента,
Day 18 — фоновое/периодическое исполнение, Day 19 — композицию **нескольких инструментов одного**
сервера в пайплайн. Day 20 — это оркестрация: **несколько разных MCP-серверов** одновременно, где
агент сам решает, какой сервер и какой инструмент вызвать под конкретный запрос, и умеет
прогонять длинный флоу через все три сразу, в одном ходе.

## Три сервера в игре

1. **Удалённый** `mcp-server` (Firebase, wger.de) — `get_exercise_info`, `suggest_workout`, через
   [`KotlinSdkMcpGateway`](../app/src/main/java/com/example/geminichat/mcp/KotlinSdkMcpGateway.kt).
2. **`workout-digest`** (Day 18, локально) — `log_workout`, `get_workout_summary`, через
   [`LocalWorkoutMcpGateway`](../app/src/main/java/com/example/geminichat/mcp/LocalWorkoutMcpGateway.kt).
3. **`workout-planner`** (Day 19, локально) — `find_exercises`, `build_workout_plan`,
   `save_workout_plan`, через
   [`LocalWorkoutPlannerMcpGateway`](../app/src/main/java/com/example/geminichat/mcp/LocalWorkoutPlannerMcpGateway.kt).

## Почему не отдельный "God agent" с ручным if/else в цикле

Вариант "добавить в `McpToolCallingAgent` список гейтвеев и ветвление внутри цикла" изменил бы
уже протестированный Day 17/18/19 код и его контракт. Вместо этого — как и в Day 19 ("новый
контрактно-совместимый гейтвей, а не новый цикл") — сделан **новый `McpGateway`**:

[`CompositeMcpGateway`](../app/src/main/java/com/example/geminichat/mcp/CompositeMcpGateway.kt)
оборачивает список `NamedMcpGateway(name, serverUrl, gateway)` и реализует тот же контракт
`connect`/`listTools`/`callTool`/`close`, что и любой другой `McpGateway`:

- **`connect`** — подключается к каждому member'у отдельно (его собственным `serverUrl`). Если
  один сервер недоступен (например, удалённый wger не отвечает), это **не валит весь коннект** —
  сервер помечается недоступным и просто не даёт инструментов, остальные продолжают работать
  (graceful degradation — см. бизнес-кейс ниже).
- **`listTools`** — собирает инструменты со всех доступных серверов и запоминает, **какой
  сервер объявил какой инструмент** (`toolOwners: Map<String, NamedMcpGateway>`).
- **`callTool`** — маршрутизирует вызов на владельца инструмента по имени. Ошибка с понятным
  текстом, если инструмент не объявлен ни одним подключённым сервером.

`McpToolCallingAgent` (Day 17) не изменён ни на строчку — он просто получает
`CompositeMcpGateway` вместо одиночного гейтвея и работает как обычно.

## Новая персона

`AgentCatalog.ORCHESTRATOR_COACH` ("Orchestrator Coach (multi-server MCP)") — системная
инструкция явно перечисляет все 7 инструментов с привязкой к их серверу и явно запрещает путать
похожие по смыслу инструменты с разных серверов (например: сохранение плана никогда не трогает
удалённый сервер, а логирование тренировки никогда не трогает планировщик). Подключена в
`ChatViewModel.buildAgent` так же, как остальные MCP-персоны, только с
`CompositeMcpGateway(listOf(NamedMcpGateway("wger", ...), NamedMcpGateway("workout-digest", ...),
NamedMcpGateway("workout-planner", ...)))`.

## Бизнес-кейс: «Проверенный план тренировки» (Verified Workout Plan)

Пользователь: *«Проверь Squat по внешней базе, составь и сохрани тренировку на ноги на 30 минут
под названием "Leg Day", и залогируй её как выполненную»*.

Один вызов `agent.handle(...)` автоматически проходит через все три сервера:

1. **`get_exercise_info("Squat")`** — `wger` (удалённый). Верификация упражнения по реальной
   базе. Если сервер недоступен — агент не падает, а продолжает планирование на своих знаниях.
2. **`find_exercises(goal="legs", level="intermediate")`** — `workout-planner`.
3. **`build_workout_plan(exercises_json, goal, level, minutes=30)`** — `workout-planner`,
   принимает **дословно** результат шага 2.
4. **`save_workout_plan(name="Leg Day", plan_json)`** — `workout-planner`, принимает **дословно**
   результат шага 3.
5. **`log_workout(goal="legs", minutes=30)`** — `workout-digest` (другой сервер, не тот, что у
   шагов 2–4, хотя оба "про тренировки").

Это ровно тот сценарий, который проверяют тесты ниже: инструменты с разных серверов, в строго
определённом порядке, за один ход, без путаницы между похожими по смыслу инструментами.

## Тесты

- **`CompositeMcpGatewayTest`** — три сценария на уровне гейтвея, без Gemini/LLM:
  - агрегация инструментов со всех member'ов и маршрутизация `callTool` на правильного владельца;
  - недоступный сервер не валит `connect`/`listTools`, остальные member'ы продолжают работать;
  - вызов незнакомого инструмента (не объявленного ни одним подключённым сервером) кидает
    понятную ошибку.
- **`McpToolCallingAgentOrchestratorTest`** — главный тест оркестрации: скриптованный
  `ToolCallingLlmClient` эмитит 5 `requires_action`-ответов подряд (сценарий "Verified Workout
  Plan" выше) плюс финальный `completed`; три фейковых `McpGateway` (по одному на сервер) через
  `CompositeMcpGateway` записывают **каждый** вызов с указанием, на каком сервере он произошёл.
  Проверяется:
  - все 5 инструментов вызываются автоматически, по порядку, в рамках одного `handle(...)`;
  - реальная последовательность серверов — `wger → workout-planner → workout-planner →
    workout-planner → workout-digest` — совпадает с ожидаемой, то есть агент не спутал
    инструменты с похожими именами/смыслом между серверами.

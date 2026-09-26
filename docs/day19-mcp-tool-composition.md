# Day 19 — Композиция MCP-инструментов (Workout Plan Builder)

Day 17 дал агенту один вызов реального MCP-инструмента, Day 18 добавил фоновое/периодическое
исполнение. Day 19 — это композиция: **несколько** MCP-инструментов, соединённых в пайплайн, где
результат одного инструмента становится входом следующего, и вся цепочка выполняется
автоматически, без вмешательства пользователя между шагами.

## Почему не отдельный generic "search/summarize/saveToFile"

Задание допускает любую цепочку из нескольких MCP-инструментов — вместо игрушечного
`search`→`summarize`→`saveToFile` цепочка сделана под реальный бизнес-кейс приложения (Personal
Trainer): **построение и сохранение плана тренировки**.

## Пайплайн: "Workout Plan Builder"

1. **`find_exercises(goal, level)`** — шаг 1 ("получить данные"): ищет упражнения во встроенном
   офлайн-каталоге (`ExerciseCatalog`) по цели/группе мышц и уровню подготовки. Возвращает сырой
   JSON-список упражнений.
2. **`build_workout_plan(exercises_json, goal, level, minutes)`** — шаг 2 ("обработать"): берёт
   **точно тот** JSON, что вернул `find_exercises`, обрезает список под тайм-бюджет и назначает
   подходы/повторения по уровню (`WorkoutPlanBuilder`, чистая детерминированная функция — без
   второго вызова Gemini, чтобы поток данных было легко проверять в тестах). Возвращает
   структурированный JSON плана.
3. **`save_workout_plan(name, plan_json)`** — шаг 3 ("сохранить результат"): берёт **точно тот**
   JSON, что вернул `build_workout_plan`, и сохраняет его под именем в файл на диске
   (`SavedWorkoutPlanStore`, append-only JSON-файл, тот же подход, что и у `WorkoutLogStore` из
   Day 18). Возвращает подтверждение с id сохранённого плана.

Пример бизнес-сценария: пользователь пишет *«Составь тренировку на ноги на 30 минут и сохрани как
"Leg Day"»* — агент сам, за один ход (`agent.handle(...)`), вызывает все три инструмента по
очереди, каждый раз передавая следующему инструменту сырой результат предыдущего.

## Почему цепочка выполняется автоматически без новых изменений в агенте

[`McpToolCallingAgent`](../app/src/main/java/com/example/geminichat/agent/mcp/McpToolCallingAgent.kt)
(Day 17/18) уже реализует цикл function-calling: объявляет инструменты Gemini, при
`status == "requires_action"` выполняет запрошенный инструмент(ы) через `McpGateway.callTool`,
пересылает результат обратно и повторяет, пока модель не вернёт `"completed"` (до
`maxToolRounds`, сейчас 4). Этот цикл уже поддерживает произвольное число **последовательных**
вызовов инструментов за один `handle(...)` — Day 19 не меняет ни строчки в этом цикле, а просто
даёт ему три новых, совместимых по контракту `McpGateway` инструмента для композиции.

Системная инструкция новой персоны (`AgentCatalog.WORKOUT_PLAN_PIPELINE_COACH`) явно
проговаривает порядок и требование "передавай именно тот текст, что вернул предыдущий инструмент"
— это то, что заставляет модель реально форвардить `exercises_json`/`plan_json` между шагами, а не
придумывать JSON заново.

## Файлы

```
app/src/main/java/com/example/geminichat/
  agent/planner/
    ExerciseCatalog.kt        # статический каталог упражнений + чистая search(goal, level)
    WorkoutPlanBuilder.kt     # чистая build(exercises, goal, level, minutes) -> WorkoutPlan
    SavedWorkoutPlan.kt       # одна сохранённая запись (id, name, planJson, savedAt)
    SavedWorkoutPlanStore.kt  # JSON-файл, append/list (тот же подход, что WorkoutLogStore)
  mcp/
    LocalWorkoutPlannerMcpGateway.kt  # McpGateway с 3 инструментами пайплайна, без сети
```

`LocalWorkoutPlannerMcpGateway` реализует тот же контракт `McpGateway`, что и Day 18's
`LocalWorkoutMcpGateway` (`connect`/`listTools`/`callTool`/`close`) — только `connect` — это
no-op рукопожатие, а `callTool` диспатчит на `ExerciseCatalog`/`WorkoutPlanBuilder`/
`SavedWorkoutPlanStore` вместо сетевого вызова.

## Видимость на экране MCP (Call log)

Изначально локальные шлюзы (`LocalWorkoutMcpGateway` из Day 18, и новый
`LocalWorkoutPlannerMcpGateway`) не писали в общий `McpCallLog` — это делал только удалённый
`KotlinSdkMcpGateway`, поэтому на экране **MCP** («Call log») не было видно ни вызовов Day 18,
ни новой цепочки Day 19. Исправлено: `connect`/`listTools`/`callTool` у обоих локальных шлюзов
теперь тоже вызывают `McpCallLog.record(...)` в том же формате, что и `KotlinSdkMcpGateway`, —
все три шага пайплайна видны на экране MCP сразу, в реальном времени, без необходимости смотреть
`adb logcat`.

## Валидация hand-off между шагами

`build_workout_plan` и `save_workout_plan` не доверяют входным данным вслепую: перед обработкой
они пытаются распарсить `exercises_json`/`plan_json` как ожидаемую структуру (`List<Exercise>` /
`WorkoutPlan`) и возвращают `isError = true` с понятным сообщением, если модель передала не тот
JSON (например, придумала его сама вместо форварда). Это одновременно защита от галлюцинаций
модели и то, что делает пайплайн детерминированно тестируемым.

## Новая персона

`AgentCatalog.WORKOUT_PLAN_PIPELINE_COACH` ("Workout Plan Builder (tool pipeline)") подключена в
`ChatViewModel.buildAgent` так же, как `FITNESS_MCP_COACH`/`WORKOUT_DIGEST_COACH`, только со
своим гейтвеем `workoutPlannerMcpGateway`.

## Тесты

- **`ExerciseCatalogTest`** / **`WorkoutPlanBuilderTest`** — чистые функции: поиск по цели,
  фильтр bodyweight для новичков, обрезка под тайм-бюджет, назначение подходов/повторений.
- **`LocalWorkoutPlannerMcpGatewayTest`** — каждый инструмент по отдельности (включая ошибки на
  неизвестную цель / битый JSON), плюс сквозной прогон `find_exercises` → `build_workout_plan` →
  `save_workout_plan` с реальным (temp-dir) `SavedWorkoutPlanStore`, проверяющий, что сохранённый
  `planJson` совпадает с тем, что вернул `build_workout_plan`.
- **`McpToolCallingAgentPipelineTest`** — главный тест композиции: скриптованный
  `ToolCallingLlmClient` эмитит 3 `requires_action`-ответа подряд (по одному на инструмент) плюс
  финальный `completed`, а фейковый `McpGateway` записывает **каждый** вызов (не только
  последний). Проверяется:
  - все 3 инструмента вызываются автоматически, по порядку, в рамках одного `agent.handle(...)`;
  - аргумент `exercises_json` у `build_workout_plan` **дословно** равен результату
    `find_exercises`;
  - аргумент `plan_json` у `save_workout_plan` **дословно** равен результату
    `build_workout_plan`;
  - `response.toolCalls` содержит все 3 вызова по порядку с верным `resultText`.

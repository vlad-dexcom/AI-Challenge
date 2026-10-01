# Сценарий проверки первого RAG-запроса (Day 22)

## 1. Юнит-тесты (без сети)
```bash
./gradlew :rag:test :app:testDebugUnitTest
```
Ожидается: всё зелёное (в т.ч. `RagPipelineTest`, `ControlSetTest`, `RagAgentTest`).

## 2. CLI: один вопрос в двух режимах (нужен `GEMINI_API_KEY`)
```bash
./gradlew :rag:run --args='ask "What is on Day 2 of the beginner 3-day full-body program?"'
```
Ожидается: секция `WITHOUT RAG` — общий/выдуманный Day 2; секция `WITH RAG` — Trap-bar/kettlebell deadlift 3×5–8, overhead press,
lat pulldown, reverse lunge, dead bug со ссылками `[1]` и списком `Sources` (`13-beginner-program-design.md > …`).
`--mode rag` / `--mode no-rag` — один режим; `--k 6` — больше чанков.

Вопрос вне корпуса:
```bash
./gradlew :rag:run --args='ask "What is the current men raw deadlift world record?"'
```
Ожидается: без RAG — конкретные цифры; с RAG — «база знаний не содержит этой информации».

## 3. Контрольный набор
```bash
./gradlew :rag:run --args="rag-eval"
```
Ожидается: таблица на 10 вопросов, `Facts hit … RAG 34/34` (на живой модели возможны небольшие отличия), файлы
`rag/eval/control-results.json` (полные ответы) и `control-report.md`. Невалидный набор (нет файла/раздела) — ошибка до вызовов LLM.

## 4. Приложение
```bash
./gradlew :app:installDebug      # GEMINI_API_KEY в local.properties; индекс копируется в assets задачей copyRagIndex
```
1. Settings → Agent → **Knowledge Coach (RAG)**; появляются чипы *With RAG / Without RAG / Compare*.
2. *With RAG*, вопрос «What are the useful pull-up progressions if I cannot do a strict pull-up yet?» → ответ со ссылками `[n]`
   и блоком **Sources** (файл > раздел, сходство).
3. *Without RAG*, тот же вопрос → общий ответ, без Sources.
4. *Compare* → в одном сообщении разделы «Without RAG» и «With RAG» (+ Sources).
5. Вопрос вне корпуса (рекорд в становой) в режиме *With RAG* → честный отказ.
6. Без сети/ключа — понятное сообщение об ошибке, приложение не падает.
(Шаги приложения вручную не прогонялись при подготовке PR — только сборка и юнит-тесты.)

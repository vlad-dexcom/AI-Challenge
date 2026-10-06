# Сценарий проверки первого RAG-запроса (Day 22)

## 1. Юнит-тесты (без сети)
```bash
./gradlew :rag:core:test :rag:tools:test :web-console:test :app:testDebugUnitTest
```
Ожидается: всё зелёное (в т.ч. `RagPipelineTest`, `ControlSetTest`, `RagAgentTest`).

## 2. CLI: один вопрос в двух режимах (нужен `GEMINI_API_KEY`)
```bash
./gradlew :rag:tools:run --args='ask "What is on Day 2 of the beginner 3-day full-body program?"'
```
Ожидается: секция `WITHOUT RAG` — общий/выдуманный Day 2; секция `WITH RAG` — Trap-bar/kettlebell deadlift 3×5–8, overhead press,
lat pulldown, reverse lunge, dead bug со ссылками `[1]` и списком `Sources` (`13-beginner-program-design.md > …`).
`--mode rag` / `--mode no-rag` — один режим; `--k 6` — больше чанков.

Вопрос вне корпуса:
```bash
./gradlew :rag:tools:run --args='ask "What is the current men raw deadlift world record?"'
```
Ожидается: без RAG — конкретные цифры; с RAG — «база знаний не содержит этой информации».

## 3. Контрольный набор
```bash
./gradlew :rag:tools:run --args="rag-eval"
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

## 5. Веб-чат (Day 22+)
```bash
./gradlew :web-console:run      # http://localhost:8080, GEMINI_API_KEY в local.properties
```
1. Открывается вкладка **Chat**; в настройках индекс `structure`, topK 4, в списке 10 контрольных вопросов; под настройками надпись, что история не используется для ответа.
2. Режим *With RAG*, вопрос c02 (pull-up progressions) → ответ, список «Sources» (`файл > раздел`, score), раскрывающийся «Retrieved chunks (4)» с текстом и score.
3. *Without RAG*, тот же вопрос → общий ответ без источников.
4. *Compare*, тот же вопрос → две колонки (Without RAG слева, With RAG справа), у каждой своя задержка.
5. Вопрос c09 (мировой рекорд) в *With RAG* → отказ модели и жёлтое «knowledge base has no relevant information»; в *Compare* слева — уверенные цифры.
6. Пустое сообщение → «Type a question first»; topK = 0 → ошибка 400 в пузыре. Без ключа (запустить `ui` без `GEMINI_API_KEY`) → предупреждение вверху и «GEMINI_API_KEY is not set» в ответе.
7. Enter отправляет, Shift+Enter — перенос; «Clear chat» очищает ленту; вкладка «Chunk visualiser» работает как раньше.
8. Markdown: вопрос c01 в *Compare* → заголовки/списки/таблицы/код отрисованы в обеих колонках, `[1]` читаемы, блоки кода и таблицы прокручиваются; в «Retrieved chunks» текст чанка тоже в Markdown. `node web-console/src/test/js/markdown.test.js` → `markdown tests OK`.


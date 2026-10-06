# Сценарий проверки реранкинга, фильтра и rewrite (Day 23)

## 1. Юнит-тесты (без сети)
```bash
./gradlew :rag:core:test :rag:tools:test :web-console:test :app:testDebugUnitTest
```
Ожидается зелёное: `RerankingTest` (фильтр, эвристический и LLM-реранкер, rewrite и откаты, стадии конвейера, «ничего не прошло» без вызова LLM,
sweep-метрики, кэши, `Judge`), `ChatApiTest` (поля фильтра/реранка/rewrite и `trace`), `RagAgentTest` (фильтр в приложении).

## 2. Sweep порога и topK (нужен `GEMINI_API_KEY`, один раз эмбеддит 40 вопросов; потом из кэша `rag/cache/`)
```bash
./gradlew :rag:tools:run --args="sweep --threshold 0.65 --before 10 --after 4"
```
Ожидается: блок «Top-1 cosine similarity» (min в корпусе ≈ 0.698, вне корпуса до 0.596 и 0.731 у c10), таблицы по threshold/before/after и
`rag/eval/sweep.{md,json}` на 144 строки; при threshold 0.65 — OOC отсечено 100%, зря отсечено 0%.

## 3. Один вопрос со стадиями
```bash
./gradlew :rag:tools:run --args='ask "What is the current men raw deadlift world record?" --mode rag --filter on --rerank on --before 10 --after 4 --threshold 0.65'
```
Ожидается: «Retrieved 10 -> filtered 0 -> reranked 0» и ответ «Not enough information: the knowledge base does not cover…» (LLM не вызывался).
```bash
./gradlew :rag:tools:run --args='ask "Сколько белка нужно для набора мышц?" --mode rag --filter on --rerank on --rewrite on'
```
Ожидается: `Search query:` на английском (protein/muscle…), источники из `08-nutrition…`, ответ на русском.

## 4. Сравнение режимов (реальный Gemini; первый прогон ~25 мин, повторный — из кэша `rag/cache/llm`)
```bash
./gradlew :rag:tools:run --args="modes-eval --threshold 0.65 --before 10 --after 4"
./gradlew :rag:tools:run --args="modes-eval --threshold 0.65 --before 10 --after 4 --questions rag/eval/questions-ru.json --report rag/eval/ru"
```
Ожидается: таблицы A–G как в `docs/day23-reranking.md` (цифры LLM-режимов могут слегка отличаться); файлы `modes-report.md/json`.

## 5. Веб-чат
```bash
./gradlew :web-console:run      # http://localhost:8080
```
1. Вкладка **Chat**: в настройках topK after 4, topK before 10, threshold 0.65, галочки filter и rerank включены, rewrite выключен.
2. С RAG, вопрос c02 (pull-up progressions) → ответ, источники; раскрыть **Pipeline debug**: таблицы «Retrieved (cosine order)», «After filter», «Final (reranked)» с cosine и rerank score, `1 LLM call(s)`.
3. Включить *query rewrite*, спросить по-русски «Сколько белка нужно для набора мышц?» → в debug «Rewritten query: …» на английском, `2 LLM call(s)`, ответ по-русски.
4. Вопрос вне корпуса («Who won the 2018 football World Cup?») → жёлтая плашка «Nothing passed the relevance filter…», 0 LLM-вызовов.
5. Снять все три галочки → поведение Day 22 (top-K по косинусу).
6. Некорректные настройки (topK after > before, threshold > 1) → понятная ошибка 400.

## 6. Приложение
`./gradlew :app:installDebug`, агент **Knowledge Coach (RAG)**: вопрос из корпуса отвечает с источниками как раньше; вопрос вне корпуса («Кто выиграл чемпионат мира 2018?»)
в режиме *With RAG* → сообщение «Not enough information…» без обращения к модели и «Sources: none retrieved».
(Шаги приложения вручную не прогонялись — только сборка и юнит-тесты.)

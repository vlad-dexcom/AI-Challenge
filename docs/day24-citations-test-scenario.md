# Сценарий проверки цитат и режима «не знаю» (Day 24)

## 1. Юнит-тесты (без сети)
```bash
./gradlew :rag:core:test :rag:tools:test :web-console:test :app:testDebugUnitTest
```
Ожидается зелёное: `CitationsTest` (парсер и ремонт JSON, нормализация, подделанная цитата, неверный/несуществующий chunk id, пробелы/регистр/тире,
русский ответ с английскими цитатами, три пути «не знаю», повтор со строгим промптом, конвейер), `ChatApiTest` (поле `structured`, «не знаю», отклонённые цитаты),
`RagAgentTest` (блоки Sources/Quotes и «не знаю» в приложении).

## 2. Один вопрос в CLI (нужен `GEMINI_API_KEY`)
```bash
./gradlew :rag:tools:run --args='ask "Сколько белка нужно для набора мышц?" --mode rag --filter on --rerank on'
```
Ожидается: русский ответ, `Sources (verified)` с chunk id, цитаты `OK` на английском, `Verification: quotes N/N verified`.
```bash
./gradlew :rag:tools:run --args='ask "What is the current men raw deadlift world record?" --mode rag --filter on --rerank on'
```
Ожидается: «I don't know: …», ближайшие темы «(not an answer…)», один уточняющий вопрос, LLM не вызывался. Тот же вопрос по-русски — «Не знаю: …».
Случай выше порога: `ask "Which specific brand of protein powder does the knowledge base recommend buying?" --mode rag --filter on --rerank on` → «I don't know» (`MODEL_UNANSWERABLE`).

## 3. Веб-чат
```bash
./gradlew :web-console:run   # http://localhost:8080, вкладка Chat, With RAG, включить Filter + Rerank
```
Вопрос из списка (c07) → ответ, Sources (chunk_id, score), цитаты с ✓, «Verification report». Вопрос c09/c10 → синяя карточка «I don't know» с уточняющим вопросом и без источников.
Режим Without RAG → пометка «No sources…».

## 4. Оценка на 12 вопросах (реальный Gemini; повтор — из кэша)
```bash
./gradlew :rag:tools:run --args="citations-eval"
```
Ожидается: `rag/eval/citations-report.{md,json}`; «answered 9/12», источники 9/9, цитаты 9/9 дословные, «IDK exactly on out-of-corpus: 12/12». LLM-вердикты могут немного плавать
между прогонами без кэша (`--no-cache`).

## 5. Приложение
Агент **Knowledge Coach (RAG)**, режим With RAG: под ответом **Sources** и **Quotes** (✓), для вопроса вне базы — «Не знаю/I don't know» и уточняющий вопрос.

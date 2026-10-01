# Сценарий проверки индексации документов (Day 21)

## 1. Юнит-тесты (без сети)
```bash
./gradlew :rag:test
```
Ожидается: все тесты зелёные (чанкеры, индекс/хранилище, отчёт, `GeminiEmbeddingClient` на MockEngine:
батчинг, `taskType`, ретраи на 429, отсутствие ретраев на 400).

## 2. Офлайн-индексация
```bash
./gradlew :rag:run --args="index --embedder offline"
```
Ожидается: `17 documents, ~22000 words`, созданы `rag/index/fixed.json` и `rag/index/structure.json`
(≈200 чанков каждый). В JSON у каждого чанка есть `chunkId`, `source`, `title`, `section`,
`startOffset`, `endOffset`, `embedding`; в `meta` — модель, размерность, стратегия, дата.

## 3. Сравнение
```bash
./gradlew :rag:run --args="compare --embedder offline"
```
Ожидается таблица статистики (у `structure` доля «обрыв посреди предложения» заметно ниже, чем у
`fixed`) и 8 sample-запросов; файл `rag/index/comparison-report.md` обновлён.
Несовпадение `--embedder` с моделью индекса даёт понятную ошибку.

## 4. Реальные эмбеддинги Gemini (нужен ключ)
```bash
export GEMINI_API_KEY=...
./gradlew :rag:run --args="index --embedder gemini"
./gradlew :rag:run --args="compare --embedder gemini"
```
Ожидается: `meta.embeddingModel = gemini-embedding-001`, `dimension = 768`; sample-запросы чаще
возвращают нужную статью на первых местах, чем офлайн-эмбеддер.

## 5. Приложение не сломалось
```bash
./gradlew :app:testDebugUnitTest
```

# Сценарий проверки индексации документов (Day 21)

## 1. Юнит-тесты (без сети)
```bash
./gradlew :rag:core:test :rag:tools:test :web-console:test
```
Ожидается: все тесты зелёные (чанкеры, индекс/хранилище, отчёт, `GeminiEmbeddingClient` на MockEngine:
батчинг, `taskType`, ретраи на 429, отсутствие ретраев на 400).

## 2. Офлайн-индексация
```bash
./gradlew :rag:tools:run --args="index --embedder offline"
```
Ожидается: `17 documents, ~22000 words`, созданы `rag/index/fixed.json` и `rag/index/structure.json`
(≈200 чанков каждый). В JSON у каждого чанка есть `chunkId`, `source`, `title`, `section`,
`startOffset`, `endOffset`, `embedding`; в `meta` — модель, размерность, стратегия, дата.

## 3. Сравнение
```bash
./gradlew :rag:tools:run --args="compare --embedder offline"
```
Ожидается таблица статистики (у `structure` доля «обрыв посреди предложения» заметно ниже, чем у
`fixed`) и 8 sample-запросов; файл `rag/index/comparison-report.md` обновлён.
Несовпадение `--embedder` с моделью индекса даёт понятную ошибку.

## 4. Реальные эмбеддинги Gemini (нужен ключ)
```bash
export GEMINI_API_KEY=...
./gradlew :rag:tools:run --args="index --embedder gemini"
./gradlew :rag:tools:run --args="compare --embedder gemini"
```
Ожидается: `meta.embeddingModel = gemini-embedding-001`, `dimension = 768`; sample-запросы чаще
возвращают нужную статью на первых местах, чем офлайн-эмбеддер. Индексы пишутся в `rag/index/`
(офлайн — в `rag/index-offline/`).

### 4a. Eval
```bash
./gradlew :rag:tools:run --args="eval --embedder offline"   # без ключа
./gradlew :rag:tools:run --args="eval --embedder gemini"    # нужен ключ и gemini-индексы
```
Ожидается: таблица hit@1/3/5, MRR, средние top-1 score (in-corpus выше out-of-corpus), список промахов;
файл `rag/eval/report-<embedder>.md`. Ориентиры: gemini hit@3 ≈ 96%, offline ≈ 70–77%. Эмбеддер, не совпадающий с
моделью индекса, даёт понятную ошибку. В UI: кнопки «Eval fixed/structure» в панели Index inspection.

## 5. Визуализатор чанков (ручной)
```bash
./gradlew :web-console:run
```
1. Откройте http://localhost:8080, выберите файл `01-squat-technique.md` в списке — текст загрузится и
   сразу разобьётся.
2. Слева `fixed`, справа `structure`: блоки чередуют цвета, красные полоски — начала чанков,
   штриховка — overlap (у fixed), `!` — обрыв посреди предложения (почти у каждого fixed-чанка, редко у structure).
3. Наведите курсор/кликните на блок — внизу метаданные (chunk_id, source, title, section, offsets, length, strategy, флаги).
4. Поставьте overlap = 0 — штриховка пропадает; overlap ≥ size — сообщение об ошибке. Поменяйте
   min/max — число structure-чанков меняется. Сводка совпадает по смыслу с `compare`.
5. Вставьте свой markdown в поле ввода (очистив выбор файла) и нажмите Chunk.
6. Блок поиска: введите `how much protein per day?`, выберите стратегию, нажмите Search — top-k
   со score и секциями (с офлайн-индексом — лексический поиск, не семантический).

### 5a. Инспекция индекса и ping эмбеддера (в том же UI)
1. Нажмите **Inspect fixed index** — ожидается «✅ all checks passed», путь/размер/mtime, meta
   (модель `gemini-embedding-001`, dim 768) и 3 примера чанков с первыми 5 значениями вектора.
2. Повторите для **structure**.
3. Проверка устаревания: измените любой файл в `rag/corpus/` (например, добавьте слово), снова нажмите
   Inspect — проверка «up to date with the corpus» станет ❌ (text hash DIFFERS). Верните файл (`git checkout rag/corpus`).
4. Временно переименуйте `rag/index/fixed.json` — Inspect даёт сообщение «No saved index…» (404). Верните файл.
5. **Embedder ping** — для gemini-индекса: длина 768, норма ≈1, задержка, cosine связанной пары > несвязанной,
   вердикт PASS. Для офлайн-индекса — длина 256 и красная пометка «lexical-only». Для Gemini-индекса без ключа — ошибка про `GEMINI_API_KEY`; с ключом
   (после `index --embedder gemini`) — вердикт PASS при related > unrelated.

## 6. Приложение не сломалось
```bash
./gradlew :app:testDebugUnitTest
```

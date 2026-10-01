# Day 21 — Индексация документов (RAG, часть 1)

Неделя RAG: Day 21 — индексация, Day 22 — первый RAG-запрос (вопрос → поиск чанков → промпт → LLM),
Day 23 — реранкинг/фильтрация. Сегодня строим только **пайплайн индексации**: документы → чанки →
эмбеддинги → локальный индекс (JSON). `Retriever`/`Reranker` намеренно не реализованы.

## Модуль `:rag`

Чистый Kotlin/JVM-модуль без Android-зависимостей (`include(":rag")`, `:app` зависит от `:rag`).
Ktor/kotlinx-serialization той же версии, что и в `:app`.

| Файл | Роль |
|---|---|
| `Chunk.kt` | `Document`, `Chunk` (chunkId, source, title, section, text, startOffset/endOffset, strategy) |
| `Chunker.kt` | интерфейс `Chunker` + разбор markdown-заголовков (код-блоки игнорируются) |
| `FixedSizeChunker.kt` | стратегия 1: окно `size` символов с `overlap` |
| `StructureChunker.kt` | стратегия 2: по заголовкам; мелкие секции склеиваются, крупные режутся по абзацам/предложениям |
| `EmbeddingClient.kt` | интерфейс + `HashingEmbeddingClient` (офлайн, детерминированный, только лексика) |
| `GeminiEmbeddingClient.kt` | Ktor REST `models/gemini-embedding-001:batchEmbedContents` |
| `VectorIndex.kt`, `IndexStore.kt` | индекс в памяти (косинус, brute-force) и JSON-хранилище |
| `Indexer.kt` | `CorpusLoader` + `Indexer` (корпус → чанки → эмбеддинги → индекс) |
| `Comparison.kt` | статистика чанков + sample-запросы, markdown-отчёт |
| `Main.kt` | CLI: `index`, `compare` |

### Gemini embeddings
- Модель `gemini-embedding-001`, заголовок `x-goog-api-key`, батчи ≤100 запросов.
- `taskType`: `RETRIEVAL_DOCUMENT` для чанков, `RETRIEVAL_QUERY` для вопросов.
- `outputDimensionality=768`; векторы нормализуются на клиенте (API нормализует только полные 3072).
- Ретраи с экспоненциальной паузой на 429/5xx и сетевые ошибки; прочие 4xx — сразу ошибка.
- Ключ: env `GEMINI_API_KEY` или `local.properties` (в git не попадает).

### Формат индекса (`rag/index/<strategy>.json`)
```
{ "meta": { embeddingModel, dimension, strategy, strategyParams, createdAt, sourceCorpus, documentCount, chunkCount },
  "chunks": [ { chunkId, source, title, section, startOffset, endOffset, strategy, text, embedding:[...] } ] }
```
Векторы округлены до 5 знаков. Индекс можно искать только тем же эмбеддером (`meta.embeddingModel`).

## Корпус
`rag/corpus/` — 17 статей на английском (техника упражнений, программирование силовых, гипертрофия,
сон/восстановление, питание, разминка, травмы, кардио, добавки и т.д.): ~22 000 слов ≈ 44 страницы.

## Запуск
```bash
export GEMINI_API_KEY=...            # или строка в local.properties
./gradlew :rag:run --args="index"    # rag/index/fixed.json + structure.json
./gradlew :rag:run --args="compare"  # печатает и пишет rag/index/comparison-report.md
./gradlew :rag:test
```
`--embedder offline` — без сети и ключа (только лексика, не семантическая модель).

## Визуализатор чанков (UI)

```bash
./gradlew :rag:run --args="ui"        # http://localhost:8080 (--port, --corpus, --out)
```
Локальная страница (JDK `com.sun.net.httpserver`, loopback, одна статическая HTML+JS, без сборки):
- вход: вставленный текст/markdown или файл из `rag/corpus/`;
- параметры: fixed size/overlap, structure min-merge/max-split (дефолты = дефолты чанкеров);
- две стратегии рядом: чанки подсвечены поверх исходного текста чередующимися цветами, красная
  полоска — начало чанка, штриховка — зона overlap, `!` — чанк обрывается посреди предложения;
  hover/клик показывает chunk_id, source, title, section, offsets, длину, strategy и флаги
  mid-sentence start/end;
- сводка по каждой стратегии — те же `ChunkStats`, что и в `compare`;
- поиск: запрос → косинус по сохранённому индексу выбранной стратегии (`rag/index/<strategy>.json`),
  top-k со score. Эмбеддер подбирается по `meta.embeddingModel` индекса: офлайн-hashing или Gemini
  (нужен `GEMINI_API_KEY`). Поиск идёт по сохранённому индексу корпуса, а не по вставленному тексту.

### Инспекция индекса и проверка эмбеддера
Панель «Index inspection» в UI (кнопки на каждую стратегию):
- `GET /api/index/inspect?strategy=fixed|structure` (404, если индекса нет) — путь/размер/mtime файла, `meta`,
  число чанков и чек-лист pass/fail: файл парсится; `chunkCount` == `meta.chunkCount`; метаданные
  (chunk_id, source, title, offsets, text) заполнены; у чанков есть `section`; chunk_id уникальны; длина
  векторов == `meta.dimension`; нет NaN/Infinity; нет нулевых векторов; L2-нормы min/avg/max и нормализованность;
  **актуальность** — корпус перечанкуется той же стратегией/параметрами (из `meta.strategyParams`),
  сравниваются число чанков/документов и SHA-256 id+офсетов+текста (устаревший индекс → FAIL). Плюс 3 примера
  чанков (id, section, 100 символов, первые 5 значений вектора). Логика — `IndexInspector.kt`.
- `GET /api/embedder/ping?strategy=...` — эмбеддит 3 фразы тем же эмбеддером, что в `meta` индекса
  (офлайн-hashing или Gemini при наличии ключа, иначе понятная ошибка 400 «no key»); показывает модель, длину
  вектора, норму, задержку, cosine для связанной пары (protein/muscle) и несвязанной. Офлайн-эмбеддер явно помечен
  как **lexical-only** (вердикт о семантике не выносится); для Gemini — PASS, если related > unrelated.

API (`UiServer.kt`): `GET /api/files`, `GET /api/file?name=`, `POST /api/chunk`, `POST /api/search`, `GET /api/index/inspect`, `GET /api/embedder/ping`;
ошибки — JSON `{"error": ...}` (400 на некорректные параметры, 404 на неизвестный файл/индекс;
path traversal отклоняется). Тесты: `UiServerTest`, `IndexInspectorTest` (хороший индекс, неверная размерность, NaN/нулевой вектор, пропущенные метаданные/дубликаты, устаревший индекс, ping, эндпоинты).

## Сравнение стратегий

> **Важно.** В репозитории закоммичены индексы и отчёт, собранные **офлайн-эмбеддером**
> (`offline-hashing-bow-256`), т.к. при разработке не было Gemini-ключа. Реальные эмбеддинги Gemini
> **не проверялись** (клиент покрыт только тестами с MockEngine). Метрики чанков (ниже) от эмбеддингов
> не зависят; результаты поиска — иллюстративные. Чтобы получить настоящие: запустить `index` и
> `compare` с ключом.

Параметры: fixed — 800/100, structure — max 1500 / min 300. Всего 17 документов.

| Метрика | fixed | structure |
|---|---|---|
| Чанков | 198 | 213 |
| Средний размер, симв. | 770 | 630 |
| Мин / макс | 109 / 800 | 301 / 1475 |
| Заканчивается посреди предложения | 88.9% | 9.9% |
| Начинается посреди предложения | 84.8% | 0.0% |
| Захватывает больше одной секции | 94.4% | 18.8% |

Выводы:
- **fixed** предсказуем по размеру и прост, но почти каждый чанк обрезан по краям и смешивает
  несколько секций — в промпт попадает «шум» и оборванные фразы.
- **structure** даёт самодостаточные чанки с осмысленным `section` (путь заголовков). Платой
  служит разброс размеров (301–1475) и то, что у 18.8% чанков есть вторая секция — это результат
  склейки мелких секций.
- Поиск на 8 sample-запросах (офлайн-эмбеддер): top-1 попадание в нужную статью 6/8 у обеих
  стратегий; различия на отдельных запросах см. в `rag/index/comparison-report.md`. На такой
  выборке и с лексическим эмбеддером разницу в качестве поиска делать выводом нельзя —
  перепроверить с Gemini на Day 22.
- Рекомендация для Day 22: `structure` по умолчанию (чистые границы и метаданные секции для цитат),
  `fixed` оставить как baseline.

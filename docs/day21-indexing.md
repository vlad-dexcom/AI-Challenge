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
./gradlew :rag:run --args="index"    # rag/index/fixed.json + structure.json (gemini)
./gradlew :rag:run --args="compare"  # печатает и пишет rag/index/comparison-report.md
./gradlew :rag:run --args="eval"     # retrieval eval, rag/eval/report-gemini.md
./gradlew :rag:test
```
`--embedder offline` — без сети и ключа (только лексика, не семантическая модель); по умолчанию пишет/читает
`rag/index-offline/`, а gemini — `rag/index/` (переопределяется `--out`). Без ключа `--embedder` по умолчанию = offline.

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

> Индексы в `rag/index/` собраны **реальными эмбеддингами Gemini** (`gemini-embedding-001`, 768 dims;
> видно в `meta.embeddingModel`). Копия на офлайн-эмбеддере (только лексика) лежит в `rag/index-offline/`
> — для запуска без ключа и сравнения. Метрики чанков от эмбеддингов не зависят.

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
- Поиск на 8 sample-запросах (Gemini): top-1 попадание в нужную статью **fixed 7/8, structure 8/8**
  (офлайн-hashing: 6/8 у обеих). Подробности — `rag/index/comparison-report.md`.
- Рекомендация для Day 22: `structure` по умолчанию (чистые границы и метаданные секции для цитат),
  `fixed` оставить как baseline.

## Retrieval eval (`eval`)

`rag/eval/questions.json` — 30 вопросов по корпусу (английские, как и корпус): 15 `direct` (слова из текста),
11 `paraphrase` (почти без лексического пересечения), 4 `out_of_corpus` (нет ожидаемого источника, исключены из
hit@k/MRR). Для каждого in-corpus вопроса указан `expectedSource` и `expectedSection` — ключевое слово, которое
должно встречаться в заголовке этого файла (проверяется автоматически, тест `EvalTest` валидирует файл против корпуса).

```bash
./gradlew :rag:run --args="eval"                                   # оба индекса, эмбеддер = gemini при наличии ключа
./gradlew :rag:run --args="eval --embedder offline --k 5 --strategy structure"
```
Для каждого индекса: hit@1/3/5, MRR (по чанкам; hit = среди top-k есть чанк из ожидаемого файла), section hit@3
(чанк ещё и из секции с ключевым словом), hit@3 отдельно для direct/paraphrase, средний top-1 score для
in-corpus и out-of-corpus, таблица по вопросам и список промахов. Отчёт: `rag/eval/report-<embedder>.md`.
В UI — кнопки «Eval fixed/structure» (`GET /api/eval?strategy=`).

### Результаты (26 in-corpus + 4 out-of-corpus, k=5)

| Метрика | gemini fixed | gemini structure | offline fixed | offline structure |
|---|---|---|---|---|
| hit@1 | 92.3% | 88.5% | 50.0% | 53.8% |
| hit@3 | 96.2% | 96.2% | 69.2% | 76.9% |
| hit@5 | 96.2% | 100% | 76.9% | 84.6% |
| MRR | 0.942 | 0.931 | 0.605 | 0.654 |
| section hit@3 | 73.1% | 92.3% | 11.5% | 53.8% |
| hit@3 paraphrase | 90.9% | 90.9% | 63.6% | 63.6% |
| avg top-1 in-corpus | 0.727 | 0.738 | 0.334 | 0.336 |
| avg top-1 out-of-corpus | 0.532 | 0.524 | 0.174 | 0.187 |
| min in-corpus / max out-of-corpus top-1 | 0.684 / 0.568 | 0.698 / 0.556 | 0.189 / 0.200 | 0.185 / 0.218 |

Выводы:
- Gemini резко лучше лексического эмбеддера, особенно на перефразированных вопросах и по hit@1.
- По попаданию в **файл** стратегии почти равны (фиксированные размеры: hit@1 выше на 1 вопрос; structure
  догоняет к k=5). Но по **секции** structure заметно лучше (92% против 73% section hit@3): его чанки
  выровнены по заголовкам, и `section` в метаданных реально указывает на ответ.
- Единственный промах fixed на k=5 — q14 («bar speed flat… reduce training stress», ожидался deload в 05): топ занял
  чанк про overreaching из `07-sleep-and-recovery.md`, то есть близкий по смыслу, но не тот файл. У structure этот
  вопрос на 5-й позиции.
- Для порога на Day 23: у Gemini все in-corpus top-1 ≥ 0.68, а out-of-corpus top-1 ≤ 0.57, поэтому порог около
  0.6 разделяет эти 30 вопросов; на такой маленькой выборке это ориентир, а не гарантия. Для offline-эмбеддера
  диапазоны перекрываются (0.19 vs 0.20), порог не работает.
- Ограничения: 30 вопросов, оценка на уровне файла, вопросы писались автором корпуса.

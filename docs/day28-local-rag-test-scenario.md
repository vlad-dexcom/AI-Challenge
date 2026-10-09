# День 28 — сценарий проверки (локальный RAG)

Предусловия: Ollama ≥ 0.40 запущена, скачаны `gemma4:26b-a4b-it-qat`, `gemma4:31b-mlx`, `embeddinggemma-2:270m-bf16-text`; для облачных строк — `GEMINI_API_KEY` в `local.properties`.

1. **Индекс.** `./gradlew :rag:tools:run --args="index --provider ollama"`. Ожидание: `Embedder: ollama:embeddinggemma-2:270m-bf16-text+prompts (768 dims)`, `structure: 213 chunks -> rag/index-local/structure.json`.
2. **Retrieval.** `... --args="eval --provider ollama"` (отчёт `rag/eval/report-ollama-…md`) и `sweep --provider ollama --report /tmp/sweep-local`: порог 0.65 даёт 100% отсечения вопросов вне корпуса и 0% ошибочных отказов.
3. **CLI, полностью локально.** `ask "Что такое NEAT и почему он падает на диете?" --provider ollama --mode rag --filter on --rerank on --rewrite on`. Ожидание: поисковый запрос на английском, ответ на русском, `Quotes: OK`, `quotes 1/1 verified`.
4. **Без интернета.** Запустить то же под `sandbox-exec -p '(version 1)(allow default)(deny network*)(allow network* (remote ip "localhost:*"))(allow network* (local ip "localhost:*"))(allow network-bind)(allow network-inbound)'` без `GEMINI_API_KEY`:
   `curl https://generativelanguage.googleapis.com/` внутри даёт `000`, `ask` и веб-консоль (`web-console/build/install/web-console/bin/web-console --port 8090`) работают.
5. **Веб-консоль.** `./gradlew :web-console:run`, вкладка Chat:
   - Answer with → Local, режим «Chat with task memory»: «I want a 3-day strength program for a beginner. I have a bad knee and no gym.» → панель памяти: цель и ограничения `injury`, `equipment`.
   - Answer with → **Compare: Local vs Cloud**, режим With RAG: «How much protein per kilogram should I eat to build muscle?» → две колонки заполняются независимо (параллельно), сверху строка сравнения (задержка, вызовы LLM, общие источники, проверенные цитаты, «не знаю»).
   - Answer with → Hybrid: локальный retrieval + ответ Gemini; без `GEMINI_API_KEY` — предупреждение.
   - Остановить Ollama (`brew services stop ollama`): локальная колонка показывает ошибку, облачная работает.
6. **Бенчмарк.** При запущенной веб-консоли: `python3 rag/eval/local-rag/benchmark.py` (≈ 1 час) → `python3 rag/eval/local-rag/report.py`. Ожидание: таблица как в `day28-local-rag.md`; прогон можно продолжать после сбоя ключом `--resume`.
7. **Автотесты.** `./gradlew check` — зелёный (клиенты Ollama, `LlmProvider`, маршрутизация провайдеров, параллельные запросы, откат без `format: "json"`).

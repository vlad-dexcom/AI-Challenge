# День 29 — сценарий проверки (оптимизация локальной LLM)

Предусловия: Ollama ≥ 0.40, модели `gemma4:26b-a4b-it-qat`, `gemma4:31b-mlx`, `embeddinggemma-2:270m-bf16-text`; `./gradlew :web-console:installDist`.

1. **Тесты.** `./gradlew check` — зелёный (настройки и статистика `OllamaTuning`, `PromptProfile` с побайтовой проверкой исходных промптов, откат `token repeat limit`, определение языка, `LlmProvider`).
2. **Модель из Modelfile.** `ollama create trainer-rag-fast -f rag/local-model/Modelfile`; `ollama show trainer-rag-fast --parameters` → `num_ctx 8192`, `temperature 0.3`; короткий запрос возвращает ответ (≈40 tok/s).
3. **Профиль промптов в CLI.** `... --args='ask "Что такое NEAT и почему он падает на диете?" --provider ollama --mode rag --filter on --rerank on --rewrite on'` — по умолчанию действуют профиль `local` и рекомендованные настройки; `--prompt-profile default --ollama-tuning none` возвращает исходное поведение.
4. **Частичный вопрос.** В веб-консоли (Answer with → Local) спросить: «For zone 2 cardio, what intensity cues does the corpus give, and what exact heart-rate numbers should I use?» — ответ про подтверждённую часть с цитатой и фразой, чего корпус не покрывает (с `--prompt-profile default` будет «не знаю»).
5. **Статистика вызовов.** `curl localhost:8080/api/llm/stats` после нескольких вопросов — токены и скорость по назначению (`cited`, `rewrite`, `memory`, `summary`); `POST /api/llm/stats/reset` обнуляет.
6. **Бенчмарк «до/после».** `python3 rag/eval/local-rag/optimize.py --configs baseline-fast,final-fast --sets tuning,holdout --repeats 3` (≈ 40 мин, Ollama не нагружать) → `python3 rag/eval/local-rag/optimize_report.py baseline-fast final-fast --profile`. Ожидание: таблицы раздела «Итог» документа; остальные конфигурации — в `optimization/*.json`.
7. **Размеры промптов в чате.** `python3 rag/eval/local-rag/session_profile.py` — таблица: ответ с цитатами ≤ ~2,1K токенов, пик ответа ≈ 680 токенов.
8. **Окно «до / после».** В веб-консоли Answer with → *Compare: before vs after optimization*: панель с таблицей параметров и итогами бенчмарка; демо-вопрос `c07` — слева «не знаю», справа ответ с цитатой; `h23` (частичный) — слева отказ, справа ответ на подтверждённую часть и пометка, чего корпус не покрывает; `c09` (вне корпуса) — оба отказываются; под колонками строка сравнения.

# Day 30 — test scenario

Use `B=http://<lan-ip>:8088` and `K=<device key>` from `scripts/private-ai.sh keygen`.

## 0. Without a model (fake upstream)
1. `python3 scripts/fake_ollama.py` and a gateway config with `upstreamUrl: http://127.0.0.1:11435`, `models: ["fake:latest"]`.
2. `./gradlew :ai-gateway:test :core:llm:test` is green.

## 1. Network exposure
- `lsof -nP -iTCP:11434 -sTCP:LISTEN` → only `127.0.0.1`. `curl -m3 http://<lan-ip>:11434/api/tags` → refused.
- `lsof -nP -iTCP -sTCP:LISTEN | grep -E ':(8088|8089)'` → `*:8088` (Python forwarder), `127.0.0.1:8089` (gateway); `curl -m3 http://<lan-ip>:8089/healthz` fails.

`/usr/bin/python3 scripts/test_lan_forward.py` checks the forwarder (plain request, chunked streaming, abort closes upstream).

## 2. Access over the LAN address (not loopback)
Start with `scripts/private-ai.sh start` (forwarder on Apple python, see the day-30 doc; direct mode needs the firewall allowance), then:
- `curl $B/healthz` → 200. `curl -H "Authorization: Bearer $K" $B/v1/models` → allow-list.
- Non-stream chat → JSON with `usage`; `"stream":true` with `curl -N` → SSE deltas, `[DONE]`.
- No key → 401, wrong key → 403.

## 3. Limits
- Prompt larger than `numCtx` → 400 `context_length_exceeded`; body > `maxBodyBytes` → 413; > `maxMessages` → 400.
- `"max_tokens":999999` → accepted, upstream gets `num_predict = maxTokensCap`.
- More than `rateBurst` quick requests → 429 + `Retry-After`; wait → 200 again.
- Hold `maxConcurrent` + `queueSize` requests, send one more → 503 `queue_full` + `Retry-After`.
- `curl -m1 -N` a long stream, then `GET /stats` → `clientDisconnects` +1, `active` 0 (fake: `/_stats.cancelled`).

## 4. Stability
- `GATEWAY_KEY=$K python3 scripts/loadtest.py --url $B -c 1|2|4|8 -n 24 --stream` (real model: only when no other benchmark runs).
- Soak: `-c 2 --duration 900 --stream`; watch `ollama ps` and `/stats`.
- Failures: restart Ollama during load → 502/503 then recovery; fake modes `500`, `drop`, `slow`.

## 5. Phone
Open the URL/QR, enter the key, chat (streaming visible), lock screen / switch Wi-Fi mid-answer → `/stats` shows a disconnect and
no stuck `active`. Result: TBD после дня 29.

### Phone questions

Plain chat (Docs off): different difficulty and Markdown output.
1. Привет! Ответь одним предложением, кто ты.
2. Составь таблицу из 3 упражнений на ноги: название, мышцы, подходы.
3. Что такое NEAT и почему он падает на диете? (длинный ответ, проверка лимита токенов и кнопки «Продолжить»)
4. Напиши на Kotlin функцию подсчёта калорий по белкам, жирам и углеводам, с кодом в блоке.
5. Объясни разницу между гипертрофией и силовой тренировкой списком из 5 пунктов.

Knowledge base (Docs on), one session:
6. Хочу набрать мышечную массу. Сколько белка в день мне нужно? (источник + проверенная цитата)
7. А если я вегетарианец? (память цели, переписанный запрос)
8. Как часто нужно менять программу тренировок?
9. Сколько отдыхать между подходами при работе на силу?
10. Как восстанавливаться после тяжёлой тренировки ног?
11. Что такое прогрессивная перегрузка и как её применять?
12. Нужно ли делать разминку перед тяжёлым приседом?
13. Какая столица Франции? (вне базы: «Не знаю» + напоминание цели)
14. Как рассчитать калорийность для похудения? (после вопроса вне базы диалог не теряется)
15. «New chat» затем повтори вопрос 7: память должна быть пустой, цель не подтягивается.


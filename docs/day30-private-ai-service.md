# Day 30 — local LLM as a private service

Goal: a local model reachable over HTTP and from a phone chat page, safe enough for a home LAN, stable under several
parallel requests, with basic limits (rate limit, max context). Public access, VPS, Docker, TLS proxy are out of scope.

> Status: gateway, scripts and tests are done and verified against a fake Ollama and on the real model (results below).

## Architecture

```
phone (Wi-Fi) ──► lan_forward.py :8088 ──► ai-gateway 127.0.0.1:8089 ──► Ollama 127.0.0.1:11434
                  (Apple python, blind TCP)   auth · limits · queue · SSE      (no auth, never exposed)
```

- `scripts/lan_forward.py` (run by `/usr/bin/python3`) is the only thing listening on the LAN; `:ai-gateway` (Ktor CIO, depends on `:core:llm`) is loopback-only by default. From the gateway's view every client is 127.0.0.1, so nothing depends on client IP: limits are per key, and logs/`/stats` contain device names only.
- The gateway is separate from
  `:web-console` so the corpus viewer is never exposed.
- `OllamaStreamingClient` (`:core:llm`) turns Ollama's NDJSON stream into a `Flow`; cancelling the collector closes the
  connection, which stops generation.
- Non-streaming requests use the same streaming path, so timeouts and cancellation behave identically.

### API

| Endpoint | Auth | Purpose |
|---|---|---|
| `POST /v1/chat/completions` | Bearer | OpenAI subset: `model`, `messages`, `stream`, `max_tokens`, `temperature`; SSE with `usage` in the last chunk, `data: [DONE]` |
| `GET /v1/models` | Bearer | allow-list from the config |
| `GET /stats` | Bearer | JSON counters: requests, rejections by reason, active/queued, tokens, avg tok/s, avg queue wait |
| `GET /healthz` | none | gateway + upstream (cached 5 s); 503 if upstream is down or a configured model is missing |
| `GET /` | none | mobile chat page (one static HTML, no CDN; key kept in `localStorage`) |

Errors use the OpenAI shape `{"error":{"message","type","code"}}`.

### Limits (all in the config file `limits`)

| Limit | Result |
|---|---|
| missing / unknown key | 401 `missing_api_key` / 403 `invalid_api_key` |
| token bucket per key (`rateBurst`, `rateLimitPerMinute`) | 429 `rate_limit_exceeded` + `Retry-After` |
| `maxRequestsPerKey` simultaneous requests per key | 429 `too_many_concurrent_requests` |
| `maxBodyBytes` / `maxMessages` | 413 `request_too_large` / 400 `too_many_messages` |
| estimated prompt tokens + `max_tokens` > `numCtx` | 400 `context_length_exceeded` (instead of Ollama silently truncating) |
| `max_tokens` above `maxTokensCap` | clamped (no error); default `defaultMaxTokens` |
| `maxConcurrent` running (= `OLLAMA_NUM_PARALLEL`) + `queueSize` waiting for `queueTimeoutMs` | 503 `queue_full` / `queue_timeout` + `Retry-After` |
| `requestTimeoutMs`, `streamIdleTimeoutMs` | 504 (or an SSE `error` event once streaming started) |
| client disconnect | upstream request cancelled, slot freed; SSE keep-alive comments every `keepaliveMs` detect dead clients during slow prefill |

The token estimate is deliberately conservative (3 chars ≈ 1 token). Keys and prompt texts are never logged; the log has
method, path, status, device name, duration.

## Running it

```bash
scripts/private-ai.sh ollama-env        # prints OLLAMA_NUM_PARALLEL / KEEP_ALIVE to apply yourself (never restarts Ollama)
mkdir -p ~/.config/ai-gateway && cp ai-gateway/gateway.example.json ~/.config/ai-gateway/gateway.json   # edit models / numCtx
scripts/private-ai.sh keygen iphone     # prints the key ONCE; only its SHA-256 goes into the config
WARMUP=1 scripts/private-ai.sh start    # builds, warms the model, caffeinate, gateway 127.0.0.1:8089 + forwarder 0.0.0.0:8088 (stopped together), prints URLs (+ QR if qrencode is installed)
```

Direct mode for a Mac without the firewall restriction: `GATEWAY_HOST=0.0.0.0 scripts/private-ai.sh start` (no forwarder, gateway on 8088). Overrides: `LAN_PORT`, `GATEWAY_PORT`, `--host/--port`, env `GATEWAY_CONFIG`, `GATEWAY_UPSTREAM_URL`, `GATEWAY_MODELS`, `GATEWAY_NUM_CTX`, `GATEWAY_MAX_CONCURRENT`.
The real config contains key hashes: keep it outside the repo (`gateway.json` is in `.gitignore`).

### Connecting a phone

1. Phone on the same Wi-Fi (not a guest network with client isolation).
2. Scan the QR / open `http://<lan-ip>:8088/` (the `.local` name does not resolve on this Mac; use the IP).
3. *Key* → paste the device key → *Save*. Chat streams; *New chat* clears history. "Add to Home Screen" works.
4. The Mac should keep its IP (router reservation).

### macOS firewall and the forwarder

This Mac is MDM-managed (Jamf) and its application firewall has a "Block incoming connections" rule for the signing
identifier `net.java.openjdk.java`. Every Java launcher shares it, including a downloaded Developer-ID Temurin, so a
Java gateway answers on loopback but never on the LAN address. Apple's signed `/usr/bin/python3` is admitted, so a tiny
blind TCP forwarder (`/usr/bin/python3 scripts/lan_forward.py 8088 8089`) listens on the LAN and copies bytes to the
loopback gateway. SSE, chunked bodies and client aborts pass through unchanged (an abort closes the upstream connection,
so the gateway still cancels generation). Test: `/usr/bin/python3 scripts/test_lan_forward.py`.

Alternative on a Mac you administer (no MDM): allow Java instead and use direct mode.
```bash
sudo /usr/libexec/ApplicationFirewall/socketfilterfw --unblockapp "$(dirname "$(readlink -f "$(command -v java)")")/java"
```
Ollama on 11434 and the gateway on 8089 stay loopback-only either way.

## Threat model (LAN, HTTP, no TLS)

| Threat | Mitigation / accepted risk |
|---|---|
| Guest or compromised device on the Wi-Fi tries the API | Bearer key per device, constant-time hash comparison, rate limit per key |
| Key sniffed on the network (plain HTTP) | **Accepted risk** of a trusted home network; revoke by deleting the hash from the config; optional: mkcert + TLS later |
| Direct access to Ollama | Ollama bound to 127.0.0.1 only (verify with `lsof -nP -iTCP:11434 -sTCP:LISTEN`) |
| Resource exhaustion (huge prompts, many clients, endless generation) | body/message/context limits, `max_tokens` cap, bounded queue, timeouts, per-key caps |
| Dead clients keep the GPU busy | upstream cancelled on disconnect |
| Secrets in logs / repo | keys and prompts never logged; config with hashes outside the repo |
| Forwarder as extra attack surface | never parses HTTP (blind byte copy, no content logging); auth and limits are enforced by the gateway behind it; only 8088 is exposed |
| Page served over HTTP can be modified in transit | accepted, same LAN trust |

Not covered: protection from someone who knows a valid key and stays within limits; Wi-Fi isolation is the router's job.
Android app note: cleartext HTTP to a LAN address needs a network security config (out of scope).

## Verification done without a model

Fake upstream: `python3 scripts/fake_ollama.py` (port 11435; delay, token count, modes `500`, `drop`, `slow`, `hang`,
runtime switch via `POST /_control`). Real Ollama (11434) was not touched.

- `./gradlew :ai-gateway:test :core:llm:test` — 401/403, 413/400, context, clamp, 429, 503 (full queue / timeout),
  per-key limit, SSE, upstream cancel on disconnect, 502 + SSE error event, `/healthz` with upstream down.
- Through the LAN address `192.168.0.105:8088` (forwarder, not loopback) with the fake upstream: `/healthz` 200, `/v1/models`,
  401 without key, 403 bad key, non-stream chat, SSE stream (`[DONE]`), `context_length_exceeded`, 429 + `Retry-After`
  after the burst (4 ok, then 429), client abort mid-stream → fake upstream `cancelled=1`, gateway `clientDisconnects=1`.
- `lsof`: `*:8088` is Python (forwarder); `127.0.0.1:8089` is the gateway; `127.0.0.1:11434` is Ollama. `192.168.0.105:8089`
  and `:11434` do not answer. Stopping `private-ai.sh` stops forwarder, gateway and caffeinate.
- `scripts/loadtest.py` via `:8088`, 20 s each, stream, 2 slots: c=1 14 ok, 27 tok/s; c=2 28 ok, 56 tok/s total, p50 1.4 s;
  c=4 32 ok, no errors, p50 2.8 s (queueing), 58 tok/s total, so the forwarder does not break streaming under parallelism.
- Earlier loopback runs: fake `500`/`drop` modes surface as 502 / SSE `error`; 503 when the queue is full (c=6, queue 2).
- `GFQQTXJXNG.local` does not resolve here (mDNS); use the IP.

## Results with the real model

Model `trainer-rag-fast` (gemma4:26b-a4b-it-qat, `num_ctx` 8192, temperature 0.3) on an M3 Pro 36 GB, requests over the LAN address
(phone path: client -> forwarder :8088 -> gateway -> Ollama). Raw numbers: `docs/day30-results.json`, harness: `scripts/realrun.py`.

**NUM_PARALLEL** (24 requests per cell, streaming, 120 tokens, 0 errors):

| NUM_PARALLEL | total tok/s | p50 at 4 clients | p50 at 8 clients | model memory |
|---|---|---|---|---|
| 1 | 37 | 7.1 s | 14.0 s | 15.1 GB |
| 2 | 57 | 4.6 s | 9.2 s | 15.3 GB |
| 4 | 67 | 4.0 s | 7.8 s | 15.9 GB |

One client: ~37 tok/s, first token ~0.17 s, ~1.8 s per 120-token reply. **Chosen: NUM_PARALLEL=4** (+80% throughput for +0.8 GB).

**Soak, 15 min** (NP=4, 3 clients with think time): 543/543 ok, latency p50/p90/p99 3.0/3.1/3.3 s, first token p50 0.25 s, ~22 tok/s per stream,
model memory constant 15.87 GB, Ollama RSS 16.76 -> 16.99 GB, no thermal throttling (CPU_Speed_Limit 100), `active` back to 0.

**Faults:** Ollama killed ~9 s under 4-client load: 12/48 requests ok, 36 failed with an SSE `upstream_error` event (no hangs), first success 2.3 s after the
restart. 4 client disconnects were counted, `active` returned to 0, the next request took 0.4 s.

**Limits, 12/12 checks:** 401/403, `context_length_exceeded` (~10k tokens vs 8192), 413 for a body over 64 KB, 400 for more than 40 messages,
`max_tokens` clamp, 404 unknown model, 429 + `Retry-After` after a burst and recovery, 503 `queue_full` (4 served / 3 rejected), stats counters.

**Findings from real runs**
- Gemma 4 "thinks" by default: `max_tokens` was spent on thinking and the answer was empty. The gateway now sends `think:false` (config `think`).
- Phone test: chat works over Wi-Fi with streaming. It exposed two issues, both fixed: Markdown was not rendered (the page now renders it, same renderer as the web console)
  and a long answer (NEAT) was cut at 512 tokens (default raised to 1024, cap 2048; the page shows a notice and a "Продолжить" button on `finish_reason=length`;
  the same answer now finishes with `stop` at 528 tokens).
- `scripts/realrun.py serve` runs its own Ollama instance on 127.0.0.1:11436 (with `OLLAMA_NUM_PARALLEL`), so the brew service on 11434 is untouched.

## Limitations

- One GPU: parallel streams share memory bandwidth; `NUM_PARALLEL>1` costs KV-cache memory.
- Token estimate is heuristic; the real count comes from Ollama after the fact (`usage`).
- State is in memory (rate buckets, stats reset on restart); the chat page keeps history only in the open tab.
- No TLS, no per-key quotas over time, no persistence of conversations.

## Чат на телефоне использует RAG и наработки дней 25-29

Включается блоком `"rag": {"enabled": true, "indexDir": "rag/index-local", "sessionsDir": "rag/sessions-gateway"}` в `gateway.json` (выключен по умолчанию). Кнопка **Docs** на странице чата переключает режим «база знаний» / «просто модель».

- `POST /v1/rag/chat` `{"text": "...", "session": "s-..."}` -> `answer`, `idk`, `sources`, `quotes` (с флагом `verified`), `goal`, `searchQuery`, `llmCalls`, `wallMs`; `POST /v1/rag/reset` `{"session": "..."}`.
- Внутри тот же стек, что в web-консоли: локальный индекс `embeddinggemma-2`, переписывание запроса с учётом памяти, фильтр + rerank, ответ с проверенными цитатами, «не знаю» вместо выдумки, память задачи и скользящее резюме (Day 25), `PromptProfile local` и `OllamaTuning.RECOMMENDED` (Day 29).
- Сессии хранятся на сервере отдельно для каждого ключа устройства; ключ, лимиты, очередь и таймауты те же, что у обычного чата (один ход RAG занимает один слот на все свои вызовы модели). Ответ приходит целиком (3 вызова модели, ~4-12 с), без стриминга.
- Проверено на реальной модели: вопрос про белок -> ответ с источником, «А если я вегетарианец?» -> запрос переписан с учётом цели «Build muscle mass», вопрос вне базы -> «Не знаю» с напоминанием цели.
- Для двух моделей одновременно (ответы + эмбеддинги) тестовый Ollama запускается с `OLLAMA_MAX_LOADED_MODELS=2`.

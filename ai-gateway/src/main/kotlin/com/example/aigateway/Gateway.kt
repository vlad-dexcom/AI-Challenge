package com.example.aigateway

import com.example.core.llm.OllamaChatClient
import com.example.core.llm.OllamaMessage
import com.example.core.llm.OllamaStreamChunk
import com.example.core.llm.OllamaStreamException
import com.example.core.llm.OllamaStreamingClient
import com.example.core.platform.defaultHttpEngine
import io.ktor.client.engine.HttpClientEngine
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentLength
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readRemaining
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID

private const val SSE_KEEPALIVE = ": keepalive\n\n"

/** Private, rate-limited, OpenAI-shaped front door for a loopback-only Ollama. Never logs keys or prompts. */
class Gateway(
    private val config: GatewayConfig,
    engine: HttpClientEngine = defaultHttpEngine(),
    private val log: (String) -> Unit = { System.err.println(it) },
    rag: RagService? = null,
) {
    private val ragService: RagService? = rag ?: if (config.rag.enabled) LocalRagService(config.rag, config.upstreamUrl, config.models.first()) else null
    private val limits = config.limits
    private val keys = KeyStore(config.keys)
    private val rate = RateLimiter(limits.rateBurst, limits.rateLimitPerMinute)
    private val perKey = PerKeyGate(limits.maxRequestsPerKey)
    private val gate = GenerationGate(limits.maxConcurrent, limits.queueSize, limits.queueTimeoutMs)
    private val stats = Stats()
    private val streaming = OllamaStreamingClient(config.upstreamUrl, engine, limits.requestTimeoutMs, limits.streamIdleTimeoutMs)
    private val tags = OllamaChatClient(config.upstreamUrl, config.models.first(), engine)
    private val json = Json { ignoreUnknownKeys = true }
    private val page: String by lazy { Gateway::class.java.getResource("/chat.html")!!.readText() }

    /** Same dependency-free, escaping Markdown renderer as the web console (copy of web-console/src/main/resources/ui/markdown.js). */
    private val markdownJs: String by lazy { Gateway::class.java.getResource("/markdown.js")!!.readText() }

    @Volatile private var health: Pair<Long, JsonObject>? = null

    fun install(app: Application) = app.routing {
        get("/") { call.respondText(page, ContentType.Text.Html) }
        get("/markdown.js") { call.respondText(markdownJs, ContentType.parse("application/javascript; charset=utf-8")) }
        get("/healthz") { handleHealth(call) }
        get("/v1/models") {
            val k = authenticate(call) ?: return@get
            call.respondJson(HttpStatusCode.OK, buildJsonObject {
                put("object", "list")
                put("rag", ragService != null)
                put("data", buildJsonArray {
                    config.models.forEach { add(buildJsonObject { put("id", it); put("object", "model"); put("owned_by", "local") }) }
                })
            })
            log("GET /v1/models 200 key=$k")
        }
        get("/stats") {
            authenticate(call) ?: return@get
            call.respondJson(HttpStatusCode.OK, toJson(stats.snapshot(gate.active, gate.queued)))
        }
        post("/v1/chat/completions") { handleChat(call) }
        post("/v1/rag/chat") { handleRag(call, reset = false) }
        post("/v1/rag/reset") { handleRag(call, reset = true) }
    }

    /** RAG turn through the same auth, rate limit, per-key cap and generation queue as plain chat; one turn holds one slot for all of its LLM calls. */
    private suspend fun handleRag(call: ApplicationCall, reset: Boolean) {
        val started = System.currentTimeMillis()
        stats.requests.incrementAndGet()
        val key = authenticate(call) ?: return
        var status = 0
        try {
            val svc = ragService ?: return reject(call, key, HttpStatusCode.NotFound, "invalid_request_error", "rag_disabled", "RAG is not enabled on this server.")
            val wait = rate.tryTake(key)
            if (wait > 0) return reject(call, key, HttpStatusCode.TooManyRequests, "rate_limit_exceeded", "rate_limit_exceeded", "Too many requests, retry in $wait s.", wait)
            val declared = call.request.contentLength()
            if (declared != null && declared > limits.maxBodyBytes) return reject(call, key, HttpStatusCode.PayloadTooLarge, "invalid_request_error", "request_too_large", "Body exceeds ${limits.maxBodyBytes} bytes.")
            val raw = call.receiveChannel().readRemaining(limits.maxBodyBytes + 1L).readByteArray()
            if (raw.size > limits.maxBodyBytes) return reject(call, key, HttpStatusCode.PayloadTooLarge, "invalid_request_error", "request_too_large", "Body exceeds ${limits.maxBodyBytes} bytes.")
            val obj = try { json.parseToJsonElement(raw.decodeToString()).jsonObject } catch (e: Exception) { return reject(call, key, HttpStatusCode.BadRequest, "invalid_request_error", "invalid_json", "Body must be a JSON object.") }
            val session = obj["session"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull
            if (reset) {
                val had = session != null && svc.reset(key, session)
                call.respondJson(HttpStatusCode.OK, buildJsonObject { put("reset", had) })
                status = 200
                return
            }
            val text = obj["text"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (text.isEmpty()) return reject(call, key, HttpStatusCode.BadRequest, "invalid_request_error", "empty_text", "'text' must not be empty.")
            if (text.length > config.rag.maxQuestionChars) return reject(call, key, HttpStatusCode.BadRequest, "invalid_request_error", "text_too_long", "'text' is limited to ${config.rag.maxQuestionChars} characters.")
            svc.status()?.let { return reject(call, key, HttpStatusCode.ServiceUnavailable, "server_error", "rag_index_missing", "The RAG index is not built on the server.") }
            if (!perKey.tryEnter(key)) return reject(call, key, HttpStatusCode.TooManyRequests, "rate_limit_exceeded", "too_many_concurrent_requests", "At most ${limits.maxRequestsPerKey} requests per key can run at once.", limits.retryAfterSeconds.toLong())
            try {
                when (gate.enter()) {
                    Admission.QUEUE_FULL -> return reject(call, key, HttpStatusCode.ServiceUnavailable, "server_busy", "queue_full", "Server is busy, try again shortly.", limits.retryAfterSeconds.toLong())
                    Admission.QUEUE_TIMEOUT -> return reject(call, key, HttpStatusCode.ServiceUnavailable, "server_busy", "queue_timeout", "Waited too long for a free slot.", limits.retryAfterSeconds.toLong())
                    Admission.ADMITTED -> {}
                }
                try {
                    val result = withTimeoutOrNull(limits.requestTimeoutMs) { svc.ask(key, session, text) }
                    if (result == null) {
                        stats.upstreamErrors.incrementAndGet()
                        call.respondJson(HttpStatusCode.GatewayTimeout, errorJson("upstream_timeout", "The answer took too long."))
                        status = 504
                    } else result.fold({ r ->
                        stats.completed.incrementAndGet()
                        call.respondJson(HttpStatusCode.OK, json.encodeToJsonElement(RagReply.serializer(), r))
                        status = 200
                    }, { e ->
                        if (e is CancellationException) throw e
                        stats.upstreamErrors.incrementAndGet()
                        call.respondJson(HttpStatusCode.BadGateway, errorJson("upstream_error", "Answer failed: ${(e.message ?: e::class.simpleName).orEmpty().take(200)}"))
                        status = 502
                    })
                } finally {
                    gate.leave()
                }
            } finally {
                perKey.leave(key)
            }
        } finally {
            log("POST /v1/rag/${if (reset) "reset" else "chat"} ${if (status == 0) "rejected" else status} key=$key ${System.currentTimeMillis() - started}ms")
        }
    }

    private suspend fun handleHealth(call: ApplicationCall) {
        val now = System.currentTimeMillis()
        val cached = health?.takeIf { now - it.first < 5_000 }?.second
        val result = cached ?: run {
            val r = tags.models()
            val names = r.getOrNull()
            val missing = names?.let { n -> config.models.filter { m -> n.none { it == m || it == "$m:latest" } } }.orEmpty()
            buildJsonObject {
                put("gateway", "ok")
                put("upstream", if (r.isSuccess) "ok" else "down")
                put("status", if (r.isSuccess && missing.isEmpty()) "ok" else "degraded")
                put("missingModels", JsonArray(missing.map(::JsonPrimitive)))
            }.also { health = now to it }
        }
        call.respondJson(if (result["status"]!!.jsonPrimitive.content == "ok") HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable, result)
    }

    private suspend fun handleChat(call: ApplicationCall) {
        val started = System.currentTimeMillis()
        stats.requests.incrementAndGet()
        val key = authenticate(call) ?: return
        var status = 0
        try {
            val wait = rate.tryTake(key)
            if (wait > 0) return reject(call, key, HttpStatusCode.TooManyRequests, "rate_limit_exceeded", "rate_limit_exceeded", "Too many requests, retry in $wait s.", wait)
            val declared = call.request.contentLength()
            if (declared != null && declared > limits.maxBodyBytes) return reject(call, key, HttpStatusCode.PayloadTooLarge, "invalid_request_error", "request_too_large", "Body exceeds ${limits.maxBodyBytes} bytes.")
            val raw = call.receiveChannel().readRemaining(limits.maxBodyBytes + 1L).readByteArray()
            if (raw.size > limits.maxBodyBytes) return reject(call, key, HttpStatusCode.PayloadTooLarge, "invalid_request_error", "request_too_large", "Body exceeds ${limits.maxBodyBytes} bytes.")
            val req = when (val p = parse(raw.decodeToString())) {
                is Parsed.Bad -> return reject(call, key, p.status, "invalid_request_error", p.code, p.message)
                is Parsed.Ok -> p.request
            }
            if (!perKey.tryEnter(key)) return reject(call, key, HttpStatusCode.TooManyRequests, "rate_limit_exceeded", "too_many_concurrent_requests", "At most ${limits.maxRequestsPerKey} simultaneous requests per key.", limits.retryAfterSeconds.toLong())
            try {
                val queued = System.currentTimeMillis()
                when (gate.enter()) {
                    Admission.QUEUE_FULL -> return reject(call, key, HttpStatusCode.ServiceUnavailable, "server_busy", "queue_full", "Server is busy, try again shortly.", limits.retryAfterSeconds.toLong())
                    Admission.QUEUE_TIMEOUT -> return reject(call, key, HttpStatusCode.ServiceUnavailable, "server_busy", "queue_timeout", "Waited too long for a free slot.", limits.retryAfterSeconds.toLong())
                    Admission.ADMITTED -> {}
                }
                try {
                    stats.queueWait(System.currentTimeMillis() - queued)
                    status = generate(call, req)
                } finally {
                    gate.leave()
                }
            } finally {
                perKey.leave(key)
            }
        } finally {
            log("POST /v1/chat/completions ${if (status == 0) "rejected" else status} key=$key ${System.currentTimeMillis() - started}ms")
        }
    }

    private suspend fun generate(call: ApplicationCall, req: ChatRequest): Int {
        val id = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").take(24)
        val created = System.currentTimeMillis() / 1000
        val upstream = streaming.chatStream(req.messages, req.model, req.temperature, config.numCtx, req.maxTokens, config.think)
        return coroutineScope {
            val ch = produce<OllamaStreamChunk>(capacity = 16) {
                // Failures travel through the channel so they cannot cancel the request scope.
                try { upstream.collect { send(it) } } catch (e: CancellationException) { throw e } catch (e: Exception) { close(e) }
            }
            try {
                if (req.stream) streamResponse(call, ch, id, created, req) else plainResponse(call, ch, id, created, req)
            } finally {
                ch.cancel()
            }
        }
    }

    private suspend fun plainResponse(call: ApplicationCall, ch: ReceiveChannel<OllamaStreamChunk>, id: String, created: Long, req: ChatRequest): Int {
        val text = StringBuilder()
        var last: OllamaStreamChunk? = null
        val deadline = System.currentTimeMillis() + limits.requestTimeoutMs
        while (true) {
            val r = receive(ch, limits.streamIdleTimeoutMs.coerceAtMost((deadline - System.currentTimeMillis()).coerceAtLeast(1))) ?: return upstreamFailure(call, 504, "upstream_timeout", "Upstream did not answer in time.")
            val chunk = r.getOrNull() ?: return upstreamFailure(call, 502, "upstream_error", describe(r.exceptionOrNull()))
            text.append(chunk.content)
            if (chunk.done) { last = chunk; break }
        }
        val (prompt, completion) = usage(last!!, req, text.length)
        stats.generation(prompt, completion, last.evalMillis); stats.completed.incrementAndGet()
        call.respondJson(HttpStatusCode.OK, buildJsonObject {
            put("id", id); put("object", "chat.completion"); put("created", created); put("model", req.model)
            put("choices", buildJsonArray { add(buildJsonObject {
                put("index", 0)
                put("message", buildJsonObject { put("role", "assistant"); put("content", text.toString().trim()) })
                put("finish_reason", finish(last))
            }) })
            put("usage", usageJson(prompt, completion))
        })
        return 200
    }

    private suspend fun streamResponse(call: ApplicationCall, ch: ReceiveChannel<OllamaStreamChunk>, id: String, created: Long, req: ChatRequest): Int {
        call.respondBytesWriter(ContentType.Text.EventStream) {
            try {
                call.response.header(HttpHeaders.CacheControl, "no-cache")
                sse(chunkJson(id, created, req.model, buildJsonObject { put("role", "assistant"); put("content", "") }, null))
                var sent = 0
                var lastActivity = System.currentTimeMillis()
                val deadline = lastActivity + limits.requestTimeoutMs
                while (true) {
                    val now = System.currentTimeMillis()
                    if (now > deadline || now - lastActivity > limits.streamIdleTimeoutMs) {
                        stats.upstreamErrors.incrementAndGet()
                        sse(errorJson("upstream_timeout", "Upstream did not answer in time."), done = true)
                        return@respondBytesWriter
                    }
                    val r = receive(ch, limits.keepaliveMs.coerceAtMost(limits.streamIdleTimeoutMs))
                    if (r == null) { writeStringUtf8(SSE_KEEPALIVE); flush(); continue }
                    val chunk = r.getOrNull()
                    if (chunk == null) {
                        stats.upstreamErrors.incrementAndGet()
                        sse(errorJson("upstream_error", describe(r.exceptionOrNull())), done = true)
                        return@respondBytesWriter
                    }
                    lastActivity = System.currentTimeMillis()
                    if (chunk.content.isNotEmpty()) {
                        sent += chunk.content.length
                        sse(chunkJson(id, created, req.model, buildJsonObject { put("content", chunk.content) }, null))
                    }
                    if (chunk.done) {
                        val (prompt, completion) = usage(chunk, req, sent)
                        stats.generation(prompt, completion, chunk.evalMillis); stats.completed.incrementAndGet()
                        sse(chunkJson(id, created, req.model, buildJsonObject {}, finish(chunk), usageJson(prompt, completion)), done = true)
                        return@respondBytesWriter
                    }
                }
            } catch (e: CancellationException) {
                stats.clientDisconnects.incrementAndGet(); throw e
            } catch (e: Exception) {
                stats.clientDisconnects.incrementAndGet() // write to a vanished client failed
            } finally {
                ch.cancel() // closing the upstream connection makes Ollama stop generating
            }
        }
        return 200
    }

    private suspend fun ByteWriteChannel.sse(data: JsonElement, done: Boolean = false) {
        writeStringUtf8("data: $data\n\n")
        if (done) writeStringUtf8("data: [DONE]\n\n")
        flush()
    }

    /** null = nothing arrived within [timeoutMs]; otherwise the next chunk, or the failure that closed the channel. */
    private suspend fun receive(ch: ReceiveChannel<OllamaStreamChunk>, timeoutMs: Long): Result<OllamaStreamChunk>? =
        withTimeoutOrNull(timeoutMs) {
            val r = ch.receiveCatching()
            r.getOrNull()?.let { Result.success(it) } ?: Result.failure(r.exceptionOrNull() ?: IllegalStateException("upstream closed"))
        }

    private fun describe(e: Throwable?): String = when {
        e is OllamaStreamException && e.status == 404 -> "Model is not available on the upstream server."
        e is OllamaStreamException -> "Upstream error."
        else -> "Upstream is unavailable."
    }

    private suspend fun upstreamFailure(call: ApplicationCall, status: Int, code: String, message: String): Int {
        stats.upstreamErrors.incrementAndGet()
        call.respondJson(HttpStatusCode.fromValue(status), errorJson(code, message))
        return status
    }

    private fun usage(last: OllamaStreamChunk, req: ChatRequest, chars: Int): Pair<Int, Int> {
        val prompt = if (last.promptTokens > 0) last.promptTokens else estimateTokens(req.messages.map { it.role to it.content })
        val completion = if (last.completionTokens > 0) last.completionTokens else chars / 3
        return prompt to completion
    }

    private fun finish(c: OllamaStreamChunk) = if (c.doneReason == "length") "length" else "stop"

    private fun usageJson(prompt: Int, completion: Int) = buildJsonObject {
        put("prompt_tokens", prompt); put("completion_tokens", completion); put("total_tokens", prompt + completion)
    }

    private fun chunkJson(id: String, created: Long, model: String, delta: JsonObject, finish: String?, usage: JsonObject? = null) = buildJsonObject {
        put("id", id); put("object", "chat.completion.chunk"); put("created", created); put("model", model)
        put("choices", buildJsonArray { add(buildJsonObject {
            put("index", 0); put("delta", delta)
            put("finish_reason", finish?.let(::JsonPrimitive) ?: JsonNull)
        }) })
        if (usage != null) put("usage", usage)
    }

    private fun errorJson(code: String, message: String, type: String = code) = buildJsonObject {
        put("error", buildJsonObject { put("message", message); put("type", type); put("code", code) })
    }

    private suspend fun ApplicationCall.respondJson(status: HttpStatusCode, body: JsonElement) =
        respondText(body.toString(), ContentType.Application.Json, status)

    private suspend fun reject(call: ApplicationCall, key: String?, status: HttpStatusCode, type: String, code: String, message: String, retryAfter: Long? = null) {
        stats.reject(code)
        if (retryAfter != null) call.response.header(HttpHeaders.RetryAfter, retryAfter.toString())
        call.respondJson(status, errorJson(code, message, type))
        log("${call.request.httpMethod.value} ${call.request.path()} ${status.value} $code key=${key ?: "-"}")
    }

    /** Device name for a valid Bearer key; otherwise answers 401 (missing) / 403 (unknown) and returns null. */
    private suspend fun authenticate(call: ApplicationCall): String? {
        val header = call.request.headers[HttpHeaders.Authorization]
        val token = header?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substring(7)?.trim()?.takeIf { it.isNotEmpty() }
        if (token == null) {
            call.response.header(HttpHeaders.WWWAuthenticate, "Bearer")
            reject(call, null, HttpStatusCode.Unauthorized, "authentication_error", "missing_api_key", "Send the device key as 'Authorization: Bearer <key>'.")
            return null
        }
        val name = keys.authenticate(token)
        if (name == null) reject(call, null, HttpStatusCode.Forbidden, "authentication_error", "invalid_api_key", "Unknown API key.")
        return name
    }

    private class ChatRequest(val model: String, val messages: List<OllamaMessage>, val stream: Boolean, val maxTokens: Int, val temperature: Double?)

    private sealed interface Parsed {
        class Ok(val request: ChatRequest) : Parsed
        class Bad(val status: HttpStatusCode, val code: String, val message: String) : Parsed
    }

    private fun bad(code: String, message: String, status: HttpStatusCode = HttpStatusCode.BadRequest) = Parsed.Bad(status, code, message)

    private fun parse(text: String): Parsed {
        val obj = try { json.parseToJsonElement(text).jsonObject } catch (e: Exception) { return bad("invalid_json", "Body must be a JSON object.") }
        val model = obj["model"]?.jsonPrimitive?.contentOrNull ?: config.models.first()
        if (model !in config.models) return bad("model_not_found", "Model '$model' is not served here.", HttpStatusCode.NotFound)
        val raw = obj["messages"] as? JsonArray ?: return bad("invalid_messages", "'messages' must be an array.")
        if (raw.isEmpty()) return bad("invalid_messages", "'messages' must not be empty.")
        if (raw.size > limits.maxMessages) return bad("too_many_messages", "At most ${limits.maxMessages} messages are allowed.")
        val messages = raw.map { m ->
            val o = m as? JsonObject ?: return bad("invalid_messages", "Each message must be an object.")
            val role = o["role"]?.jsonPrimitive?.contentOrNull
            if (role !in setOf("system", "user", "assistant")) return bad("invalid_messages", "Unsupported role.")
            OllamaMessage(role!!, contentOf(o["content"]) ?: return bad("invalid_messages", "Message content must be text."))
        }
        val temperature = obj["temperature"]?.takeIf { it !is JsonNull }?.let { it.jsonPrimitive.doubleOrNull ?: return bad("invalid_temperature", "temperature must be a number.") }
        if (temperature != null && temperature !in 0.0..2.0) return bad("invalid_temperature", "temperature must be within 0..2.")
        val requested = (obj["max_completion_tokens"] ?: obj["max_tokens"])?.takeIf { it !is JsonNull }?.let { it.jsonPrimitive.intOrNull ?: return bad("invalid_max_tokens", "max_tokens must be an integer.") }
        if (requested != null && requested < 1) return bad("invalid_max_tokens", "max_tokens must be positive.")
        val maxTokens = minOf(requested ?: limits.defaultMaxTokens, limits.maxTokensCap)
        val stream = obj["stream"]?.takeIf { it !is JsonNull }?.let { it.jsonPrimitive.booleanOrNull ?: return bad("invalid_stream", "stream must be a boolean.") } ?: false
        val estimate = estimateTokens(messages.map { it.role to it.content })
        if (estimate + maxTokens > config.numCtx) {
            return bad("context_length_exceeded", "About $estimate prompt tokens plus $maxTokens completion tokens exceed the ${config.numCtx}-token context window. Shorten the conversation or start a new chat.")
        }
        return Parsed.Ok(ChatRequest(model, messages, stream, maxTokens, temperature))
    }

    private fun contentOf(e: JsonElement?): String? = when (e) {
        is JsonPrimitive -> e.contentOrNull
        is JsonArray -> e.map { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull }.takeIf { null !in it }?.joinToString("")
        else -> null
    }

    private fun toJson(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is Number -> JsonPrimitive(v)
        is String -> JsonPrimitive(v)
        is Map<*, *> -> JsonObject(v.entries.associate { it.key.toString() to toJson(it.value) })
        else -> JsonPrimitive(v.toString())
    }
}

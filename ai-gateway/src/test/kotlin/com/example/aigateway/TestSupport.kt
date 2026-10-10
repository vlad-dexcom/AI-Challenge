package com.example.aigateway

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Loopback stand-in for Ollama; no real network, no model. */
class FakeUpstream(var tokens: Int = 3, var tokenDelayMs: Long = 0, var failWith: Int? = null) {
    val started = AtomicInteger()
    val cancelled = AtomicInteger()
    val lastBody = AtomicReference("")
    private val server: EmbeddedServer<*, *> = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
        routing {
            get("/api/tags") { call.respondText("""{"models":[{"name":"test-model"}]}""", ContentType.Application.Json) }
            post("/api/chat") {
                lastBody.set(call.receiveText())
                started.incrementAndGet()
                failWith?.let { call.respondText("""{"error":"boom"}""", ContentType.Application.Json, HttpStatusCode.fromValue(it)); return@post }
                call.respondBytesWriter(ContentType("application", "x-ndjson")) {
                    var finished = false
                    try {
                        repeat(tokens) { i ->
                            writeStringUtf8("""{"model":"test-model","message":{"role":"assistant","content":"t$i "},"done":false}""" + "\n")
                            flush()
                            if (tokenDelayMs > 0) delay(tokenDelayMs)
                        }
                        writeStringUtf8("""{"model":"test-model","message":{"role":"assistant","content":""},"done":true,"done_reason":"stop","prompt_eval_count":5,"eval_count":$tokens,"eval_duration":1000000000,"total_duration":1000000000}""" + "\n")
                        flush()
                        finished = true
                    } finally {
                        if (!finished) cancelled.incrementAndGet()
                    }
                }
            }
        }
    }.also { it.start() }
    val url: String get() = "http://127.0.0.1:${runBlocking { server.engine.resolvedConnectors().first().port }}"
    fun stop() = server.stop(0, 0)
}

const val KEY_A = "sk-lan-device-a"
const val KEY_B = "sk-lan-device-b"

fun testConfig(upstream: String, limits: Limits = Limits(), numCtx: Int = 4096) = GatewayConfig(
    host = "127.0.0.1", port = 0, upstreamUrl = upstream, models = listOf("test-model"), numCtx = numCtx,
    keys = listOf(KeyEntry("a", KeyHasher.sha256Hex(KEY_A)), KeyEntry("b", KeyHasher.sha256Hex(KEY_B))),
    limits = limits,
)

class RunningGateway(val config: GatewayConfig) {
    private val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) { Gateway(config, log = {}).install(this) }.also { it.start() }
    val base: String = "http://127.0.0.1:${runBlocking { server.engine.resolvedConnectors().first().port }}"
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    fun stop() = server.stop(0, 0)

    fun request(method: String, path: String, key: String? = KEY_A, body: String? = null): HttpResponse<String> =
        http.send(build(method, path, key, body), HttpResponse.BodyHandlers.ofString())

    fun build(method: String, path: String, key: String?, body: String?): HttpRequest =
        HttpRequest.newBuilder(URI("$base$path")).timeout(Duration.ofSeconds(20)).apply {
            key?.let { header("Authorization", "Bearer $it") }
            header("Content-Type", "application/json")
            method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body))
        }.build()

    fun chat(content: String = "hi", key: String? = KEY_A, stream: Boolean = false, extra: String = ""): HttpResponse<String> =
        request("POST", "/v1/chat/completions", key, chatBody(content, stream, extra))

    fun openStream(key: String = KEY_A, content: String = "hi"): HttpResponse<java.io.InputStream> =
        http.send(build("POST", "/v1/chat/completions", key, chatBody(content, true, "")), HttpResponse.BodyHandlers.ofInputStream())

    fun chatAsync(key: String = KEY_A, content: String = "hi") =
        http.sendAsync(build("POST", "/v1/chat/completions", key, chatBody(content, false, "")), HttpResponse.BodyHandlers.ofString())

    private fun chatBody(content: String, stream: Boolean, extra: String) =
        """{"model":"test-model","messages":[{"role":"user","content":${kotlinx.serialization.json.JsonPrimitive(content).toString()}}],"stream":$stream$extra}"""
}

fun eventually(timeoutMs: Long = 5000, check: () -> Boolean): Boolean {
    val end = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < end) { if (check()) return true; Thread.sleep(25) }
    return check()
}

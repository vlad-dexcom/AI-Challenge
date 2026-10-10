package com.example.aigateway

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayTest {
    private val upstream = FakeUpstream()
    private val gateways = mutableListOf<RunningGateway>()

    private fun gateway(limits: Limits = Limits(), numCtx: Int = 4096, upstreamUrl: String = upstream.url) =
        RunningGateway(testConfig(upstreamUrl, limits, numCtx)).also { gateways += it }

    @After fun tearDown() { gateways.forEach { it.stop() }; upstream.stop() }

    @Test fun missingKeyIs401AndUnknownKeyIs403() {
        val g = gateway()
        val none = g.chat(key = null)
        assertEquals(401, none.statusCode()); assertTrue(none.body().contains("missing_api_key"))
        assertNotNull(none.headers().firstValue("WWW-Authenticate").orElse(null))
        assertEquals(403, g.chat(key = "sk-lan-wrong").statusCode())
        assertEquals(401, g.request("GET", "/v1/models", null).statusCode())
        assertEquals(403, g.request("GET", "/stats", "nope").statusCode())
        assertEquals(0, upstream.started.get())
    }

    @Test fun modelsListsAllowListOnly() {
        val r = gateway().request("GET", "/v1/models")
        assertEquals(200, r.statusCode()); assertTrue(r.body().contains("\"id\":\"test-model\""))
    }

    @Test fun chatReturnsOpenAiShapeWithUsage() {
        val r = gateway().chat()
        assertEquals(200, r.statusCode())
        assertTrue(r.body().contains("\"object\":\"chat.completion\""))
        assertTrue(r.body().contains("t0 t1 t2"))
        assertTrue(r.body().contains("\"prompt_tokens\":5") && r.body().contains("\"completion_tokens\":3") && r.body().contains("\"total_tokens\":8"))
    }

    @Test fun streamingEmitsSseDeltasUsageAndDone() {
        val lines = gateway().openStream().body().bufferedReader().readLines().filter { it.startsWith("data:") }
        assertTrue(lines.first().contains("\"role\":\"assistant\""))
        assertTrue(lines.any { it.contains("\"content\":\"t1 \"") })
        assertTrue(lines.any { it.contains("\"usage\"") && it.contains("\"finish_reason\":\"stop\"") })
        assertEquals("data: [DONE]", lines.last())
    }

    @Test fun maxTokensIsClampedToServerCapAndDefaulted() {
        val g = gateway(Limits(maxTokensCap = 100, defaultMaxTokens = 50))
        g.chat(extra = ""","max_tokens":99999""")
        assertTrue(upstream.lastBody.get(), upstream.lastBody.get().contains("\"num_predict\":100"))
        g.chat()
        assertTrue(upstream.lastBody.get().contains("\"num_predict\":50"))
        assertTrue(upstream.lastBody.get().contains("\"num_ctx\":4096"))
    }

    @Test fun oversizedBodyIs413() {
        val r = gateway(Limits(maxBodyBytes = 1024)).chat("x".repeat(5000))
        assertEquals(413, r.statusCode()); assertTrue(r.body().contains("request_too_large"))
        assertEquals(0, upstream.started.get())
    }

    @Test fun tooManyMessagesAndBadInputAre400() {
        val g = gateway(Limits(maxMessages = 2))
        val many = """{"messages":[{"role":"user","content":"a"},{"role":"user","content":"b"},{"role":"user","content":"c"}]}"""
        assertTrue(g.request("POST", "/v1/chat/completions", body = many).body().contains("too_many_messages"))
        assertEquals(400, g.request("POST", "/v1/chat/completions", body = "not json").statusCode())
        assertEquals(400, g.request("POST", "/v1/chat/completions", body = """{"messages":[]}""").statusCode())
        assertEquals(404, g.request("POST", "/v1/chat/completions", body = """{"model":"other","messages":[{"role":"user","content":"a"}]}""").statusCode())
        assertEquals(0, upstream.started.get())
    }

    @Test fun promptBeyondContextIsRejectedInsteadOfTruncated() {
        val g = gateway(Limits(maxTokensCap = 256, defaultMaxTokens = 128, maxBodyBytes = 1 shl 20), numCtx = 1024)
        val r = g.chat("word ".repeat(1500))
        assertEquals(400, r.statusCode()); assertTrue(r.body().contains("context_length_exceeded"))
        assertEquals(200, g.chat("short").statusCode())
        assertEquals(1, upstream.started.get())
    }

    @Test fun rateLimitGives429WithRetryAfterPerKey() {
        val g = gateway(Limits(rateBurst = 2, rateLimitPerMinute = 6))
        assertEquals(200, g.chat().statusCode()); assertEquals(200, g.chat().statusCode())
        val third = g.chat()
        assertEquals(429, third.statusCode())
        assertTrue(third.headers().firstValue("Retry-After").get().toInt() >= 1)
        assertEquals("other key keeps its own bucket", 200, g.chat(key = KEY_B).statusCode())
        assertTrue(g.request("GET", "/stats").body().contains("\"rate_limit_exceeded\":1"))
    }

    @Test fun fullQueueGives503WithRetryAfter() {
        upstream.tokens = 2; upstream.tokenDelayMs = 400
        val g = gateway(Limits(maxConcurrent = 1, queueSize = 0, maxRequestsPerKey = 5))
        val slow = g.chatAsync()
        assertTrue(eventually { upstream.started.get() == 1 })
        val r = g.chat(key = KEY_B)
        assertEquals(503, r.statusCode()); assertTrue(r.body().contains("queue_full"))
        assertNotNull(r.headers().firstValue("Retry-After").orElse(null))
        assertEquals(200, slow.get().statusCode())
    }

    @Test fun queuedRequestWaitsThenRunsOrTimesOut() {
        upstream.tokens = 2; upstream.tokenDelayMs = 300
        val g = gateway(Limits(maxConcurrent = 1, queueSize = 1, queueTimeoutMs = 3000, maxRequestsPerKey = 5))
        val first = g.chatAsync()
        assertTrue(eventually { upstream.started.get() == 1 })
        assertEquals("queued request is served after the first", 200, g.chat(key = KEY_B).statusCode())
        assertEquals(200, first.get().statusCode())

        upstream.tokenDelayMs = 1500
        val t = gateway(Limits(maxConcurrent = 1, queueSize = 1, queueTimeoutMs = 200, maxRequestsPerKey = 5))
        val blocker = t.chatAsync()
        assertTrue(eventually { upstream.started.get() >= 3 })
        val timedOut = t.chat(key = KEY_B)
        assertEquals(503, timedOut.statusCode()); assertTrue(timedOut.body().contains("queue_timeout"))
        blocker.get()
    }

    @Test fun perKeyConcurrencyIsCapped() {
        upstream.tokens = 2; upstream.tokenDelayMs = 400
        val g = gateway(Limits(maxConcurrent = 2, queueSize = 2, maxRequestsPerKey = 1))
        val slow = g.chatAsync(KEY_A)
        assertTrue(eventually { upstream.started.get() == 1 })
        val second = g.chat(key = KEY_A)
        assertEquals(429, second.statusCode()); assertTrue(second.body().contains("too_many_concurrent_requests"))
        assertEquals("another key is unaffected", 200, g.chat(key = KEY_B).statusCode())
        assertEquals(200, slow.get().statusCode())
    }

    @Test fun clientDisconnectCancelsUpstreamGeneration() {
        upstream.tokens = 1000; upstream.tokenDelayMs = 30
        val g = gateway()
        val resp = g.openStream()
        val reader = resp.body().bufferedReader()
        assertTrue(eventually { upstream.started.get() == 1 })
        assertNotNull(reader.readLine())
        resp.body().close()
        assertTrue("upstream generation must be cancelled", eventually(8000) { upstream.cancelled.get() == 1 })
        assertTrue("slot must be released", eventually { g.request("GET", "/stats").body().contains("\"active\":0") })
        assertEquals(200, run { upstream.tokens = 1; upstream.tokenDelayMs = 0; g.chat().statusCode() })
    }

    @Test fun upstreamFailureIs502AndStreamsGetErrorEvent() {
        upstream.failWith = 500
        val g = gateway()
        val r = g.chat()
        assertEquals(502, r.statusCode()); assertTrue(r.body().contains("upstream_error"))
        assertFalse(r.body().contains("boom"))
        val sse = g.openStream().body().bufferedReader().readText()
        assertTrue(sse.contains("upstream_error") && sse.contains("[DONE]"))
    }

    @Test fun healthzReportsUpstreamState() {
        assertEquals(200, gateway().request("GET", "/healthz", null).statusCode())
        val down = gateway(upstreamUrl = "http://127.0.0.1:1")
        val r = down.request("GET", "/healthz", null)
        assertEquals(503, r.statusCode()); assertTrue(r.body().contains("\"upstream\":\"down\""))
        assertEquals(502, down.chat().statusCode())
    }

    @Test fun rootServesChatPageWithoutKey() {
        val r = gateway().request("GET", "/", null)
        assertEquals(200, r.statusCode()); assertTrue(r.body().contains("<title>Private AI</title>"))
        assertFalse(r.body().contains("http://") || r.body().contains("https://"))
    }
}

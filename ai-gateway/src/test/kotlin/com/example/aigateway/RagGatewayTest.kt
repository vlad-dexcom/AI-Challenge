package com.example.aigateway

import kotlinx.coroutines.delay
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RagGatewayTest {
    private val upstream = FakeUpstream()
    private val gateways = mutableListOf<RunningGateway>()

    private class FakeRag(var delayMs: Long = 0, var fail: Boolean = false, var missingIndex: Boolean = false) : RagService {
        val calls = mutableListOf<Triple<String, String?, String>>()
        val resets = mutableListOf<Pair<String, String>>()
        override suspend fun ask(device: String, sessionId: String?, text: String): Result<RagReply> {
            calls += Triple(device, sessionId, text)
            if (delayMs > 0) delay(delayMs)
            if (fail) return Result.failure(IllegalStateException("model exploded"))
            return Result.success(RagReply(sessionId ?: "s-new", calls.size, "Sets of 5 [source].", false, listOf(RagSource("nutrition.md > Protein", 0.82f)), listOf(RagQuote("c1", "1.6 g/kg", true)), "cut", "protein intake", 3, 1200))
        }
        override fun reset(device: String, sessionId: String): Boolean = resets.add(device to sessionId)
        override fun status(): String? = if (missingIndex) "no index" else null
    }

    private fun gateway(rag: RagService?, limits: Limits = Limits()) = RunningGateway(testConfig(upstream.url, limits), rag).also { gateways += it }
    private fun ask(g: RunningGateway, text: String, session: String? = null, key: String? = KEY_A) =
        g.request("POST", "/v1/rag/chat", key, """{"text":${kotlinx.serialization.json.JsonPrimitive(text)}${if (session != null) ""","session":"$session"""" else ""}}""")

    @After fun tearDown() { gateways.forEach { it.stop() }; upstream.stop() }

    @Test fun answersWithSourcesQuotesAndSessionPerDevice() {
        val rag = FakeRag()
        val g = gateway(rag)
        val r = ask(g, "How much protein?", "s-abc")
        assertEquals(200, r.statusCode())
        assertTrue(r.body().contains("\"answer\":\"Sets of 5 [source].\"") && r.body().contains("nutrition.md > Protein") && r.body().contains("\"verified\":true") && r.body().contains("\"session\":\"s-abc\""))
        assertEquals(Triple("a", "s-abc", "How much protein?"), rag.calls.single())
        assertEquals("b", run { ask(g, "x", key = KEY_B); rag.calls.last().first })
        assertEquals(0, upstream.started.get())
    }

    @Test fun requiresKeyAndValidatesInput() {
        val rag = FakeRag()
        val g = gateway(rag)
        assertEquals(401, ask(g, "hi", key = null).statusCode())
        assertEquals(403, ask(g, "hi", key = "sk-wrong").statusCode())
        assertEquals(400, ask(g, "   ").statusCode())
        assertEquals(400, g.request("POST", "/v1/rag/chat", KEY_A, "not json").statusCode())
        assertEquals(400, ask(g, "x".repeat(2001)).statusCode())
        assertTrue(rag.calls.isEmpty())
    }

    @Test fun disabledRagIs404AndMissingIndexIs503() {
        assertEquals(404, ask(gateway(null), "hi").statusCode())
        val r = ask(gateway(FakeRag(missingIndex = true)), "hi")
        assertEquals(503, r.statusCode()); assertTrue(r.body().contains("rag_index_missing"))
    }

    @Test fun failureIs502WithShortMessage() {
        val r = ask(gateway(FakeRag(fail = true)), "hi")
        assertEquals(502, r.statusCode()); assertTrue(r.body().contains("model exploded"))
    }

    @Test fun resetForwardsTheSessionOfTheCallingDevice() {
        val rag = FakeRag()
        val r = gateway(rag).request("POST", "/v1/rag/reset", KEY_A, """{"session":"s-abc"}""")
        assertEquals(200, r.statusCode()); assertTrue(r.body().contains("\"reset\":true"))
        assertEquals("a" to "s-abc", rag.resets.single())
    }

    @Test fun ragTurnsShareTheGenerationQueueWithPlainChat() {
        val rag = FakeRag(delayMs = 800)
        val g = gateway(rag, Limits(maxConcurrent = 1, queueSize = 0, maxRequestsPerKey = 5))
        val first = g.http().sendAsync(g.build("POST", "/v1/rag/chat", KEY_A, """{"text":"slow"}"""), java.net.http.HttpResponse.BodyHandlers.ofString())
        assertTrue(eventually { rag.calls.isNotEmpty() })
        assertEquals(503, g.chat().statusCode())
        assertEquals(200, first.get().statusCode())
    }

    @Test fun modelsAdvertiseWhetherRagIsOn() {
        assertTrue(gateway(FakeRag()).request("GET", "/v1/models").body().contains("\"rag\":true"))
        assertFalse(gateway(null).request("GET", "/v1/models").body().contains("\"rag\":true"))
    }
}

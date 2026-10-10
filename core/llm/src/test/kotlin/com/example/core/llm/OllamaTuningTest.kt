package com.example.core.llm

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OllamaTuningTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    @Test fun parsesBaseAndPerPurposeValues() {
        val t = OllamaTuning.parse("numCtx=8192, keepAlive=30m, rewrite.maxTokens=96, cited.temperature=0.1, topK=40")
        assertEquals(8192, t.base.numCtx)
        assertEquals("30m", t.base.keepAlive)
        assertEquals(40, t.base.topK)
        assertEquals(96, t.resolve(CallPurpose.REWRITE).maxTokens)
        assertEquals(8192, t.resolve(CallPurpose.REWRITE).numCtx)
        assertEquals(0.1, t.resolve(CallPurpose.CITED_ANSWER).temperature!!, 1e-9)
        assertEquals(null, t.resolve(CallPurpose.ANSWER).maxTokens)
        assertEquals(OllamaTuning.DEFAULT, OllamaTuning.parse(null))
        assertEquals(OllamaTuning.DEFAULT, OllamaTuning.parse(" "))
    }

    @Test fun recommendedTuningAndNone() {
        val r = OllamaTuning.RECOMMENDED
        assertEquals(8192, r.resolve(CallPurpose.ANSWER).numCtx)
        assertEquals(64, r.resolve(CallPurpose.REWRITE).maxTokens)
        assertEquals(1024, r.resolve(CallPurpose.CITED_ANSWER).maxTokens)
        assertEquals(null, r.resolve(CallPurpose.MEMORY).maxTokens)
        assertEquals(OllamaTuning.DEFAULT, OllamaTuning.parse("none"))
    }

    @Test fun rejectsUnknownNames() {
        for (bad in listOf("nope=1", "nope.numCtx=1", "numCtx", "rewrite.nope=1", "numCtx=abc")) {
            assertTrue(bad, runCatching { OllamaTuning.parse(bad) }.isFailure)
        }
    }

    @Test fun generatorAppliesPerPurposeLimitsAndTemperaturePrecedence() = runTest {
        val bodies = mutableListOf<String>()
        val engine = MockEngine { req ->
            bodies += (req.body as TextContent).text
            respond("""{"message":{"role":"assistant","content":"ok"},"prompt_eval_count":50,"eval_count":10,"eval_duration":500000000,"prompt_eval_duration":100000000,"load_duration":2000000,"total_duration":900000000}""", HttpStatusCode.OK, jsonHeaders)
        }
        val stats = LlmCallStats()
        val gen = OllamaTextGenerator(
            tuning = OllamaTuning.parse("numCtx=4096,temperature=0.5,topP=0.9,keepAlive=10m,rewrite.maxTokens=64,rewrite.temperature=0"), stats = stats, engine = engine, sleep = {},
        )
        gen.generate(null, "q", GenerationOptions(temperature = 0.3, purpose = CallPurpose.REWRITE)).getOrThrow()
        gen.generate(null, "q", GenerationOptions(temperature = 0.3, purpose = CallPurpose.ANSWER)).getOrThrow()
        gen.generate(null, "q", GenerationOptions(purpose = CallPurpose.ANSWER)).getOrThrow()
        assertTrue(bodies[0].contains("\"num_ctx\":4096") && bodies[0].contains("\"num_predict\":64") && bodies[0].contains("\"temperature\":0.0"))
        assertTrue(bodies[0].contains("\"top_p\":0.9") && bodies[0].contains("\"keep_alive\":\"10m\""))
        assertTrue(bodies[1].contains("\"temperature\":0.3") && !bodies[1].contains("num_predict"))
        assertTrue(bodies[2].contains("\"temperature\":0.5"))
        val s = stats.summary().associateBy { it.purpose }
        assertEquals(1, s.getValue(CallPurpose.REWRITE).calls)
        assertEquals(2, s.getValue(CallPurpose.ANSWER).calls)
        assertEquals(20.0, s.getValue(CallPurpose.ANSWER).generationTokPerSec, 1e-6)
        assertEquals(500.0, s.getValue(CallPurpose.ANSWER).promptTokPerSec, 1e-6)
        stats.reset()
        assertTrue(stats.summary().isEmpty())
    }

    @Test fun defaultTuningKeepsTheOldRequestShape() = runTest {
        var body = ""
        val engine = MockEngine { req -> body = (req.body as TextContent).text; respond("""{"message":{"role":"assistant","content":"ok"}}""", HttpStatusCode.OK, jsonHeaders) }
        OllamaTextGenerator(engine = engine).generate(null, "q").getOrThrow()
        assertTrue(body.contains("\"num_ctx\":16384") && body.contains("\"temperature\":0.2") && body.contains("\"think\":false"))
        assertTrue(!body.contains("num_predict") && !body.contains("keep_alive") && !body.contains("top_p"))
    }

    @Test fun statsPercentilesUseTheNearestRank() = runTest {
        val stats = LlmCallStats()
        (1..10).forEach { stats.record(LlmCall(CallPurpose.MEMORY, it * 100, it, 10, 10, 20, 0)) }
        val m = stats.summary().single()
        assertEquals(900, m.promptTokensP90)
        assertEquals(1000, m.promptTokensMax)
        assertEquals(550.0, m.promptTokensAvg, 1e-9)
    }
}

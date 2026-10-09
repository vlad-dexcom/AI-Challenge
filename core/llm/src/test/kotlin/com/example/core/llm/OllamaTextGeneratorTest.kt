package com.example.core.llm

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OllamaTextGeneratorTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private fun reply(text: String) = """{"message":{"role":"assistant","content":${kotlinx.serialization.json.JsonPrimitive(text)}},"eval_count":1,"eval_duration":1000000}"""

    @Test fun sendsSystemAndUserWithThinkOffContextAndTemperature() = runTest {
        var body = ""
        val engine = MockEngine { req -> body = (req.body as TextContent).text; respond(reply(" Hi "), HttpStatusCode.OK, jsonHeaders) }
        val out = OllamaTextGenerator("m1", "http://h:1", engine = engine, sleep = {}).generate("sys", "hello").getOrThrow()
        assertEquals("Hi", out)
        assertTrue(body.indexOf("sys") < body.indexOf("hello"))
        assertTrue(body.contains("\"think\":false") && body.contains("\"num_ctx\":16384") && body.contains("\"temperature\":0.2"))
        assertFalse(body.contains("\"format\""))
    }

    @Test fun jsonOptionUsesJsonFormatAndPerCallTemperatureAndStripsFences() = runTest {
        var body = ""
        val engine = MockEngine { req -> body = (req.body as TextContent).text; respond(reply("```json\n{\"a\":1}\n```"), HttpStatusCode.OK, jsonHeaders) }
        val out = OllamaTextGenerator(engine = engine, sleep = {}).generate(null, "x", GenerationOptions(temperature = 0.0, json = true)).getOrThrow()
        assertEquals("{\"a\":1}", out)
        assertTrue(body.contains("\"format\":\"json\"") && body.contains("\"temperature\":0.0"))
    }

    @Test fun retriesServerErrorsButNotMissingModel() = runTest {
        var calls = 0
        val flaky = MockEngine { calls++; if (calls < 3) respond("boom", HttpStatusCode.InternalServerError, jsonHeaders) else respond(reply("ok"), HttpStatusCode.OK, jsonHeaders) }
        assertEquals("ok", OllamaTextGenerator(engine = flaky, sleep = {}).generate(null, "x").getOrThrow())
        assertEquals(3, calls)

        var missing = 0
        val notFound = MockEngine { missing++; respond("""{"error":"model 'm' not found"}""", HttpStatusCode.NotFound, jsonHeaders) }
        val r = OllamaTextGenerator(engine = notFound, sleep = {}).generate(null, "x")
        assertTrue(r.isFailure && r.exceptionOrNull()!!.message!!.contains("404"))
        assertEquals(1, missing)
    }

    @Test fun dropsJsonFormatWhenTheRunnerHasNoStructuredOutput() = runTest {
        val bodies = mutableListOf<String>()
        val engine = MockEngine { req ->
            val b = (req.body as TextContent).text; bodies += b
            if (b.contains("\"format\"")) respond("""{"error":"structured output is unavailable"}""", HttpStatusCode.NotImplemented, jsonHeaders)
            else respond(reply("```json\n{\"ok\":true}\n```"), HttpStatusCode.OK, jsonHeaders)
        }
        val gen = OllamaTextGenerator(engine = engine, sleep = {})
        assertEquals("{\"ok\":true}", gen.generate(null, "x", GenerationOptions(json = true)).getOrThrow())
        assertEquals(2, bodies.size)
        assertEquals("{\"ok\":true}", gen.generate(null, "y", GenerationOptions(json = true)).getOrThrow())
        assertEquals(3, bodies.size)
        assertFalse(bodies.last().contains("\"format\""))
    }

    @Test fun givesUpAfterMaxRetriesOnNetworkErrors() = runTest {
        var calls = 0
        val down = MockEngine { calls++; throw java.io.IOException("refused") }
        assertTrue(OllamaTextGenerator(engine = down, maxRetries = 2, sleep = {}).generate(null, "x").isFailure)
        assertEquals(3, calls)
    }
}

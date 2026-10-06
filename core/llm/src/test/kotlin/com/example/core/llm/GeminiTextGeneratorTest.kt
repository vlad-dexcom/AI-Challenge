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

class GeminiTextGeneratorTest {
    @Test fun geminiTextGeneratorParsesAnswerAndSendsSystemInstruction() = runTest {
        var body = ""
        var url = ""
        val engine = MockEngine { req ->
            body = (req.body as TextContent).text
            url = req.url.toString()
            respond(
                """{"candidates":[{"content":{"parts":[{"text":" Hello "},{"text":"world"}]}}]}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val out = GeminiTextGenerator("KEY", "m1", engine = engine).generate("sys", "hi").getOrThrow()
        assertEquals("Hello world", out)
        assertTrue(url.endsWith("/models/m1:generateContent"))
        assertTrue(body.contains("\"systemInstruction\"") && body.contains("sys") && body.contains("hi"))
    }

    @Test fun geminiTextGeneratorRetries429ButNot400() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            if (calls == 1) respond("slow down", HttpStatusCode.TooManyRequests)
            else respond("""{"candidates":[{"content":{"parts":[{"text":"ok"}]}}]}""", HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"))
        }
        assertEquals("ok", GeminiTextGenerator("K", engine = engine, sleep = {}).generate(null, "x").getOrThrow())
        assertEquals(2, calls)

        calls = 0
        val bad = MockEngine { calls++; respond("bad", HttpStatusCode.BadRequest) }
        assertTrue(GeminiTextGenerator("K", engine = bad, sleep = {}).generate(null, "x").isFailure)
        assertEquals(1, calls)
        assertTrue(GeminiTextGenerator("", engine = bad).generate(null, "x").isFailure)
    }
}

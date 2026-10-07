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

class OllamaChatClientTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    @Test fun chatSendsMessagesNonStreamingAndParsesReplyAndStats() = runTest {
        var body = ""
        var url = ""
        val engine = MockEngine { req ->
            body = (req.body as TextContent).text
            url = req.url.toString()
            respond(
                """{"model":"m1","message":{"role":"assistant","content":" Paris. "},"prompt_eval_count":10,"eval_count":50,"eval_duration":2000000000,"total_duration":2500000000}""",
                HttpStatusCode.OK, jsonHeaders,
            )
        }
        val reply = OllamaChatClient("http://host:1", "m1", engine).chat(listOf(OllamaMessage("user", "Capital of France?")), temperature = 0.3).getOrThrow()
        assertEquals("Paris.", reply.content)
        assertEquals(25.0, reply.tokensPerSecond, 0.001)
        assertEquals(2500, reply.totalMillis)
        assertEquals("http://host:1/api/chat", url)
        assertTrue(body.contains("\"stream\":false") && body.contains("Capital of France?") && body.contains("\"temperature\":0.3"))
    }

    @Test fun chatReportsHttpErrorAndEmptyAnswer() = runTest {
        val bad = OllamaChatClient(engine = MockEngine { respond("""{"error":"model not found"}""", HttpStatusCode.NotFound, jsonHeaders) })
        assertTrue(bad.chat(listOf(OllamaMessage("user", "x"))).exceptionOrNull()!!.message!!.contains("404"))
        val empty = OllamaChatClient(engine = MockEngine { respond("""{"message":{"role":"assistant","content":""}}""", HttpStatusCode.OK, jsonHeaders) })
        assertTrue(empty.chat(listOf(OllamaMessage("user", "x"))).isFailure)
    }

    @Test fun modelsListsNamesAndFailsWhenUnreachable() = runTest {
        val ok = OllamaChatClient(engine = MockEngine { respond("""{"models":[{"name":"a:1"},{"name":"b:2"}]}""", HttpStatusCode.OK, jsonHeaders) })
        assertEquals(listOf("a:1", "b:2"), ok.models().getOrThrow())
        val down = OllamaChatClient(engine = MockEngine { throw java.io.IOException("refused") })
        assertTrue(down.models().isFailure)
        assertTrue(down.chat(listOf(OllamaMessage("user", "x"))).isFailure)
    }
}

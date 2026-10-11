package com.example.core.llm

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OllamaStreamingClientTest {
    private val ndjson = headersOf(HttpHeaders.ContentType, "application/x-ndjson")
    private val msgs = listOf(OllamaMessage("user", "hi"))

    private val body = """
        {"model":"m","message":{"role":"assistant","content":"Hel"},"done":false}
        {"model":"m","message":{"role":"assistant","content":"lo"},"done":false}

        {"model":"m","message":{"role":"assistant","content":""},"done":true,"done_reason":"stop","prompt_eval_count":7,"eval_count":2,"eval_duration":1000000000,"total_duration":1500000000}
    """.trimIndent()

    @Test fun streamsChunksAndFinalCounters() = runTest {
        var sent = ""
        val engine = MockEngine { req ->
            sent = (req.body as TextContent).text
            respond(body, HttpStatusCode.OK, ndjson)
        }
        val chunks = OllamaStreamingClient("http://h:1", engine).chatStream(msgs, "m", temperature = 0.2, numCtx = 4096, maxTokens = 64).toList()
        assertEquals(listOf("Hel", "lo", ""), chunks.map { it.content })
        val last = chunks.last()
        assertTrue(last.done)
        assertEquals("stop", last.doneReason)
        assertEquals(7, last.promptTokens)
        assertEquals(2, last.completionTokens)
        assertEquals(1000, last.evalMillis)
        assertFalse(chunks.first().done)
        assertTrue(sent.contains("\"stream\":true") && sent.contains("\"num_ctx\":4096") && sent.contains("\"num_predict\":64"))
    }

    @Test fun collectorCanStopEarly() = runTest {
        val engine = MockEngine { respond(body, HttpStatusCode.OK, ndjson) }
        val first = OllamaStreamingClient(engine = engine).chatStream(msgs, "m").take(1).toList()
        assertEquals("Hel", first.single().content)
    }

    @Test fun httpErrorBecomesException() = runTest {
        val engine = MockEngine { respond("""{"error":"model not found"}""", HttpStatusCode.NotFound, ndjson) }
        val e = runCatching { OllamaStreamingClient(engine = engine).chatStream(msgs, "m").toList() }.exceptionOrNull() as OllamaStreamException
        assertEquals(404, e.status)
        assertTrue(e.message!!.contains("model not found"))
    }

    @Test fun inStreamErrorAndTruncatedStreamFail() = runTest {
        val err = MockEngine { respond("""{"message":{"content":"a"},"done":false}""" + "\n" + """{"error":"boom"}""", HttpStatusCode.OK, ndjson) }
        val e = runCatching { OllamaStreamingClient(engine = err).chatStream(msgs, "m").toList() }.exceptionOrNull() as OllamaStreamException
        assertNull(e.status)
        assertTrue(e.message!!.contains("boom"))

        val cut = MockEngine { respond("""{"message":{"content":"a"},"done":false}""", HttpStatusCode.OK, ndjson) }
        val e2 = runCatching { OllamaStreamingClient(engine = cut).chatStream(msgs, "m").toList() }.exceptionOrNull()
        assertTrue(e2 is OllamaStreamException)
    }
}

package com.example.core.llm

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GeminiEmbeddingClientTest {
    private fun okBody(n: Int, dim: Int) =
        """{"embeddings":[${(1..n).joinToString(",") { """{"values":[${(1..dim).joinToString(",") { "3.0" }}]}""" }}]}"""

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    @Test fun batchesRequestsAndSendsTaskTypeAndKey() = runTest {
        val bodies = mutableListOf<String>()
        var keyHeader: String? = null
        var url = ""
        val engine = MockEngine { req ->
            bodies += (req.body as TextContent).text
            keyHeader = req.headers["x-goog-api-key"]
            url = req.url.toString()
            val n = Json.parseToJsonElement(bodies.last()).jsonObject["requests"]!!.jsonArray.size
            respond(okBody(n, 4), HttpStatusCode.OK, jsonHeaders)
        }
        val client = GeminiEmbeddingClient("KEY", dimension = 4, batchSize = 2, engine = engine)
        val out = client.embed(listOf("a", "b", "c"), EmbeddingTaskType.RETRIEVAL_QUERY)

        assertEquals(3, out.size)
        assertEquals(2, bodies.size)
        assertEquals("KEY", keyHeader)
        assertTrue(url.endsWith("/models/gemini-embedding-001:batchEmbedContents"))
        val first = Json.parseToJsonElement(bodies[0]).jsonObject["requests"]!!.jsonArray[0].jsonObject
        assertEquals("RETRIEVAL_QUERY", first["taskType"]!!.jsonPrimitive.content)
        assertEquals("models/gemini-embedding-001", first["model"]!!.jsonPrimitive.content)
        assertEquals("4", first["outputDimensionality"]!!.jsonPrimitive.content)
        assertEquals(1.0, out[0].sumOf { (it * it).toDouble() }, 1e-5) // normalised
    }

    @Test fun retriesOnRateLimitThenSucceeds() = runTest {
        var calls = 0
        val sleeps = mutableListOf<Long>()
        val engine = MockEngine {
            if (++calls < 3) respondError(HttpStatusCode.TooManyRequests) else respond(okBody(1, 4), HttpStatusCode.OK, jsonHeaders)
        }
        val client = GeminiEmbeddingClient("K", dimension = 4, engine = engine, sleep = { sleeps += it })
        assertEquals(1, client.embed(listOf("x"), EmbeddingTaskType.RETRIEVAL_DOCUMENT).size)
        assertEquals(3, calls)
        assertEquals(listOf(1000L, 2000L), sleeps)
    }

    @Test fun clientErrorIsNotRetried() = runTest {
        var calls = 0
        val engine = MockEngine { calls++; respondError(HttpStatusCode.BadRequest, "bad") }
        val client = GeminiEmbeddingClient("K", dimension = 4, engine = engine, sleep = {})
        try {
            client.embed(listOf("x"), EmbeddingTaskType.RETRIEVAL_DOCUMENT); fail()
        } catch (e: EmbeddingException) {
            assertTrue(e.message!!.contains("400"))
        }
        assertEquals(1, calls)
    }

    @Test fun givesUpAfterMaxRetries() = runTest {
        var calls = 0
        val engine = MockEngine { calls++; respondError(HttpStatusCode.ServiceUnavailable) }
        val client = GeminiEmbeddingClient("K", dimension = 4, maxRetries = 2, engine = engine, sleep = {})
        try {
            client.embed(listOf("x"), EmbeddingTaskType.RETRIEVAL_DOCUMENT); fail()
        } catch (_: EmbeddingException) {}
        assertEquals(3, calls)
    }
}

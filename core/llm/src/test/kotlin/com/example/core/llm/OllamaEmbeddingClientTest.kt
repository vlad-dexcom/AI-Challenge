package com.example.core.llm

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OllamaEmbeddingClientTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private fun vectors(n: Int, dim: Int) = """{"embeddings":[${(1..n).joinToString(",") { "[" + (1..dim).joinToString(",") { "3.0" } + "]" }}]}"""

    @Test fun batchesInputsPrefixesByTaskTypeAndNormalises() = runTest {
        val inputs = mutableListOf<List<String>>()
        var url = ""
        val engine = MockEngine { req ->
            url = req.url.toString()
            val arr = Json.parseToJsonElement((req.body as TextContent).text).jsonObject["input"]!!.jsonArray.map { it.jsonPrimitive.content }
            inputs += arr
            respond(vectors(arr.size, 4), HttpStatusCode.OK, jsonHeaders)
        }
        val client = OllamaEmbeddingClient("m", 4, EmbedPrompts("D:", "Q:"), "http://h:1", batchSize = 2, engine = engine)
        val out = client.embed(listOf("a", "b", "c"), EmbeddingTaskType.RETRIEVAL_DOCUMENT)
        assertEquals("http://h:1/api/embed", url)
        assertEquals(listOf(listOf("D:a", "D:b"), listOf("D:c")), inputs)
        assertEquals(3, out.size)
        assertEquals(0.5f, out[0][0], 1e-6f)
        client.embed(listOf("x"), EmbeddingTaskType.RETRIEVAL_QUERY)
        assertEquals(listOf("Q:x"), inputs.last())
        assertEquals("ollama:m+prompts", client.modelName)
    }

    @Test fun connectDetectsDimensionAndModelNameWithoutPrompts() = runTest {
        val engine = MockEngine { respond(vectors(1, 7), HttpStatusCode.OK, jsonHeaders) }
        val client = OllamaEmbeddingClient.connect("plain-model", EmbedPrompts.NONE, engine = engine)
        assertEquals(7, client.dimension)
        assertEquals("ollama:plain-model", client.modelName)
    }

    @Test fun defaultPromptsFollowModelFamilies() {
        assertTrue(EmbedPrompts.defaultsFor("embeddinggemma-2:270m").query.startsWith("task: search result"))
        assertTrue(EmbedPrompts.defaultsFor("qwen3-embedding:0.6b").document.isEmpty())
        assertTrue(EmbedPrompts.defaultsFor("qwen3-embedding:0.6b").query.startsWith("Instruct:"))
        assertTrue(EmbedPrompts.defaultsFor("bge-m3").isEmpty)
    }

    @Test fun errorsAreReportedAsEmbeddingException() = runTest {
        val missing = OllamaEmbeddingClient("m", 4, engine = MockEngine { respond("""{"error":"model not found"}""", HttpStatusCode.NotFound, jsonHeaders) })
        try { missing.embed(listOf("a"), EmbeddingTaskType.RETRIEVAL_QUERY); fail() } catch (e: EmbeddingException) { assertTrue(e.message!!.contains("404")) }
        val down = OllamaEmbeddingClient("m", 4, engine = MockEngine { throw java.io.IOException("refused") })
        try { down.embed(listOf("a"), EmbeddingTaskType.RETRIEVAL_QUERY); fail() } catch (e: EmbeddingException) { assertTrue(e.message!!.contains("not reachable")) }
        val wrongDim = OllamaEmbeddingClient("m", 8, engine = MockEngine { respond(vectors(1, 4), HttpStatusCode.OK, jsonHeaders) })
        try { wrongDim.embed(listOf("a"), EmbeddingTaskType.RETRIEVAL_QUERY); fail() } catch (e: IllegalStateException) { assertTrue(e.message!!.contains("Expected 8")) }
    }
}

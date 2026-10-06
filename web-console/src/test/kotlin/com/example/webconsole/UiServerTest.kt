package com.example.webconsole

import com.example.core.platform.toKxPath

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.HttpURLConnection
import java.net.URL
import kotlin.time.Instant
import com.example.core.llm.HashingEmbeddingClient
import com.example.rag.Chunk
import com.example.rag.CorpusLoader
import com.example.rag.Document
import com.example.rag.FixedSizeChunker
import com.example.rag.IndexStore
import com.example.rag.Indexer
import com.example.rag.StructureChunker
import com.example.rag.chunk

class UiServerTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var corpus: java.io.File
    private lateinit var index: java.io.File
    private lateinit var api: UiApi
    private lateinit var server: UiServer

    private val sample = "# Sleep\n\n## Hygiene\n\n" + "Sleep eight hours in a dark cool bedroom to recover. ".repeat(30) +
        "\n\n## Protein\n\n" + "Eat protein every day to build muscle. ".repeat(30) + "\n"

    @Before fun setUp() = runTest {
        corpus = tmp.newFolder("corpus").also { java.io.File(it, "sleep.md").writeText(sample) }
        index = tmp.newFolder("index")
        val docs = CorpusLoader.load(corpus.toKxPath())
        val embedder = HashingEmbeddingClient(64)
        IndexStore().save(Indexer(embedder) { Instant.fromEpochMilliseconds(0) }.build(docs, StructureChunker(400, 50), "t", "c"), java.io.File(index, "structure.json").toKxPath())
        api = UiApi(corpus, index, apiKey = "")
        server = UiServer(api, 0).also { it.start() }
    }

    @After fun tearDown() = server.stop()

    private fun http(method: String, path: String, body: String? = null): Pair<Int, String> {
        val c = URL("http://localhost:${server.port}$path").openConnection() as HttpURLConnection
        c.requestMethod = method
        if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body.toByteArray()) } }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream).readBytes().decodeToString()
        return code to text
    }

    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test fun chunkEndpointReturnsBothStrategiesWithStatsAndFlags() {
        val body = Json.encodeToString(ChunkRequest.serializer(), ChunkRequest(text = sample, fixedSize = 200, overlap = 20, minChars = 50, maxChars = 400))
        val (code, resp) = http("POST", "/api/chunk", body)
        assertEquals(200, code)
        val r = obj(resp)
        val fixed = r["fixed"]!!.jsonObject
        val structure = r["structure"]!!.jsonObject
        assertEquals("fixed", fixed["strategy"]!!.jsonPrimitive.content)
        assertEquals("structure", structure["strategy"]!!.jsonPrimitive.content)
        val expected = FixedSizeChunker(200, 20).chunk(Document("pasted.md", "Pasted text", sample))
        assertEquals(expected.size, fixed["chunks"]!!.jsonArray.size)
        assertEquals(expected.size.toString(), fixed["stats"]!!.jsonObject["count"]!!.jsonPrimitive.content)
        val first = fixed["chunks"]!!.jsonArray[0].jsonObject
        assertTrue(first["chunk"]!!.jsonObject.containsKey("chunkId"))
        assertEquals("true", first["endsMidSentence"]!!.jsonPrimitive.content)
        assertTrue(structure["chunks"]!!.jsonArray.all { it.jsonObject["length"]!!.jsonPrimitive.content.toInt() <= 400 })
    }

    @Test fun invalidParametersGive400() {
        val bad = Json.encodeToString(ChunkRequest.serializer(), ChunkRequest(text = "x", fixedSize = 10, overlap = 10))
        val (code, resp) = http("POST", "/api/chunk", bad)
        assertEquals(400, code)
        assertTrue(obj(resp)["error"]!!.jsonPrimitive.content.contains("overlap"))
        assertEquals(400, http("POST", "/api/chunk", "not json").first)
    }

    @Test fun emptyTextYieldsNoChunksNotAnError() {
        val (code, resp) = http("POST", "/api/chunk", """{"text":""}""")
        assertEquals(200, code)
        assertEquals(0, obj(resp)["fixed"]!!.jsonObject["chunks"]!!.jsonArray.size)
    }

    @Test fun filesAndFileEndpointsAndPathTraversalIsRejected() {
        assertTrue(http("GET", "/api/files").second.contains("sleep.md"))
        assertTrue(obj(http("GET", "/api/file?name=sleep.md").second)["text"]!!.jsonPrimitive.content.startsWith("# Sleep"))
        assertEquals(404, http("GET", "/api/file?name=..%2Findex%2Fstructure.json").first)
        assertEquals(404, http("GET", "/nope").first)
    }

    @Test fun searchEndpointRanksRelevantChunkFirst() {
        val (code, resp) = http("POST", "/api/search", """{"strategy":"structure","query":"protein to build muscle","k":2}""")
        assertEquals(200, code)
        val hits = obj(resp)["hits"]!!.jsonArray
        assertEquals(2, hits.size)
        assertTrue(hits[0].jsonObject["chunk"]!!.jsonObject["section"]!!.jsonPrimitive.content.contains("Protein"))
        assertEquals(404, http("POST", "/api/search", """{"strategy":"fixed","query":"x"}""").first)
    }

    @Test fun servesHtmlPage() {
        val (code, html) = http("GET", "/")
        assertEquals(200, code)
        assertTrue(html.contains("Chunk visualiser"))
    }
}

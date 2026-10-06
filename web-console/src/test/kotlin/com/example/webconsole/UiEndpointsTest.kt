package com.example.webconsole

import com.example.core.platform.toKxPath

import com.example.core.llm.HashingEmbeddingClient
import com.example.rag.CorpusLoader
import com.example.rag.Document
import com.example.rag.IndexStore
import com.example.rag.Indexer
import com.example.rag.StructureChunker
import com.example.rag.VectorIndex
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.time.Instant

/** HTTP endpoints of [UiApi] that inspect indexes and run the retrieval eval. */
class UiEndpointsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val text = "# Sleep\n\n## Hygiene\n\n" + "Sleep eight hours in a dark cool bedroom to recover. ".repeat(20) +
        "\n\n## Protein\n\n" + "Eat protein every day to build muscle. ".repeat(20) + "\n"
    private val docs = listOf(Document("sleep.md", "Sleep", text))
    private val chunker = StructureChunker(400, 50)

    private suspend fun good() = Indexer(HashingEmbeddingClient(64)) { Instant.fromEpochMilliseconds(0) }.build(docs, chunker, "max=400, min=50", "corpus")

    private lateinit var server: UiServer

    private fun startServer(api: UiApi) { server = UiServer(api, 0).also { it.start() } }

    @After fun stop() { if (this::server.isInitialized) server.stop() }

    private fun get(path: String): Pair<Int, String> {
        val c = URL("http://localhost:${server.port}$path").openConnection() as HttpURLConnection
        val code = c.responseCode
        return code to (if (code < 400) c.inputStream else c.errorStream).readBytes().decodeToString()
    }

    @Test fun inspectEndpointReturnsReportAnd404() = runTest {
        val corpus = tmp.newFolder("corpus").also { File(it, "sleep.md").writeText(text) }
        val idx = tmp.newFolder("index")
        IndexStore().save(good(), File(idx, "structure.json").toKxPath())
        startServer(UiApi(corpus, idx, apiKey = ""))

        val (code, body) = get("/api/index/inspect?strategy=structure")
        assertEquals(200, code)
        val o = Json.parseToJsonElement(body).jsonObject
        assertEquals("structure", o["strategy"]!!.jsonPrimitive.content)
        assertTrue(o["checks"].toString().contains("\"ok\":false").not())
        assertEquals(404, get("/api/index/inspect?strategy=fixed").first)

        File(corpus, "sleep.md").writeText(text.replace("protein", "carbs"))
        assertTrue(get("/api/index/inspect?strategy=structure").second.contains("DIFFERS"))

        val ping = get("/api/embedder/ping?strategy=structure")
        assertEquals(200, ping.first)
        assertTrue(ping.second.contains("\"lexicalOnly\":true"))
    }

    @Test fun pingWithoutKeyForGeminiIndexExplainsWhy() = runTest {
        val corpus = tmp.newFolder("corpus")
        val idx = tmp.newFolder("index")
        val i = good()
        IndexStore().save(VectorIndex(i.meta.copy(embeddingModel = "gemini-embedding-001"), i.entries), File(idx, "structure.json").toKxPath())
        startServer(UiApi(corpus, idx, apiKey = ""))
        val (code, body) = get("/api/embedder/ping?strategy=structure")
        assertEquals(400, code)
        assertTrue(body.contains("GEMINI_API_KEY"))
    }

    @Test fun evalEndpointReturnsMetricsFor404AndSuccess() = runTest {
        val corpus = tmp.newFolder("corpus").also { File(it, "sleep.md").writeText("# Sleep\n\n## Hygiene\n\nSleep eight hours in a dark cool bedroom to recover.") }
        val idx = tmp.newFolder("index")
        val e = HashingEmbeddingClient(256)
        IndexStore().save(Indexer(e) { Instant.fromEpochMilliseconds(0) }.build(CorpusLoader.load(corpus.toKxPath()), StructureChunker(400, 10), "p", "c"), File(idx, "structure.json").toKxPath())
        val qf = tmp.newFile("q.json").also {
            it.writeText("""{"questions":[{"id":"a","type":"direct","question":"hours of sleep","expectedSource":"sleep.md","expectedSection":"Sleep"}]}""")
        }
        val server = UiServer(UiApi(corpus, idx, apiKey = "", evalFile = qf.path), 0).also { it.start() }
        try {
            fun get(path: String): Pair<Int, String> {
                val c = java.net.URL("http://localhost:${server.port}$path").openConnection() as java.net.HttpURLConnection
                val code = c.responseCode
                return code to (if (code < 400) c.inputStream else c.errorStream).readBytes().decodeToString()
            }
            val (code, body) = get("/api/eval?strategy=structure")
            assertEquals(200, code)
            assertTrue(body.contains("\"hitAt1\":1.0"))
            assertEquals(404, get("/api/eval?strategy=fixed").first)
        } finally { server.stop() }
    }
}

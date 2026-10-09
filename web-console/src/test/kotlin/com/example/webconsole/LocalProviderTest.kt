package com.example.webconsole

import com.example.core.llm.EmbeddingClient
import com.example.core.llm.HashingEmbeddingClient
import com.example.core.llm.TextGenerator
import com.example.core.platform.toKxPath
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import io.ktor.client.engine.mock.respond
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
import com.example.rag.CorpusLoader
import com.example.rag.IndexStore
import com.example.rag.Indexer
import com.example.rag.StructureChunker

/** The `ollama` provider must use only the local index, local embedder and local generator - never the cloud ones. */
class LocalProviderTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: UiServer
    private val cloudCalls = mutableListOf<String>()
    private val localModels = mutableListOf<String>()

    private val sample = "# Sleep\n\n## Hygiene\n\n" + "Sleep eight hours in a dark cool bedroom to recover. ".repeat(30) +
        "\n\n## Protein\n\n" + "Eat protein every day to build muscle. ".repeat(30) + "\n"

    private fun start(withLocalIndex: Boolean = true) = runTest {
        val corpus = tmp.newFolder("corpus").also { File(it, "sleep.md").writeText(sample) }
        val cloudIndex = tmp.newFolder("index")
        val localIndex = tmp.newFolder("index-local")
        suspend fun build(dir: File) = IndexStore().save(
            Indexer(HashingEmbeddingClient(64)) { Instant.fromEpochMilliseconds(0) }.build(CorpusLoader.load(corpus.toKxPath()), StructureChunker(400, 50), "t", "c"),
            File(dir, "structure.json").toKxPath())
        build(cloudIndex)
        if (withLocalIndex) build(localIndex)
        val api = UiApi(
            corpus, cloudIndex, "", controlFile = tmp.newFile("c.json").also { it.writeText("""{"questions":[{"id":"c01","category":"specific","question":"How much protein?"}]}""") }.path,
            generatorFactory = { m -> cloudCalls += m; TextGenerator { _, _ -> Result.success("cloud answer") } },
            sessionsDir = tmp.newFolder("sessions"),
            localIndexDir = localIndex,
            localGeneratorFactory = { m -> localModels += m; TextGenerator { _, _ -> Result.success("local answer [1]") } },
            localEmbedderFactory = { meta -> HashingEmbeddingClient(meta.dimension) as EmbeddingClient },
            localModels = { listOf("gemma-x", "other:1b") },
        )
        server = UiServer(api, 0).also { it.start() }
    }

    @After fun tearDown() { if (::server.isInitialized) server.stop() }

    private fun call(method: String, path: String, body: String? = null): Pair<Int, kotlinx.serialization.json.JsonObject> {
        val c = URL("http://localhost:${server.port}$path").openConnection() as HttpURLConnection
        c.requestMethod = method
        if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body.toByteArray()) } }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream).readBytes().decodeToString()
        return code to Json.parseToJsonElement(text).jsonObject
    }

    @Test fun localProviderAnswersWithLocalGeneratorAndModelNameWithColon() {
        start()
        val (code, r) = call("POST", "/api/chat", """{"question":"protein to build muscle","mode":"rag","provider":"ollama","model":"gemma4:26b-a4b-it-qat","citations":false}""")
        assertEquals(200, code)
        assertEquals("local answer [1]", r["results"]!!.jsonArray.single().jsonObject["answer"]!!.jsonPrimitive.content)
        assertEquals(listOf("gemma4:26b-a4b-it-qat"), localModels)
        assertTrue(cloudCalls.isEmpty())
    }

    @Test fun localProviderWithoutModelUsesTheConfiguredDefault() {
        start()
        call("POST", "/api/chat", """{"question":"hi","mode":"no_rag","provider":"ollama"}""")
        assertEquals(listOf(com.example.core.llm.OllamaChatClient.DEFAULT_MODEL), localModels)
    }

    @Test fun missingLocalIndexExplainsHowToBuildIt() {
        start(withLocalIndex = false)
        val (code, r) = call("POST", "/api/chat", """{"question":"protein","mode":"rag","provider":"ollama"}""")
        assertEquals(404, code)
        assertTrue(r["error"]!!.jsonPrimitive.content.contains("--provider ollama"))
        assertTrue(cloudCalls.isEmpty())
    }

    @Test fun unknownProviderIsRejectedAndCloudStillWorksByDefault() {
        start()
        assertEquals(400, call("POST", "/api/chat", """{"question":"hi","mode":"no_rag","provider":"openai"}""").first)
        val (code, r) = call("POST", "/api/chat", """{"question":"hi","mode":"no_rag"}""")
        assertEquals(200, code)
        assertEquals("cloud answer", r["results"]!!.jsonArray.single().jsonObject["answer"]!!.jsonPrimitive.content)
        assertTrue(localModels.isEmpty())
    }

    @Test fun configListsLocalIndexesAndDefaultModel() {
        start()
        val (_, cfg) = call("GET", "/api/chat/config")
        assertEquals(listOf("structure"), cfg["localStrategies"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(com.example.core.llm.OllamaChatClient.DEFAULT_MODEL, cfg["localDefaultModel"]!!.jsonPrimitive.content)
        assertEquals(listOf("gemma-x", "other:1b"), cfg["localModels"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test fun installedChatModelsHidesEmbeddingModelsAndSurvivesAnUnreachableServer() {
        val h = io.ktor.http.headersOf(io.ktor.http.HttpHeaders.ContentType, "application/json")
        val ok = com.example.core.llm.OllamaChatClient(engine = io.ktor.client.engine.mock.MockEngine {
            respond("""{"models":[{"name":"gemma4:26b"},{"name":"embeddinggemma-2:270m"}]}""", io.ktor.http.HttpStatusCode.OK, h)
        })
        assertEquals(listOf("gemma4:26b"), installedChatModels(ok))
        val down = com.example.core.llm.OllamaChatClient(engine = io.ktor.client.engine.mock.MockEngine { throw java.io.IOException("refused") })
        assertTrue(installedChatModels(down).isEmpty())
    }

    @Test fun sessionMessagesCanUseTheLocalProvider() {
        start()
        val id = call("POST", "/api/sessions").second["session"]!!.jsonObject["id"]!!.jsonPrimitive.content
        val (code, _) = call("POST", "/api/sessions/$id/messages", """{"text":"protein to build muscle","options":{"provider":"ollama","memoryMode":"NONE"}}""")
        assertEquals(200, code)
        assertTrue(localModels.isNotEmpty())
        assertTrue(cloudCalls.isEmpty())
    }
}

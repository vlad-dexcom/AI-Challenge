package com.example.rag

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

class ChatApiTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: UiServer
    private val prompts = mutableListOf<Pair<String?, String>>()
    private var reply: Result<String> = Result.success("Fake answer [1]")
    private var models = mutableListOf<String>()

    private val sample = "# Sleep\n\n## Hygiene\n\n" + "Sleep eight hours in a dark cool bedroom to recover. ".repeat(30) +
        "\n\n## Protein\n\n" + "Eat protein every day to build muscle. ".repeat(30) + "\n"

    private fun start(apiKey: String, generator: Boolean = true) = runTest {
        val corpus = tmp.newFolder("corpus").also { File(it, "sleep.md").writeText(sample) }
        val index = tmp.newFolder("index")
        IndexStore().save(
            Indexer(HashingEmbeddingClient(64)) { Instant.EPOCH }.build(CorpusLoader.load(corpus), StructureChunker(400, 50), "t", "c"),
            File(index, "structure.json"),
        )
        val control = tmp.newFile("control.json").also {
            it.writeText("""{"questions":[{"id":"c01","category":"specific","question":"How much protein?"}]}""")
        }
        val api = UiApi(corpus, index, apiKey, controlFile = control.path, generatorFactory = if (generator) { m ->
            models += m
            TextGenerator { sys, p -> prompts += sys to p; reply }
        } else { _ -> null })
        server = UiServer(api, 0).also { it.start() }
    }

    @After fun tearDown() { if (::server.isInitialized) server.stop() }

    private fun post(body: String): Pair<Int, kotlinx.serialization.json.JsonObject> = call("POST", "/api/chat", body)

    private fun call(method: String, path: String, body: String? = null): Pair<Int, kotlinx.serialization.json.JsonObject> {
        val c = URL("http://localhost:${server.port}$path").openConnection() as HttpURLConnection
        c.requestMethod = method
        if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body.toByteArray()) } }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream).readBytes().decodeToString()
        return code to Json.parseToJsonElement(text).jsonObject
    }

    @Test fun ragModeReturnsAnswerSourcesAndChunks() {
        start("")
        val (code, r) = post("""{"question":"protein to build muscle","mode":"rag","topK":2}""")
        assertEquals(200, code)
        val t = r["results"]!!.jsonArray.single().jsonObject
        assertEquals("rag", t["mode"]!!.jsonPrimitive.content)
        assertEquals("Fake answer [1]", t["answer"]!!.jsonPrimitive.content)
        assertEquals(2, t["sources"]!!.jsonArray.size)
        assertEquals(2, t["chunks"]!!.jsonArray.size)
        assertTrue(t["sources"]!!.jsonArray[0].jsonObject["label"]!!.jsonPrimitive.content.startsWith("sleep.md"))
        assertTrue(t["chunks"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content.isNotBlank())
        assertEquals("false", t["notFound"]!!.jsonPrimitive.content)
        assertTrue(prompts.single().second.startsWith("Context:"))
    }

    @Test fun noRagModeHasNoSourcesAndPlainQuestionPrompt() {
        start("")
        val (code, r) = post("""{"question":"hello there","mode":"no_rag"}""")
        assertEquals(200, code)
        val t = r["results"]!!.jsonArray.single().jsonObject
        assertEquals("no_rag", t["mode"]!!.jsonPrimitive.content)
        assertEquals(0, t["sources"]!!.jsonArray.size)
        assertEquals("hello there", prompts.single().second)
    }

    @Test fun compareReturnsBothModesInOrder() {
        start("")
        val (code, r) = post("""{"question":"protein?","mode":"compare"}""")
        assertEquals(200, code)
        val modes = r["results"]!!.jsonArray.map { it.jsonObject["mode"]!!.jsonPrimitive.content }
        assertEquals(listOf("no_rag", "rag"), modes)
        assertEquals(2, prompts.size)
        assertTrue(r["results"]!!.jsonArray[1].jsonObject["sources"]!!.jsonArray.isNotEmpty())
    }

    @Test fun notFoundAnswerIsFlaggedAndModelIsForwarded() {
        start("")
        reply = Result.success("The knowledge base does not cover this.")
        val (_, r) = post("""{"question":"world record?","mode":"rag","model":"gemini-x"}""")
        assertEquals("true", r["results"]!!.jsonArray.single().jsonObject["notFound"]!!.jsonPrimitive.content)
        assertEquals(listOf("gemini-x"), models)
    }

    @Test fun notFoundHeuristicCoversCommonRefusals() {
        assertTrue(ChatApi.looksLikeNotFound("I am sorry, but the provided knowledge base does not contain information about X."))
        assertFalse(ChatApi.looksLikeNotFound("Eat 1.6-2.2 g/kg of protein [1]."))
    }

    @Test fun generatorFailureIsReportedPerModeNotAsHttpError() {
        start("")
        reply = Result.failure(IllegalStateException("Gemini HTTP 429"))
        val (code, r) = post("""{"question":"q","mode":"compare"}""")
        assertEquals(200, code)
        assertTrue(r["results"]!!.jsonArray.all { it.jsonObject["error"]!!.jsonPrimitive.content.contains("429") })
        assertTrue(r["results"]!!.jsonArray.all { it.jsonObject["answer"] == null || it.jsonObject["answer"]!!.toString() == "null" })
    }

    @Test fun invalidInputGives400() {
        start("")
        assertEquals(400, post("not json").first)
        assertEquals(400, post("""{"question":"   ","mode":"rag"}""").first)
        assertEquals(400, post("""{"question":"q","mode":"weird"}""").first)
        assertEquals(400, post("""{"question":"q","topK":0}""").first)
        assertEquals(400, post("""{"question":"q","topK":99}""").first)
        assertEquals(400, post("""{"question":"q","strategy":"nope"}""").first)
        assertEquals(400, post("""{"question":"q","model":"a b/../c"}""").first)
        assertEquals(400, post("""{"question":"${"x".repeat(2001)}"}""").first)
        assertTrue(post("""{"question":""}""").second["error"]!!.jsonPrimitive.content.contains("empty"))
    }

    @Test fun missingIndexIs404AndMissingKeyIs400WithClearMessage() {
        start("", generator = false)
        val (code, r) = post("""{"question":"q","mode":"no_rag"}""")
        assertEquals(400, code)
        assertTrue(r["error"]!!.jsonPrimitive.content.contains("GEMINI_API_KEY"))
        File(tmp.root, "index/structure.json").delete()
        assertEquals(404, post("""{"question":"q","mode":"rag"}""").first)
    }

    @Test fun configListsStrategiesAndControlQuestions() {
        start("k")
        val (code, r) = call("GET", "/api/chat/config")
        assertEquals(200, code)
        assertEquals(listOf("structure"), r["strategies"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("true", r["keyConfigured"]!!.jsonPrimitive.content)
        assertEquals("How much protein?", r["controlQuestions"]!!.jsonArray.single().jsonObject["question"]!!.jsonPrimitive.content)
    }

    @Test fun chatPageIsServed() {
        start("")
        val c = URL("http://localhost:${server.port}/").openConnection() as HttpURLConnection
        val html = c.inputStream.readBytes().decodeToString()
        assertTrue(html.contains("id=\"csend\"") && html.contains("Chunk visualiser"))
    }

    @Test fun markdownRendererIsServedAndUsedByThePageWithoutRawInnerHtml() {
        start("")
        fun get(path: String) = (URL("http://localhost:${server.port}$path").openConnection() as HttpURLConnection).let {
            it.contentType to it.inputStream.readBytes().decodeToString()
        }
        val (type, js) = get("/ui/markdown.js")
        assertTrue(type.startsWith("application/javascript"))
        assertTrue(js.contains("function esc(") && js.contains("noopener noreferrer") && js.contains("https?:"))
        val html = get("/").second
        assertTrue(html.contains("""<script src="/ui/markdown.js">"""))
        assertTrue(html.contains("md(t.answer") && html.contains("md(c.text)"))
        assertFalse(html.contains("${'$'}{t.answer"))
        assertFalse(html.contains("${'$'}{c.text}"))
    }

    @Test fun stagedRequestReturnsPerStageTrace() {
        start("")
        val (code, r) = post("""{"question":"protein to build muscle","mode":"rag","topK":2,"topKBefore":4,"threshold":0.0,"filter":true,"rerank":true}""")
        assertEquals(200, code)
        val t = r["results"]!!.jsonArray.single().jsonObject
        val trace = t["trace"]!!.jsonObject
        assertEquals("protein to build muscle", trace["searchQuery"]!!.jsonPrimitive.content)
        assertTrue(trace["retrieved"]!!.jsonArray.size >= trace["filtered"]!!.jsonArray.size)
        assertEquals(2, trace["reranked"]!!.jsonArray.size)
        assertTrue(trace["reranked"]!!.jsonArray[0].jsonObject["rerankScore"] != null)
        assertEquals("false", t["insufficient"]!!.jsonPrimitive.content)
        assertEquals("1", t["llmCalls"]!!.jsonPrimitive.content)
    }

    @Test fun filterRejectingEverythingSkipsTheModel() {
        start("")
        val (code, r) = post("""{"question":"protein","mode":"rag","filter":true,"threshold":1.0,"topKBefore":5}""")
        assertEquals(200, code)
        val t = r["results"]!!.jsonArray.single().jsonObject
        assertEquals("true", t["insufficient"]!!.jsonPrimitive.content)
        assertEquals("true", t["notFound"]!!.jsonPrimitive.content)
        assertEquals(0, t["sources"]!!.jsonArray.size)
        assertTrue(prompts.isEmpty())
    }

    @Test fun rewriteUsesTheModelAndFallsBackWhenItDrifts() {
        start("")
        reply = Result.success("completely different squat knee valgus")
        val (_, r) = post("""{"question":"protein to build muscle","mode":"rag","rewrite":true,"topKBefore":4}""")
        val trace = r["results"]!!.jsonArray.single().jsonObject["trace"]!!.jsonObject
        assertEquals("protein to build muscle", trace["searchQuery"]!!.jsonPrimitive.content)
        assertTrue(trace["rewriteFallback"]!!.jsonPrimitive.content.startsWith("drift"))
        assertEquals(2, prompts.size)
    }

    @Test fun invalidStageSettingsAreRejected() {
        start("")
        assertEquals(400, post("""{"question":"x","filter":true,"threshold":1.5}""").first)
        assertEquals(400, post("""{"question":"x","filter":true,"topK":8,"topKBefore":4}""").first)
        assertEquals(400, post("""{"question":"x","topKBefore":0}""").first)
    }
}

package com.example.webconsole

import com.example.core.llm.HashingEmbeddingClient
import com.example.devassistant.GitMcpGateway
import com.example.devassistant.GitRunner
import com.example.devassistant.HelpAssistant
import com.example.rag.Retriever
import com.example.rag.ScriptedGenerator
import com.example.rag.hit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.HttpURLConnection
import java.net.URL

/** `/api/help`: request validation, response shape and the disabled/not-configured cases. */
class HelpApiTest {
    private val git = GitMcpGateway(GitRunner { args ->
        when (args.first()) {
            "rev-parse" -> "main"
            "log" -> "abc Subject"
            else -> "## main"
        }
    })
    private val reply = """{"answerable":true,"answer":"Agent logic lives in :agent [1].","sources":[{"file":"docs/modules.md","section":"Modules","chunkId":"m#0"}],"quotes":[{"chunkId":"m#0","text":"holds all agent logic: memory, profile"}]}"""

    private fun assistant() = HelpAssistant(
        { listOf(hit("m#0", 0.9f, "docs/modules.md", "Modules", "The :agent module holds all agent logic: memory, profile, MCP.")) },
        ScriptedGenerator(mutableListOf(Result.success(reply))), git,
    )

    private lateinit var server: UiServer
    private val tmp = TemporaryFolder().also { it.create() }

    @After fun stop() { if (this::server.isInitialized) server.stop(); tmp.delete() }

    private fun post(api: UiApi, body: String): Pair<Int, String> {
        server = UiServer(api, 0).also { it.start() }
        val c = URL("http://localhost:${server.port}/api/help").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.outputStream.use { it.write(body.toByteArray()) }
        val code = c.responseCode
        return code to (if (code < 400) c.inputStream else c.errorStream).readBytes().decodeToString()
    }

    private fun uiApi(help: HelpApi?) = UiApi(tmp.newFolder("corpus"), tmp.newFolder("index"), apiKey = "", helpApi = help)

    @Test fun answersWithSourcesAndMcpCalls() {
        val (code, body) = post(uiApi(HelpApi { assistant() }), """{"question":"/help where does agent logic live?"}""")
        assertEquals(body, 200, code)
        val o = Json.parseToJsonElement(body).jsonObject
        assertEquals("where does agent logic live?", o["question"]!!.jsonPrimitive.content)
        assertFalse(o["idk"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("docs/modules.md", o["sources"]!!.jsonArray.single().jsonObject["file"]!!.jsonPrimitive.content)
        assertEquals(listOf("git_branch", "git_status"), o["mcpCalls"]!!.jsonArray.map { it.jsonObject["tool"]!!.jsonPrimitive.content })
    }

    @Test fun rejectsEmptyQuestion() {
        val (code, body) = post(uiApi(HelpApi { assistant() }), """{"question":"/help  "}""")
        assertEquals(400, code); assertTrue(body, "empty" in body)
    }

    @Test fun missingKeyIsReportedAsBadRequest() {
        val (code, body) = post(uiApi(HelpApi { throw IllegalArgumentException("GEMINI_API_KEY is not set") }), """{"question":"hi"}""")
        assertEquals(400, code); assertTrue(body, "GEMINI_API_KEY" in body)
    }

    @Test fun disabledWhenNotConfigured() {
        assertEquals(404, post(uiApi(null), """{"question":"hi"}""").first)
    }
}

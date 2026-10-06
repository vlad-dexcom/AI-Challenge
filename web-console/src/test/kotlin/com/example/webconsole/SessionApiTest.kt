package com.example.webconsole

import com.example.core.platform.toKxPath

import com.example.rag.IndexMeta
import com.example.rag.IndexStore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import com.example.rag.chat.FakeLlm
import com.example.rag.chat.SessionStore
import com.example.rag.chat.fixtureIndex

class SessionApiTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun api(llm: FakeLlm = FakeLlm(extractor = { Result.success("""{"goal":"plan","constraints":{"diet":"vegetarian"}}""") })): SessionApi = runTestApi(llm)

    private fun runTestApi(llm: FakeLlm): SessionApi {
        val dir = tmp.newFolder()
        val idx = kotlinx.coroutines.runBlocking { fixtureIndex() }
        val index = java.io.File(dir, "idx").also { it.mkdirs() }
        IndexStore().save(idx, java.io.File(index, "structure.json").toKxPath())
        return SessionApi(SessionStore(java.io.File(dir, "s").toKxPath()), index, "key", { com.example.core.llm.HashingEmbeddingClient(64) }, { llm })
    }

    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject
    private fun sid(created: String) = obj(created)["session"]!!.jsonObject["id"]!!.toString().trim('"')

    @Test fun createSendGetEditResetFlow() {
        val a = api()
        val id = sid(a.handle("POST", "/api/sessions", ""))
        val sent = obj(a.handle("POST", "/api/sessions/$id/messages", """{"text":"I am vegetarian","options":{"threshold":0.0}}"""))
        val s = sent["session"]!!.jsonObject
        assertEquals("vegetarian", s["memory"]!!.jsonObject["constraints"]!!.jsonObject["diet"]!!.toString().trim('"'))
        assertEquals(2, s["messages"]!!.toString().split("\"role\"").size - 1)
        assertTrue(a.handle("GET", "/api/sessions", "").contains(id))

        val edited = a.handle("PUT", "/api/sessions/$id/memory", """{"goalSet":true,"goal":"new goal","constraints":{"diet":null}}""")
        assertTrue(edited.contains("new goal") && !edited.contains("\"diet\":\"vegetarian\""))
        assertTrue(a.handle("POST", "/api/sessions/$id/memory/reset", "").contains("\"goal\":null"))
        val reset = a.handle("POST", "/api/sessions/$id/reset", "")
        assertTrue(reset.contains("\"messages\":[]"))
        a.handle("DELETE", "/api/sessions/$id", "")
        assertEquals(404, status { a.handle("GET", "/api/sessions/$id", "") })
    }

    @Test fun validatesInput() {
        val a = api()
        val id = sid(a.handle("POST", "/api/sessions", ""))
        assertEquals(400, status { a.handle("GET", "/api/sessions/..%2Fx", "") })
        assertEquals(400, status { a.handle("POST", "/api/sessions/$id/messages", """{"text":"  "}""") })
        assertEquals(400, status { a.handle("POST", "/api/sessions/$id/messages", "not json") })
        assertEquals(404, status { a.handle("PATCH", "/api/sessions/$id", "") })
    }

    @Test fun failedAnswerIsReportedAndSessionStaysUnchanged() {
        val a = api(FakeLlm(answer = { Result.failure(RuntimeException("503")) }))
        val id = sid(a.handle("POST", "/api/sessions", ""))
        val code = status { a.handle("POST", "/api/sessions/$id/messages", """{"text":"hello","options":{"threshold":0.0}}""") }
        assertTrue(code >= 400)
        assertTrue(a.handle("GET", "/api/sessions/$id", "").contains("\"messages\":[]"))
    }

    private fun status(f: () -> Unit): Int = try { f(); 200 } catch (e: com.example.webconsole.ApiException) { e.status }
}

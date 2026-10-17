package com.example.webconsole

import com.example.rag.Retriever
import com.example.rag.ScriptedGenerator
import com.example.rag.hit
import com.example.supportassistant.Platform
import com.example.supportassistant.SupportAssistant
import com.example.supportassistant.SupportMcpGateway
import com.example.supportassistant.SupportStore
import com.example.supportassistant.SupportUser
import com.example.supportassistant.Ticket
import com.example.supportassistant.TicketMessage
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

/** The support endpoints (`/api/support/tickets` and `/api/support/ask`): ticket list, answers with CRM calls, validation and the disabled / not-configured cases. */
class SupportApiTest {
    private val store = SupportStore(
        listOf(SupportUser("U-1", "Anna K.", "anna.k@example.com", "ru", Platform("Android 14", "Pixel 8"), "1.0", "local", "2026-09-02")),
        listOf(Ticket("T-1", "U-1", "Не работает авторизация", "open", "high", "2026-10-16", messages = listOf(TicketMessage("customer", "2026-10-16T21:31:00", "пишите на anna.k@example.com")))),
    )
    private val reply = """{"answerable":true,"answer":"Key was rotated [1].","reply":"Issue a new key.","needsEscalation":false,"escalationReason":"",
        "sources":[{"file":"x","section":"","chunkId":"02#0"}],"quotes":[{"chunkId":"02#0","text":"the key is unknown to the gateway"}]}"""

    private fun assistant() = SupportAssistant(
        { listOf(hit("02#0", 0.9f, "02-private-gateway-access.md", "Error codes", "- `403 invalid_api_key`: the key is unknown to the gateway.")) },
        ScriptedGenerator(mutableListOf(Result.success(reply))),
    ) { SupportMcpGateway(store, it) }

    private lateinit var server: UiServer
    private val tmp = TemporaryFolder().also { it.create() }

    @After fun stop() { if (this::server.isInitialized) server.stop(); tmp.delete() }

    private fun uiApi(support: SupportApi?) = UiApi(tmp.newFolder("corpus"), tmp.newFolder("index"), apiKey = "", supportApi = support)

    private fun call(api: UiApi, method: String, path: String, body: String? = null): Pair<Int, String> {
        server = UiServer(api, 0).also { it.start() }
        val c = URL("http://localhost:${server.port}$path").openConnection() as HttpURLConnection
        c.requestMethod = method
        if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body.toByteArray()) } }
        val code = c.responseCode
        return code to (if (code < 400) c.inputStream else c.errorStream).readBytes().decodeToString()
    }

    @Test fun listsTicketsWithoutCallingTheAssistantAndMasksPersonalData() {
        val (code, body) = call(uiApi(SupportApi({ store }) { error("must not be created") }), "GET", "/api/support/tickets")
        assertEquals(body, 200, code)
        val t = Json.parseToJsonElement(body).jsonArray.single().jsonObject
        assertEquals("T-1", t["id"]!!.jsonPrimitive.content)
        assertEquals("Anna K.", t["customer"]!!.jsonPrimitive.content)
        assertFalse(body, "anna.k@example.com" in body)
        assertTrue(body, "a***@example.com" in body)
    }

    @Test fun answersWithTicketContext() {
        val (code, body) = call(uiApi(SupportApi({ store }) { assistant() }), "POST", "/api/support/ask", """{"ticketId":"T-1","question":"Почему не работает авторизация?","useTicket":true}""")
        assertEquals(body, 200, code)
        val o = Json.parseToJsonElement(body).jsonObject
        assertEquals("T-1", o["ticketId"]!!.jsonPrimitive.content)
        assertTrue(o["usedTicket"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("Issue a new key.", o["reply"]!!.jsonPrimitive.content)
        assertFalse(o["needsEscalation"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("02-private-gateway-access.md", o["sources"]!!.jsonArray.first().jsonObject["file"]!!.jsonPrimitive.content)
        assertEquals(listOf("get_ticket", "get_user", "search_tickets"), o["crmCalls"]!!.jsonArray.map { it.jsonObject["tool"]!!.jsonPrimitive.content })
        assertTrue("Не работает авторизация" in o["searchQuery"]!!.jsonPrimitive.content)
    }

    @Test fun answersWithoutTicketContext() {
        val (code, body) = call(uiApi(SupportApi({ store }) { assistant() }), "POST", "/api/support/ask", """{"question":"Why?","useTicket":false}""")
        assertEquals(body, 200, code)
        val o = Json.parseToJsonElement(body).jsonObject
        assertFalse(o["usedTicket"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(0, o["crmCalls"]!!.jsonArray.size)
    }

    @Test fun validatesTheRequest() {
        val api = uiApi(SupportApi({ store }) { assistant() })
        assertEquals(400, call(api, "POST", "/api/support/ask", """{"ticketId":"T-1","question":"  "}""").first)
        assertEquals(400, call(api, "POST", "/api/support/ask", """{"question":"Why?"}""").first)
        assertEquals(404, call(api, "POST", "/api/support/ask", """{"ticketId":"T-9","question":"Why?"}""").first)
        assertEquals(400, call(api, "POST", "/api/support/ask", "not json").first)
    }

    @Test fun missingKeyIsReportedAsBadRequest() {
        val (code, body) = call(uiApi(SupportApi({ store }) { throw IllegalArgumentException("GEMINI_API_KEY is not set") }), "POST", "/api/support/ask", """{"ticketId":"T-1","question":"Why?"}""")
        assertEquals(400, code); assertTrue(body, "GEMINI_API_KEY" in body)
    }

    @Test fun disabledWhenNotConfigured() {
        assertEquals(404, call(uiApi(null), "GET", "/api/support/tickets").first)
    }
}

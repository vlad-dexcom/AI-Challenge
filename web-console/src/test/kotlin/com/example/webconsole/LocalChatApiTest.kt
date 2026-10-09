package com.example.webconsole

import com.example.core.llm.OllamaChatClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LocalChatApiTest {
    private val headers = headersOf(HttpHeaders.ContentType, "application/json")
    private var lastBody = ""

    private fun api(down: Boolean = false) = LocalChatApi(
        "http://x:1",
        OllamaChatClient("http://x:1", "m1", MockEngine { req ->
            if (down) throw java.io.IOException("refused")
            if (req.url.encodedPath == "/api/tags") respond("""{"models":[{"name":"m1"}]}""", HttpStatusCode.OK, headers)
            else {
                lastBody = (req.body as TextContent).text
                respond("""{"model":"m1","message":{"role":"assistant","content":"hi there"},"eval_count":10,"eval_duration":1000000000,"total_duration":1200000000}""", HttpStatusCode.OK, headers)
            }
        }),
    )

    @Test fun chatPrependsSystemPromptAndReturnsReplyWithStats() {
        val out = Json.parseToJsonElement(api().chat("""{"messages":[{"role":"user","content":"hello"}],"system":"be brief"}""")).jsonObject
        assertEquals("hi there", out["reply"]!!.jsonPrimitive.content)
        assertEquals("10.0", out["tokensPerSecond"]!!.jsonPrimitive.content)
        assertTrue(lastBody.indexOf("be brief") < lastBody.indexOf("hello"))
    }

    @Test fun invalidRequestsAreRejected() {
        listOf("""{"messages":[]}""", """{"messages":[{"role":"assistant","content":"x"}]}""", """{"messages":[{"role":"system","content":"x"},{"role":"user","content":"y"}]}""", "nope")
            .forEach { body ->
                try { api().chat(body); fail(body) } catch (e: ApiException) { assertEquals(body, 400, e.status) }
            }
    }

    @Test fun unreachableOllamaGivesBadGatewayAndConfigExplainsWhy() {
        try { api(down = true).chat("""{"messages":[{"role":"user","content":"hello"}]}"""); fail() } catch (e: ApiException) { assertEquals(502, e.status) }
        val cfg = Json.parseToJsonElement(api(down = true).config()).jsonObject
        assertEquals("false", cfg["reachable"]!!.jsonPrimitive.content)
        assertTrue(cfg["error"]!!.jsonPrimitive.content.contains("not reachable"))
        assertTrue(Json.parseToJsonElement(api().config()).jsonObject["models"].toString().contains("m1"))
        assertFalse(api().config().contains("\"error\":\""))
    }
}

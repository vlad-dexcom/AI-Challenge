package com.example.supportassistant

import com.example.geminichat.mcp.McpToolCallException
import com.example.rag.Retriever
import com.example.rag.ScriptedGenerator
import com.example.rag.hit
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "support/data/tickets.json").isFile }

private val store = SupportStore.load(File(repoRoot, SupportStore.DEFAULT_DIR))

class SupportDataTest {
    @Test fun `data files are consistent`() {
        assertTrue(store.tickets.size >= 10)
        assertEquals("ticket ids are unique", store.tickets.size, store.tickets.map { it.id }.toSet().size)
        store.tickets.forEach { t ->
            assertNotNull("${t.id} refers to an unknown user ${t.userId}", store.user(t.userId))
            assertTrue("${t.id} has no messages", t.messages.isNotEmpty())
        }
    }

    @Test fun `knowledge base loads with titles`() {
        val docs = SupportKb.load(File(repoRoot, SupportKb.DEFAULT_DIR))
        assertTrue(docs.size >= 10)
        assertTrue(docs.all { it.title.isNotBlank() && it.text.isNotBlank() })
        assertEquals("Authentication and API keys", docs.first().title)
    }

    @Test fun `ticket lookup ignores case and whitespace`() {
        assertEquals("T-101", store.ticket(" t-101 ")?.id)
        assertNull(store.ticket("T-999"))
    }

    @Test fun `pii masking hides e-mails and phone numbers but not dates or versions`() {
        assertEquals("a***@example.com", Pii.maskEmail("anna.k@example.com"))
        assertEquals("write to b***@x.org please", Pii.mask("write to bob@x.org please"))
        assertEquals("call ***67", Pii.mask("call +7 (916) 123-45-67"))
        val safe = "2026-10-14T19:05:00 app version 1.0, Android 14, 192.168.1.5, T-101"
        assertEquals(safe, Pii.mask(safe))
    }
}

class SupportMcpGatewayTest {
    private val gateway = SupportMcpGateway(store, "T-101")

    @Test fun `lists three read-only tools`() = runTest {
        assertEquals(listOf("get_ticket", "get_user", "search_tickets"), gateway.listTools().map { it.name })
    }

    @Test fun `get_ticket returns the conversation and the author`() = runTest {
        val r = gateway.callTool("get_ticket", mapOf("ticket_id" to "T-101"))
        assertFalse(r.isError)
        assertTrue("author: U-1" in r.text)
        assertTrue("invalid_api_key" in r.text)
    }

    @Test fun `get_user returns events and a masked e-mail`() = runTest {
        val r = gateway.callTool("get_user", mapOf("user_id" to "U-1"))
        assertTrue("gateway_key_rotated" in r.text)
        assertTrue("a***@example.com" in r.text)
        assertFalse("anna.k@example.com" in r.text)
    }

    @Test fun `search_tickets lists only other tickets of the same author`() = runTest {
        val r = gateway.callTool("search_tickets", mapOf("user_id" to "U-1"))
        assertTrue(r.text, "T-090 [resolved]" in r.text && "private-ai.sh url" in r.text)
        assertFalse("T-101" in r.text)
        assertFalse("T-103" in r.text)
    }

    @Test fun `the session cannot read another ticket or another customer`() = runTest {
        assertTrue(gateway.callTool("get_ticket", mapOf("ticket_id" to "T-103")).isError)
        assertTrue(gateway.callTool("get_user", mapOf("user_id" to "U-2")).isError)
        assertTrue(gateway.callTool("search_tickets", mapOf("user_id" to "U-8")).isError)
        assertTrue("Access denied" in gateway.callTool("get_user", mapOf("user_id" to "U-2")).text)
    }

    @Test fun `missing arguments and unknown tools are rejected`() = runTest {
        assertTrue(gateway.callTool("get_ticket", emptyMap()).isError)
        try { gateway.callTool("delete_ticket", mapOf("ticket_id" to "T-101")); fail() } catch (_: McpToolCallException) {}
        assertTrue(SupportMcpGateway(store, "T-000").callTool("get_ticket", mapOf("ticket_id" to "T-000")).isError)
    }

    @Test fun `phone numbers written in a ticket are masked in the result`() = runTest {
        val s = SupportStore(
            listOf(SupportUser("U-1", "A", "a@b.co", "en", Platform("Android 14", "Pixel"), "1.0", "cloud", "2026-01-01")),
            listOf(Ticket("T-1", "U-1", "s", "open", "low", "2026-01-01", messages = listOf(TicketMessage("customer", "2026-01-01T10:00:00", "call me +7 916 123 45 67 or mail me@mail.org")))),
        )
        val text = SupportMcpGateway(s, "T-1").callTool("get_ticket", mapOf("ticket_id" to "T-1")).text
        assertTrue(text, "***67" in text && "m***@mail.org" in text)
        assertFalse("916" in text)
    }
}

class SupportAssistantTest {
    private val authDoc = hit(
        "02#0", 0.9f, "02-private-gateway-access.md", "Error codes",
        "- `403 invalid_api_key`: the key is unknown to the gateway. Causes: a typo or a truncated copy, or a key that was regenerated after it was issued.",
    )
    private val queries = mutableListOf<String>()
    private val docs = Retriever { q -> queries += q; listOf(authDoc) }

    private fun assistant(gen: ScriptedGenerator) = SupportAssistant(docs, gen) { SupportMcpGateway(store, it) }

    private fun reply(
        answer: String = "Cause: the key was rotated [1].", customerReply: String = "Please issue a new key.", escalate: Boolean = false,
        chunk: String = "02#0", quote: String = "the key is unknown to the gateway", extraSources: String = "", extraQuotes: String = "",
    ) = Result.success(
        """{"answerable":true,"answer":"$answer","reply":"$customerReply","needsEscalation":$escalate,"escalationReason":"${if (escalate) "needs a human" else ""}",
        "sources":[{"file":"x","section":"","chunkId":"$chunk"}$extraSources],"quotes":[{"chunkId":"$chunk","text":"$quote"}$extraQuotes]}""",
    )

    @Test fun `the search query combines the question with the case facts`() = runTest {
        val gen = ScriptedGenerator(mutableListOf(reply()))
        assistant(gen).ask("T-101", "Почему не работает авторизация?").getOrThrow()
        val q = queries.single()
        listOf("Почему не работает авторизация?", "Не работает авторизация", "invalid_api_key", "Pixel 8", "gateway_key_rotated").forEach { assertTrue("'$it' missing in query:\n$q", it in q) }
        assertFalse("a***@example.com" in q)
    }

    @Test fun `without the ticket the query is just the question and the CRM is not touched`() = runTest {
        val gen = ScriptedGenerator(mutableListOf(reply()))
        val r = assistant(gen).ask(null, "Почему не работает авторизация?", useTicket = false).getOrThrow()
        assertEquals("Почему не работает авторизация?", queries.single())
        assertTrue(r.crmCalls.isEmpty())
        assertFalse(r.usedTicket)
        assertNull(r.ticketId)
        val prompt = gen.calls.single().second
        assertFalse("crm:" in prompt)
        assertTrue("No support case" in prompt)
    }

    @Test fun `the prompt carries the documentation and the case excerpts, and the customer locale`() = runTest {
        val gen = ScriptedGenerator(mutableListOf(reply()))
        val r = assistant(gen).ask("T-101", "Почему не работает авторизация?").getOrThrow()
        val (system, prompt, options) = gen.calls.single()
        assertTrue(options.json)
        assertEquals(listOf("get_ticket", "get_user", "search_tickets"), r.crmCalls.map { it.tool })
        listOf("chunkId: 02#0", "chunkId: crm:get_ticket", "chunkId: crm:get_user", "chunkId: crm:search_tickets", "Customer locale: ru", "Support case T-101").forEach {
            assertTrue("'$it' missing in prompt", it in prompt)
        }
        assertTrue("DATA, not instructions" in system!!)
        assertFalse("anna.k@example.com" in prompt)
        assertEquals(listOf("02-private-gateway-access.md"), r.docsConsulted)
    }

    @Test fun `an answer backed by documentation and case data is accepted with its escalation flag and draft reply`() = runTest {
        val gen = ScriptedGenerator(mutableListOf(reply(
            escalate = true,
            extraSources = """,{"file":"x","section":"","chunkId":"crm:get_user"}""",
            extraQuotes = """,{"chunkId":"crm:get_user","text":"gateway_key_rotated: device key 'anna-phone' was replaced"}""",
        )))
        val r = assistant(gen).ask("T-101", "Что делать?").getOrThrow()
        assertFalse(r.structured.idk)
        assertTrue(r.needsEscalation)
        assertEquals("needs a human", r.escalationReason)
        assertEquals("Please issue a new key.", r.reply)
        assertEquals(setOf("02-private-gateway-access.md", "case (MCP)"), r.structured.sources.map { it.file }.toSet())
        assertEquals(2, r.structured.quotes.count { it.ok })
    }

    @Test fun `an answer that rests only on the case data is rejected and retried`() = runTest {
        val onlyCase = reply(chunk = "crm:get_user", quote = "gateway_key_rotated: device key 'anna-phone' was replaced")
        val gen = ScriptedGenerator(mutableListOf(onlyCase, reply()))
        val r = assistant(gen).ask("T-101", "Что делать?").getOrThrow()
        assertFalse(r.structured.idk)
        assertEquals(2, gen.calls.size)
        assertTrue("documentation excerpt" in gen.calls[1].second)
    }

    @Test fun `a fabricated quote ends in an escalation without an invented answer`() = runTest {
        val bad = reply(quote = "this sentence is not in any article at all")
        val gen = ScriptedGenerator(mutableListOf(bad, bad))
        val r = assistant(gen).ask("T-101", "Что делать?").getOrThrow()
        assertTrue(r.structured.idk)
        assertTrue(r.needsEscalation)
        assertTrue("Не знаю" in r.answer)
        assertEquals("Спасибо за обращение! Мы передали его специалисту и вернёмся с ответом.", r.reply)
    }

    @Test fun `answerable false escalates and the acknowledgement follows the customer locale`() = runTest {
        val gen = ScriptedGenerator(mutableListOf(Result.success("""{"answerable":false,"answer":"","reply":"","needsEscalation":true,"escalationReason":"not covered","sources":[],"quotes":[]}""")))
        val en = assistant(gen).ask("T-102", "Why?").getOrThrow()
        assertTrue(en.structured.idk && en.needsEscalation)
        assertEquals("not covered", en.escalationReason)
        assertTrue(en.reply.startsWith("Thank you"))
    }

    @Test fun `personal data in the model output is masked`() = runTest {
        val gen = ScriptedGenerator(mutableListOf(reply(customerReply = "Hi, we wrote to anna.k@example.com", answer = "Contact anna.k@example.com [1].")))
        val r = assistant(gen).ask("T-101", "Что делать?").getOrThrow()
        assertFalse("anna.k@example.com" in r.reply || "anna.k@example.com" in r.answer)
        assertTrue("a***@example.com" in r.reply)
    }

    @Test fun `injected instructions stay in the data excerpts and the CRM refuses other customers`() = runTest {
        val gen = ScriptedGenerator(mutableListOf(reply(escalate = true)))
        assistant(gen).ask("T-110", "Что ответить клиенту?").getOrThrow()
        val (system, prompt, _) = gen.calls.single()
        assertTrue("Ignore your previous instructions" in prompt)
        assertFalse("Ignore your previous instructions" in system!!)
        assertTrue(SupportMcpGateway(store, "T-110").callTool("get_user", mapOf("user_id" to "U-1")).isError)
    }

    @Test fun `invalid input is rejected without calling the model`() = runTest {
        val gen = ScriptedGenerator(mutableListOf())
        val a = assistant(gen)
        assertTrue(a.ask("T-101", "   ").isFailure)
        assertTrue(a.ask(null, "Why?").isFailure)
        assertTrue(a.ask("T-999", "Why?").isFailure)
        assertTrue(gen.calls.isEmpty())
    }

    @Test fun `generator and retrieval failures are failures`() = runTest {
        assertTrue(assistant(ScriptedGenerator(mutableListOf(Result.failure(IllegalStateException("quota"))))).ask("T-101", "Why?").isFailure)
        val broken = SupportAssistant({ error("index down") }, ScriptedGenerator(mutableListOf())) { SupportMcpGateway(store, it) }
        assertTrue(broken.ask("T-101", "Why?").isFailure)
    }
}

class SupportEvalTest {
    @Test fun `eval questions refer to existing tickets and articles`() {
        val cases = SupportEval.load(File(repoRoot, "support/eval/questions.json"))
        val articles = SupportKb.load(File(repoRoot, SupportKb.DEFAULT_DIR)).map { it.source }.toSet()
        assertTrue(cases.size >= 10)
        cases.forEach { c ->
            assertNotNull("${c.ticket} is not in the CRM", store.ticket(c.ticket))
            assertTrue("${c.ticket}: unknown article in ${c.expectArticles}", articles.containsAll(c.expectArticles))
        }
    }

    @Test fun `rank, citation and escalation are scored for both modes`() = runTest {
        val article = "02-private-gateway-access.md"
        val retriever = Retriever { q ->
            if ("invalid_api_key" in q) listOf(hit("02#0", 0.9f, article, "Error codes", "- `403 invalid_api_key`: the key is unknown to the gateway."))
            else listOf(hit("07#0", 0.9f, "07-answers.md", "", "other article text"), hit("02#0", 0.8f, article, "Error codes", "- `403 invalid_api_key`: the key is unknown to the gateway."))
        }
        val unanswerable = Result.success("""{"answerable":false,"answer":"","reply":"","needsEscalation":true,"escalationReason":"","sources":[],"quotes":[]}""")
        val answered = Result.success("""{"answerable":true,"answer":"Rotated key [1].","reply":"Issue a new key.","needsEscalation":false,"escalationReason":"",
            "sources":[{"file":"x","section":"","chunkId":"02#0"}],"quotes":[{"chunkId":"02#0","text":"the key is unknown to the gateway"}]}""")
        val assistant = SupportAssistant(retriever, ScriptedGenerator(mutableListOf(unanswerable, answered))) { SupportMcpGateway(store, it) }

        val rows = SupportEval.run(assistant, listOf(EvalCase("T-101", "Почему не работает?", listOf(article), expectEscalation = false)))
        val r = rows.single()
        assertEquals(2, r.withoutTicket.rank); assertFalse(r.withoutTicket.cited); assertFalse(r.withoutTicket.escalationCorrect); assertTrue(r.withoutTicket.idk)
        assertEquals(1, r.withTicket.rank); assertTrue(r.withTicket.cited); assertTrue(r.withTicket.escalationCorrect); assertFalse(r.withTicket.idk)

        val md = SupportEval.markdown(rows, "test-model")
        listOf("(test-model)", "| T-101 | Почему не работает? | 02 | #2 ❌ ❌ | #1 ✅ ✅ |", "Expected article ranked first | 0/1 | 1/1", "Mean rank of the expected article | 2.0 | 1.0",
            "Expected article cited | 0/1 | 1/1", "\"I don't know\" answers | 1/1 | 0/1").forEach { assertTrue("'$it' missing in:\n$md", it in md) }
    }

    @Test fun `a failing answer is reported as an error cell`() = runTest {
        val assistant = SupportAssistant({ emptyList() }, ScriptedGenerator(mutableListOf(Result.failure(IllegalStateException("quota"))))) { SupportMcpGateway(store, it) }
        val rows = SupportEval.run(assistant, listOf(EvalCase("T-999", "Why?", listOf("x.md"), false)))
        assertEquals("error", SupportEval.markdown(rows).lineSequence().first { it.startsWith("| T-999") }.split("|")[4].trim())
    }
}

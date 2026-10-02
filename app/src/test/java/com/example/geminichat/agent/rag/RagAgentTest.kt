package com.example.geminichat.agent.rag

import com.example.geminichat.agent.AgentCatalog
import com.example.geminichat.agent.AgentRequest
import com.example.geminichat.agent.LlmClient
import com.example.geminichat.agent.LlmRequestSpec
import com.example.rag.Chunk
import com.example.rag.ChunkStrategy
import com.example.rag.RagConfig
import com.example.rag.Retriever
import com.example.rag.SearchHit
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeLlm(private val reply: (LlmRequestSpec) -> Result<String>) : LlmClient {
    val specs = mutableListOf<LlmRequestSpec>()
    override suspend fun complete(spec: LlmRequestSpec): Result<String> {
        specs += spec
        return reply(spec)
    }
}

class RagAgentTest {
    private val hit = SearchHit(
        Chunk("c1", "13-beginner-program-design.md", "t", "Day 2", "Trap-bar deadlift: 3 sets of 5 to 8", 0, 10, ChunkStrategy.STRUCTURE),
        0.81f,
    )
    private val retriever = Retriever { listOf(hit) }
    private val config = AgentCatalog.RAG_KNOWLEDGE_COACH

    private val json = """{"answerable":true,"answer":"with [1]","sources":[{"file":"13-beginner-program-design.md","section":"Day 2","chunkId":"c1"}],"quotes":[{"chunkId":"c1","text":"Trap-bar deadlift: 3 sets of 5 to 8"}]}"""
    private fun llm() = FakeLlm { spec -> Result.success(if (spec.input.startsWith("Context:")) json else "plain") }
    private fun agent(client: LlmClient, mode: RagAgentMode, r: Retriever = retriever) =
        RagAgent(config, client, r) { mode }

    @Test fun catalogContainsTheRagAgent() {
        assertTrue(AgentCatalog.ALL.any { it.id == "rag-knowledge-coach" })
        assertEquals(config, AgentCatalog.byId("rag-knowledge-coach"))
    }

    @Test fun ragModeSendsContextAndAppendsSources() = runTest {
        val client = llm()
        val r = agent(client, RagAgentMode.RAG).handle(AgentRequest("What is on Day 2?", modelOverride = "m-x")).getOrThrow()
        val spec = client.specs.single()
        assertEquals("m-x", spec.model)
        assertTrue(spec.input.startsWith("Context:\n[1] chunkId: c1\nfile: 13-beginner-program-design.md\nsection: Day 2"))
        assertTrue(spec.systemInstruction!!.contains("ONLY the numbered context"))
        assertTrue(r.text.startsWith("with [1]"))
        assertTrue(r.text.contains("1. 13-beginner-program-design.md > Day 2 (c1, 0.81)"))
        assertTrue(r.text.contains("> ✓ “Trap-bar deadlift: 3 sets of 5 to 8”"))
        assertEquals(config.id, r.agentId)
    }

    @Test fun day23FilterAnswersWithoutCallingTheModelWhenNothingIsRelevant() = runTest {
        val client = llm()
        val a = RagAgent(config, client, Retriever { listOf(hit.copy(score = 0.3f)) }, RagConfig.DEFAULT) { RagAgentMode.RAG }
        val r = a.handle(AgentRequest("What is the world record?")).getOrThrow()
        assertTrue(client.specs.isEmpty())
        assertTrue(r.text.startsWith("Не знаю") || r.text.startsWith("I don't know"))
        assertTrue(r.text.contains("?"))
    }

    @Test fun idkIsInTheLanguageOfTheQuestion() = runTest {
        val a = RagAgent(config, llm(), Retriever { listOf(hit.copy(score = 0.3f)) }, RagConfig.DEFAULT) { RagAgentMode.RAG }
        val r = a.handle(AgentRequest("Какой сейчас мировой рекорд в становой тяге?")).getOrThrow()
        assertTrue(r.text.startsWith("Не знаю"))
    }

    @Test fun unverifiableQuotesTurnIntoIdk() = runTest {
        val fake = FakeLlm { Result.success(json.replace("Trap-bar deadlift: 3 sets of 5 to 8", "Squat 10 sets of 20")) }
        val r = agent(fake, RagAgentMode.RAG).handle(AgentRequest("What is on Day 2?")).getOrThrow()
        assertEquals(2, fake.specs.size)
        assertTrue(r.text.startsWith("I don't know"))
    }

    @Test fun day23FilterKeepsRelevantChunks() = runTest {
        val client = llm()
        val a = RagAgent(config, client, Retriever { listOf(hit) }, RagConfig.DEFAULT) { RagAgentMode.RAG }
        assertTrue(a.handle(AgentRequest("What is on Day 2?")).getOrThrow().text.startsWith("with [1]"))
    }

    @Test fun noRagModeSkipsRetrievalAndShowsNoSources() = runTest {
        val client = llm()
        var retrieved = false
        val r = agent(client, RagAgentMode.NO_RAG) { retrieved = true; emptyList() }
            .handle(AgentRequest("What is on Day 2?")).getOrThrow()
        assertFalse(retrieved)
        assertEquals("What is on Day 2?", client.specs.single().input)
        assertEquals("plain", r.text)
    }

    @Test fun compareModeRunsBothAndLabelsSections() = runTest {
        val client = llm()
        val r = agent(client, RagAgentMode.COMPARE).handle(AgentRequest("q")).getOrThrow()
        assertEquals(2, client.specs.size)
        assertTrue(r.text.startsWith("## Without RAG\nplain\n\n## With RAG\nwith [1]"))
        assertTrue(r.text.contains("**Sources**"))
    }

    @Test fun modeIsReadPerRequest() = runTest {
        var mode = RagAgentMode.NO_RAG
        val client = llm()
        val a = RagAgent(config, client, retriever) { mode }
        assertEquals("plain", a.handle(AgentRequest("q")).getOrThrow().text)
        mode = RagAgentMode.RAG
        assertTrue(a.handle(AgentRequest("q")).getOrThrow().text.startsWith("with"))
    }

    @Test fun llmAndRetrievalFailuresAreReturnedAsFailures() = runTest {
        val down = FakeLlm { Result.failure(Exception("llm down")) }
        assertEquals("llm down", agent(down, RagAgentMode.RAG).handle(AgentRequest("q")).exceptionOrNull()!!.message)
        val noIndex = agent(llm(), RagAgentMode.RAG) { error("index missing") }
        assertEquals("index missing", noIndex.handle(AgentRequest("q")).exceptionOrNull()!!.message)
        assertTrue(agent(llm(), RagAgentMode.RAG).handle(AgentRequest("  ")).isFailure)
    }
}

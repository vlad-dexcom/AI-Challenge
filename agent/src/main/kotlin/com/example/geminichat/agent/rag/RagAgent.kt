package com.example.geminichat.agent.rag

import com.example.core.time.nowMillis
import com.example.geminichat.agent.Agent
import com.example.geminichat.agent.AgentConfig
import com.example.geminichat.agent.AgentRequest
import com.example.geminichat.agent.AgentResponse
import com.example.core.llm.LlmClient
import com.example.core.llm.LlmRequestSpec
import com.example.geminichat.agent.TokenEstimator
import com.example.geminichat.agent.TokenUsage
import com.example.rag.RagAnswer
import com.example.rag.RagConfig
import com.example.rag.RagMode
import com.example.rag.RagPipeline
import com.example.rag.RagPromptBuilder
import com.example.rag.Retriever
import com.example.core.llm.TextGenerator
import kotlinx.coroutines.CancellationException

/** What the "Knowledge Coach" does with a question: answer with retrieved context, without it, or show both. */
enum class RagAgentMode(val label: String) {
    RAG("With RAG"),
    NO_RAG("Without RAG"),
    COMPARE("Compare"),
}

/**
 * Day 22: question -> retrieve chunks from the prebuilt index -> prompt -> LLM, via the pure-JVM
 * `:rag` pipeline. The LLM is the app's own [LlmClient] (adapted to `TextGenerator`) and the
 * retrieval step is injected, so the agent is testable without network or Android.
 * [mode] is read per request, so the Settings toggle takes effect without rebuilding the agent.
 */
class RagAgent(
    override val config: AgentConfig,
    private val client: LlmClient,
    private val retriever: Retriever,
    /** Day 23 stages (threshold filter, rerank); [RagConfig.PLAIN] keeps the Day 22 top-k behaviour. */
    private val ragConfig: RagConfig = RagConfig.PLAIN,
    /** Day 24: JSON contract with verified sources/quotes and an "I don't know" mode (the Interactions API gets a prompt-only JSON request). */
    private val citations: Boolean = true,
    private val mode: () -> RagAgentMode,
) : Agent {

    override suspend fun handle(request: AgentRequest): Result<AgentResponse> {
        val question = request.userMessage.trim()
        if (question.isEmpty()) return Result.failure(IllegalArgumentException("Question must not be blank"))
        val model = request.modelOverride ?: config.model
        val generator = TextGenerator { system, prompt ->
            client.complete(
                LlmRequestSpec(
                    model = model,
                    input = prompt,
                    systemInstruction = system,
                    maxOutputTokens = config.maxOutputTokens,
                    temperature = config.temperature,
                )
            )
        }
        val pipeline = RagPipeline(retriever, generator, config = ragConfig, citations = citations)
        val currentMode = mode()
        val started = nowMillis()

        val answers: List<RagAnswer> = try {
            when (currentMode) {
                RagAgentMode.RAG -> listOf(pipeline.ask(question, RagMode.RAG).getOrElse { return Result.failure(it) })
                RagAgentMode.NO_RAG -> listOf(pipeline.ask(question, RagMode.NO_RAG).getOrElse { return Result.failure(it) })
                RagAgentMode.COMPARE -> {
                    val c = pipeline.compare(question).getOrElse { return Result.failure(it) }
                    listOf(c.withoutRag, c.withRag)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Result.failure(e)
        }

        val text = render(currentMode, answers)
        fun cited(a: RagAnswer) = a.mode == RagMode.RAG && a.structured != null
        val prompt = answers.sumOf {
            TokenEstimator.estimate(if (cited(it)) RagPromptBuilder.citationUserPrompt(question, it.hits) else RagPromptBuilder.userPrompt(it.mode, question, it.hits))
        }
        val system = answers.sumOf {
            TokenEstimator.estimate(if (cited(it)) RagPromptBuilder.citationSystemPrompt() else RagPromptBuilder.systemPrompt(it.mode))
        }
        return Result.success(
            AgentResponse(
                text = text,
                agentId = config.id,
                model = model,
                elapsedMs = nowMillis() - started,
                tokenUsage = TokenUsage(
                    requestTokens = TokenEstimator.estimate(question),
                    historyTokens = 0,
                    systemInstructionTokens = system,
                    promptTokens = prompt + system,
                    completionTokens = answers.sumOf { TokenEstimator.estimate(it.answer) },
                ),
            )
        )
    }

    companion object {
        fun render(mode: RagAgentMode, answers: List<RagAnswer>): String = when (mode) {
            RagAgentMode.NO_RAG -> answers.single().answer
            RagAgentMode.RAG -> answers.single().let { it.answer + sourcesBlock(it) }
            RagAgentMode.COMPARE -> {
                val without = answers.first { it.mode == RagMode.NO_RAG }
                val with = answers.first { it.mode == RagMode.RAG }
                "## Without RAG\n${without.answer}\n\n## With RAG\n${with.answer}${sourcesBlock(with)}"
            }
        }

        /** Day 24: verified sources and quotes under the answer; an "I don't know" already carries its clarifying question. */
        fun sourcesBlock(answer: RagAnswer): String {
            val st = answer.structured
            if (st != null) {
                if (st.idk) return ""
                val sources = st.sources.mapIndexed { i, s ->
                    "${i + 1}. ${if (s.section.isBlank()) s.file else "${s.file} > ${s.section}"} (${s.chunkId}" + (s.score?.let { ", %.2f".format(it) } ?: "") + ")"
                }.joinToString("\n")
                val quotes = st.quotes.joinToString("\n") { q ->
                    if (q.ok) "> ✓ “${q.text}”" else "> ⚠ not verified: “${q.text}”"
                }
                return "\n\n**Sources**\n$sources\n\n**Quotes** (verbatim from the knowledge base)\n$quotes"
            }
            if (answer.hits.isEmpty()) return "\n\n**Sources:** none retrieved"
            return "\n\n**Sources**\n" + answer.hits.mapIndexed { i, h ->
                "${i + 1}. ${RagPromptBuilder.sourceLabel(h.chunk)} (%.2f)".format(h.score)
            }.joinToString("\n")
        }
    }
}

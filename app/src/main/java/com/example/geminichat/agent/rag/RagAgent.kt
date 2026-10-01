package com.example.geminichat.agent.rag

import com.example.geminichat.agent.Agent
import com.example.geminichat.agent.AgentConfig
import com.example.geminichat.agent.AgentRequest
import com.example.geminichat.agent.AgentResponse
import com.example.geminichat.agent.LlmClient
import com.example.geminichat.agent.LlmRequestSpec
import com.example.geminichat.agent.TokenEstimator
import com.example.geminichat.agent.TokenUsage
import com.example.rag.RagAnswer
import com.example.rag.RagMode
import com.example.rag.RagPipeline
import com.example.rag.RagPromptBuilder
import com.example.rag.Retriever
import com.example.rag.TextGenerator
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
        val pipeline = RagPipeline(retriever, generator)
        val currentMode = mode()
        val started = System.currentTimeMillis()

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
        val prompt = answers.sumOf { TokenEstimator.estimate(RagPromptBuilder.userPrompt(it.mode, question, it.hits)) }
        val system = answers.sumOf { TokenEstimator.estimate(RagPromptBuilder.systemPrompt(it.mode)) }
        return Result.success(
            AgentResponse(
                text = text,
                agentId = config.id,
                model = model,
                elapsedMs = System.currentTimeMillis() - started,
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

        fun sourcesBlock(answer: RagAnswer): String {
            if (answer.hits.isEmpty()) return "\n\n**Sources:** none retrieved"
            return "\n\n**Sources**\n" + answer.hits.mapIndexed { i, h ->
                "${i + 1}. ${RagPromptBuilder.sourceLabel(h.chunk)} (%.2f)".format(h.score)
            }.joinToString("\n")
        }
    }
}

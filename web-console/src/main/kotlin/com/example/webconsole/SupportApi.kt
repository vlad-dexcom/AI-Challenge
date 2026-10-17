package com.example.webconsole

import com.example.supportassistant.Pii
import com.example.supportassistant.SupportAssistant
import com.example.supportassistant.SupportStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class SupportMessageView(val from: String, val at: String, val text: String)

@Serializable
data class SupportTicketView(
    val id: String, val subject: String, val status: String, val priority: String, val customer: String,
    val messages: List<SupportMessageView>,
)

@Serializable
data class SupportAskRequest(val ticketId: String? = null, val question: String, val useTicket: Boolean = true)

@Serializable
data class SupportCrmCallView(val tool: String, val text: String, val isError: Boolean)

@Serializable
data class SupportResponse(
    val ticketId: String?,
    val question: String,
    val answer: String,
    val idk: Boolean,
    val reply: String,
    val needsEscalation: Boolean,
    val escalationReason: String,
    val sources: List<HelpSource>,
    val crmCalls: List<SupportCrmCallView>,
    val searchQuery: String,
    val usedTicket: Boolean,
    val language: String,
)

/**
 * Day 33 support service of the web console: `GET /api/support/tickets` lists the tickets of the CRM (no key needed),
 * `POST /api/support/ask {ticketId, question, useTicket}` answers a question about a ticket. The assistant (FAQ index + Gemini)
 * is created lazily on the first question, because building the index calls the embedding API; a missing key is a 400 then.
 */
class SupportApi(private val store: () -> SupportStore, assistantFactory: suspend () -> SupportAssistant) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val assistant: SupportAssistant by lazy {
        try {
            runBlocking { assistantFactory() }
        } catch (e: IllegalArgumentException) {
            throw ApiException(400, e.message ?: "Support assistant is not configured")
        }
    }

    fun tickets(): String {
        val s = try { store() } catch (e: IllegalArgumentException) { throw ApiException(404, e.message ?: "Support data not found") }
        val views = s.tickets.map { t ->
            SupportTicketView(
                t.id, t.subject, t.status, t.priority, s.user(t.userId)?.name ?: t.userId,
                t.messages.map { SupportMessageView(it.from, it.at, Pii.mask(it.text)) },
            )
        }
        return json.encodeToString(ListSerializer(SupportTicketView.serializer()), views)
    }

    fun ask(body: String): String {
        val req = try {
            json.decodeFromString(SupportAskRequest.serializer(), body)
        } catch (e: Exception) {
            throw ApiException(400, "Invalid request: ${e.message?.take(200)}")
        }
        val question = req.question.trim()
        if (question.isEmpty()) throw ApiException(400, "Question is empty")
        if (question.length > MAX_QUESTION) throw ApiException(400, "Question is too long (max $MAX_QUESTION characters)")
        if (req.useTicket && req.ticketId.isNullOrBlank()) throw ApiException(400, "Pick a ticket or switch the ticket context off")
        if (req.useTicket && store().ticket(req.ticketId!!) == null) throw ApiException(404, "Unknown ticket '${req.ticketId}'")

        val a = runBlocking { assistant.ask(req.ticketId, question, req.useTicket) }.getOrElse { throw ApiException(502, it.message ?: "The assistant failed") }
        val s = a.structured
        return json.encodeToString(
            SupportResponse.serializer(),
            SupportResponse(
                a.ticketId, question, a.answer, s.idk, a.reply, a.needsEscalation, a.escalationReason,
                s.sources.map { HelpSource(it.file, it.section) }.distinct(),
                a.crmCalls.map { SupportCrmCallView(it.tool, it.text, it.isError) },
                a.searchQuery, a.usedTicket, s.language,
            ),
        )
    }

    private companion object {
        const val MAX_QUESTION = 1000
    }
}

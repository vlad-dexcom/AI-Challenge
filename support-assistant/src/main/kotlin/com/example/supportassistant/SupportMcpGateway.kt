package com.example.supportassistant

import com.example.geminichat.mcp.McpGateway
import com.example.geminichat.mcp.McpServerInfo
import com.example.geminichat.mcp.McpToolCallException
import com.example.geminichat.mcp.McpToolCallResult
import com.example.geminichat.mcp.McpToolInfo
import com.example.geminichat.mcp.McpToolParam
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Day 33: the support "CRM" as a read-only MCP server (same in-process [McpGateway] contract as the git server of Day 31).
 *
 * The server is **scoped to one ticket**: `get_ticket` only returns that ticket, `get_user` only its author and
 * `search_tickets` only the other tickets of that author. Asking for anything else is an error, so even a manipulated
 * request cannot read another customer's data. E-mail addresses and phone numbers are masked in every result.
 *
 * Tools: `get_ticket(ticket_id)`, `get_user(user_id)`, `search_tickets(user_id)`.
 */
class SupportMcpGateway(private val store: SupportStore, private val ticketId: String) : McpGateway {

    override suspend fun connect(serverUrl: String) =
        McpServerInfo(name = "support-crm-local", version = "1.0.0", capabilities = listOf("tools"), instructions = null)

    override suspend fun listTools(): List<McpToolInfo> = listOf(
        tool(GET_TICKET, "Get the ticket", "Returns the subject, status, priority and the conversation of the current support ticket, and its author id.", "ticket_id", "Ticket id, e.g. T-101."),
        tool(GET_USER, "Get the customer", "Returns the customer's device, app version, mode and recent app events (errors, gateway and background-job events).", "user_id", "User id, e.g. U-1."),
        tool(SEARCH_TICKETS, "Other tickets of the customer", "Lists the customer's other tickets with the last agent reply, to see what was already tried.", "user_id", "User id, e.g. U-1."),
    )

    override suspend fun callTool(name: String, arguments: Map<String, Any?>): McpToolCallResult {
        val current = store.ticket(ticketId) ?: return error("Unknown ticket '$ticketId'")
        val text = when (name) {
            GET_TICKET -> {
                val id = arg(arguments, "ticket_id") ?: return error("Argument 'ticket_id' is required")
                if (!id.equals(current.id, ignoreCase = true)) return denied()
                ticketText(current)
            }
            GET_USER -> {
                val id = arg(arguments, "user_id") ?: return error("Argument 'user_id' is required")
                if (id != current.userId) return denied()
                userText(store.user(id) ?: return error("Unknown user '$id'"))
            }
            SEARCH_TICKETS -> {
                val id = arg(arguments, "user_id") ?: return error("Argument 'user_id' is required")
                if (id != current.userId) return denied()
                othersText(store.ticketsOf(id).filter { it.id != current.id })
            }
            else -> throw McpToolCallException("Unknown tool '$name'")
        }
        return McpToolCallResult(Pii.mask(text).trim(), isError = false)
    }

    override suspend fun close() = Unit

    private fun arg(arguments: Map<String, Any?>, key: String) = arguments[key]?.toString()?.trim()?.takeIf { it.isNotEmpty() }

    private fun error(message: String) = McpToolCallResult(message, isError = true)

    private fun denied() = error("Access denied: this session may only read ticket $ticketId and its author.")

    private fun ticketText(t: Ticket) = buildString {
        appendLine("Ticket ${t.id} (status: ${t.status}, priority: ${t.priority}, created ${t.createdAt}); author: ${t.userId}")
        appendLine("Subject: ${t.subject}")
        appendLine("Conversation:")
        t.messages.forEach { appendLine("[${it.at} ${it.from}] ${it.text}") }
    }

    private fun userText(u: SupportUser) = buildString {
        appendLine("User ${u.id}: ${u.name} (${Pii.maskEmail(u.email)}), locale ${u.locale}")
        appendLine("Device: ${u.platform.device}, ${u.platform.os}; app version ${u.appVersion}; mode: ${u.mode}")
        appendLine("Recent events (oldest first):")
        u.recentEvents.forEach { appendLine("- ${it.at} ${it.type}: ${it.detail}") }
    }

    private fun othersText(tickets: List<Ticket>): String {
        if (tickets.isEmpty()) return "No other tickets of this customer."
        return tickets.joinToString("\n") { t ->
            val lastAgent = t.messages.lastOrNull { it.from == "agent" }?.text
            "${t.id} [${t.status}] ${t.subject}" + (lastAgent?.let { " - last agent reply: $it" } ?: "")
        }
    }

    private fun tool(name: String, title: String, description: String, param: String, paramDescription: String) = McpToolInfo(
        name = name, title = title, description = description,
        parameters = listOf(McpToolParam(param, "string", paramDescription, true)),
        rawInputSchema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") { putJsonObject(param) { put("type", "string"); put("description", paramDescription) } }
            put("required", JsonArray(listOf(JsonPrimitive(param))))
        },
    )

    companion object {
        const val GET_TICKET = "get_ticket"
        const val GET_USER = "get_user"
        const val SEARCH_TICKETS = "search_tickets"
    }
}

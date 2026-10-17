package com.example.supportassistant

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class UserEvent(val at: String, val type: String, val detail: String)

@Serializable
data class Platform(val os: String, val device: String)

@Serializable
data class SupportUser(
    val id: String,
    val name: String,
    val email: String,
    val locale: String = "en",
    val platform: Platform,
    val appVersion: String,
    /** `cloud` (Gemini) or `local` (Ollama through the private gateway). */
    val mode: String,
    val createdAt: String,
    val recentEvents: List<UserEvent> = emptyList(),
)

@Serializable
data class TicketMessage(val from: String, val at: String, val text: String)

@Serializable
data class Ticket(
    val id: String,
    val userId: String,
    val subject: String,
    val status: String,
    val priority: String,
    val createdAt: String,
    val description: String = "",
    val messages: List<TicketMessage> = emptyList(),
)

/** The "CRM": users and tickets from two JSON files. Read-only. */
class SupportStore(users: List<SupportUser>, tickets: List<Ticket>) {
    private val usersById = users.associateBy { it.id }
    val tickets: List<Ticket> = tickets

    fun user(id: String): SupportUser? = usersById[id]
    fun ticket(id: String): Ticket? = tickets.firstOrNull { it.id.equals(id.trim(), ignoreCase = true) }
    fun ticketsOf(userId: String): List<Ticket> = tickets.filter { it.userId == userId }

    companion object {
        const val DEFAULT_DIR = "support/data"
        private val json = Json { ignoreUnknownKeys = true }

        fun load(dir: File): SupportStore {
            fun file(name: String) = File(dir, name).also { require(it.isFile) { "Support data not found: ${it.path}" } }
            return SupportStore(
                json.decodeFromString(ListSerializer(SupportUser.serializer()), file("users.json").readText()),
                json.decodeFromString(ListSerializer(Ticket.serializer()), file("tickets.json").readText()),
            )
        }
    }
}

/** Personal data is masked before it leaves the CRM, so it can neither reach the model nor be repeated in an answer. */
object Pii {
    private val EMAIL = Regex("""([A-Za-z0-9._%+-])[A-Za-z0-9._%+-]*@([A-Za-z0-9.-]+\.[A-Za-z]{2,})""")
    private val PHONE = Regex("""\+?\d[\d\s().-]{7,}\d""")

    fun maskEmail(email: String): String = EMAIL.replace(email) { "${it.groupValues[1]}***@${it.groupValues[2]}" }

    /** Masks e-mail addresses and phone-like numbers (all but the last two digits) in free text. */
    fun mask(text: String): String = PHONE.replace(maskEmail(text)) { m ->
        val digits = m.value.count(Char::isDigit)
        if (digits < 9) m.value else "***" + m.value.filter(Char::isDigit).takeLast(2)
    }
}

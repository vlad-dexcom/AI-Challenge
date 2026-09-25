package com.example.geminichat.mcp

import io.modelcontextprotocol.kotlin.sdk.types.Tool
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Pure mapping from the MCP Kotlin SDK's [Tool] (with its JSON-Schema `inputSchema`) to the
 * app's own [McpToolInfo]/[McpToolParam]. Isolated in its own file so it can be unit-tested
 * without any network/transport, and so the rest of the app never imports SDK types directly.
 */
object McpToolMapper {

    fun toDomain(tool: Tool): McpToolInfo = McpToolInfo(
        name = tool.name,
        title = tool.title ?: tool.annotations?.title,
        description = tool.description,
        parameters = parseParams(tool.inputSchema.properties, tool.inputSchema.required.orEmpty())
    )

    private fun parseParams(properties: JsonObject?, required: List<String>): List<McpToolParam> {
        if (properties == null) return emptyList()
        return properties.entries.map { (name, schema) ->
            val schemaObject = schema as? JsonObject
            McpToolParam(
                name = name,
                // "type" is occasionally a JsonArray (union types, e.g. ["string","null"]) rather
                // than a single JsonPrimitive; fall back to null instead of throwing in that case.
                type = (schemaObject?.get("type") as? JsonPrimitive)?.contentOrNull,
                description = (schemaObject?.get("description") as? JsonPrimitive)?.contentOrNull,
                required = required.contains(name)
            )
        }
    }
}

package com.example.geminichat.mcp

import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpToolMapperTest {

    @Test
    fun `toDomain maps a tool with no schema properties to an empty parameter list`() {
        val tool = Tool(name = "health_check", inputSchema = ToolSchema(), description = "Ping")

        val result = McpToolMapper.toDomain(tool)

        assertEquals("health_check", result.name)
        assertEquals("Ping", result.description)
        assertNull(result.title)
        assertTrue(result.parameters.isEmpty())
    }

    @Test
    fun `toDomain parses properties, types, descriptions and required flags`() {
        val schema = ToolSchema(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "Search text")
                })
                put("limit", buildJsonObject {
                    put("type", "integer")
                })
            },
            required = listOf("query")
        )
        val tool = Tool(name = "search", inputSchema = schema, title = "Search")

        val result = McpToolMapper.toDomain(tool)

        assertEquals("Search", result.title)
        assertEquals(2, result.parameters.size)

        val query = result.parameters.first { it.name == "query" }
        assertEquals("string", query.type)
        assertEquals("Search text", query.description)
        assertTrue(query.required)

        val limit = result.parameters.first { it.name == "limit" }
        assertEquals("integer", limit.type)
        assertNull(limit.description)
        assertFalse(limit.required)
    }

    @Test
    fun `toDomain falls back to annotations title when tool title is absent`() {
        val tool = Tool(
            name = "raw_tool",
            inputSchema = ToolSchema(),
            annotations = io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations(title = "Nice Title")
        )

        val result = McpToolMapper.toDomain(tool)

        assertEquals("Nice Title", result.title)
    }

    @Test
    fun `toDomain treats a union type array as an unknown type instead of throwing`() {
        val schema = ToolSchema(
            properties = buildJsonObject {
                put(
                    "value",
                    buildJsonObject {
                        put(
                            "type",
                            kotlinx.serialization.json.JsonArray(
                                listOf(JsonPrimitive("string"), JsonPrimitive("null"))
                            )
                        )
                    }
                )
            }
        )
        val tool = Tool(name = "weird", inputSchema = schema)

        val result = McpToolMapper.toDomain(tool)

        assertNull(result.parameters.single().type)
    }
}

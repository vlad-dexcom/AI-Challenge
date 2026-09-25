package com.example.geminichat

import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression test for a real bug found while testing Day 17's tool-calling: kotlinx.serialization
 * omits a property from the encoded JSON when its value equals its declared default — so
 * [GeminiFunctionTool.type] ("function") and [FunctionResultInput.type] ("function_result") were
 * silently missing from every request, and the Gemini API rejected the request with
 * `"The 'type' parameter is required at 'tools[1]'."`. Both fields are now annotated
 * `@EncodeDefault` so they're always sent even though every instance uses the default value.
 */
class GeminiToolModelsSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `GeminiFunctionTool always serializes its type field`() {
        val tool = GeminiFunctionTool(name = "get_exercise_info", description = "desc")

        val encoded = json.encodeToString(GeminiFunctionTool.serializer(), tool)

        assertTrue(encoded.contains("\"type\":\"function\""))
    }

    @Test
    fun `FunctionResultInput always serializes its type field`() {
        val input = FunctionResultInput(callId = "call-1", result = kotlinx.serialization.json.JsonPrimitive("ok"))

        val encoded = json.encodeToString(FunctionResultInput.serializer(), input)

        assertTrue(encoded.contains("\"type\":\"function_result\""))
    }
}

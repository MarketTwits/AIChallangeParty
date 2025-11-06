package com.markettwits.aichallenge

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class ChatRequest(
    val message: String,
    val sessionId: String,
    val coachStyle: String? = "default"
)

@Serializable
data class ChatResponse(
    val response: String,
    val remainingMessages: Int? = null,
    val structuredResponse: StructuredLLMResponse? = null
)

@Serializable
data class StructuredLLMResponse(
    val tag: String,
    val answer: String,
    val answerTimestamp: String,
    val coachMood: String? = null,
    val intensityLevel: String? = null,
    val nextAction: String? = null,
    val planCreated: Boolean? = false
)

@Serializable
data class Message(
    val role: String,
    val content: List<ContentBlock>
)

@Serializable
data class ContentBlock(
    val type: String,
    val text: String? = null,
    val id: String? = null,
    val name: String? = null,
    val input: JsonObject? = null,
    val tool_use_id: String? = null,
    val content: String? = null
)

@Serializable
data class AnthropicRequest(
    val model: String,
    val max_tokens: Int,
    val messages: List<Message>,
    val tools: List<Tool>? = null,
    val system: String? = null
)

@Serializable
data class AnthropicResponse(
    val id: String,
    val type: String,
    val role: String,
    val content: List<ContentBlock>,
    val model: String,
    val stop_reason: String? = null,
    val stop_sequence: String? = null,
    val usage: Usage
)

@Serializable
data class Usage(
    val input_tokens: Int,
    val output_tokens: Int
)

@Serializable
data class Tool(
    val name: String,
    val description: String,
    val input_schema: InputSchema
)

@Serializable
data class InputSchema(
    val type: String,
    val properties: JsonObject,
    val required: List<String>
)

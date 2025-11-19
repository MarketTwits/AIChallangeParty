package com.markettwits.aichallenge

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

@Serializable
data class ChatRequest(
    val message: String,
    val sessionId: String,
    val coachStyle: String? = "default",
    val maxContextTokens: Int? = null,
)

@Serializable
data class ReasoningChatRequest(
    val message: String,
    val sessionId: String,
    val reasoningMode: String = "direct",
    val temperature: Double? = null,
    val maxContextTokens: Int? = null,
    val compressionThreshold: Int? = null,
)

@Serializable
data class ReasoningChatResponse(
    val response: String,
    val reasoningMode: String,
    val timestamp: String,
    val experts: List<ExpertOpinion>? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val totalInputTokens: Int? = null,
    val contextLimit: Int? = null,
    val summaries: List<DialogSummary>? = null,
    val compressionOccurred: Boolean? = null,
)

@Serializable
data class ExpertOpinion(
    val expertName: String,
    val opinion: String,
    val confidence: Int,
)

@Serializable
data class ChatResponse(
    val response: String,
    val remainingMessages: Int? = null,
    val structuredResponse: StructuredLLMResponse? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val totalInputTokens: Int? = null,
    val contextLimit: Int? = null,
)

@Serializable
data class StructuredLLMResponse(
    val tag: String,
    val answer: String,
    val answerTimestamp: String? = null,
    val coachMood: String? = null,
    val intensityLevel: String? = null,
    val nextAction: String? = null,
    val planCreated: Boolean? = false,
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
    val system: String? = null,
    val temperature: Double? = null,
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

@Serializable
data class DialogSummary(
    val summary: String,
    val originalMessageCount: Int,
    val timestamp: String,
    val tokensBeforeCompression: Int,
    val tokensAfterCompression: Int,
)

@Serializable
data class SimpleMessage(
    val role: String,
    val content: List<SimpleContentBlock>,
)

@Serializable
data class SimpleContentBlock(
    val type: String,
    val text: String? = null,
    val id: String? = null,
    val name: String? = null,
)

@Serializable
data class ChatMessagesResponse(
    val sessionId: String,
    val messages: List<SimpleMessage>,
    val messageCount: Int,
)

// MCP API Response models
@Serializable
data class McpToolsResponse(
    val tools: List<McpToolResponse>,
    val count: Int,
    val status: String,
    val error: String? = null,
)

@Serializable
data class McpToolResponse(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
)

// GitHub API Response models
@Serializable
data class GitHubToolsResponse(
    val tools: List<GitHubToolResponse>,
    val count: Int,
    val status: String,
    val error: String? = null,
)

@Serializable
data class GitHubToolResponse(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
)

@Serializable
data class GitHubExecuteRequest(
    val tool: String,
    val parameters: JsonObject = buildJsonObject { },
)

@Serializable
data class GitHubExecuteResponse(
    val tool: String,
    val result: String,
    val success: Boolean,
    val error: String? = null,
)

// MCP Status Response model
@Serializable
data class McpStatusResponse(
    val status: String,
    val activeSessions: Int,
    val githubTokenConfigured: Boolean,
    val error: String? = null,
)

// Reminder/Task models
@Serializable
data class ReminderTask(
    val id: String,
    val title: String,
    val description: String,
    val priority: String = "medium",
    val dueDate: String? = null, // оставим для совместимости
    val reminderTime: String? = null, // оставим для совместимости
    val status: String = "pending",
    val createdAt: String,
    val completedAt: String? = null,
    val recurringType: String? = null, // daily, weekly, monthly, hourly, minutely, custom
    val periodicityMinutes: Int? = null, // период в минутах (1=минута, 10=10 минут, 60=час, 1440=день, 10080=неделя)
    val nextReminderTime: String? = null, // время следующего напоминания
    val metadata: Map<String, String> = emptyMap(),
)

@Serializable
data class ReminderCreateRequest(
    val title: String,
    val description: String,
    val priority: String = "medium",
    val dueDate: String? = null, // для совместимости
    val reminderTime: String? = null, // для совместимости
    val recurringType: String? = null, // daily, weekly, monthly, hourly, minutely, custom
    val periodicityMinutes: Int? = null, // период в минутах (1=минута, 10=10 минут, 60=час, 1440=день, 10080=неделя)
    val metadata: Map<String, String> = emptyMap(),
)

@Serializable
data class ReminderUpdateRequest(
    val title: String? = null,
    val description: String? = null,
    val priority: String? = null,
    val dueDate: String? = null,
    val reminderTime: String? = null,
    val status: String? = null,
    val recurringType: String? = null,
    val periodicityMinutes: Int? = null,
    val nextReminderTime: String? = null,
    val metadata: Map<String, String>? = null,
)

@Serializable
data class ReminderListResponse(
    val tasks: List<ReminderTask>,
    val count: Int,
    val error: String? = null,
)

@Serializable
data class ReminderResponse(
    val task: ReminderTask,
    val success: Boolean,
    val error: String? = null,
)

@Serializable
data class ReminderSummary(
    val date: String,
    val totalTasks: Int,
    val completedTasks: Int,
    val pendingTasks: Int,
    val overdueTasks: Int,
    val todayReminders: List<ReminderTask>,
    val conversationSummary: String? = null,
)

@Serializable
data class NotificationRequest(
    val message: String,
    val type: String = "info",
    val timestamp: String,
    val taskId: String? = null,
)

@Serializable
data class NotificationHistoryRecord(
    val id: Int,
    val taskId: String,
    val message: String,
    val type: String,
    val timestamp: String,
    val sent: Boolean,
)



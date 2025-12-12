package com.markettwits.aichallenge

import kotlinx.serialization.Serializable

@Serializable
data class StacktraceTaskCreateRequest(
    val stacktracePath: String? = null,
    val stacktraceText: String? = null,
    val title: String? = null,
    val model: String? = null,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val topP: Double? = null,
    val presencePenalty: Double? = null,
    val frequencyPenalty: Double? = null,
)

@Serializable
data class StacktraceTaskUpdateRequest(
    val stacktracePath: String? = null,
    val stacktraceText: String? = null,
    val title: String? = null,
    val model: String? = null,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val topP: Double? = null,
    val presencePenalty: Double? = null,
    val frequencyPenalty: Double? = null,
)

@Serializable
data class StacktraceTaskResponse(
    val id: String,
    val title: String,
    val status: StacktraceTaskStatus,
    val createdAt: String,
    val startedAt: String? = null,
    val completedAt: String? = null,
    val model: String? = null,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val topP: Double? = null,
    val presencePenalty: Double? = null,
    val frequencyPenalty: Double? = null,
    val report: String? = null,
    val error: String? = null,
    val usage: LMStudioUsage? = null,
    val stacktracePreview: String? = null,
    val stacktrace: String? = null,
    val progress: Int = 0,
)

@Serializable
enum class StacktraceTaskStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
}

@Serializable
data class StacktraceTaskCreateResponse(
    val task: StacktraceTaskResponse,
)

@Serializable
data class StacktraceTasksListResponse(
    val tasks: List<StacktraceTaskResponse>,
    val count: Int,
)

@Serializable
data class StacktraceStatusResponse(
    val available: Boolean,
    val recentTasks: List<StacktraceTaskResponse> = emptyList(),
    val activeCount: Int = 0,
    val message: String? = null,
)

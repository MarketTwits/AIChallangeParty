package com.markettwits.aichallenge.whatsnew

import kotlinx.serialization.Serializable
import java.util.*

@Serializable
data class WhatsNewRequest(
    val baseBranch: String = "main",
    val headBranch: String? = null,
    val prTitle: String? = null,
    val includeFileContent: Boolean = false,
)

enum class WhatsNewStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    FAILED
}

@Serializable
data class WhatsNewItem(
    val text: String,
)

@Serializable
data class WhatsNewResult(
    val id: String = UUID.randomUUID().toString(),
    val status: WhatsNewStatus,
    val baseBranch: String,
    val headBranch: String,
    val prTitle: String? = null,
    val generatedAt: Long = System.currentTimeMillis(),
    val items: List<WhatsNewItem> = emptyList(),
    val markdown: String? = null,
    val errorMessage: String? = null,
)

data class WhatsNewState(
    val id: String,
    val status: WhatsNewStatus,
    val request: WhatsNewRequest,
    val result: WhatsNewResult? = null,
    val error: String? = null,
    val startedAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
)

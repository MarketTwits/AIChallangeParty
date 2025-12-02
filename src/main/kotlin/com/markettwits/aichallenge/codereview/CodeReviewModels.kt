package com.markettwits.aichallenge.codereview

import kotlinx.serialization.Serializable
import java.util.*

/**
 * Request to analyze a PR for code review
 */
@Serializable
data class CodeReviewRequest(
    val baseBranch: String = "main",
    val headBranch: String? = null,
    val analysisTypes: List<String> = listOf("general", "security", "best_practices"),
    val includeFileContent: Boolean = false,
    val fileTypes: List<String>? = null,
)

/**
 * Status of a code review job
 */
enum class ReviewStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    FAILED
}

/**
 * Individual finding in the code review
 */
@Serializable
data class ReviewFinding(
    val severity: Severity,
    val category: String,
    val file: String? = null,
    val line: Int? = null,
    val title: String,
    val description: String,
    val suggestion: String? = null,
) {
    @Serializable
    enum class Severity {
        CRITICAL,  // Must fix before merge
        HIGH,      // Should fix before merge
        MEDIUM,    // Should fix soon
        LOW,       // Nice to have
        INFO       // Informational
    }
}

/**
 * Complete code review result
 */
@Serializable
data class CodeReviewResult(
    val reviewId: String = UUID.randomUUID().toString(),
    val status: ReviewStatus,
    val baseBranch: String,
    val headBranch: String,
    val timestamp: Long = System.currentTimeMillis(),
    val summary: ReviewSummary? = null,
    val findings: List<ReviewFinding> = emptyList(),
    val recommendations: List<String> = emptyList(),
    val ragContext: List<String> = emptyList(),
    val errorMessage: String? = null,
)

/**
 * Summary statistics of the review
 */
@Serializable
data class ReviewSummary(
    val totalFindings: Int,
    val bySeverity: Map<String, Int>,
    val byCategory: Map<String, Int>,
    val filesAnalyzed: Int,
    val linesChanged: Int,
)

/**
 * Internal state for tracking review progress
 */
data class ReviewState(
    val reviewId: String,
    val status: ReviewStatus,
    val result: CodeReviewResult? = null,
    val error: String? = null,
    val startedAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
)

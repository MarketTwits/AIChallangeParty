package com.markettwits.aichallenge.codereview

import com.markettwits.aichallenge.rag.RAGQueryService
import com.markettwits.aichallenge.tools.core.ToolResult
import com.markettwits.aichallenge.tools.implementations.AnalyzeCodeTool
import com.markettwits.aichallenge.tools.implementations.GetPRDiffTool
import com.markettwits.aichallenge.tools.implementations.GetPRFilesTool
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Service for automated code review
 * Coordinates tools, RAG, and Claude AI to analyze PRs
 */
class CodeReviewService(
    private val ragQueryService: RAGQueryService,
    private val claudeReviewClient: ClaudeCodeReviewClient,
    private val repositoryPath: String = ".",
) {
    private val logger = LoggerFactory.getLogger(CodeReviewService::class.java)

    private val getDiffTool = GetPRDiffTool(repositoryPath)
    private val getFilesTool = GetPRFilesTool(repositoryPath)
    private val analyzeCodeTool = AnalyzeCodeTool(ragQueryService)

    // In-memory storage for review states (in production, use a database)
    private val reviewStates = ConcurrentHashMap<String, ReviewState>()

    /**
     * Start a code review job asynchronously
     * Returns review ID for tracking
     */
    suspend fun startReview(request: CodeReviewRequest): String {
        val reviewId = java.util.UUID.randomUUID().toString()

        val initialState = ReviewState(
            reviewId = reviewId,
            status = ReviewStatus.PENDING,
            result = CodeReviewResult(
                reviewId = reviewId,
                status = ReviewStatus.PENDING,
                baseBranch = request.baseBranch,
                headBranch = request.headBranch ?: "HEAD"
            )
        )

        reviewStates[reviewId] = initialState

        // Start review in background
        GlobalScope.launch {
            try {
                performReview(reviewId, request)
            } catch (e: Exception) {
                logger.error("Error performing review $reviewId", e)
                updateReviewState(reviewId, ReviewStatus.FAILED, error = e.message)
            }
        }

        return reviewId
    }

    /**
     * Get status of a review job
     */
    fun getReviewStatus(reviewId: String): ReviewState? {
        return reviewStates[reviewId]
    }

    /**
     * Get result of a completed review
     */
    fun getReviewResult(reviewId: String): CodeReviewResult? {
        return reviewStates[reviewId]?.result
    }

    /**
     * Perform the actual code review
     */
    private suspend fun performReview(reviewId: String, request: CodeReviewRequest) {
        logger.info("Starting code review: $reviewId")
        updateReviewState(reviewId, ReviewStatus.IN_PROGRESS)

        try {
            // Step 1: Get PR diff
            logger.info("[$reviewId] Getting PR diff...")
            val diffParams = buildJsonObject {
                put("base_branch", request.baseBranch)
                request.headBranch?.let { put("head_branch", it) }
                put("include_stats", true)
            }

            val diffResult = getDiffTool.execute(diffParams)
            if (diffResult !is ToolResult.Success) {
                throw RuntimeException("Failed to get diff: ${(diffResult as ToolResult.Error).message}")
            }

            val diff = diffResult.data

            // Step 2: Get changed files
            logger.info("[$reviewId] Getting changed files...")
            val filesParams = buildJsonObject {
                put("base_branch", request.baseBranch)
                request.headBranch?.let { put("head_branch", it) }
                put("include_content", request.includeFileContent)
                request.fileTypes?.let { types ->
                    putJsonArray("file_types") {
                        types.forEach { type -> add(JsonPrimitive(type)) }
                    }
                }
            }

            val filesResult = getFilesTool.execute(filesParams)
            if (filesResult !is ToolResult.Success) {
                throw RuntimeException("Failed to get files: ${(filesResult as ToolResult.Error).message}")
            }

            val changedFiles = filesResult.data

            // Step 3: Get RAG context for the analysis types
            logger.info("[$reviewId] Gathering RAG context...")
            val ragContext = mutableListOf<String>()

            if (ragQueryService.isReady()) {
                request.analysisTypes.forEach { analysisType ->
                    try {
                        val query = buildContextQuery(analysisType)
                        val ragResult = ragQueryService.queryWithRAG(query, topK = 3)

                        ragResult.retrievedChunks.take(2).forEach { chunk ->
                            ragContext.add("[$analysisType] ${chunk.text}")
                        }
                    } catch (e: Exception) {
                        logger.warn("Could not get RAG context for $analysisType", e)
                    }
                }
            } else {
                logger.warn("RAG system not ready, proceeding without context")
            }

            // Step 4: Generate review with Claude
            logger.info("[$reviewId] Generating review with Claude AI...")
            val claudeResponse = claudeReviewClient.generateReview(
                diff = diff,
                changedFiles = changedFiles,
                ragContext = ragContext,
                analysisTypes = request.analysisTypes
            )

            // Step 5: Build final result
            logger.info("[$reviewId] Building final result...")
            val findings = claudeResponse.findings.map { finding ->
                ReviewFinding(
                    severity = parseSeverity(finding.severity),
                    category = finding.category,
                    file = finding.file,
                    line = finding.line,
                    title = finding.title,
                    description = finding.description,
                    suggestion = finding.suggestion
                )
            }

            val summary = ReviewSummary(
                totalFindings = findings.size,
                bySeverity = findings.groupBy { it.severity.name }.mapValues { it.value.size },
                byCategory = findings.groupBy { it.category }.mapValues { it.value.size },
                filesAnalyzed = extractFileCount(changedFiles),
                linesChanged = extractLinesChanged(diff)
            )

            val result = CodeReviewResult(
                reviewId = reviewId,
                status = ReviewStatus.COMPLETED,
                baseBranch = request.baseBranch,
                headBranch = request.headBranch ?: "HEAD",
                timestamp = System.currentTimeMillis(),
                summary = summary,
                findings = findings,
                recommendations = claudeResponse.recommendations,
                ragContext = ragContext
            )

            updateReviewState(reviewId, ReviewStatus.COMPLETED, result)
            logger.info("[$reviewId] Code review completed successfully")

        } catch (e: Exception) {
            logger.error("Error performing review $reviewId", e)
            updateReviewState(reviewId, ReviewStatus.FAILED, error = e.message ?: "Unknown error")
            throw e
        }
    }

    /**
     * Update review state
     */
    private fun updateReviewState(
        reviewId: String,
        status: ReviewStatus,
        result: CodeReviewResult? = null,
        error: String? = null,
    ) {
        val currentState = reviewStates[reviewId] ?: return

        val updatedState = currentState.copy(
            status = status,
            result = result ?: currentState.result?.copy(status = status, errorMessage = error),
            error = error,
            completedAt = if (status == ReviewStatus.COMPLETED || status == ReviewStatus.FAILED) {
                System.currentTimeMillis()
            } else {
                null
            }
        )

        reviewStates[reviewId] = updatedState
    }

    /**
     * Build context query for RAG based on analysis type
     */
    private fun buildContextQuery(analysisType: String): String {
        return when (analysisType) {
            "security" -> "security best practices authentication authorization"
            "performance" -> "performance optimization caching database queries"
            "architecture" -> "architecture SOLID principles design patterns"
            "best_practices" -> "coding standards best practices code quality"
            else -> "development guidelines conventions"
        }
    }

    /**
     * Parse severity string to enum
     */
    private fun parseSeverity(severity: String): ReviewFinding.Severity {
        return try {
            ReviewFinding.Severity.valueOf(severity.uppercase())
        } catch (e: Exception) {
            ReviewFinding.Severity.INFO
        }
    }

    /**
     * Extract file count from changed files output
     */
    private fun extractFileCount(changedFiles: String): Int {
        val pattern = "Total files changed: (\\d+)".toRegex()
        return pattern.find(changedFiles)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    /**
     * Extract lines changed from diff output
     */
    private fun extractLinesChanged(diff: String): Int {
        var insertions = 0
        var deletions = 0

        val pattern = "(\\d+) insertion.*?(\\d+) deletion".toRegex()
        val match = pattern.find(diff)

        if (match != null) {
            insertions = match.groupValues[1].toIntOrNull() ?: 0
            deletions = match.groupValues[2].toIntOrNull() ?: 0
        }

        return insertions + deletions
    }

    /**
     * Get all active reviews
     */
    fun getAllReviews(): List<ReviewState> {
        return reviewStates.values.toList()
    }

    /**
     * Clean up old reviews (older than 24 hours)
     */
    fun cleanupOldReviews() {
        val cutoffTime = System.currentTimeMillis() - (24 * 60 * 60 * 1000)
        val toRemove = reviewStates.filter { (_, state) ->
            val completedAt = state.completedAt ?: state.startedAt
            completedAt < cutoffTime
        }

        toRemove.keys.forEach { reviewStates.remove(it) }
        logger.info("Cleaned up ${toRemove.size} old reviews")
    }
}

package com.markettwits.aichallenge.codereview

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("CodeReviewRoutes")

/**
 * Configure code review routes
 */
fun Application.configureCodeReviewRoutes(codeReviewService: CodeReviewService) {
    routing {
        route("/code-review") {

            /**
             * POST /code-review/analyze
             * Start a new code review
             */
            post("/analyze") {
                try {
                    val request = call.receive<CodeReviewRequest>()
                    logger.info("Starting code review: base=${request.baseBranch}, head=${request.headBranch}")

                    val reviewId = codeReviewService.startReview(request)

                    call.respond(
                        HttpStatusCode.Accepted,
                        mapOf(
                            "status" to "accepted",
                            "reviewId" to reviewId,
                            "message" to "Code review started. Use /code-review/status/$reviewId to check progress."
                        )
                    )
                } catch (e: Exception) {
                    logger.error("Error starting code review", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf(
                            "error" to "Failed to start code review",
                            "message" to (e.message ?: "Unknown error")
                        )
                    )
                }
            }

            /**
             * GET /code-review/status/{reviewId}
             * Get status of a review job
             */
            get("/status/{reviewId}") {
                val reviewId = call.parameters["reviewId"]

                if (reviewId == null) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "reviewId is required")
                    )
                    return@get
                }

                val state = codeReviewService.getReviewStatus(reviewId)

                if (state == null) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        mapOf("error" to "Review not found: $reviewId")
                    )
                    return@get
                }

                val responseData = buildMap {
                    put("reviewId", state.reviewId)
                    put("status", state.status.name)
                    put("startedAt", state.startedAt)
                    state.completedAt?.let { put("completedAt", it) }
                    state.error?.let { put("error", it) }

                    if (state.status == ReviewStatus.COMPLETED) {
                        put("resultAvailable", true)
                        put("resultUrl", "/code-review/result/$reviewId")
                    }
                }

                call.respond(HttpStatusCode.OK, responseData)
            }

            /**
             * GET /code-review/result/{reviewId}
             * Get result of a completed review
             */
            get("/result/{reviewId}") {
                val reviewId = call.parameters["reviewId"]

                if (reviewId == null) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "reviewId is required")
                    )
                    return@get
                }

                val result = codeReviewService.getReviewResult(reviewId)

                if (result == null) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        mapOf("error" to "Review result not found: $reviewId")
                    )
                    return@get
                }

                if (result.status != ReviewStatus.COMPLETED) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf(
                            "error" to "Review not completed yet",
                            "status" to result.status.name,
                            "message" to "Use /code-review/status/$reviewId to check progress"
                        )
                    )
                    return@get
                }

                call.respond(HttpStatusCode.OK, result)
            }

            /**
             * GET /code-review/result/{reviewId}/markdown
             * Get review result in markdown format (for GitHub comments)
             */
            get("/result/{reviewId}/markdown") {
                val reviewId = call.parameters["reviewId"]

                if (reviewId == null) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "reviewId is required")
                    )
                    return@get
                }

                val result = codeReviewService.getReviewResult(reviewId)

                if (result == null || result.status != ReviewStatus.COMPLETED) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        mapOf("error" to "Review result not found or not completed")
                    )
                    return@get
                }

                val markdown = formatReviewAsMarkdown(result)
                call.respondText(markdown, ContentType.Text.Plain)
            }

            /**
             * GET /code-review/list
             * Get all reviews
             */
            get("/list") {
                val reviews = codeReviewService.getAllReviews().map { state ->
                    mapOf(
                        "reviewId" to state.reviewId,
                        "status" to state.status.name,
                        "baseBranch" to (state.result?.baseBranch ?: ""),
                        "headBranch" to (state.result?.headBranch ?: ""),
                        "startedAt" to state.startedAt,
                        "completedAt" to state.completedAt
                    )
                }

                call.respond(
                    HttpStatusCode.OK,
                    mapOf(
                        "total" to reviews.size,
                        "reviews" to reviews
                    )
                )
            }

            /**
             * POST /code-review/cleanup
             * Clean up old reviews
             */
            post("/cleanup") {
                codeReviewService.cleanupOldReviews()
                call.respond(
                    HttpStatusCode.OK,
                    mapOf("message" to "Old reviews cleaned up")
                )
            }
        }
    }
}

/**
 * Format review result as markdown for GitHub comments
 */
private fun formatReviewAsMarkdown(result: CodeReviewResult): String {
    return buildString {
        appendLine("# 🤖 Automated Code Review")
        appendLine()
        appendLine("**Review ID:** `${result.reviewId}`")
        appendLine("**Branch:** `${result.baseBranch}...${result.headBranch}`")
        appendLine("**Date:** ${java.time.Instant.ofEpochMilli(result.timestamp)}")
        appendLine()

        // Summary
        result.summary?.let { summary ->
            appendLine("## 📊 Summary")
            appendLine()
            appendLine("- **Total Findings:** ${summary.totalFindings}")
            appendLine("- **Files Analyzed:** ${summary.filesAnalyzed}")
            appendLine("- **Lines Changed:** ${summary.linesChanged}")
            appendLine()

            if (summary.bySeverity.isNotEmpty()) {
                appendLine("### By Severity")
                summary.bySeverity.entries.sortedByDescending { it.value }.forEach { (severity, count) ->
                    val emoji = when (severity) {
                        "CRITICAL" -> "🔴"
                        "HIGH" -> "🟠"
                        "MEDIUM" -> "🟡"
                        "LOW" -> "🔵"
                        else -> "⚪"
                    }
                    appendLine("- $emoji **$severity:** $count")
                }
                appendLine()
            }
        }

        // Findings
        if (result.findings.isNotEmpty()) {
            appendLine("## 🔍 Findings")
            appendLine()

            // Group by severity
            val groupedFindings = result.findings.groupBy { it.severity }

            listOf(
                ReviewFinding.Severity.CRITICAL,
                ReviewFinding.Severity.HIGH,
                ReviewFinding.Severity.MEDIUM,
                ReviewFinding.Severity.LOW,
                ReviewFinding.Severity.INFO
            ).forEach { severity ->
                val findings = groupedFindings[severity] ?: return@forEach

                if (findings.isNotEmpty()) {
                    val emoji = when (severity) {
                        ReviewFinding.Severity.CRITICAL -> "🔴"
                        ReviewFinding.Severity.HIGH -> "🟠"
                        ReviewFinding.Severity.MEDIUM -> "🟡"
                        ReviewFinding.Severity.LOW -> "🔵"
                        ReviewFinding.Severity.INFO -> "ℹ️"
                    }

                    appendLine("### $emoji ${severity.name} Issues")
                    appendLine()

                    findings.forEach { finding ->
                        appendLine("#### ${finding.title}")

                        finding.file?.let { file ->
                            if (finding.line != null) {
                                appendLine("**Location:** `$file:${finding.line}`")
                            } else {
                                appendLine("**File:** `$file`")
                            }
                        }

                        appendLine("**Category:** `${finding.category}`")
                        appendLine()
                        appendLine(finding.description)
                        appendLine()

                        finding.suggestion?.let { suggestion ->
                            appendLine("**💡 Suggestion:**")
                            appendLine(suggestion)
                            appendLine()
                        }

                        appendLine("---")
                        appendLine()
                    }
                }
            }
        } else {
            appendLine("## ✅ No Issues Found")
            appendLine()
            appendLine("The code looks good! No issues detected in this review.")
            appendLine()
        }

        // Recommendations
        if (result.recommendations.isNotEmpty()) {
            appendLine("## 💡 General Recommendations")
            appendLine()
            result.recommendations.forEach { recommendation ->
                appendLine("- $recommendation")
            }
            appendLine()
        }

        // Footer
        appendLine("---")
        appendLine()
        appendLine("🤖 *Generated with Claude Code Review*")
        appendLine()

        if (result.ragContext.isNotEmpty()) {
            appendLine("<details>")
            appendLine("<summary>📚 Project Context Used</summary>")
            appendLine()
            result.ragContext.take(3).forEach { context ->
                appendLine("- ${context.take(100)}...")
            }
            appendLine()
            appendLine("</details>")
        }
    }
}

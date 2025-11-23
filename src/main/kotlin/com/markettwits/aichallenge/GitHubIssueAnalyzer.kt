package com.markettwits.aichallenge

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * Анализатор issues и pull requests GitHub репозитория
 */
object GitHubIssueAnalyzer {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient(CIO)
    private val baseUrl = "https://api.github.com"
    private val gitHubToken = System.getProperty("GITHUB_TOKEN") ?: System.getenv("GITHUB_TOKEN") ?: ""

    suspend fun analyzeRepositoryIssues(
        owner: String,
        repo: String,
        limit: Int = 100,
    ): JsonObject {
        try {
            // Получаем issues
            val issuesUrl = "$baseUrl/repos/$owner/$repo/issues"
            val response = client.get(issuesUrl) {
                header("Accept", "application/vnd.github.v3+json")
                if (gitHubToken.isNotEmpty()) {
                    header("Authorization", "token $gitHubToken")
                }
                parameter("per_page", limit)
                parameter("state", "all")
            }

            if (!response.status.isSuccess()) {
                return createErrorAnalysis("Failed to fetch repository issues")
            }

            val issues = json.decodeFromString<List<JsonElement>>(response.bodyAsText())
            val analysis = analyzeIssuesData(issues)

            return buildJsonObject {
                put("total_issues_analyzed", analysis.totalIssues)
                put("open_issues", analysis.openIssues)
                put("closed_issues", analysis.closedIssues)
                put("pull_requests", analysis.pullRequests)
                put("issue_labels", buildJsonArray {
                    analysis.issueLabels.forEach { (label, count) ->
                        add(buildJsonObject {
                            put("label", label)
                            put("count", count)
                        })
                    }
                })
                put("issue_activity", buildJsonArray {
                    analysis.issueActivity.forEach { activity ->
                        add(buildJsonObject {
                            put("title", activity.title)
                            put("created_at", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'").format(activity.createdAt))
                            put("updated_at", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'").format(activity.updatedAt))
                            put(
                                "closed_at",
                                activity.closedAt?.let { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'").format(it) })
                            put("comments_count", activity.commentsCount)
                            put("state", activity.state)
                        })
                    }
                })
                put("top_contributors", buildJsonArray {
                    analysis.topContributors.forEach { (contributor, count) ->
                        add(buildJsonObject {
                            put("contributor", contributor)
                            put("issues", count)
                        })
                    }
                })
                put("resolution_time", buildJsonObject {
                    put("average_days", analysis.resolutionTime.averageDays)
                    put("total_resolved", analysis.resolutionTime.totalResolved)
                })
                put("issue_priorities", buildJsonObject {
                    put("critical", analysis.issuePriorities.critical)
                    put("high", analysis.issuePriorities.high)
                    put("medium", analysis.issuePriorities.medium)
                    put("low", analysis.issuePriorities.low)
                })
            }

        } catch (e: Exception) {
            return createErrorAnalysis("Error analyzing issues: ${e.message}")
        }
    }

    private fun analyzeIssuesData(issues: List<JsonElement>): IssueAnalysisResult {
        var openIssues = 0
        var closedIssues = 0
        var pullRequests = 0
        val labels = mutableMapOf<String, Int>()
        val contributors = mutableMapOf<String, Int>()
        val issueActivity = mutableListOf<IssueActivityItem>()
        val resolutionTimes = mutableListOf<Long>()

        for (issue in issues) {
            // Пропускаем PRs если нужно будет анализировать отдельно
            val isPullRequest = issue.jsonObject["pull_request"] != null
            if (isPullRequest) {
                pullRequests++
                continue
            }

            // Считаем статус
            val state = issue.jsonObject["state"]?.jsonPrimitive?.content ?: "open"
            if (state == "open") {
                openIssues++
            } else {
                closedIssues++
            }

            // Анализируем метки
            val labelsArray = issue.jsonObject["labels"]?.jsonArray
            if (labelsArray != null) {
                for (labelElement in labelsArray) {
                    val labelName = labelElement.jsonObject["name"]?.jsonPrimitive?.content ?: "unknown"
                    labels[labelName] = labels.getOrDefault(labelName, 0) + 1
                }
            }

            // Учитываем авторов и ассигнованных
            val author = issue.jsonObject["user"]?.jsonObject?.get("login")?.jsonPrimitive?.content ?: "unknown"
            contributors[author] = contributors.getOrDefault(author, 0) + 1

            // Анализируем активность
            val createdAt = issue.jsonObject["created_at"]?.jsonPrimitive?.content
            val updatedAt = issue.jsonObject["updated_at"]?.jsonPrimitive?.content
            val closedAt = issue.jsonObject["closed_at"]?.jsonPrimitive?.content
            val commentsCount = issue.jsonObject["comments"]?.jsonPrimitive?.int ?: 0

            if (createdAt != null) {
                val createdDate = parseDate(createdAt)
                val updatedDate = if (updatedAt != null) parseDate(updatedAt) else createdDate
                val closedDate = if (closedAt != null) parseDate(closedAt) else null

                val activityItem = IssueActivityItem(
                    title = issue.jsonObject["title"]?.jsonPrimitive?.content ?: "Untitled",
                    createdAt = createdDate,
                    updatedAt = updatedDate,
                    closedAt = closedDate,
                    commentsCount = commentsCount,
                    state = state
                )
                issueActivity.add(activityItem)

                // Считаем время решения
                if (closedDate != null) {
                    resolutionTimes.add(closedDate.time - createdDate.time)
                }
            }
        }

        // Сортируем по активности
        val sortedContributors = contributors.toList().sortedByDescending { it.second }.take(10)
        val sortedLabels = labels.toList().sortedByDescending { it.second }.take(15)

        // Анализируем время решения
        val avgResolutionTime = if (resolutionTimes.isNotEmpty()) {
            resolutionTimes.average() / (1000 * 60 * 60 * 24) // в днях
        } else {
            0.0
        }

        // Анализируем приоритеты на основе меток
        val issuePriorities = analyzeIssuePriorities(labels)

        return IssueAnalysisResult(
            totalIssues = issues.size - pullRequests,
            openIssues = openIssues,
            closedIssues = closedIssues,
            pullRequests = pullRequests,
            issueLabels = sortedLabels,
            topContributors = sortedContributors,
            issueActivity = issueActivity.sortedByDescending { it.updatedAt }.take(20),
            resolutionTime = ResolutionInfo(
                averageDays = avgResolutionTime,
                totalResolved = resolutionTimes.size
            ),
            issuePriorities = issuePriorities
        )
    }

    private fun analyzeIssuePriorities(labels: Map<String, Int>): IssuePriorities {
        var critical = 0
        var high = 0
        var medium = 0
        var low = 0

        for ((label, count) in labels) {
            val lowerLabel = label.lowercase()
            when {
                lowerLabel.contains("critical") || lowerLabel.contains("urgent") || lowerLabel.contains("blocker") ->
                    critical += count

                lowerLabel.contains("high") || lowerLabel.contains("important") ->
                    high += count

                lowerLabel.contains("medium") || lowerLabel.contains("normal") ->
                    medium += count

                lowerLabel.contains("low") || lowerLabel.contains("minor") ->
                    low += count
            }
        }

        return IssuePriorities(
            critical = critical,
            high = high,
            medium = medium,
            low = low
        )
    }

    private fun parseDate(dateStr: String): Date {
        return try {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'").parse(dateStr) ?: Date()
        } catch (e: Exception) {
            Date()
        }
    }

    private fun createErrorAnalysis(error: String): JsonObject {
        return buildJsonObject {
            put("success", false)
            put("error", error)
            put("analysis", buildJsonObject {
                put("total_issues_analyzed", 0)
                put("open_issues", 0)
                put("closed_issues", 0)
                put("pull_requests", 0)
                put("issue_labels", buildJsonArray {})
                put("top_contributors", buildJsonArray {})
                put("resolution_time", buildJsonObject {
                    put("average_days", 0.0)
                    put("total_resolved", 0)
                })
                put("issue_priorities", buildJsonObject {
                    put("critical", 0)
                    put("high", 0)
                    put("medium", 0)
                    put("low", 0)
                })
            })
        }
    }
}

data class IssueAnalysisResult(
    val totalIssues: Int,
    val openIssues: Int,
    val closedIssues: Int,
    val pullRequests: Int,
    val issueLabels: List<Pair<String, Int>>,
    val topContributors: List<Pair<String, Int>>,
    val issueActivity: List<IssueActivityItem>,
    val resolutionTime: ResolutionInfo,
    val issuePriorities: IssuePriorities,
)

data class IssueActivityItem(
    val title: String,
    val createdAt: Date,
    val updatedAt: Date,
    val closedAt: Date?,
    val commentsCount: Int,
    val state: String,
)

data class ResolutionInfo(
    val averageDays: Double,
    val totalResolved: Int,
)

data class IssuePriorities(
    val critical: Int,
    val high: Int,
    val medium: Int,
    val low: Int,
)
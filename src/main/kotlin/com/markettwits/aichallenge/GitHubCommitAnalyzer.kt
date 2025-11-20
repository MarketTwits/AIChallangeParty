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
 * Анализатор коммитов GitHub репозитория
 */
object GitHubCommitAnalyzer {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient(CIO)
    private val baseUrl = "https://api.github.com"
    private val gitHubToken = System.getProperty("GITHUB_TOKEN") ?: System.getenv("GITHUB_TOKEN") ?: ""

    suspend fun analyzeRepositoryCommits(
        owner: String,
        repo: String,
        limit: Int = 100,
    ): JsonObject {
        try {
            // Получаем последние коммиты
            val commitsUrl = "$baseUrl/repos/$owner/$repo/commits"
            val response = client.get(commitsUrl) {
                header("Accept", "application/vnd.github.v3+json")
                if (gitHubToken.isNotEmpty()) {
                    header("Authorization", "token $gitHubToken")
                }
                parameter("per_page", limit)
            }

            if (!response.status.isSuccess()) {
                return createErrorAnalysis("Failed to fetch repository commits")
            }

            val commits = json.decodeFromString<List<JsonElement>>(response.bodyAsText())
            val analysis = analyzeCommitsData(commits)

            return buildJsonObject {
                put("total_commits_analyzed", analysis.totalCommits)
                put("date_range", buildJsonObject {
                    analysis.dateRange?.let { range ->
                        put("start_date", range.startDate)
                        put("end_date", range.endDate)
                        put("total_days", range.totalDays)
                    }
                })
                put("authors", buildJsonObject {
                    analysis.authors.forEach { (author, count) ->
                        put(author, count)
                    }
                })
                put("commit_frequency", buildJsonObject {
                    put("most_active_day_of_week", analysis.commitFrequency.mostActiveDayOfWeek)
                    put("most_active_hour_of_day", analysis.commitFrequency.mostActiveHourOfDay)
                    put("average_commits_per_day", analysis.commitFrequency.averageCommitsPerDay)
                })
                put("most_active_authors", buildJsonArray {
                    analysis.mostActiveAuthors.forEach { (author, count) ->
                        add(buildJsonObject {
                            put("author", author)
                            put("commits", count)
                        })
                    }
                })
                put("commit_patterns", buildJsonObject {
                    analysis.commitPatterns.forEach { (pattern, count) ->
                        put(pattern, count)
                    }
                })
                put("commit_messages", buildJsonArray {
                    analysis.commitMessages.forEach { message ->
                        add(message)
                    }
                })
                put("development_activity", buildJsonObject {
                    put("activity_level", analysis.developmentActivity.activityLevel)
                    put("recent_commit_count", analysis.developmentActivity.recentCommitCount)
                    put("collaborator_count", analysis.developmentActivity.collaboratorCount)
                })
            }

        } catch (e: Exception) {
            return createErrorAnalysis("Error analyzing commits: ${e.message}")
        }
    }

    private fun analyzeCommitsData(commits: List<JsonElement>): CommitAnalysisResult {
        val authors = mutableMapOf<String, Int>()
        val commitMessages = mutableListOf<String>()
        val commitDates = mutableListOf<Date>()
        val messagePatterns = mutableMapOf<String, Int>()

        var firstCommit: Date? = null
        var lastCommit: Date? = null

        for (commit in commits) {
            // Извлекаем информацию об авторе
            val author = commit.jsonObject["commit"]?.jsonObject?.get("author")
            val authorName = author?.jsonObject?.get("name")?.jsonPrimitive?.content ?: "Unknown"
            author?.jsonObject?.get("email")?.jsonPrimitive?.content ?: "unknown@example.com"

            authors[authorName] = authors.getOrDefault(authorName, 0) + 1

            // Извлекаем сообщение коммита
            val message = commit.jsonObject["commit"]?.jsonObject?.get("message")?.jsonPrimitive?.content ?: ""
            commitMessages.add(message)

            // Анализируем паттерны сообщений
            analyzeMessagePattern(message, messagePatterns)

            // Извлекаем дату коммита
            val dateStr = author?.jsonObject?.get("date")?.jsonPrimitive?.content
            if (dateStr != null) {
                try {
                    val date = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'").parse(dateStr)
                    commitDates.add(date)

                    if (firstCommit == null || date.before(firstCommit)) {
                        firstCommit = date
                    }
                    if (lastCommit == null || date.after(lastCommit)) {
                        lastCommit = date
                    }
                } catch (e: Exception) {
                    // Ignore parsing errors
                }
            }
        }

        // Сортируем авторов по активности
        val sortedAuthors = authors.toList().sortedByDescending { it.second }.take(10)

        // Анализируем частоту коммитов
        val commitFrequency = analyzeCommitFrequency(commitDates)

        // Анализируем активность разработки
        val developmentActivity = analyzeDevelopmentActivity(commitDates, sortedAuthors)

        return CommitAnalysisResult(
            totalCommits = commits.size,
            authors = authors,
            mostActiveAuthors = sortedAuthors,
            commitMessages = commitMessages.takeLast(20),
            commitPatterns = messagePatterns,
            commitFrequency = commitFrequency,
            developmentActivity = developmentActivity,
            dateRange = if (firstCommit != null && lastCommit != null) {
                DateRange(
                    startDate = SimpleDateFormat("yyyy-MM-dd").format(firstCommit),
                    endDate = SimpleDateFormat("yyyy-MM-dd").format(lastCommit),
                    totalDays = ((lastCommit.time - firstCommit.time) / (1000 * 60 * 60 * 24)).toInt()
                )
            } else null
        )
    }

    private fun analyzeMessagePattern(message: String, patterns: MutableMap<String, Int>) {
        val lowerMessage = message.lowercase()

        // Категоризируем типы коммитов
        when {
            lowerMessage.startsWith("fix") || lowerMessage.contains("bug") ->
                patterns["Bug Fixes"] = patterns.getOrDefault("Bug Fixes", 0) + 1

            lowerMessage.startsWith("feat") || lowerMessage.contains("add") || lowerMessage.contains("new") ->
                patterns["Features"] = patterns.getOrDefault("Features", 0) + 1

            lowerMessage.startsWith("refactor") || lowerMessage.contains("refactor") ->
                patterns["Refactoring"] = patterns.getOrDefault("Refactoring", 0) + 1

            lowerMessage.startsWith("docs") || lowerMessage.contains("documentation") ->
                patterns["Documentation"] = patterns.getOrDefault("Documentation", 0) + 1

            lowerMessage.startsWith("test") || lowerMessage.contains("test") ->
                patterns["Tests"] = patterns.getOrDefault("Tests", 0) + 1

            lowerMessage.startsWith("merge") || lowerMessage.contains("merge") ->
                patterns["Merge"] = patterns.getOrDefault("Merge", 0) + 1

            lowerMessage.startsWith("update") || lowerMessage.contains("update") ->
                patterns["Updates"] = patterns.getOrDefault("Updates", 0) + 1

            else ->
                patterns["Other"] = patterns.getOrDefault("Other", 0) + 1
        }
    }

    private fun analyzeCommitFrequency(dates: List<Date>): CommitFrequency {
        if (dates.isEmpty()) return CommitFrequency(0, 0, 0.0)

        // Группируем по дням недели
        val dayOfWeekCount = IntArray(7) { 0 }
        val hourOfDayCount = IntArray(24) { 0 }

        val calendar = Calendar.getInstance()

        for (date in dates) {
            calendar.time = date
            dayOfWeekCount[calendar.get(Calendar.DAY_OF_WEEK) - 1]++
            hourOfDayCount[calendar.get(Calendar.HOUR_OF_DAY)]++
        }

        val mostActiveDay = dayOfWeekCount.withIndex().maxByOrNull { it.value }?.index ?: 0
        val mostActiveHour = hourOfDayCount.withIndex().maxByOrNull { it.value }?.index ?: 0

        val avgCommitsPerDay = if (dates.size > 1) {
            val timeSpan = (dates.last().time - dates.first().time) / (1000 * 60 * 60 * 24).toDouble()
            dates.size / timeSpan
        } else {
            dates.size.toDouble()
        }

        return CommitFrequency(
            mostActiveDayOfWeek = mostActiveDay,
            mostActiveHourOfDay = mostActiveHour,
            averageCommitsPerDay = avgCommitsPerDay
        )
    }

    private fun analyzeDevelopmentActivity(dates: List<Date>, authors: List<Pair<String, Int>>): DevelopmentActivity {
        if (dates.isEmpty()) return DevelopmentActivity("Low", 0, 0)

        val calendar = Calendar.getInstance()
        val recentCommits = dates.filter { date ->
            calendar.time = Date()
            calendar.add(Calendar.DAY_OF_YEAR, -30)
            date.after(calendar.time)
        }

        val recentActivityScore = recentCommits.size / 30.0 // коммиты в день за последние 30 дней
        val collaboratorCount = authors.size

        val activityLevel = when {
            recentActivityScore > 2.0 -> "Very High"
            recentActivityScore > 1.0 -> "High"
            recentActivityScore > 0.5 -> "Medium"
            recentActivityScore > 0.1 -> "Low"
            else -> "Very Low"
        }

        return DevelopmentActivity(
            activityLevel = activityLevel,
            recentCommitCount = recentCommits.size,
            collaboratorCount = collaboratorCount
        )
    }

    private fun createErrorAnalysis(error: String): JsonObject {
        return buildJsonObject {
            put("success", false)
            put("error", error)
            put("analysis", buildJsonObject {
                put("total_commits_analyzed", 0)
                put("authors", buildJsonObject {})
                put("commit_frequency", buildJsonObject {})
                put("development_activity", buildJsonObject {
                    put("activity_level", "Unknown")
                    put("recent_commit_count", 0)
                    put("collaborator_count", 0)
                })
            })
        }
    }
}

data class CommitAnalysisResult(
    val totalCommits: Int,
    val authors: Map<String, Int>,
    val mostActiveAuthors: List<Pair<String, Int>>,
    val commitMessages: List<String>,
    val commitPatterns: Map<String, Int>,
    val commitFrequency: CommitFrequency,
    val developmentActivity: DevelopmentActivity,
    val dateRange: DateRange?,
)

data class CommitFrequency(
    val mostActiveDayOfWeek: Int,
    val mostActiveHourOfDay: Int,
    val averageCommitsPerDay: Double,
)

data class DevelopmentActivity(
    val activityLevel: String,
    val recentCommitCount: Int,
    val collaboratorCount: Int,
)

data class DateRange(
    val startDate: String,
    val endDate: String,
    val totalDays: Int,
)
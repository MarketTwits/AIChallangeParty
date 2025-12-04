package com.markettwits.aichallenge.tools.implementations.team

import com.markettwits.aichallenge.team.ProjectStatus
import com.markettwits.aichallenge.team.TaskRepository
import com.markettwits.aichallenge.tools.core.Tool
import com.markettwits.aichallenge.tools.core.ToolResult
import com.markettwits.aichallenge.tools.core.ToolSchema
import com.markettwits.aichallenge.tools.core.ToolType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.slf4j.LoggerFactory

/**
 * MCP Tool for getting overall project status and top priority tasks
 */
class GetProjectStatusTool(private val taskRepository: TaskRepository) : Tool {

    private val logger = LoggerFactory.getLogger(GetProjectStatusTool::class.java)

    override val name = "get_project_status"
    override val description =
        "Get overall project status including key metrics and top priority tasks that need attention"
    override val type = ToolType.MCP

    override val schema = ToolSchema(
        properties = buildJsonObject {
            // No input parameters needed
        },
        required = emptyList()
    )

    override suspend fun execute(params: JsonObject): ToolResult {
        return try {
            val status = taskRepository.getProjectStatus()

            logger.info("📊 Retrieved project status via tool")

            val healthScore = calculateHealthScore(status)
            val healthStatus = when {
                healthScore >= 80 -> "Excellent"
                healthScore >= 60 -> "Good"
                healthScore >= 40 -> "Fair"
                else -> "Needs Attention"
            }
            val healthEmoji = when {
                healthScore >= 80 -> "🟢"
                healthScore >= 60 -> "🟡"
                healthScore >= 40 -> "🟠"
                else -> "🔴"
            }

            val resultData = buildString {
                appendLine("📊 Project Status Overview")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                appendLine("📋 Total Tasks: ${status.totalTasks}")
                appendLine("✅ Completed: ${status.completedTasks}")
                appendLine("🔄 In Progress: ${status.inProgressTasks}")
                appendLine("🚫 Blocked: ${status.blockedTasks}")
                appendLine("🔴 Critical: ${status.criticalTasks}")
                appendLine("📈 Completion Rate: ${"%.2f".format(status.completionRate)}%")
                appendLine("📊 Recent Activity: ${status.recentActivity}")
                appendLine()

                appendLine("🏥 Project Health: $healthEmoji $healthStatus ($healthScore/100)")
                appendLine()

                if (status.topPriorityTasks.isNotEmpty()) {
                    appendLine("🎯 Top Priority Tasks:")
                    status.topPriorityTasks.forEach { task ->
                        appendLine("  • #${task.id}: ${task.title}")
                        appendLine("    📝 ${task.description}")
                        appendLine("    📊 Status: ${task.status} | 🎯 Priority: ${task.priority}")
                        if (task.assignee != null) {
                            appendLine("    👤 Assignee: ${task.assignee}")
                        }
                        if (task.dueDate != null) {
                            val isOverdue = task.dueDate < System.currentTimeMillis()
                            val overdueStatus = if (isOverdue) " (OVERDUE)" else ""
                            appendLine("    📅 Due Date: ${task.dueDate}$overdueStatus")
                        }
                        appendLine()
                    }
                }

                appendLine("💡 Recommendations:")
                if (status.criticalTasks > 0) {
                    appendLine("  🔴 Focus on ${status.criticalTasks} critical tasks first")
                }
                if (status.blockedTasks > 0) {
                    appendLine("  🚧 Unblock ${status.blockedTasks} blocked tasks to improve flow")
                }
                if (status.inProgressTasks > status.totalTasks * 0.5 && status.totalTasks > 0) {
                    appendLine("  ⚠️ Too many tasks in progress - consider focusing on fewer tasks")
                }
                if (status.topPriorityTasks.isEmpty() && status.totalTasks > 0) {
                    appendLine("  ✅ All high-priority tasks are done! Consider reviewing backlog")
                }
                if (status.completionRate >= 80.0) {
                    appendLine("  🎉 Great progress! ${status.completionRate.toInt()}% complete")
                } else if (status.completionRate < 30.0 && status.totalTasks > 5) {
                    appendLine("  📊 Many tasks pending - prioritize and create a sprint plan")
                }
            }

            ToolResult.Success(
                data = resultData,
                metadata = mapOf(
                    "totalTasks" to status.totalTasks,
                    "completedTasks" to status.completedTasks,
                    "inProgressTasks" to status.inProgressTasks,
                    "blockedTasks" to status.blockedTasks,
                    "criticalTasks" to status.criticalTasks,
                    "completionRate" to status.completionRate,
                    "healthScore" to healthScore,
                    "healthStatus" to healthStatus
                )
            )

        } catch (e: Exception) {
            logger.error("Failed to get project status", e)
            ToolResult.Error("Failed to get project status: ${e.message}")
        }
    }

    /**
     * Calculate project health score (0-100)
     */
    private fun calculateHealthScore(status: ProjectStatus): Int {
        var score = 50 // Base score

        // Positive factors
        score += (status.completionRate * 0.3).toInt() // Up to +30 for completion
        if (status.blockedTasks == 0) score += 10
        if (status.criticalTasks == 0) score += 10

        // Negative factors
        score -= status.criticalTasks * 5  // -5 per critical task
        score -= status.blockedTasks * 3   // -3 per blocked task

        // Clamp to 0-100
        return score.coerceIn(0, 100)
    }

    override fun validateParams(params: JsonObject): String? {
        // No parameters to validate
        return null
    }
}

package com.markettwits.aichallenge.tools.implementations.team

import com.markettwits.aichallenge.team.TaskRepository
import com.markettwits.aichallenge.tools.core.Tool
import com.markettwits.aichallenge.tools.core.ToolResult
import com.markettwits.aichallenge.tools.core.ToolSchema
import com.markettwits.aichallenge.tools.core.ToolType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.slf4j.LoggerFactory

/**
 * MCP Tool for getting task statistics and analytics
 */
class GetTaskStatisticsTool(private val taskRepository: TaskRepository) : Tool {

    private val logger = LoggerFactory.getLogger(GetTaskStatisticsTool::class.java)

    override val name = "get_task_statistics"
    override val description =
        "Get comprehensive statistics about tasks including counts by status, priority, assignee, and completion rates"
    override val type = ToolType.MCP

    override val schema = ToolSchema(
        properties = buildJsonObject {
            // No input parameters needed
        },
        required = emptyList()
    )

    override suspend fun execute(params: JsonObject): ToolResult {
        return try {
            val stats = taskRepository.getStatistics()

            logger.info("📊 Retrieved task statistics via tool")

            val resultData = buildString {
                appendLine("📊 Task Statistics Overview")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                appendLine("📋 Total Tasks: ${stats.total}")
                appendLine("✅ Completion Rate: ${"%.2f".format(stats.completionRate)}%")
                appendLine("🔴 Critical Tasks: ${stats.criticalCount}")
                appendLine("🚫 Blocked Tasks: ${stats.blockedCount}")
                appendLine("⏰ Overdue Tasks: ${stats.overdueCount}")
                appendLine()

                appendLine("📊 By Status:")
                stats.byStatus.forEach { (status, count) ->
                    appendLine("  • $status: $count")
                }
                appendLine()

                appendLine("🎯 By Priority:")
                stats.byPriority.forEach { (priority, count) ->
                    appendLine("  • $priority: $count")
                }
                appendLine()

                appendLine("👤 By Assignee:")
                stats.byAssignee.forEach { (assignee, count) ->
                    appendLine("  • $assignee: $count")
                }
                appendLine()

                appendLine("💡 Insights:")
                if (stats.criticalCount > 0) {
                    appendLine("  ⚠️ ${stats.criticalCount} critical tasks require immediate attention")
                }
                if (stats.blockedCount > 0) {
                    appendLine("  🚫 ${stats.blockedCount} tasks are currently blocked")
                }
                if (stats.overdueCount > 0) {
                    appendLine("  ⏰ ${stats.overdueCount} tasks are overdue")
                }
                if (stats.completionRate >= 80.0) {
                    appendLine("  ✅ Excellent progress! ${stats.completionRate.toInt()}% tasks completed")
                } else if (stats.completionRate >= 50.0) {
                    appendLine("  📈 Good progress: ${stats.completionRate.toInt()}% tasks completed")
                } else if (stats.completionRate > 0) {
                    appendLine("  ⚡ ${stats.completionRate.toInt()}% tasks completed - keep going!")
                }
            }

            ToolResult.Success(
                data = resultData,
                metadata = mapOf(
                    "total" to stats.total,
                    "completionRate" to stats.completionRate,
                    "criticalCount" to stats.criticalCount,
                    "blockedCount" to stats.blockedCount,
                    "overdueCount" to stats.overdueCount
                )
            )

        } catch (e: Exception) {
            logger.error("Failed to get task statistics", e)
            ToolResult.Error("Failed to get statistics: ${e.message}")
        }
    }

    override fun validateParams(params: JsonObject): String? {
        // No parameters to validate
        return null
    }
}

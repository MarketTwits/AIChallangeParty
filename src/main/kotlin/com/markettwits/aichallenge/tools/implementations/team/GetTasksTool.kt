package com.markettwits.aichallenge.tools.implementations.team

import com.markettwits.aichallenge.team.TaskFilter
import com.markettwits.aichallenge.team.TaskRepository
import com.markettwits.aichallenge.tools.core.Tool
import com.markettwits.aichallenge.tools.core.ToolResult
import com.markettwits.aichallenge.tools.core.ToolSchema
import com.markettwits.aichallenge.tools.core.ToolType
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

/**
 * MCP Tool for retrieving tasks with optional filtering
 */
class GetTasksTool(private val taskRepository: TaskRepository) : Tool {

    private val logger = LoggerFactory.getLogger(GetTasksTool::class.java)

    override val name = "get_tasks"
    override val description = "Get list of tasks with optional filtering by status, priority, assignee, or tags"
    override val type = ToolType.MCP

    override val schema = ToolSchema(
        properties = buildJsonObject {
            put("status", buildJsonObject {
                put("type", "string")
                put("enum", buildJsonArray {
                    add("TODO")
                    add("IN_PROGRESS")
                    add("DONE")
                    add("BLOCKED")
                })
                put("description", "Filter by task status")
            })
            put("priority", buildJsonObject {
                put("type", "string")
                put("enum", buildJsonArray {
                    add("LOW")
                    add("MEDIUM")
                    add("HIGH")
                    add("CRITICAL")
                })
                put("description", "Filter by priority level")
            })
            put("assignee", buildJsonObject {
                put("type", "string")
                put("description", "Filter by assigned person")
            })
            put("tags", buildJsonObject {
                put("type", "string")
                put("description", "Filter by tags (comma-separated)")
            })
            put("limit", buildJsonObject {
                put("type", "integer")
                put("description", "Maximum number of tasks to return (default: 100)")
            })
        },
        required = emptyList()
    )

    override suspend fun execute(params: JsonObject): ToolResult {
        return try {
            val status = params["status"]?.jsonPrimitive?.content
            val priority = params["priority"]?.jsonPrimitive?.content
            val assignee = params["assignee"]?.jsonPrimitive?.content
            val tags = params["tags"]?.jsonPrimitive?.content
            val limit = params["limit"]?.jsonPrimitive?.intOrNull ?: 100

            val filter = TaskFilter(
                status = status,
                priority = priority,
                assignee = assignee,
                tags = tags,
                limit = limit
            )

            val tasks = taskRepository.getTasks(filter)

            logger.info("📋 Retrieved ${tasks.size} tasks via tool (filter: $filter)")

            if (tasks.isEmpty()) {
                return ToolResult.Success(
                    data = "No tasks found matching the specified criteria.",
                    metadata = mapOf("count" to 0, "filter" to filter.toString())
                )
            }

            val resultData = buildString {
                appendLine("📋 Found ${tasks.size} tasks:")
                appendLine("=".repeat(50))
                appendLine()

                tasks.forEach { task ->
                    val priorityEmoji = when (task.priority) {
                        "CRITICAL" -> "🔴"
                        "HIGH" -> "🟠"
                        "MEDIUM" -> "🟡"
                        "LOW" -> "🟢"
                        else -> "⚪"
                    }

                    val statusEmoji = when (task.status) {
                        "DONE" -> "✅"
                        "IN_PROGRESS" -> "⚡"
                        "BLOCKED" -> "🚫"
                        "TODO" -> "📝"
                        else -> "❓"
                    }

                    appendLine("$priorityEmoji ${statusEmoji} #${task.id}: ${task.title}")
                    appendLine("   📝 ${task.description}")
                    appendLine("   🎯 Priority: ${task.priority} | 📊 Status: ${task.status}")

                    if (task.assignee != null) {
                        appendLine("   👤 Assignee: ${task.assignee}")
                    }

                    if (!task.tags.isNullOrEmpty()) {
                        appendLine("   🏷️ Tags: ${task.tags}")
                    }

                    if (task.dueDate != null) {
                        val isOverdue = task.dueDate < System.currentTimeMillis() && task.status != "DONE"
                        val overdueFlag = if (isOverdue) " ⚠️ OVERDUE" else ""
                        appendLine("   📅 Due: ${task.dueDate}$overdueFlag")
                    }

                    appendLine()
                }
            }

            ToolResult.Success(
                data = resultData,
                metadata = mapOf(
                    "count" to tasks.size,
                    "filter" to filter.toString(),
                    "taskIds" to tasks.map { it.id }
                )
            )

        } catch (e: Exception) {
            logger.error("Failed to retrieve tasks", e)
            ToolResult.Error("Failed to retrieve tasks: ${e.message}")
        }
    }

    override fun validateParams(params: JsonObject): String? {
        // No required parameters for this tool
        return null
    }
}

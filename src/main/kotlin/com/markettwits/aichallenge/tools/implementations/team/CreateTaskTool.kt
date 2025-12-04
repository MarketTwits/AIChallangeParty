package com.markettwits.aichallenge.tools.implementations.team

import com.markettwits.aichallenge.team.CreateTaskRequest
import com.markettwits.aichallenge.team.TaskPriority
import com.markettwits.aichallenge.team.TaskRepository
import com.markettwits.aichallenge.tools.core.Tool
import com.markettwits.aichallenge.tools.core.ToolResult
import com.markettwits.aichallenge.tools.core.ToolSchema
import com.markettwits.aichallenge.tools.core.ToolType
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

/**
 * MCP Tool for creating new tasks
 */
class CreateTaskTool(private val taskRepository: TaskRepository) : Tool {

    private val logger = LoggerFactory.getLogger(CreateTaskTool::class.java)

    override val name = "create_task"
    override val description = "Create a new task in the project management system"
    override val type = ToolType.MCP

    override val schema = ToolSchema(
        properties = buildJsonObject {
            put("title", buildJsonObject {
                put("type", "string")
                put("description", "Task title (short summary)")
            })
            put("description", buildJsonObject {
                put("type", "string")
                put("description", "Detailed task description")
            })
            put("priority", buildJsonObject {
                put("type", "string")
                put("description", "Task priority level")
                put("enum", buildJsonArray {
                    add("LOW")
                    add("MEDIUM")
                    add("HIGH")
                    add("CRITICAL")
                })
            })
            put("assignee", buildJsonObject {
                put("type", "string")
                put("description", "Person assigned to the task (optional)")
            })
            put("tags", buildJsonObject {
                put("type", "string")
                put("description", "Comma-separated tags (e.g. 'backend,api,bug')")
            })
            put("dueDate", buildJsonObject {
                put("type", "integer")
                put("description", "Due date as Unix timestamp in milliseconds (optional)")
            })
        },
        required = listOf("title", "description")
    )

    override suspend fun execute(params: JsonObject): ToolResult {
        return try {
            val title = params["title"]?.jsonPrimitive?.content
                ?: return ToolResult.Error("Missing required parameter: title")

            val description = params["description"]?.jsonPrimitive?.content
                ?: return ToolResult.Error("Missing required parameter: description")

            val priority = params["priority"]?.jsonPrimitive?.content ?: TaskPriority.MEDIUM.name
            val assignee = params["assignee"]?.jsonPrimitive?.content
            val tags = params["tags"]?.jsonPrimitive?.content
            val dueDate = params["dueDate"]?.jsonPrimitive?.longOrNull

            // Validate priority
            if (!isValidPriority(priority)) {
                return ToolResult.Error("Invalid priority: $priority. Must be one of: LOW, MEDIUM, HIGH, CRITICAL")
            }

            val request = CreateTaskRequest(
                title = title,
                description = description,
                priority = priority,
                assignee = assignee,
                tags = tags,
                dueDate = dueDate
            )

            val task = taskRepository.createTask(request)

            logger.info("✅ Task created via tool: #${task.id} - ${task.title}")

            val resultData = buildString {
                appendLine("✅ Task created successfully")
                appendLine("📋 Task #${task.id}: ${task.title}")
                appendLine("📝 Description: ${task.description}")
                appendLine("🎯 Priority: ${task.priority}")
                appendLine("📊 Status: ${task.status}")
                if (task.assignee != null) {
                    appendLine("👤 Assignee: ${task.assignee}")
                }
                if (!task.tags.isNullOrEmpty()) {
                    appendLine("🏷️ Tags: ${task.tags}")
                }
                if (task.dueDate != null) {
                    appendLine("📅 Due Date: ${task.dueDate}")
                }
            }

            ToolResult.Success(
                data = resultData,
                metadata = mapOf(
                    "taskId" to task.id,
                    "title" to task.title,
                    "priority" to task.priority,
                    "status" to task.status
                )
            )

        } catch (e: Exception) {
            logger.error("Failed to create task", e)
            ToolResult.Error("Failed to create task: ${e.message}")
        }
    }

    private fun isValidPriority(priority: String): Boolean {
        return try {
            TaskPriority.valueOf(priority)
            true
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    override fun validateParams(params: JsonObject): String? {
        if (!params.contains("title")) return "Missing required parameter: title"
        if (!params.contains("description")) return "Missing required parameter: description"
        return null
    }
}

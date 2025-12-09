package com.markettwits.aichallenge.tools.implementations.team

import com.markettwits.aichallenge.team.TaskPriority
import com.markettwits.aichallenge.team.TaskRepository
import com.markettwits.aichallenge.team.TaskStatus
import com.markettwits.aichallenge.team.UpdateTaskRequest
import com.markettwits.aichallenge.tools.core.Tool
import com.markettwits.aichallenge.tools.core.ToolResult
import com.markettwits.aichallenge.tools.core.ToolSchema
import com.markettwits.aichallenge.tools.core.ToolType
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

/**
 * MCP Tool for updating existing tasks
 */
class UpdateTaskTool(private val taskRepository: TaskRepository) : Tool {

    private val logger = LoggerFactory.getLogger(UpdateTaskTool::class.java)

    override val name = "update_task"
    override val description = "Update an existing task's properties (status, priority, assignee, etc.)"
    override val type = ToolType.MCP

    override val schema = ToolSchema(
        properties = buildJsonObject {
            put("taskId", buildJsonObject {
                put("type", "integer")
                put("description", "ID of the task to update")
            })
            put("title", buildJsonObject {
                put("type", "string")
                put("description", "New task title")
            })
            put("description", buildJsonObject {
                put("type", "string")
                put("description", "New task description")
            })
            put("status", buildJsonObject {
                put("type", "string")
                put("enum", buildJsonArray {
                    add("TODO")
                    add("IN_PROGRESS")
                    add("DONE")
                    add("BLOCKED")
                })
                put("description", "New task status")
            })
            put("priority", buildJsonObject {
                put("type", "string")
                put("enum", buildJsonArray {
                    add("LOW")
                    add("MEDIUM")
                    add("HIGH")
                    add("CRITICAL")
                })
                put("description", "New priority level")
            })
            put("assignee", buildJsonObject {
                put("type", "string")
                put("description", "New assignee")
            })
            put("tags", buildJsonObject {
                put("type", "string")
                put("description", "New tags (comma-separated)")
            })
            put("dueDate", buildJsonObject {
                put("type", "integer")
                put("description", "New due date as Unix timestamp")
            })
        },
        required = listOf("taskId")
    )

    override suspend fun execute(params: JsonObject): ToolResult {
        return try {
            val taskId = params["taskId"]?.jsonPrimitive?.longOrNull
                ?: return ToolResult.Error("Missing or invalid parameter: taskId")

            // Check if task exists
            taskRepository.getTaskById(taskId)
                ?: return ToolResult.Error("Task not found with ID: $taskId")

            val title = params["title"]?.jsonPrimitive?.content
            val description = params["description"]?.jsonPrimitive?.content
            val status = params["status"]?.jsonPrimitive?.content
            val priority = params["priority"]?.jsonPrimitive?.content
            val assignee = params["assignee"]?.jsonPrimitive?.content
            val tags = params["tags"]?.jsonPrimitive?.content
            val dueDate = params["dueDate"]?.jsonPrimitive?.longOrNull

            // Validate status if provided
            if (status != null && !isValidStatus(status)) {
                return ToolResult.Error("Invalid status: $status")
            }

            // Validate priority if provided
            if (priority != null && !isValidPriority(priority)) {
                return ToolResult.Error("Invalid priority: $priority")
            }

            val updateRequest = UpdateTaskRequest(
                title = title,
                description = description,
                status = status,
                priority = priority,
                assignee = assignee,
                tags = tags,
                dueDate = dueDate
            )

            val updatedTask = taskRepository.updateTask(taskId, updateRequest)
                ?: return ToolResult.Error("Failed to update task")

            logger.info("✅ Task updated via tool: #${updatedTask.id} - ${updatedTask.title}")

            val resultData = buildString {
                appendLine("✅ Task updated successfully")
                appendLine("📋 Task #${updatedTask.id}: ${updatedTask.title}")
                appendLine("📝 Description: ${updatedTask.description}")
                appendLine("🎯 Priority: ${updatedTask.priority}")
                appendLine("📊 Status: ${updatedTask.status}")
                if (updatedTask.assignee != null) {
                    appendLine("👤 Assignee: ${updatedTask.assignee}")
                }
                if (!updatedTask.tags.isNullOrEmpty()) {
                    appendLine("🏷️ Tags: ${updatedTask.tags}")
                }
                if (updatedTask.dueDate != null) {
                    appendLine("📅 Due Date: ${updatedTask.dueDate}")
                }
                appendLine("🕒 Updated At: ${updatedTask.updatedAt}")

                val changes = mutableListOf<String>()
                if (title != null) changes.add("title")
                if (description != null) changes.add("description")
                if (status != null) changes.add("status")
                if (priority != null) changes.add("priority")
                if (assignee != null) changes.add("assignee")
                if (tags != null) changes.add("tags")
                if (dueDate != null) changes.add("dueDate")

                if (changes.isNotEmpty()) {
                    appendLine("🔄 Changed fields: ${changes.joinToString(", ")}")
                }
            }

            ToolResult.Success(
                data = resultData,
                metadata = mapOf(
                    "taskId" to updatedTask.id,
                    "title" to updatedTask.title,
                    "status" to updatedTask.status,
                    "priority" to updatedTask.priority,
                    "updatedAt" to updatedTask.updatedAt
                )
            )

        } catch (e: Exception) {
            logger.error("Failed to update task", e)
            ToolResult.Error("Failed to update task: ${e.message}")
        }
    }

    private fun isValidStatus(status: String): Boolean {
        return try {
            TaskStatus.valueOf(status)
            true
        } catch (e: IllegalArgumentException) {
            false
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
        if (!params.contains("taskId")) return "Missing required parameter: taskId"
        return null
    }
}

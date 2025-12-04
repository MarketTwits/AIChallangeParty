package com.markettwits.aichallenge.tools.implementations.team

import com.markettwits.aichallenge.team.TaskRepository
import com.markettwits.aichallenge.tools.core.Tool
import com.markettwits.aichallenge.tools.core.ToolResult
import com.markettwits.aichallenge.tools.core.ToolSchema
import com.markettwits.aichallenge.tools.core.ToolType
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

/**
 * MCP Tool for deleting tasks
 */
class DeleteTaskTool(private val taskRepository: TaskRepository) : Tool {

    private val logger = LoggerFactory.getLogger(DeleteTaskTool::class.java)

    override val name = "delete_task"
    override val description = "Delete a task from the project management system"
    override val type = ToolType.MCP

    override val schema = ToolSchema(
        properties = buildJsonObject {
            put("taskId", buildJsonObject {
                put("type", "integer")
                put("description", "ID of the task to delete")
            })
        },
        required = listOf("taskId")
    )

    override suspend fun execute(params: JsonObject): ToolResult {
        return try {
            val taskId = params["taskId"]?.jsonPrimitive?.longOrNull
                ?: return ToolResult.Error("Missing or invalid parameter: taskId")

            // Check if task exists first
            val task = taskRepository.getTaskById(taskId)
                ?: return ToolResult.Error("Task not found with ID: $taskId")

            val deleted = taskRepository.deleteTask(taskId)

            if (deleted) {
                logger.info("🗑️ Task deleted via tool: #$taskId - ${task.title}")

                val resultData = buildString {
                    appendLine("✅ Task deleted successfully")
                    appendLine("📋 Deleted Task #${task.id}: ${task.title}")
                    appendLine("📝 Description: ${task.description}")
                    appendLine("🎯 Priority: ${task.priority}")
                    appendLine("📊 Status: ${task.status}")
                    if (task.assignee != null) {
                        appendLine("👤 Assignee: ${task.assignee}")
                    }
                    if (!task.tags.isNullOrEmpty()) {
                        appendLine("🏷️ Tags: ${task.tags}")
                    }
                }

                ToolResult.Success(
                    data = resultData,
                    metadata = mapOf(
                        "deletedTaskId" to task.id,
                        "deletedTaskTitle" to task.title
                    )
                )
            } else {
                ToolResult.Error("Failed to delete task")
            }

        } catch (e: Exception) {
            logger.error("Failed to delete task", e)
            ToolResult.Error("Failed to delete task: ${e.message}")
        }
    }

    override fun validateParams(params: JsonObject): String? {
        if (!params.contains("taskId")) return "Missing required parameter: taskId"
        return null
    }
}

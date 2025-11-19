package com.markettwits.aichallenge

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

class ReminderMcpServer(
    private val reminderRepository: ReminderRepository,
    private val httpClient: HttpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json()
        }
    },
) {
    private val logger = LoggerFactory.getLogger(ReminderMcpServer::class.java)

    // MCP Tools available for reminders
    fun getAvailableTools(): List<Tool> {
        return listOf(
            Tool(
                name = "create_reminder",
                description = "Create a new reminder task",
                input_schema = InputSchema(
                    type = "object",
                    properties = buildJsonObject {
                        put("title", buildJsonObject {
                            put("type", "string")
                            put("description", "Title of the reminder task")
                        })
                        put("description", buildJsonObject {
                            put("type", "string")
                            put("description", "Detailed description of the task")
                        })
                        put("priority", buildJsonObject {
                            put("type", "string")
                            put("enum", buildJsonArray { add("low"); add("medium"); add("high") })
                            put("description", "Priority level of the task")
                            put("default", "medium")
                        })
                        put("dueDate", buildJsonObject {
                            put("type", "string")
                            put("description", "Due date in ISO format (e.g., 2024-01-15T14:30:00)")
                        })
                        put("reminderTime", buildJsonObject {
                            put("type", "string")
                            put("description", "When to remind about this task in ISO format")
                        })
                        put("recurringType", buildJsonObject {
                            put("type", "string")
                            put("enum", buildJsonArray { add("daily"); add("weekly"); add("monthly") })
                            put("description", "Type of recurring reminder")
                        })
                    },
                    required = listOf("title", "description")
                )
            ),
            Tool(
                name = "list_reminders",
                description = "List all reminders with optional filtering",
                input_schema = InputSchema(
                    type = "object",
                    properties = buildJsonObject {
                        put("status", buildJsonObject {
                            put("type", "string")
                            put("enum", buildJsonArray { add("pending"); add("completed"); add("all") })
                            put("description", "Filter by task status")
                            put("default", "all")
                        })
                        put("priority", buildJsonObject {
                            put("type", "string")
                            put("enum", buildJsonArray { add("low"); add("medium"); add("high") })
                            put("description", "Filter by priority")
                        })
                        put("limit", buildJsonObject {
                            put("type", "integer")
                            put("description", "Maximum number of reminders to return")
                            put("default", 10)
                        })
                    },
                    required = listOf()
                )
            ),
            Tool(
                name = "complete_reminder",
                description = "Mark a reminder as completed",
                input_schema = InputSchema(
                    type = "object",
                    properties = buildJsonObject {
                        put("taskId", buildJsonObject {
                            put("type", "string")
                            put("description", "ID of the reminder to complete")
                        })
                    },
                    required = listOf("taskId")
                )
            ),
            Tool(
                name = "delete_reminder",
                description = "Delete a reminder",
                input_schema = InputSchema(
                    type = "object",
                    properties = buildJsonObject {
                        put("taskId", buildJsonObject {
                            put("type", "string")
                            put("description", "ID of the reminder to delete")
                        })
                    },
                    required = listOf("taskId")
                )
            ),
            Tool(
                name = "get_today_reminders",
                description = "Get all reminders for today",
                input_schema = InputSchema(
                    type = "object",
                    properties = emptyJsonObject,
                    required = listOf()
                )
            ),
            Tool(
                name = "get_reminder_summary",
                description = "Get summary of reminders (total, completed, pending, overdue)",
                input_schema = InputSchema(
                    type = "object",
                    properties = emptyJsonObject,
                    required = listOf()
                )
            ),
            Tool(
                name = "update_reminder",
                description = "Update an existing reminder",
                input_schema = InputSchema(
                    type = "object",
                    properties = buildJsonObject {
                        put("taskId", buildJsonObject {
                            put("type", "string")
                            put("description", "ID of the reminder to update")
                        })
                        put("title", buildJsonObject {
                            put("type", "string")
                            put("description", "New title for the reminder")
                        })
                        put("description", buildJsonObject {
                            put("type", "string")
                            put("description", "New description for the reminder")
                        })
                        put("priority", buildJsonObject {
                            put("type", "string")
                            put("enum", buildJsonArray { add("low"); add("medium"); add("high") })
                            put("description", "New priority level")
                        })
                        put("dueDate", buildJsonObject {
                            put("type", "string")
                            put("description", "New due date in ISO format")
                        })
                        put("reminderTime", buildJsonObject {
                            put("type", "string")
                            put("description", "New reminder time in ISO format")
                        })
                        put("status", buildJsonObject {
                            put("type", "string")
                            put("enum", buildJsonArray { add("pending"); add("completed") })
                            put("description", "New status")
                        })
                    },
                    required = listOf("taskId")
                )
            )
        )
    }

    // Execute MCP tool
    suspend fun executeTool(toolName: String, parameters: JsonObject): String {
        return try {
            when (toolName) {
                "create_reminder" -> createReminder(parameters)
                "list_reminders" -> listReminders(parameters)
                "complete_reminder" -> completeReminder(parameters)
                "delete_reminder" -> deleteReminder(parameters)
                "get_today_reminders" -> getTodayReminders(parameters)
                "get_reminder_summary" -> getReminderSummary(parameters)
                "update_reminder" -> updateReminder(parameters)
                else -> "Error: Unknown tool '$toolName'"
            }
        } catch (e: Exception) {
            logger.error("Error executing tool $toolName", e)
            "Error executing tool '$toolName': ${e.message}"
        }
    }

    private fun createReminder(parameters: JsonObject): String {
        val title = parameters["title"]?.jsonPrimitive?.content
            ?: return "Error: 'title' is required"
        val description = parameters["description"]?.jsonPrimitive?.content
            ?: return "Error: 'description' is required"
        val priority = parameters["priority"]?.jsonPrimitive?.content ?: "medium"
        val dueDate = parameters["dueDate"]?.jsonPrimitive?.content
        val reminderTime = parameters["reminderTime"]?.jsonPrimitive?.content
        val recurringType = parameters["recurringType"]?.jsonPrimitive?.content

        val request = ReminderCreateRequest(
            title = title,
            description = description,
            priority = priority,
            dueDate = dueDate,
            reminderTime = reminderTime,
            recurringType = recurringType
        )

        val task = reminderRepository.createReminder(request)
        return "✅ Reminder created successfully!\n\n" +
                "**Title:** ${task.title}\n" +
                "**Description:** ${task.description}\n" +
                "**Priority:** ${task.priority}\n" +
                "**Due Date:** ${task.dueDate ?: "Not set"}\n" +
                "**Reminder Time:** ${task.reminderTime ?: "Not set"}\n" +
                "**ID:** ${task.id}"
    }

    private fun listReminders(parameters: JsonObject): String {
        val status = parameters["status"]?.jsonPrimitive?.content ?: "all"
        val priority = parameters["priority"]?.jsonPrimitive?.content
        val limit = parameters["limit"]?.jsonPrimitive?.content?.toIntOrNull() ?: 10

        val reminders = when (status) {
            "pending" -> reminderRepository.getPendingReminders()
            "completed" -> reminderRepository.getRemindersByStatus("completed")
            else -> reminderRepository.getAllReminders()
        }

        val filteredReminders = if (priority != null) {
            reminders.filter { it.priority == priority }
        } else {
            reminders
        }.take(limit)

        if (filteredReminders.isEmpty()) {
            return "No reminders found for the specified criteria."
        }

        val result = StringBuilder("📋 **Reminders (${filteredReminders.size} found):**\n\n")
        filteredReminders.forEach { reminder ->
            val statusIcon = when (reminder.status) {
                "completed" -> "✅"
                "pending" -> "⏳"
                else -> "📌"
            }
            val priorityIcon = when (reminder.priority) {
                "high" -> "🔴"
                "medium" -> "🟡"
                "low" -> "🟢"
                else -> "⚪"
            }

            result.append("$statusIcon **${reminder.title}** $priorityIcon\n")
            result.append("   ${reminder.description}\n")
            result.append("   Due: ${reminder.dueDate ?: "No due date"} | ID: ${reminder.id}\n\n")
        }

        return result.toString()
    }

    private fun completeReminder(parameters: JsonObject): String {
        val taskId = parameters["taskId"]?.jsonPrimitive?.content
            ?: return "Error: 'taskId' is required"

        val updatedTask = reminderRepository.updateReminder(taskId, ReminderUpdateRequest(status = "completed"))
        return if (updatedTask != null) {
            "✅ Reminder marked as completed: **${updatedTask.title}**"
        } else {
            "❌ Error: Reminder with ID '$taskId' not found"
        }
    }

    private fun deleteReminder(parameters: JsonObject): String {
        val taskId = parameters["taskId"]?.jsonPrimitive?.content
            ?: return "Error: 'taskId' is required"

        val deleted = reminderRepository.deleteReminder(taskId)
        return if (deleted) {
            "🗑️ Reminder deleted successfully"
        } else {
            "❌ Error: Reminder with ID '$taskId' not found"
        }
    }

    private fun getTodayReminders(parameters: JsonObject): String {
        val todayReminders = reminderRepository.getTodayReminders()

        if (todayReminders.isEmpty()) {
            return "📅 No reminders for today."
        }

        val result = StringBuilder("📅 **Today's Reminders (${todayReminders.size}):**\n\n")
        todayReminders.forEach { reminder ->
            val priorityIcon = when (reminder.priority) {
                "high" -> "🔴"
                "medium" -> "🟡"
                "low" -> "🟢"
                else -> "⚪"
            }

            result.append("$priorityIcon **${reminder.title}**\n")
            result.append("   ${reminder.description}\n")
            result.append("   Time: ${reminder.reminderTime ?: "All day"} | ID: ${reminder.id}\n\n")
        }

        return result.toString()
    }

    private fun getReminderSummary(parameters: JsonObject): String {
        val summary = reminderRepository.getReminderSummary()

        val result = StringBuilder("📊 **Reminder Summary for ${summary.date}**\n\n")
        result.append("📈 **Statistics:**\n")
        result.append("• Total Tasks: ${summary.totalTasks}\n")
        result.append("• ✅ Completed: ${summary.completedTasks}\n")
        result.append("• ⏳ Pending: ${summary.pendingTasks}\n")
        result.append("• ⚠️ Overdue: ${summary.overdueTasks}\n")

        if (summary.todayReminders.isNotEmpty()) {
            result.append("\n📅 **Today's Reminders (${summary.todayReminders.size}):**\n")
            summary.todayReminders.forEach { reminder ->
                val priorityIcon = when (reminder.priority) {
                    "high" -> "🔴"
                    "medium" -> "🟡"
                    "low" -> "🟢"
                    else -> "⚪"
                }
                result.append("  $priorityIcon ${reminder.title} - ${reminder.reminderTime ?: "All day"}\n")
            }
        }

        return result.toString()
    }

    private fun updateReminder(parameters: JsonObject): String {
        val taskId = parameters["taskId"]?.jsonPrimitive?.content
            ?: return "Error: 'taskId' is required"

        val updateRequest = ReminderUpdateRequest(
            title = parameters["title"]?.jsonPrimitive?.content,
            description = parameters["description"]?.jsonPrimitive?.content,
            priority = parameters["priority"]?.jsonPrimitive?.content,
            dueDate = parameters["dueDate"]?.jsonPrimitive?.content,
            reminderTime = parameters["reminderTime"]?.jsonPrimitive?.content,
            status = parameters["status"]?.jsonPrimitive?.content
        )

        val updatedTask = reminderRepository.updateReminder(taskId, updateRequest)
        return if (updatedTask != null) {
            "✅ Reminder updated successfully!\n\n" +
                    "**Title:** ${updatedTask.title}\n" +
                    "**Description:** ${updatedTask.description}\n" +
                    "**Priority:** ${updatedTask.priority}\n" +
                    "**Status:** ${updatedTask.status}\n" +
                    "**Due Date:** ${updatedTask.dueDate ?: "Not set"}"
        } else {
            "❌ Error: Reminder with ID '$taskId' not found"
        }
    }

    private val emptyJsonObject = buildJsonObject { }
}
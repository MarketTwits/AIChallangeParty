package com.markettwits.aichallenge.mcp

import com.markettwits.aichallenge.ReminderMcpServer
import com.markettwits.aichallenge.ReminderRepository
import kotlinx.serialization.json.*

/**
 * Real MCP Server for Reminders/Tasks management
 * Implements Model Context Protocol for task and reminder operations
 */
class RemindersRealMcpServer(
    private val reminderRepository: ReminderRepository,
) : RealMcpServer(
    serverInfo = Implementation(
        name = "reminders-mcp-server",
        version = "1.0.0"
    )
) {
    private val reminderService = ReminderMcpServer(reminderRepository)

    override fun getCapabilities(): ServerCapabilities {
        return ServerCapabilities(
            tools = ToolsCapability(listChanged = false)
        )
    }

    override suspend fun listTools(): List<McpToolDefinition> {
        return listOf(
            McpToolDefinition(
                name = "create_reminder",
                description = "Create a new reminder or task",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("title", buildJsonObject {
                            put("type", "string")
                            put("description", "Title of the reminder")
                        })
                        put("description", buildJsonObject {
                            put("type", "string")
                            put("description", "Detailed description of the task")
                        })
                        put("priority", buildJsonObject {
                            put("type", "string")
                            put("enum", buildJsonArray { add("low"); add("medium"); add("high") })
                            put("description", "Priority level")
                            put("default", "medium")
                        })
                        put("dueDate", buildJsonObject {
                            put("type", "string")
                            put("description", "Due date in ISO format")
                        })
                    })
                    put("required", buildJsonArray { add("title"); add("description") })
                }
            ),
            McpToolDefinition(
                name = "list_reminders",
                description = "List all reminders with optional filtering",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("status", buildJsonObject {
                            put("type", "string")
                            put("enum", buildJsonArray { add("pending"); add("completed"); add("all") })
                            put("default", "all")
                        })
                        put("priority", buildJsonObject {
                            put("type", "string")
                            put("enum", buildJsonArray { add("low"); add("medium"); add("high") })
                        })
                        put("limit", buildJsonObject {
                            put("type", "integer")
                            put("default", 10)
                        })
                    })
                    put("required", buildJsonArray {})
                }
            ),
            McpToolDefinition(
                name = "complete_reminder",
                description = "Mark a reminder as completed",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("taskId", buildJsonObject {
                            put("type", "string")
                            put("description", "ID of the reminder to complete")
                        })
                    })
                    put("required", buildJsonArray { add("taskId") })
                }
            ),
            McpToolDefinition(
                name = "get_reminder_summary",
                description = "Get summary of all reminders (total, completed, pending, overdue)",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {})
                    put("required", buildJsonArray {})
                }
            ),
            McpToolDefinition(
                name = "update_reminder",
                description = "Update an existing reminder",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("taskId", buildJsonObject {
                            put("type", "string")
                            put("description", "ID of the reminder to update")
                        })
                        put("title", buildJsonObject {
                            put("type", "string")
                        })
                        put("description", buildJsonObject {
                            put("type", "string")
                        })
                        put("priority", buildJsonObject {
                            put("type", "string")
                            put("enum", buildJsonArray { add("low"); add("medium"); add("high") })
                        })
                    })
                    put("required", buildJsonArray { add("taskId") })
                }
            ),
            McpToolDefinition(
                name = "delete_reminder",
                description = "Delete a reminder",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("taskId", buildJsonObject {
                            put("type", "string")
                            put("description", "ID of the reminder to delete")
                        })
                    })
                    put("required", buildJsonArray { add("taskId") })
                }
            )
        )
    }

    override suspend fun callTool(name: String, arguments: JsonObject?): CallToolResult {
        return try {
            val args = arguments ?: buildJsonObject {}
            val result = reminderService.executeTool(name, args)
            createToolResult(result)
        } catch (e: Exception) {
            logger.error("Error executing reminder tool: $name", e)
            createToolError("Failed to execute tool $name: ${e.message}")
        }
    }
}

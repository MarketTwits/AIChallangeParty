package com.markettwits.aichallenge.mcp

import com.markettwits.aichallenge.AnthropicClient
import com.markettwits.aichallenge.ReminderRepository
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/**
 * Configure routes for MCP Orchestration (Day 14)
 */
fun Application.configureOrchestrationRoutes(
    anthropicClient: AnthropicClient,
    reminderRepository: ReminderRepository,
) {
    val logger = LoggerFactory.getLogger("OrchestrationRoutes")

    // Create MCP Orchestrator and register servers
    val orchestrator = McpOrchestrator()

    // Register GitHub MCP Server
    orchestrator.registerServer("github", GitHubRealMcpServer())
    logger.info("Registered GitHub MCP Server")

    // Register Reminders MCP Server
    orchestrator.registerServer("reminders", RemindersRealMcpServer(reminderRepository))
    logger.info("Registered Reminders MCP Server")

    // Register Files MCP Server
    orchestrator.registerServer("files", FilesRealMcpServer())
    logger.info("Registered Files MCP Server")

    // Create Orchestration Agent
    val orchestrationAgent = OrchestrationAgent(anthropicClient, orchestrator)
    logger.info("Orchestration Agent created with ${orchestrator.getServerStatus().size} MCP servers")

    routing {
        route("/api/orchestration") {

            // Execute orchestration query
            post("/execute") {
                try {
                    val request = call.receive<OrchestrationRequest>()
                    logger.info("Orchestration request: ${request.query}")

                    val result = orchestrationAgent.execute(
                        userQuery = request.query,
                        sessionId = request.sessionId ?: "default"
                    )

                    call.respond(result)
                } catch (e: Exception) {
                    logger.error("Error executing orchestration", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        OrchestrationErrorResponse(
                            success = false,
                            error = e.message ?: "Unknown error"
                        )
                    )
                }
            }

            // Get server status
            get("/servers") {
                val status = orchestrator.getServerStatus()
                call.respond(
                    ServersListResponse(
                        servers = status.values.toList(),
                        totalServers = status.size
                    )
                )
            }

            // Get all available tools
            get("/tools") {
                try {
                    val tools = orchestrator.getAllTools()
                    call.respond(
                        ToolsListResponse(
                            tools = tools.map { toolWithServer ->
                                ToolInfo(
                                    name = toolWithServer.tool.name,
                                    description = toolWithServer.tool.description ?: "",
                                    serverId = toolWithServer.serverId,
                                    serverName = toolWithServer.serverName
                                )
                            },
                            totalTools = tools.size,
                            serverCount = orchestrator.getServerStatus().size
                        )
                    )
                } catch (e: Exception) {
                    logger.error("Error getting tools", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        OrchestrationErrorResponse(
                            success = false,
                            error = e.message ?: "Unknown error"
                        )
                    )
                }
            }

            // Get tool mapping
            get("/tool-mapping") {
                val mapping = orchestrator.getToolMapping()
                call.respond(mapping)
            }

            // Health check
            get("/health") {
                val serverStatus = orchestrator.getServerStatus()
                val healthyServers = serverStatus.count { it.value.state == McpServerState.READY }

                call.respond(
                    buildJsonObject {
                        put("status", if (healthyServers == serverStatus.size) "healthy" else "degraded")
                        put("totalServers", serverStatus.size)
                        put("healthyServers", healthyServers)
                        put("timestamp", System.currentTimeMillis())
                    }
                )
            }
        }
    }
}

// ============== Request/Response Models ==============

@Serializable
data class OrchestrationRequest(
    val query: String,
    val sessionId: String? = null,
)

@Serializable
data class OrchestrationErrorResponse(
    val success: Boolean,
    val error: String,
)

@Serializable
data class ToolsListResponse(
    val tools: List<ToolInfo>,
    val totalTools: Int,
    val serverCount: Int,
)

@Serializable
data class ToolInfo(
    val name: String,
    val description: String,
    val serverId: String,
    val serverName: String,
)

@Serializable
data class ServersListResponse(
    val servers: List<ServerStatus>,
    val totalServers: Int,
)

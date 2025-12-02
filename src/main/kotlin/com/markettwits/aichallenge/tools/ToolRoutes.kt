package com.markettwits.aichallenge.tools

import com.markettwits.aichallenge.tools.core.ToolResult
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

@Serializable
data class ToolExecuteRequest(
    val toolName: String,
    val params: JsonObject = buildJsonObject {},
)

@Serializable
data class HelpRequest(
    val question: String,
    val includeGitStatus: Boolean = false,
)

@Serializable
data class ToolSystemStatus(
    val toolSystemActive: Boolean,
    val totalTools: Int,
    val ragReady: Boolean,
    val toolsByType: Map<String, List<String>>,
)

/**
 * Configure routes for the new tool system
 */
fun Application.configureToolRoutes(toolManager: ToolManager) {
    val logger = LoggerFactory.getLogger("ToolRoutes")

    routing {
        route("/tools") {
            // Get all available tools
            get {
                try {
                    val summary = toolManager.getToolsSummary()
                    call.respond(HttpStatusCode.OK, summary)
                } catch (e: Exception) {
                    logger.error("Error getting tools summary", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to e.message)
                    )
                }
            }

            // Get system status
            get("/status") {
                try {
                    val statusMap = toolManager.getSystemStatus()
                    val status = ToolSystemStatus(
                        toolSystemActive = statusMap["toolSystemActive"] as Boolean,
                        totalTools = statusMap["totalTools"] as Int,
                        ragReady = statusMap["ragReady"] as Boolean,
                        toolsByType = statusMap["toolsByType"] as Map<String, List<String>>
                    )
                    call.respond(HttpStatusCode.OK, status)
                } catch (e: Exception) {
                    logger.error("Error getting system status", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to e.message)
                    )
                }
            }

            // Execute a tool directly
            post("/execute") {
                try {
                    val request = call.receive<ToolExecuteRequest>()
                    logger.info("Direct tool execution request: ${request.toolName}")

                    val result = toolManager.executeTool(request.toolName, request.params)

                    val response = when (result) {
                        is ToolResult.Success -> mapOf(
                            "success" to true,
                            "tool" to request.toolName,
                            "result" to result.data,
                            "metadata" to result.metadata
                        )

                        is ToolResult.Error -> mapOf(
                            "success" to false,
                            "tool" to request.toolName,
                            "error" to result.message,
                            "code" to result.code,
                            "details" to result.details
                        )
                    }

                    val statusCode = if (result.isSuccess()) HttpStatusCode.OK else HttpStatusCode.BadRequest
                    call.respond(statusCode, response)
                } catch (e: Exception) {
                    logger.error("Error executing tool", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf(
                            "success" to false,
                            "error" to e.message
                        )
                    )
                }
            }
        }

        // /help command endpoint
        route("/help") {
            post {
                try {
                    val request = call.receive<HelpRequest>()
                    logger.info("Help request: ${request.question}")

                    val params = buildJsonObject {
                        put("question", request.question)
                        put("include_git_status", request.includeGitStatus)
                    }

                    val response = when (val result = toolManager.executeTool("project_help", params)) {
                        is ToolResult.Success -> mapOf(
                            "success" to true,
                            "answer" to result.data,
                            "metadata" to result.metadata,
                            "helpSystemReady" to toolManager.isHelpSystemReady()
                        )

                        is ToolResult.Error -> mapOf(
                            "success" to false,
                            "error" to result.message,
                            "code" to result.code
                        )
                    }

                    call.respond(HttpStatusCode.OK, response)
                } catch (e: Exception) {
                    logger.error("Error processing help request", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf(
                            "success" to false,
                            "error" to e.message
                        )
                    )
                }
            }

            // Quick help endpoint (GET)
            get {
                try {
                    val question = call.request.queryParameters["q"] ?: "How do I use this project?"

                    val params = buildJsonObject {
                        put("question", question)
                        put("include_git_status", false)
                    }

                    when (val result = toolManager.executeTool("project_help", params)) {
                        is ToolResult.Success -> {
                            call.respondText(
                                result.data,
                                contentType = ContentType.Text.Plain,
                                status = HttpStatusCode.OK
                            )
                        }

                        is ToolResult.Error -> {
                            call.respondText(
                                "Error: ${result.message}",
                                contentType = ContentType.Text.Plain,
                                status = HttpStatusCode.InternalServerError
                            )
                        }
                    }
                } catch (e: Exception) {
                    logger.error("Error processing GET help request", e)
                    call.respondText(
                        "Error: ${e.message}",
                        contentType = ContentType.Text.Plain,
                        status = HttpStatusCode.InternalServerError
                    )
                }
            }
        }

        // Project documentation search endpoint
        route("/docs") {
            post("/search") {
                try {
                    @Serializable
                    data class SearchRequest(val query: String, val topK: Int = 5)

                    val request = call.receive<SearchRequest>()
                    logger.info("Documentation search: ${request.query}")

                    val params = buildJsonObject {
                        put("query", request.query)
                        put("topK", request.topK)
                    }

                    val response = when (val result = toolManager.executeTool("query_project_docs", params)) {
                        is ToolResult.Success -> mapOf(
                            "success" to true,
                            "query" to request.query,
                            "result" to result.data,
                            "metadata" to result.metadata
                        )

                        is ToolResult.Error -> mapOf(
                            "success" to false,
                            "error" to result.message,
                            "code" to result.code
                        )
                    }

                    call.respond(HttpStatusCode.OK, response)
                } catch (e: Exception) {
                    logger.error("Error searching documentation", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf(
                            "success" to false,
                            "error" to e.message
                        )
                    )
                }
            }
        }

        // Git status endpoint
        route("/git") {
            get("/status") {
                try {
                    val params = buildJsonObject {
                        put("include_remote", true)
                    }

                    when (val result = toolManager.executeTool("get_git_branch", params)) {
                        is ToolResult.Success -> {
                            call.respondText(
                                result.data,
                                contentType = ContentType.Text.Plain,
                                status = HttpStatusCode.OK
                            )
                        }

                        is ToolResult.Error -> {
                            call.respond(
                                HttpStatusCode.InternalServerError,
                                mapOf("error" to result.message, "code" to result.code)
                            )
                        }
                    }
                } catch (e: Exception) {
                    logger.error("Error getting git status", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to e.message)
                    )
                }
            }
        }
    }
}

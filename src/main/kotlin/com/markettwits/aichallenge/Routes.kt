package com.markettwits.aichallenge

import com.markettwits.aichallenge.DemoMcpIntegration.*
import com.markettwits.aichallenge.rag.*
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import org.jetbrains.exposed.sql.Database
import org.slf4j.LoggerFactory
import java.io.File
import java.time.format.DateTimeFormatter

// RAG Query Request models
@Serializable
data class RAGQueryRequest(val question: String, val topK: Int = 5)

@Serializable
data class RAGCompareRequest(val question: String, val topK: Int = 5)

fun Application.configureRouting(
    sessionManager: SessionManager,
    apiKey: String,
    huggingFaceKey: String,
    repository: ConversationRepository,
    reminderRepository: ReminderRepository,
    reminderScheduler: ReminderScheduler,
    mcpIntegrationService: DemoMcpIntegration,
    anthropicClient: AnthropicClient,
) {
    val logger = LoggerFactory.getLogger("Routes")
    val reasoningAgents = mutableMapOf<String, ReasoningAgent>()
    val mcpAgents = mutableMapOf<String, McpAgent>()
    val reminderMcpServer = ReminderMcpServer(reminderRepository)
    // val llmSummarizer = AdvancedLLMSummarizer(anthropicClient, reminderRepository, repository)
    // val realMcpDemo = RealMcpDemo(reminderRepository, anthropicClient)

    val hfKeyMasked = if (huggingFaceKey.length > 8) {
        "${huggingFaceKey.substring(0, 8)}...${huggingFaceKey.substring(huggingFaceKey.length - 4)}"
    } else {
        "NOT_SET"
    }
    logger.info("HuggingFace API Key in Routes: $hfKeyMasked (length: ${huggingFaceKey.length})")

    if (huggingFaceKey.isEmpty()) {
        logger.error("HUGGINGFACE_API_KEY is empty!")
    }

    val huggingFaceClient = HuggingFaceClient(huggingFaceKey)

    // Initialize RAG system (Day 15)
    val database = Database.connect("jdbc:sqlite:data/documents.db", driver = "org.sqlite.JDBC")
    val embeddingClient = OllamaEmbeddingClient()
    val ragRetriever = RAGRetriever(database, embeddingClient)

    // Initialize RAG Query system (Day 16)
    val llmClient = OllamaLLMClient(model = "llama3.2")
    val vectorStore = VectorStore()
    val ragQueryService = RAGQueryService(database, embeddingClient, llmClient, vectorStore)
    val ragComparisonService = RAGComparisonService(ragQueryService)

    // Flag to track if knowledge base is built
    var isKnowledgeBaseBuilt = false

    routing {
        post("/chat") {
            try {
                val request = call.receive<ChatRequest>()
                logger.info("Received chat request from session ${request.sessionId}: ${request.message}")

                val agent = sessionManager.getOrCreateSession(request.sessionId) {
                    AnthropicClient(apiKey)
                }

                val (response, structuredResponse, usage) = agent.chat(
                    request.message,
                    request.coachStyle ?: "default",
                    request.maxContextTokens,
                    request.sessionId
                )
                val remainingMessages = agent.getRemainingMessages()

                call.respond(ChatResponse(
                    response = response,
                    remainingMessages = remainingMessages,
                    structuredResponse = structuredResponse,
                    inputTokens = usage?.input_tokens,
                    outputTokens = usage?.output_tokens,
                    totalInputTokens = agent.getTotalInputTokens(),
                    contextLimit = agent.getContextLimit()
                ))
            } catch (e: Exception) {
                logger.error("Error processing chat request", e)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ChatResponse(response = "Error: ${e.message}")
                )
            }
        }

        post("/api/anthropic/messages") {
            try {
                val requestBody = call.receiveText()
                logger.info("Proxying request to Anthropic API")

                val proxyClient = HttpClient(CIO) {
                    install(ContentNegotiation) {
                        json(Json {
                            ignoreUnknownKeys = true
                            isLenient = true
                        })
                    }
                }

                val response: HttpResponse = proxyClient.post("https://api.anthropic.com/v1/messages") {
                    header("x-api-key", System.getenv("ANTHROPIC_API_KEY") ?: "")
                    header("anthropic-version", "2023-06-01")
                    contentType(ContentType.Application.Json)
                    setBody(requestBody)
                }

                val responseBody = response.bodyAsText()
                logger.debug("Received response from Anthropic API")

                call.respondText(
                    text = responseBody,
                    contentType = ContentType.Application.Json,
                    status = response.status
                )

                proxyClient.close()
            } catch (e: Exception) {
                logger.error("Error proxying to Anthropic API", e)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to e.message)
                )
            }
        }

        post("/reasoning-chat") {
            try {
                val request = call.receive<ReasoningChatRequest>()
                logger.info("Received reasoning chat request from session ${request.sessionId}: ${request.message}, mode: ${request.reasoningMode}")

                val agent = reasoningAgents.getOrPut(request.sessionId) {
                    ReasoningAgent(AnthropicClient(apiKey))
                }

                val response =
                    agent.chat(
                        request.message,
                        request.sessionId,
                        request.reasoningMode,
                        request.temperature,
                        request.maxContextTokens,
                        request.compressionThreshold
                    )

                call.respond(response)
            } catch (e: Exception) {
                logger.error("Error processing reasoning chat request", e)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to e.message)
                )
            }
        }

        get("/chat/messages/{sessionId}") {
            try {
                val sessionId = call.parameters["sessionId"] ?: ""
                val messages = repository.loadMessages(sessionId)

                // Convert messages to simple format for JSON serialization
                val simpleMessages = messages.map { msg ->
                    SimpleMessage(
                        role = msg.role,
                        content = msg.content.map { block ->
                            SimpleContentBlock(
                                type = block.type,
                                text = block.text,
                                id = block.id,
                                name = block.name
                            )
                        }
                    )
                }

                val response = ChatMessagesResponse(
                    sessionId = sessionId,
                    messages = simpleMessages,
                    messageCount = messages.size
                )

                call.respond(HttpStatusCode.OK, response)
            } catch (e: Exception) {
                logger.error("Error getting chat messages", e)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to e.message)
                )
            }
        }

        get("/chat/history/{sessionId}") {
            try {
                val sessionId = call.parameters["sessionId"] ?: ""
                val agent = sessionManager.getOrCreateSession(sessionId) {
                    AnthropicClient(apiKey)
                }

                call.respond(
                    HttpStatusCode.OK, mapOf(
                        "sessionId" to sessionId,
                        "messageCount" to agent.getMessageCount(),
                        "remainingMessages" to agent.getRemainingMessages()
                    )
                )
            } catch (e: Exception) {
                logger.error("Error getting chat history", e)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to e.message)
                )
            }
        }

        post("/chat/clear") {
            try {
                val sessionId = call.receive<Map<String, String>>()["sessionId"] ?: ""
                val agent = sessionManager.getSession(sessionId)
                agent?.clearHistory(sessionId)
                sessionManager.clearSession(sessionId)
                call.respond(HttpStatusCode.OK, mapOf("status" to "cleared"))
            } catch (e: Exception) {
                logger.error("Error clearing chat session", e)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to e.message)
                )
            }
        }

        post("/reasoning-chat/clear") {
            try {
                val sessionId = call.receive<Map<String, String>>()["sessionId"] ?: ""
                reasoningAgents[sessionId]?.clearHistory(sessionId)
                reasoningAgents.remove(sessionId)
                call.respond(HttpStatusCode.OK, mapOf("status" to "cleared"))
            } catch (e: Exception) {
                logger.error("Error clearing reasoning chat session", e)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to e.message)
                )
            }
        }

        post("/model-comparison") {
            try {
                val request = call.receive<ComparisonRequest>()
                logger.info("Received model comparison request: ${request.query}")

                val response = huggingFaceClient.compareModels(request.query)

                call.respond(response)
            } catch (e: Exception) {
                logger.error("Error processing model comparison request", e)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to e.message)
                )
            }
        }

        get("/health") {
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        }

        // GitHub Tools endpoint
        get("/github/tools") {
            try {
                val gitHubServer = GitHubMcpServer()
                val tools = gitHubServer.getAvailableTools()

                val response = GitHubToolsResponse(
                    tools = tools.map { tool ->
                        GitHubToolResponse(
                            name = tool.name,
                            description = tool.description,
                            inputSchema = tool.inputSchema
                        )
                    },
                    count = tools.size,
                    status = "connected"
                )

                call.respond(HttpStatusCode.OK, response)
                gitHubServer.close()
            } catch (e: Exception) {
                logger.error("Error getting GitHub tools", e)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    GitHubToolsResponse(
                        tools = emptyList(),
                        count = 0,
                        status = "error",
                        error = e.message
                    )
                )
            }
        }

        // GitHub Tool execution endpoint
        post("/github/execute") {
            try {
                val request = call.receive<GitHubExecuteRequest>()

                // Convert JsonObject to Map<String, Any>
                val parameters = mutableMapOf<String, Any>()
                request.parameters.forEach { (key, value) ->
                    parameters[key] = when {
                        value is kotlinx.serialization.json.JsonPrimitive -> value.content
                        else -> value.toString()
                    }
                }

                val gitHubServer = GitHubMcpServer()
                val result = gitHubServer.executeTool(request.tool, parameters)
                gitHubServer.close()

                call.respond(
                    HttpStatusCode.OK, GitHubExecuteResponse(
                        tool = request.tool,
                        result = result,
                        success = true
                    )
                )
            } catch (e: Exception) {
                logger.error("Error executing GitHub tool", e)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    GitHubExecuteResponse(
                        tool = "",
                        result = "",
                        success = false,
                        error = e.message
                    )
                )
            }
        }

        get("/mcp/tools") {
            try {
                val mcpClient = McpClient()
                val tools = mcpClient.connectToMcpServer()

                val response = McpToolsResponse(
                    tools = tools.map { tool ->
                        McpToolResponse(
                            name = tool.name,
                            description = tool.description,
                            inputSchema = tool.inputSchema
                        )
                    },
                    count = tools.size,
                    status = "connected"
                )

                call.respond(HttpStatusCode.OK, response)
                mcpClient.close()
            } catch (e: Exception) {
                logger.error("Error getting MCP tools", e)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf(
                        "error" to e.message,
                        "tools" to emptyList<Map<String, Any>>(),
                        "count" to 0,
                        "status" to "error"
                    )
                )
            }
        }

        // MCP Agent endpoints
        post("/mcp/chat") {
            try {
                val request = call.receive<McpChatRequest>()
                logger.info("Received MCP chat request from session ${request.sessionId}: ${request.message}")

                val agent = mcpAgents.getOrPut(request.sessionId) {
                    McpAgent(AnthropicClient(apiKey))
                }

                val response = agent.chat(
                    request.message,
                    request.sessionId,
                    request.temperature,
                    request.maxContextTokens
                )

                call.respond(HttpStatusCode.OK, response)
            } catch (e: Exception) {
                logger.error("Error processing MCP chat request", e)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    McpChatResponse(
                        response = "Error: ${e.message}",
                        timestamp = java.time.Instant.now().toString(),
                        mcpResults = listOf(
                            McpToolResult(
                                tool = "error",
                                parameters = buildJsonObject {},
                                result = e.message ?: "Unknown error",
                                success = false,
                                timestamp = java.time.Instant.now().toString()
                            )
                        )
                    )
                )
            }
        }

        post("/mcp/clear") {
            try {
                val sessionId = call.receive<Map<String, String>>()["sessionId"] ?: ""
                mcpAgents[sessionId]?.close()
                mcpAgents.remove(sessionId)
                call.respond(HttpStatusCode.OK, mapOf("status" to "cleared"))
            } catch (e: Exception) {
                logger.error("Error clearing MCP chat session", e)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to e.message)
                )
            }
        }

        get("/mcp/status") {
            try {
                val status = McpStatusResponse(
                    status = "active",
                    activeSessions = mcpAgents.size,
                    githubTokenConfigured = ((System.getProperty("GITHUB_TOKEN")
                        ?: System.getenv("GITHUB_TOKEN"))?.isNotEmpty() == true)
                )
                call.respond(HttpStatusCode.OK, status)
            } catch (e: Exception) {
                logger.error("Error getting MCP status", e)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    McpStatusResponse(status = "error", activeSessions = 0, githubTokenConfigured = false)
                )
            }
        }

        // Reminder MCP endpoints
        get("/reminder/mcp/tools") {
            try {
                val tools = reminderMcpServer.getAvailableTools()

                val response = McpToolsResponse(
                    tools = tools.map { tool ->
                        McpToolResponse(
                            name = tool.name,
                            description = tool.description,
                            inputSchema = tool.input_schema.properties
                        )
                    },
                    count = tools.size,
                    status = "connected"
                )

                call.respond(HttpStatusCode.OK, response)
            } catch (e: Exception) {
                logger.error("Error getting reminder MCP tools", e)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    McpToolsResponse(
                        tools = emptyList(),
                        count = 0,
                        status = "error",
                        error = e.message
                    )
                )
            }
        }

        post("/reminder/mcp/execute") {
            try {
                val request = call.receive<GitHubExecuteRequest>() // Reusing the same request model

                val result = reminderMcpServer.executeTool(request.tool, request.parameters)

                call.respond(
                    HttpStatusCode.OK, GitHubExecuteResponse(
                        tool = request.tool,
                        result = result,
                        success = true
                    )
                )
            } catch (e: Exception) {
                logger.error("Error executing reminder MCP tool", e)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    GitHubExecuteResponse(
                        tool = "",
                        result = "",
                        success = false,
                        error = e.message
                    )
                )
            }
        }

        // Direct Reminder API endpoints
        post("/reminder/create") {
            try {
                val request = call.receive<ReminderCreateRequest>()
                val task = reminderRepository.createReminder(request)

                call.respond(
                    HttpStatusCode.Created, ReminderResponse(
                        task = task,
                        success = true
                    )
                )
            } catch (e: Exception) {
                logger.error("Error creating reminder", e)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ReminderResponse(
                        task = ReminderTask(
                            id = "",
                            title = "",
                            description = "",
                            createdAt = ""
                        ),
                        success = false,
                        error = e.message
                    )
                )
            }
        }

        get("/reminder/list") {
            try {
                val status = call.request.queryParameters["status"] ?: "all"
                val priority = call.request.queryParameters["priority"]
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 10

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

                call.respond(
                    HttpStatusCode.OK, ReminderListResponse(
                        tasks = filteredReminders,
                        count = filteredReminders.size
                    )
                )
            } catch (e: Exception) {
                logger.error("Error listing reminders", e)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ReminderListResponse(
                        tasks = emptyList(),
                        count = 0,
                        error = e.message
                    )
                )
            }
        }

        get("/reminder/{id}") {
            try {
                val id = call.parameters["id"] ?: return@get call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Reminder ID is required")
                )

                val reminder = reminderRepository.getReminder(id)
                if (reminder != null) {
                    call.respond(
                        HttpStatusCode.OK, ReminderResponse(
                            task = reminder,
                            success = true
                        )
                    )
                } else {
                    call.respond(
                        HttpStatusCode.NotFound,
                        ReminderResponse(
                            task = ReminderTask(
                                id = "",
                                title = "",
                                description = "",
                                createdAt = ""
                            ),
                            success = false,
                            error = "Reminder not found"
                        )
                    )
                }
            } catch (e: Exception) {
                logger.error("Error getting reminder", e)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ReminderResponse(
                        task = ReminderTask(
                            id = "",
                            title = "",
                            description = "",
                            createdAt = ""
                        ),
                        success = false,
                        error = e.message
                    )
                )
            }
        }

        put("/reminder/{id}") {
            try {
                val id = call.parameters["id"] ?: return@put call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Reminder ID is required")
                )

                val request = call.receive<ReminderUpdateRequest>()
                val updatedTask = reminderRepository.updateReminder(id, request)

                if (updatedTask != null) {
                    call.respond(
                        HttpStatusCode.OK, ReminderResponse(
                            task = updatedTask,
                            success = true
                        )
                    )
                } else {
                    call.respond(
                        HttpStatusCode.NotFound,
                        ReminderResponse(
                            task = ReminderTask(
                                id = "",
                                title = "",
                                description = "",
                                createdAt = ""
                            ),
                            success = false,
                            error = "Reminder not found"
                        )
                    )
                }
            } catch (e: Exception) {
                logger.error("Error updating reminder", e)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ReminderResponse(
                        task = ReminderTask(
                            id = "",
                            title = "",
                            description = "",
                            createdAt = ""
                        ),
                        success = false,
                        error = e.message
                    )
                )
            }
        }

        delete("/reminder/{id}") {
            try {
                val id = call.parameters["id"] ?: return@delete call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Reminder ID is required")
                )

                val deleted = reminderRepository.deleteReminder(id)
                if (deleted) {
                    call.respond(HttpStatusCode.OK, mapOf("success" to true, "message" to "Reminder deleted"))
                } else {
                    call.respond(
                        HttpStatusCode.NotFound,
                        mapOf("success" to false, "error" to "Reminder not found")
                    )
                }
            } catch (e: Exception) {
                logger.error("Error deleting reminder", e)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("success" to false, "error" to e.message)
                )
            }
        }

        get("/reminder/today") {
            try {
                val todayReminders = reminderRepository.getTodayReminders()
                call.respond(
                    HttpStatusCode.OK, ReminderListResponse(
                        tasks = todayReminders,
                        count = todayReminders.size
                    )
                )
            } catch (e: Exception) {
                logger.error("Error getting today reminders", e)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ReminderListResponse(
                        tasks = emptyList(),
                        count = 0,
                        error = e.message
                    )
                )
            }
        }

        get("/reminder/summary") {
            try {
                val summary = reminderRepository.getReminderSummary()
                call.respond(HttpStatusCode.OK, summary)
            } catch (e: Exception) {
                logger.error("Error getting reminder summary", e)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to e.message)
                )
            }
        }

        get("/reminder/notifications") {
            try {
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 10
                val notifications = reminderRepository.getRecentNotifications(limit)
                call.respond(
                    HttpStatusCode.OK, mapOf(
                        "notifications" to notifications,
                        "count" to notifications.size
                    )
                )
            } catch (e: Exception) {
                logger.error("Error getting notifications", e)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to e.message, "notifications" to emptyList<Any>())
                )
            }
        }

        post("/reminder/summary/send") {
            try {
                val request = call.receive<Map<String, String>>()
                val type = request["type"] ?: "daily"

                val result = reminderScheduler.sendManualSummary(type)
                call.respond(HttpStatusCode.OK, mapOf("success" to true, "message" to result))
            } catch (e: Exception) {
                logger.error("Error sending manual summary", e)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("success" to false, "error" to e.message)
                )
            }
        }

        // Real MCP Integration endpoints
        get("/mcp-integration/status") {
            try {
                val status = mcpIntegrationService.getServiceStatus()
                call.respond(HttpStatusCode.OK, status)
            } catch (e: Exception) {
                logger.error("Error getting MCP integration status", e)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to e.message)
                )
            }
        }

        post("/mcp-integration/execute") {
            try {
                val request = call.receive<Map<String, Any>>()
                val toolName = request["toolName"]?.toString() ?: return@post call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "toolName is required")
                )
                val arguments = request["arguments"] as? Map<String, Any> ?: emptyMap()

                val demoRequest = DemoRequest(tool = toolName, parameters = arguments.mapValues { it.value.toString() })
                val result = mcpIntegrationService.executeDemoRequest(demoRequest)
                call.respond(
                    HttpStatusCode.OK, mapOf(
                        "success" to true,
                        "tool" to toolName,
                        "result" to result
                    )
                )
            } catch (e: Exception) {
                logger.error("Error executing MCP tool", e)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("success" to false, "error" to e.message)
                )
            }
        }

        get("/mcp-integration/tools") {
            try {
                val tools = mcpIntegrationService.getAvailableTools()
                call.respond(
                    HttpStatusCode.OK, mapOf(
                        "tools" to tools,
                        "count" to tools.size
                    )
                )
            } catch (e: Exception) {
                logger.error("Error getting MCP tools", e)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to e.message, "tools" to emptyList<String>())
                )
            }
        }

        post("/ai/summary") {
            try {
                val request = call.receive<Map<String, String>>()
                val focus = request["focus"]

                val demoRequest =
                    DemoRequest(tool = "ai_task_assistant", parameters = mapOf("query" to (focus ?: "general summary")))
                val summary = mcpIntegrationService.executeDemoRequest(demoRequest)
                call.respond(
                    HttpStatusCode.OK, mapOf(
                        "success" to true,
                        "summary" to summary,
                        "timestamp" to java.time.Instant.now().toString()
                    )
                )
            } catch (e: Exception) {
                logger.error("Error generating AI summary", e)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("success" to false, "error" to e.message)
                )
            }
        }

        post("/mcp/demo/execute") {
            try {
                val request = call.receive<DemoRequest>()

                val result = mcpIntegrationService.executeDemoRequest(request)
                call.respond(HttpStatusCode.OK, result)
            } catch (e: Exception) {
                logger.error("Error executing MCP demo tool", e)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    DemoResponse(
                        success = false,
                        result = "Error: ${e.message}"
                    )
                )
            }
        }

        get("/mcp/demo/tools") {
            try {
                val toolsResponse = mcpIntegrationService.getToolsResponse()
                call.respond(HttpStatusCode.OK, toolsResponse)
            } catch (e: Exception) {
                logger.error("Error getting MCP demo tools", e)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ToolsResponse(
                        tools = emptyList(),
                        count = 0,
                        description = "Error occurred",
                        powered_by = "Unknown"
                    )
                )
            }
        }

        post("/ai/daily-summary") {
            try {
                val date = call.receive<Map<String, String>>()["date"] ?: java.time.LocalDateTime.now()
                    .format(DateTimeFormatter.ISO_LOCAL_DATE)

                // Generate comprehensive daily summary using LLM
                // val dailySummary = llmSummarizer.generateDailySummary(date)

                call.respond(
                    HttpStatusCode.OK, mapOf(
                        "success" to true,
                        "message" to "Daily summary feature temporarily disabled",
                        "date" to date
                    )
                )
            } catch (e: Exception) {
                logger.error("Error generating daily summary", e)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("success" to false, "error" to e.message)
                )
            }
        }

        // MCP Tool Composition endpoints
        post("/mcp/composition") {
            try {
                val request = call.receive<Map<String, String>>()
                val compositionRequest = request["request"] ?: return@post call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "request parameter is required")
                )

                logger.info("Received composition request: $compositionRequest")

                val compositionAgent = CompositionAgent(anthropicClient)
                val result = compositionAgent.executeComposition(compositionRequest)

                call.respond(HttpStatusCode.OK, result)
            } catch (e: Exception) {
                logger.error("Error executing composition", e)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("success" to false, "error" to e.message)
                )
            }
        }

        get("/mcp/composition/tools") {
            try {
                val tools = CompositionMcpTools.getAllCompositionTools()
                val toolsList = tools.map { tool ->
                    mapOf(
                        "name" to tool.name,
                        "description" to tool.description
                    )
                }
                call.respond(
                    HttpStatusCode.OK, mapOf(
                        "tools" to toolsList,
                        "count" to toolsList.size
                    )
                )
            } catch (e: Exception) {
                logger.error("Error getting composition tools", e)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to e.message, "tools" to emptyList<String>())
                )
            }
        }

        // Download report files
        get("/download/{filename}") {
            val filename = call.parameters["filename"]
                ?: return@get call.respondText("Filename is required", status = HttpStatusCode.BadRequest)

            val file = File("reports/$filename")
            if (file.exists()) {
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    ContentDisposition.Attachment.withParameter(
                        ContentDisposition.Parameters.FileName,
                        filename
                    ).toString()
                )
                call.respondFile(file)
            } else {
                call.respondText("File not found", status = HttpStatusCode.NotFound)
            }
        }

        // RAG System Endpoints (Day 15)
        post("/rag/build-knowledge-base") {
            try {
                logger.info("Starting knowledge base build...")
                call.respond(HttpStatusCode.Accepted, mapOf("status" to "Building knowledge base in progress..."))

                // Build knowledge base asynchronously
                ragRetriever.buildKnowledgeBase("data/farming")
                isKnowledgeBaseBuilt = true

                logger.info("Knowledge base built successfully")
                call.respond(
                    mapOf(
                        "status" to "success",
                        "message" to "Knowledge base built successfully",
                        "stats" to ragRetriever.getStats()
                    )
                )
            } catch (e: Exception) {
                logger.error("Error building knowledge base", e)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to e.message, "status" to "error")
                )
            }
        }

        post("/rag/search") {
            try {
                data class RAGSearchRequest(val query: String, val topK: Int = 5)

                val request = call.receive<RAGSearchRequest>()

                logger.info("RAG search for query: ${request.query.take(100)}")

                if (!isKnowledgeBaseBuilt) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Knowledge base not built yet. Call /rag/build-knowledge-base first")
                    )
                }

                val results = ragRetriever.retrieveRelevant(request.query, request.topK)

                val formattedResults = results.map { chunk ->
                    mapOf(
                        "text" to chunk.text,
                        "sourceFile" to chunk.sourceFile,
                        "chunkIndex" to chunk.chunkIndex,
                        "similarity" to chunk.similarity
                    )
                }

                call.respond(
                    mapOf(
                        "query" to request.query,
                        "results" to formattedResults,
                        "count" to formattedResults.size
                    )
                )
            } catch (e: Exception) {
                logger.error("Error searching knowledge base", e)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to e.message)
                )
            }
        }

        get("/rag/stats") {
            try {
                val stats = ragRetriever.getStats()
                call.respond(
                    mapOf(
                        "stats" to stats,
                        "isBuilt" to isKnowledgeBaseBuilt
                    )
                )
            } catch (e: Exception) {
                logger.error("Error getting RAG stats", e)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to e.message)
                )
            }
        }

        post("/rag/reload") {
            try {
                logger.info("Reloading knowledge base...")
                ragRetriever.reloadKnowledgeBase("data/farming")
                isKnowledgeBaseBuilt = true

                call.respond(
                    mapOf(
                        "status" to "success",
                        "message" to "Knowledge base reloaded successfully",
                        "stats" to ragRetriever.getStats()
                    )
                )
            } catch (e: Exception) {
                logger.error("Error reloading knowledge base", e)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to e.message)
                )
            }
        }

        // RAG Progress Tracking Endpoints (Day 15 - Progress)
        get("/rag/progress") {
            try {
                val progress = RAGProgressTracker.getProgress()
                call.respond(progress)
            } catch (e: Exception) {
                logger.error("Error getting RAG progress", e)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to e.message)
                )
            }
        }

        get("/rag/progress/logs") {
            try {
                val logs = RAGProgressTracker.getDetailedLogs()
                call.respond(
                    mapOf(
                        "logs" to logs,
                        "count" to logs.size
                    )
                )
            } catch (e: Exception) {
                logger.error("Error getting RAG logs", e)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to e.message)
                )
            }
        }

        get("/rag/progress/console") {
            try {
                val progress = RAGProgressTracker.getProgress()
                call.respondText(progress.toConsoleOutput(), contentType = ContentType.Text.Plain)
            } catch (e: Exception) {
                logger.error("Error getting RAG console output", e)
                call.respondText("Error: ${e.message}", status = HttpStatusCode.InternalServerError)
            }
        }

        // RAG Query Endpoints (Day 16)
        post("/rag/query") {
            try {
                val request = call.receive<RAGQueryRequest>()

                logger.info("RAG query: ${request.question}")

                // Check if system is ready
                if (!ragQueryService.isReady()) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf(
                            "error" to "RAG system is not ready. Make sure the knowledge base is built and Ollama is running."
                        )
                    )
                }

                val result = ragQueryService.queryWithRAG(request.question, request.topK)
                call.respond(HttpStatusCode.OK, result)
            } catch (e: Exception) {
                logger.error("Error processing RAG query", e)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to e.message)
                )
            }
        }

        post("/rag/query-without-rag") {
            try {
                val request = call.receive<RAGQueryRequest>()

                logger.info("Query without RAG: ${request.question}")

                if (!llmClient.isAvailable()) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Ollama LLM service is not available")
                    )
                }

                val result = ragQueryService.queryWithoutRAG(request.question)
                call.respond(HttpStatusCode.OK, result)
            } catch (e: Exception) {
                logger.error("Error processing query without RAG", e)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to e.message)
                )
            }
        }

        post("/rag/compare") {
            try {
                val request = call.receive<RAGCompareRequest>()

                logger.info("RAG comparison for: ${request.question}")

                // Check if system is ready
                if (!ragQueryService.isReady()) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf(
                            "error" to "RAG system is not ready. Make sure the knowledge base is built and Ollama is running."
                        )
                    )
                }

                val result = ragComparisonService.compare(request.question, request.topK)
                call.respond(HttpStatusCode.OK, result)
            } catch (e: Exception) {
                logger.error("Error processing RAG comparison", e)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to e.message)
                )
            }
        }

        get("/rag/status") {
            try {
                val isReady = ragQueryService.isReady()
                val stats = ragQueryService.getStats()
                val totalChunks = stats["totalChunks"] ?: 0
                val sources = stats["sources"] ?: 0

                call.respondText(
                    contentType = ContentType.Application.Json,
                    text = """{"ready":$isReady,"totalChunks":$totalChunks,"sources":$sources,"ollamaEmbedding":${embeddingClient.isAvailable()},"ollamaLLM":${llmClient.isAvailable()}}"""
                )
            } catch (e: Exception) {
                logger.error("Error getting RAG status", e)
                call.respondText(
                    contentType = ContentType.Application.Json,
                    status = HttpStatusCode.InternalServerError,
                    text = """{"error":"${e.message}"}"""
                )
            }
        }
    }
}

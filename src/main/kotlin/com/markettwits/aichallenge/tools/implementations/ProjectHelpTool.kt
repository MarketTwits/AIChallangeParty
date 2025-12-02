package com.markettwits.aichallenge.tools.implementations

import com.markettwits.aichallenge.rag.RAGQueryService
import com.markettwits.aichallenge.tools.core.Tool
import com.markettwits.aichallenge.tools.core.ToolResult
import com.markettwits.aichallenge.tools.core.ToolSchema
import com.markettwits.aichallenge.tools.core.ToolType
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

/**
 * System tool for /help command - answers questions about the project
 * Combines RAG search with project metadata
 */
class ProjectHelpTool(
    private val ragQueryService: RAGQueryService,
    private val gitBranchTool: GitBranchTool,
) : Tool {
    private val logger = LoggerFactory.getLogger(ProjectHelpTool::class.java)

    override val name: String = "project_help"
    override val description: String = """
        Comprehensive help system for the project. Answers questions about:
        - Project structure and architecture
        - Available API endpoints and how to use them
        - Development workflow and git status
        - Configuration and setup instructions
        - Code style and best practices
        - Features and capabilities
        This is the main entry point for /help command.
    """.trimIndent()

    override val type: ToolType = ToolType.SYSTEM
    override val schema: ToolSchema = ToolSchema(
        properties = buildJsonObject {
            put("question", buildJsonObject {
                put("type", "string")
                put("description", "The help question or topic to explain")
            })
            put("include_git_status", buildJsonObject {
                put("type", "boolean")
                put("description", "Include current git branch and status (default: false)")
                put("default", false)
            })
        },
        required = listOf("question")
    )

    override suspend fun execute(params: JsonObject): ToolResult {
        val question = params["question"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: question")

        val includeGitStatus = params["include_git_status"]?.jsonPrimitive?.booleanOrNull ?: false

        return try {
            logger.info("Processing help request: $question")

            val helpResponse = buildString {
                appendLine("🆘 Project Help System")
                appendLine("=".repeat(60))
                appendLine()

                // Add project context
                appendLine("📦 Project: AI Running Coach (Kotlin/Ktor + Claude AI)")
                appendLine()

                // Get git status if requested
                if (includeGitStatus) {
                    val gitResult = gitBranchTool.execute(buildJsonObject {
                        put("include_remote", true)
                    })
                    if (gitResult is ToolResult.Success) {
                        appendLine(gitResult.data)
                        appendLine()
                    }
                }

                // Query documentation
                if (ragQueryService.isReady()) {
                    appendLine("📚 Searching documentation for: \"$question\"")
                    appendLine()

                    val ragResult = ragQueryService.queryWithRAG(question, topK = 3)

                    if (ragResult.retrievedChunks.isNotEmpty()) {
                        appendLine("📖 Relevant Documentation:")
                        appendLine()

                        ragResult.retrievedChunks.forEachIndexed { index, chunk ->
                            appendLine("${index + 1}. ${chunk.sourceFile}")
                            appendLine("   ${chunk.text.take(300)}${if (chunk.text.length > 300) "..." else ""}")
                            appendLine()
                        }
                    }

                    if (ragResult.answer.isNotEmpty()) {
                        appendLine("💡 Answer:")
                        appendLine(ragResult.answer)
                        appendLine()
                    }
                } else {
                    appendLine("⚠️  Documentation index is not ready yet.")
                    appendLine("   Basic help information:")
                    appendLine()
                    appendLine(getBasicHelp(question))
                }

                appendLine()
                appendLine("=".repeat(60))
                appendLine("💡 Tip: Ask specific questions about endpoints, features, or setup")
            }

            ToolResult.Success(
                data = helpResponse,
                metadata = mapOf(
                    "toolType" to "help_system",
                    "ragEnabled" to ragQueryService.isReady(),
                    "question" to question
                )
            )
        } catch (e: Exception) {
            logger.error("Error processing help request", e)
            ToolResult.Error(
                message = "Failed to process help request: ${e.message}",
                code = "HELP_ERROR"
            )
        }
    }

    override fun validateParams(params: JsonObject): String? {
        if (!params.containsKey("question")) {
            return "Missing required parameter: question"
        }

        val question = params["question"]?.jsonPrimitive?.contentOrNull
        if (question.isNullOrBlank()) {
            return "Question parameter cannot be empty"
        }

        return null
    }

    private fun getBasicHelp(question: String): String {
        val lowerQuestion = question.lowercase()

        return when {
            "endpoint" in lowerQuestion || "api" in lowerQuestion -> """
                Available API Endpoints:
                - POST /chat - Main chat interface with Claude AI
                - POST /rag/query - Query RAG system with documentation
                - GET /rag/status - Check RAG system status
                - POST /mcp/chat - Chat with MCP integration
                - GET /health - Health check endpoint
            """.trimIndent()

            "setup" in lowerQuestion || "install" in lowerQuestion -> """
                Project Setup:
                1. Clone the repository
                2. Set ANTHROPIC_API_KEY in .env file
                3. Run: ./gradlew build
                4. Run: ./gradlew run
                5. Server starts on http://localhost:8080
            """.trimIndent()

            "feature" in lowerQuestion || "capability" in lowerQuestion -> """
                Key Features:
                - AI Running Coach with Claude API
                - RAG system for documentation search
                - MCP integration for GitHub and reminders
                - Multi-model reasoning support
                - Session management and history
            """.trimIndent()

            else -> """
                This is an AI Running Coach application built with:
                - Kotlin/Ktor backend
                - Claude AI (Anthropic)
                - RAG for documentation
                - MCP for external integrations

                For specific help, try asking about:
                - API endpoints
                - Setup instructions
                - Features and capabilities
            """.trimIndent()
        }
    }
}

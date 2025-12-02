package com.markettwits.aichallenge.tools.implementations

import com.markettwits.aichallenge.rag.RAGQueryService
import com.markettwits.aichallenge.tools.core.Tool
import com.markettwits.aichallenge.tools.core.ToolResult
import com.markettwits.aichallenge.tools.core.ToolSchema
import com.markettwits.aichallenge.tools.core.ToolType
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

/**
 * RAG-powered tool for analyzing code changes
 * Uses project documentation and coding standards for context
 */
class AnalyzeCodeTool(
    private val ragQueryService: RAGQueryService,
) : Tool {
    private val logger = LoggerFactory.getLogger(AnalyzeCodeTool::class.java)

    override val name: String = "analyze_code_with_context"
    override val description: String = """
        Analyze code changes using RAG to retrieve relevant context from:
        - Project documentation and README
        - Coding standards and best practices
        - Architecture guidelines
        Use this to understand if changes follow project conventions.
    """.trimIndent()

    override val type: ToolType = ToolType.RAG
    override val schema: ToolSchema = ToolSchema(
        properties = buildJsonObject {
            put("code_snippet", buildJsonObject {
                put("type", "string")
                put("description", "Code snippet to analyze (diff or full file)")
            })
            put("file_path", buildJsonObject {
                put("type", "string")
                put("description", "File path for context (e.g., 'src/main/kotlin/Agent.kt')")
            })
            put("analysis_type", buildJsonObject {
                put("type", "string")
                put("description", "Type of analysis to perform")
                put("enum", buildJsonArray {
                    add("security")
                    add("performance")
                    add("architecture")
                    add("best_practices")
                    add("general")
                })
                put("default", "general")
            })
        },
        required = listOf("code_snippet")
    )

    override suspend fun execute(params: JsonObject): ToolResult {
        val codeSnippet = params["code_snippet"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.Error("code_snippet is required", "MISSING_PARAMETER")

        val filePath = params["file_path"]?.jsonPrimitive?.contentOrNull ?: "unknown"
        val analysisType = params["analysis_type"]?.jsonPrimitive?.contentOrNull ?: "general"

        return try {
            logger.info("Analyzing code with RAG context: $filePath ($analysisType)")

            if (!ragQueryService.isReady()) {
                return ToolResult.Error(
                    message = "RAG system is not ready. Please index documentation first.",
                    code = "RAG_NOT_READY"
                )
            }

            val analysisResult = buildString {
                appendLine("🔍 Code Analysis with RAG Context")
                appendLine("=".repeat(60))
                appendLine("File: $filePath")
                appendLine("Analysis Type: $analysisType")
                appendLine()

                // Query RAG for relevant context based on analysis type
                val contextQueries = buildContextQueries(analysisType, filePath)

                appendLine("📚 Retrieving Relevant Context from Documentation:")
                appendLine()

                val relevantContext = mutableListOf<String>()
                contextQueries.forEach { query ->
                    try {
                        val ragResult = ragQueryService.queryWithRAG(query, topK = 3)
                        if (ragResult.retrievedChunks.isNotEmpty()) {
                            appendLine("  Query: $query")
                            ragResult.retrievedChunks.take(2).forEach { chunk ->
                                appendLine("    - ${chunk.text.take(100)}...")
                                relevantContext.add(chunk.text)
                            }
                            appendLine()
                        }
                    } catch (e: Exception) {
                        logger.warn("Could not query RAG for: $query", e)
                    }
                }

                appendLine("📝 Code Snippet:")
                appendLine("-".repeat(60))
                appendLine(codeSnippet)
                appendLine("-".repeat(60))
                appendLine()

                // Build analysis guidance based on context
                appendLine("💡 Analysis Guidance from Project Context:")
                if (relevantContext.isNotEmpty()) {
                    appendLine("Based on project documentation, consider:")
                    relevantContext.take(3).forEachIndexed { index, context ->
                        appendLine("${index + 1}. ${context.lines().take(3).joinToString(" ")}")
                    }
                } else {
                    appendLine("⚠️  No specific project context found. Using general best practices.")
                }
                appendLine()

                // Add analysis-specific checks
                appendLine("🎯 Specific ${analysisType.uppercase()} Checks:")
                appendLine(getAnalysisChecklist(analysisType))
            }

            ToolResult.Success(
                data = analysisResult,
                metadata = mapOf(
                    "toolType" to "code_analysis",
                    "filePath" to filePath,
                    "analysisType" to analysisType,
                    "ragReady" to true
                )
            )
        } catch (e: Exception) {
            logger.error("Error analyzing code", e)
            ToolResult.Error(
                message = "Failed to analyze code: ${e.message}",
                code = "ANALYSIS_ERROR"
            )
        }
    }

    override fun validateParams(params: JsonObject): String? {
        val codeSnippet = params["code_snippet"]?.jsonPrimitive?.contentOrNull
        if (codeSnippet.isNullOrBlank()) {
            return "code_snippet cannot be empty"
        }
        return null
    }

    private fun buildContextQueries(analysisType: String, filePath: String): List<String> {
        val fileType = filePath.substringAfterLast('.', "")

        val baseQueries = mutableListOf<String>()

        when (analysisType) {
            "security" -> {
                baseQueries.add("security best practices")
                baseQueries.add("authentication authorization")
                baseQueries.add("input validation sanitization")
            }

            "performance" -> {
                baseQueries.add("performance optimization")
                baseQueries.add("caching strategy")
                baseQueries.add("database queries")
            }

            "architecture" -> {
                baseQueries.add("architecture design patterns")
                baseQueries.add("SOLID principles")
                baseQueries.add("project structure organization")
            }

            "best_practices" -> {
                baseQueries.add("coding standards")
                baseQueries.add("best practices")
                baseQueries.add("code quality")
            }

            else -> {
                baseQueries.add("$fileType development guidelines")
                baseQueries.add("API documentation")
                baseQueries.add("project conventions")
            }
        }

        return baseQueries
    }

    private fun getAnalysisChecklist(analysisType: String): String {
        return when (analysisType) {
            "security" -> """
                - Check for SQL injection vulnerabilities
                - Verify input validation and sanitization
                - Look for hard-coded credentials or secrets
                - Check authentication and authorization
                - Review error handling (no sensitive info leaked)
            """.trimIndent()

            "performance" -> """
                - Look for N+1 query patterns
                - Check for unnecessary loops or nested iterations
                - Verify proper use of caching
                - Check database query efficiency
                - Look for blocking operations that could be async
            """.trimIndent()

            "architecture" -> """
                - Verify adherence to SOLID principles
                - Check separation of concerns
                - Review dependency injection usage
                - Verify proper layering (controller → service → repository)
                - Check for code duplication
            """.trimIndent()

            "best_practices" -> """
                - Check code readability and naming conventions
                - Verify proper error handling
                - Look for adequate logging
                - Check for proper documentation/comments
                - Verify test coverage for new code
            """.trimIndent()

            else -> """
                - Review overall code quality
                - Check for potential bugs
                - Verify error handling
                - Look for performance issues
                - Check adherence to project conventions
            """.trimIndent()
        }
    }
}

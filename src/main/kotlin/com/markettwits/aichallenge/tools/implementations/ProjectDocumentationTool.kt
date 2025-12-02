package com.markettwits.aichallenge.tools.implementations

import com.markettwits.aichallenge.rag.RAGQueryService
import com.markettwits.aichallenge.tools.core.Tool
import com.markettwits.aichallenge.tools.core.ToolResult
import com.markettwits.aichallenge.tools.core.ToolSchema
import com.markettwits.aichallenge.tools.core.ToolType
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

/**
 * RAG-based tool for querying project documentation
 * Indexes README.md and docs/ folder
 */
class ProjectDocumentationTool(
    private val ragQueryService: RAGQueryService,
) : Tool {
    private val logger = LoggerFactory.getLogger(ProjectDocumentationTool::class.java)

    override val name: String = "query_project_docs"
    override val description: String = """
        Search and retrieve information from project documentation including README, architecture docs,
        API documentation, and project guidelines. Use this when user asks about:
        - Project structure and organization
        - How to use specific features
        - API endpoints and their usage
        - Project setup and configuration
        - Development guidelines and best practices
    """.trimIndent()

    override val type: ToolType = ToolType.RAG
    override val schema: ToolSchema = ToolSchema(
        properties = buildJsonObject {
            put("query", buildJsonObject {
                put("type", "string")
                put("description", "The question or search query about the project documentation")
            })
            put("topK", buildJsonObject {
                put("type", "integer")
                put("description", "Number of most relevant document chunks to retrieve (default: 5)")
                put("default", 5)
            })
        },
        required = listOf("query")
    )

    override suspend fun execute(params: JsonObject): ToolResult {
        val query = params["query"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: query")

        val topK = params["topK"]?.jsonPrimitive?.intOrNull ?: 5

        return try {
            logger.info("Querying project documentation: $query")

            // Check if RAG system is ready
            if (!ragQueryService.isReady()) {
                return ToolResult.Error(
                    message = "Project documentation is not indexed yet. Please wait for the system to initialize.",
                    code = "RAG_NOT_READY"
                )
            }

            // Query RAG system
            val result = ragQueryService.queryWithRAG(query, topK)

            // Format result for LLM
            val formattedResult = buildString {
                appendLine("📚 Project Documentation Search Results for: \"$query\"")
                appendLine()
                appendLine("Found ${result.retrievedChunks.size} relevant sections:")
                appendLine()

                result.retrievedChunks.forEachIndexed { index, chunk ->
                    appendLine("${index + 1}. Source: ${chunk.sourceFile}")
                    appendLine("   Relevance: ${String.format("%.2f", chunk.similarity * 100)}%")
                    appendLine("   Content:")
                    appendLine("   ${chunk.text.take(500)}${if (chunk.text.length > 500) "..." else ""}")
                    appendLine()
                }

                if (result.answer.isNotEmpty()) {
                    appendLine("📝 Summary:")
                    appendLine(result.answer)
                }
            }

            ToolResult.Success(
                data = formattedResult,
                metadata = mapOf(
                    "queryType" to "project_documentation",
                    "chunksFound" to result.retrievedChunks.size,
                    "sources" to result.retrievedChunks.map { it.sourceFile }.distinct()
                )
            )
        } catch (e: Exception) {
            logger.error("Error querying project documentation", e)
            ToolResult.Error(
                message = "Failed to query project documentation: ${e.message}",
                code = "QUERY_FAILED"
            )
        }
    }

    override fun validateParams(params: JsonObject): String? {
        if (!params.containsKey("query")) {
            return "Missing required parameter: query"
        }

        val query = params["query"]?.jsonPrimitive?.contentOrNull
        if (query.isNullOrBlank()) {
            return "Query parameter cannot be empty"
        }

        val topK = params["topK"]?.jsonPrimitive?.intOrNull
        if (topK != null && (topK < 1 || topK > 20)) {
            return "topK must be between 1 and 20"
        }

        return null
    }
}

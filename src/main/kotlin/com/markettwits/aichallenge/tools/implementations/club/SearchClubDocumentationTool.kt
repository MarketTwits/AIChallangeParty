package com.markettwits.aichallenge.tools.implementations.club

import com.markettwits.aichallenge.rag.RAGQueryService
import com.markettwits.aichallenge.tools.core.Tool
import com.markettwits.aichallenge.tools.core.ToolResult
import com.markettwits.aichallenge.tools.core.ToolSchema
import com.markettwits.aichallenge.tools.core.ToolType
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

/**
 * RAG-based tool for searching club documentation
 * Uses RAG to search through club info, events, FAQ, and messages
 */
class SearchClubDocumentationTool(
    private val ragQueryService: RAGQueryService,
) : Tool {
    private val logger = LoggerFactory.getLogger(SearchClubDocumentationTool::class.java)

    override val name: String = "search_club_docs"
    override val description: String = """
        Search through SportSauce Club documentation, announcements, and historical messages.
        Uses semantic search to find relevant information about:
        - Club events and competitions
        - Historical achievements and results
        - General club information and philosophy
        - Training camps and special events
        - Club announcements and news

        Use this tool first before MCP tools to get context about the club.
    """.trimIndent()

    override val type: ToolType = ToolType.RAG
    override val schema: ToolSchema = ToolSchema(
        properties = buildJsonObject {
            put("query", buildJsonObject {
                put("type", "string")
                put("description", "The question or search query about the club")
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
            logger.info("Searching club documentation: $query")

            // Check if RAG system is ready
            if (!ragQueryService.isReady()) {
                return ToolResult.Error(
                    message = "Club documentation is not indexed yet. Please wait for the system to initialize.",
                    code = "RAG_NOT_READY"
                )
            }

            // Query RAG system
            val result = ragQueryService.queryWithRAG(query, topK)

            // Format result for LLM
            val formattedResult = buildString {
                appendLine("📚 SportSauce Club Documentation Search")
                appendLine("=".repeat(50))
                appendLine()
                appendLine("Query: \"$query\"")
                appendLine()
                appendLine("Found ${result.retrievedChunks.size} relevant sections:")
                appendLine()

                result.retrievedChunks.forEachIndexed { index, chunk ->
                    appendLine("${index + 1}. Source: ${chunk.sourceFile}")
                    appendLine("   Relevance: ${String.format("%.2f", chunk.similarity * 100)}%")
                    appendLine("   Content:")
                    appendLine("   ${chunk.text}")
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
                    "queryType" to "club_documentation",
                    "chunksFound" to result.retrievedChunks.size,
                    "sources" to result.retrievedChunks.map { it.sourceFile }.distinct()
                )
            )
        } catch (e: Exception) {
            logger.error("Error searching club documentation", e)
            ToolResult.Error(
                message = "Failed to search club documentation: ${e.message}",
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

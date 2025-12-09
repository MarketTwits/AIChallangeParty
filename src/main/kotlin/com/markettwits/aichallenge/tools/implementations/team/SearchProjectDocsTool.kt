package com.markettwits.aichallenge.tools.implementations.team

import com.markettwits.aichallenge.rag.RAGQueryService
import com.markettwits.aichallenge.tools.core.Tool
import com.markettwits.aichallenge.tools.core.ToolResult
import com.markettwits.aichallenge.tools.core.ToolSchema
import com.markettwits.aichallenge.tools.core.ToolType
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

/**
 * RAG Tool for searching project documentation
 */
class SearchProjectDocsTool(private val ragQueryService: RAGQueryService) : Tool {

    private val logger = LoggerFactory.getLogger(SearchProjectDocsTool::class.java)

    override val name = "search_project_docs"
    override val description =
        "Search project documentation and knowledge base for information about project structure, architecture, implementation details, and best practices"
    override val type = ToolType.RAG

    override val schema = ToolSchema(
        properties = buildJsonObject {
            put("query", buildJsonObject {
                put("type", "string")
                put("description", "Search query for project documentation")
            })
            put("topK", buildJsonObject {
                put("type", "integer")
                put("description", "Number of relevant document chunks to return (default: 5)")
            })
        },
        required = listOf("query")
    )

    override suspend fun execute(params: JsonObject): ToolResult {
        return try {
            val query = params["query"]?.jsonPrimitive?.content
                ?: return ToolResult.Error("Missing required parameter: query")

            val topK = params["topK"]?.jsonPrimitive?.intOrNull ?: 5

            logger.info("🔍 Searching project docs for: \"$query\"")

            // Search in RAG system using queryWithRAG
            val ragResults = ragQueryService.queryWithRAG(query, topK = topK)
            val results = ragResults.retrievedChunks

            if (results.isEmpty()) {
                return ToolResult.Success(
                    data = "📚 No relevant documentation found for: \"$query\"",
                    metadata = mapOf("query" to query, "count" to 0)
                )
            }

            val resultData = buildString {
                appendLine("📚 Found ${results.size} relevant document sections for: \"$query\"")
                appendLine("=".repeat(60))
                appendLine()

                results.forEachIndexed { index, chunk ->
                    appendLine("${index + 1}. 📄 ${chunk.sourceFile}")
                    appendLine("   📝 ${chunk.text.take(200)}...")
                    appendLine("   📊 Score: ${"%.3f".format(chunk.similarity)}")
                    appendLine("   📍 Chunk: ${chunk.chunkIndex}")
                    if (chunk.headingContext.isNotEmpty()) {
                        appendLine("   🏷️ Context: ${chunk.headingContext}")
                    }
                    appendLine()
                }

                val sources = results.map { it.sourceFile }.distinct()
                if (sources.size > 1) {
                    appendLine("📂 Sources: ${sources.joinToString(", ")}")
                }
            }

            ToolResult.Success(
                data = resultData,
                metadata = mapOf(
                    "query" to query,
                    "count" to results.size,
                    "sources" to results.map { it.sourceFile }.distinct(),
                    "results" to results.map {
                        mapOf(
                            "text" to it.text,
                            "sourceFile" to it.sourceFile,
                            "relevance" to it.similarity
                        )
                    }
                )
            )

        } catch (e: Exception) {
            logger.error("Failed to search project docs", e)
            ToolResult.Error("Failed to search documentation: ${e.message}")
        }
    }

    override fun validateParams(params: JsonObject): String? {
        if (!params.contains("query")) return "Missing required parameter: query"
        return null
    }
}

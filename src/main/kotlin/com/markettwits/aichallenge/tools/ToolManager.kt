package com.markettwits.aichallenge.tools

import com.markettwits.aichallenge.rag.RAGQueryService
import com.markettwits.aichallenge.tools.core.ToolExecutor
import com.markettwits.aichallenge.tools.core.ToolRegistry
import com.markettwits.aichallenge.tools.core.ToolResult
import com.markettwits.aichallenge.tools.core.ToolType
import com.markettwits.aichallenge.tools.implementations.GitBranchTool
import com.markettwits.aichallenge.tools.implementations.ProjectDocumentationTool
import com.markettwits.aichallenge.tools.implementations.ProjectHelpTool
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory

/**
 * Central manager for the tool system
 * Coordinates registry, executor, and tool initialization
 */
class ToolManager(
    private val ragQueryService: RAGQueryService,
    private val repositoryPath: String = ".",
) {
    private val logger = LoggerFactory.getLogger(ToolManager::class.java)
    val registry = ToolRegistry()
    val executor = ToolExecutor(registry)

    init {
        initializeTools()
    }

    /**
     * Initialize all tools in the system
     */
    private fun initializeTools() {
        logger.info("Initializing tool system...")

        // Initialize git branch tool
        val gitBranchTool = GitBranchTool(repositoryPath)

        // Register all tools
        registry.registerAll(
            ProjectDocumentationTool(ragQueryService),
            gitBranchTool,
            ProjectHelpTool(ragQueryService, gitBranchTool)
        )

        logger.info("Tool system initialized with ${registry.getToolCount()} tools")
        logger.info("Available tools: ${registry.getAllTools().joinToString { it.name }}")
    }

    /**
     * Execute a tool by name
     */
    suspend fun executeTool(toolName: String, params: JsonObject): ToolResult {
        return executor.execute(toolName, params)
    }

    /**
     * Get all tools formatted for Claude API
     */
    fun getToolsForClaudeAPI(): List<com.markettwits.aichallenge.Tool> {
        return registry.getAllTools().map { tool ->
            com.markettwits.aichallenge.Tool(
                name = tool.name,
                description = tool.description,
                input_schema = com.markettwits.aichallenge.InputSchema(
                    type = tool.schema.type,
                    properties = tool.schema.properties,
                    required = tool.schema.required
                )
            )
        }
    }

    /**
     * Get tools summary for API responses
     */
    fun getToolsSummary(): Map<String, Any> {
        val tools = registry.getAllTools()
        val toolsList = tools.map { tool ->
            mapOf(
                "name" to tool.name,
                "type" to tool.type.name,
                "description" to (tool.description.lines().firstOrNull() ?: "")
            )
        }
        return mapOf(
            "total" to tools.size,
            "byType" to tools.groupBy { it.type.name }.mapValues { it.value.size },
            "tools" to toolsList
        )
    }

    /**
     * Check if help system is ready (RAG indexed)
     */
    suspend fun isHelpSystemReady(): Boolean {
        return ragQueryService.isReady()
    }

    /**
     * Get system status
     */
    suspend fun getSystemStatus(): Map<String, Any> {
        return mapOf(
            "toolSystemActive" to true,
            "totalTools" to registry.getToolCount(),
            "ragReady" to ragQueryService.isReady(),
            "toolsByType" to ToolType.values().associate { type ->
                type.name to registry.getToolsByType(type).map { it.name }
            }
        )
    }
}

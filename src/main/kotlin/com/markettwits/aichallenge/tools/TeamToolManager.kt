package com.markettwits.aichallenge.tools

import com.markettwits.aichallenge.rag.RAGQueryService
import com.markettwits.aichallenge.team.TicketProcessor
import com.markettwits.aichallenge.tools.core.ToolExecutor
import com.markettwits.aichallenge.tools.core.ToolRegistry
import com.markettwits.aichallenge.tools.core.ToolResult
import com.markettwits.aichallenge.tools.implementations.team.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory

/**
 * Manager for team assistant tools (support ticket processing + project documentation)
 * Day 23: Team Assistant with RAG + MCP integration
 */
class TeamToolManager(
    private val ticketProcessor: TicketProcessor,
    private val ragQueryService: RAGQueryService,
) {
    private val logger = LoggerFactory.getLogger(TeamToolManager::class.java)
    private val registry = ToolRegistry()
    private val executor = ToolExecutor(registry)

    init {
        registerTools()
    }

    /**
     * Register all team tools
     */
    private fun registerTools() {
        // Support ticket processing tools (MCP)
        registry.register(ProcessSupportRequestsTool(ticketProcessor))
        registry.register(GetTicketsTool(ticketProcessor))
        registry.register(UpdateTicketTool(ticketProcessor))
        registry.register(DeleteTicketTool(ticketProcessor))

        // Documentation search tool (RAG)
        registry.register(SearchProjectDocsTool(ragQueryService))

        logger.info("✅ Team Tool System initialized with ${registry.getToolCount()} tools")
        logger.info("📋 Team tools:")
        for (tool in registry.getAllTools()) {
            logger.info("   - ${tool.name} (${tool.type})")
        }
    }

    /**
     * Execute a tool by name
     */
    suspend fun executeTool(toolName: String, input: JsonObject): ToolResult {
        return executor.execute(toolName, input)
    }

    /**
     * Get tool definitions formatted for Claude API
     */
    fun getClaudeToolDefinitions(): List<com.markettwits.aichallenge.Tool> {
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
     * Check if RAG system is ready
     */
    fun isRagReady(): Boolean {
        return runBlocking {
            try {
                ragQueryService.isReady()
            } catch (e: Exception) {
                logger.warn("RAG system check failed", e)
                false
            }
        }
    }

    /**
     * Get system status
     */
    fun getStatus(): Map<String, Any> {
        val toolsList = mutableListOf<Map<String, String>>()
        for (tool in registry.getAllTools()) {
            toolsList.add(
                mapOf(
                    "name" to tool.name,
                    "type" to tool.type.toString()
                )
            )
        }

        return mapOf(
            "toolsAvailable" to registry.getToolCount(),
            "ragReady" to isRagReady(),
            "tools" to toolsList
        )
    }

    /**
     * Get tool registry
     */
    fun getRegistry(): ToolRegistry = registry

    /**
     * Get tool executor
     */
    fun getExecutor(): ToolExecutor = executor
}

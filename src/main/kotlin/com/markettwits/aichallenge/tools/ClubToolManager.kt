package com.markettwits.aichallenge.tools

import com.markettwits.aichallenge.rag.RAGQueryService
import com.markettwits.aichallenge.sportsauce.club.SportSauceClubsNetworkApiBase
import com.markettwits.aichallenge.tools.core.ToolExecutor
import com.markettwits.aichallenge.tools.core.ToolRegistry
import com.markettwits.aichallenge.tools.implementations.club.*
import org.slf4j.LoggerFactory

/**
 * Manager for SportSauce Club support tools
 * Orchestrates RAG and MCP tools for club support assistant
 */
class ClubToolManager(
    private val ragQueryService: RAGQueryService,
    private val apiClient: SportSauceClubsNetworkApiBase,
) {
    private val logger = LoggerFactory.getLogger(ClubToolManager::class.java)

    val registry = ToolRegistry()
    val executor = ToolExecutor(registry)

    init {
        initializeTools()
    }

    private fun initializeTools() {
        logger.info("🔧 Initializing Club Support Tools...")

        // RAG tool for documentation search
        val searchDocsTool = SearchClubDocumentationTool(ragQueryService)
        registry.register(searchDocsTool)
        logger.info("   ✅ Registered: ${searchDocsTool.name} (${searchDocsTool.type})")

        // MCP tools for API data
        val trainersTool = GetTrainersInfoTool(apiClient)
        registry.register(trainersTool)
        logger.info("   ✅ Registered: ${trainersTool.name} (${trainersTool.type})")

        val workoutsTool = GetWorkoutsInfoTool(apiClient)
        registry.register(workoutsTool)
        logger.info("   ✅ Registered: ${workoutsTool.name} (${workoutsTool.type})")

        val scheduleTool = GetScheduleTool(apiClient)
        registry.register(scheduleTool)
        logger.info("   ✅ Registered: ${scheduleTool.name} (${scheduleTool.type})")

        val subscriptionTool = GetSubscriptionInfoTool(apiClient)
        registry.register(subscriptionTool)
        logger.info("   ✅ Registered: ${subscriptionTool.name} (${subscriptionTool.type})")

        val questionsTool = GetClubQuestionsTool(apiClient)
        registry.register(questionsTool)
        logger.info("   ✅ Registered: ${questionsTool.name} (${questionsTool.type})")

        val settingsTool = GetClubSettingsTool(apiClient)
        registry.register(settingsTool)
        logger.info("   ✅ Registered: ${settingsTool.name} (${settingsTool.type})")

        logger.info("✅ Club Tool System initialized with ${registry.getToolCount()} tools")
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
     * Get system status
     */
    suspend fun getSystemStatus(): Map<String, Any> {
        val isRagReady = try {
            ragQueryService.isReady()
        } catch (e: Exception) {
            false
        }

        val ragStats = if (isRagReady) {
            ragQueryService.getStats()
        } else {
            emptyMap()
        }

        return mapOf(
            "toolsRegistered" to registry.getToolCount(),
            "ragReady" to isRagReady,
            "ragStats" to ragStats,
            "tools" to registry.getAllTools().map {
                mapOf(
                    "name" to it.name,
                    "type" to it.type.toString()
                )
            }
        )
    }

}

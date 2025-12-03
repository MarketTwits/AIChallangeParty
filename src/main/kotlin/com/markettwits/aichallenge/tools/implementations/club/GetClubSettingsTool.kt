package com.markettwits.aichallenge.tools.implementations.club

import com.markettwits.aichallenge.sportsauce.club.SportSauceClubsNetworkApiBase
import com.markettwits.aichallenge.tools.core.Tool
import com.markettwits.aichallenge.tools.core.ToolResult
import com.markettwits.aichallenge.tools.core.ToolSchema
import com.markettwits.aichallenge.tools.core.ToolType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.slf4j.LoggerFactory

/**
 * MCP tool for getting club settings and configuration from SportSauce Club API
 */
class GetClubSettingsTool(
    private val apiClient: SportSauceClubsNetworkApiBase,
) : Tool {
    private val logger = LoggerFactory.getLogger(GetClubSettingsTool::class.java)

    override val name: String = "get_club_settings"
    override val description: String = """
        Get club configuration and settings from SportSauce Club.
        Returns general club information, contacts, and configuration.
        Use this when user asks about:
        - Club contact information
        - General club details
        - Club policies or rules
    """.trimIndent()

    override val type: ToolType = ToolType.MCP
    override val schema: ToolSchema = ToolSchema(
        properties = buildJsonObject {
            // No parameters required - returns all settings
        },
        required = emptyList()
    )

    override suspend fun execute(params: JsonObject): ToolResult {
        return try {
            logger.info("Fetching club settings from SportSauce API")

            val settings = apiClient.clubSettings()

            if (settings.isEmpty()) {
                return ToolResult.Success(
                    data = "No club settings found in the system.",
                    metadata = mapOf("count" to 0)
                )
            }

            // Format settings information for LLM
            val formattedResult = buildString {
                appendLine("⚙️ SportSauce Club - Настройки и информация")
                appendLine("=".repeat(50))
                appendLine()
                appendLine("Всего настроек: ${settings.size}")
                appendLine()

                settings.forEach { setting ->
                    appendLine("🔧 ${setting.key}")
                    appendLine("   Название: ${setting.name}")

                    setting.description?.let {
                        if (it.isNotBlank()) {
                            appendLine("   Описание: $it")
                        }
                    }

                    appendLine()
                }
            }

            ToolResult.Success(
                data = formattedResult,
                metadata = mapOf(
                    "toolType" to "club_settings",
                    "settingsCount" to settings.size,
                    "settingKeys" to settings.map { it.key }
                )
            )
        } catch (e: Exception) {
            logger.error("Error fetching club settings", e)
            ToolResult.Error(
                message = "Failed to fetch club settings: ${e.message}",
                code = "API_ERROR"
            )
        }
    }

    override fun validateParams(params: JsonObject): String? {
        // No parameters required
        return null
    }
}

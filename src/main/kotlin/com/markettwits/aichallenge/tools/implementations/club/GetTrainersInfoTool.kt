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
 * MCP tool for getting trainers information from SportSauce Club API
 */
class GetTrainersInfoTool(
    private val apiClient: SportSauceClubsNetworkApiBase,
) : Tool {
    private val logger = LoggerFactory.getLogger(GetTrainersInfoTool::class.java)

    override val name: String = "get_club_trainers"
    override val description: String = """
        Get information about all trainers in SportSauce Club.
        Returns list of trainers with their names, descriptions, sports they train, and contact information.
        Use this when user asks about:
        - Who are the trainers
        - Trainer contact information
        - What sports each trainer specializes in
        - Trainer background and experience
    """.trimIndent()

    override val type: ToolType = ToolType.MCP
    override val schema: ToolSchema = ToolSchema(
        properties = buildJsonObject {
            // No parameters required - returns all trainers
        },
        required = emptyList()
    )

    override suspend fun execute(params: JsonObject): ToolResult {
        return try {
            logger.info("Fetching trainers information from SportSauce API")

            val trainers = apiClient.trainers()

            if (trainers.isEmpty()) {
                return ToolResult.Success(
                    data = "No trainers found in the system.",
                    metadata = mapOf("count" to 0)
                )
            }

            // Format trainers information for LLM
            val formattedResult = buildString {
                appendLine("👥 SportSauce Club Trainers")
                appendLine("=".repeat(50))
                appendLine()
                appendLine("Total trainers: ${trainers.size}")
                appendLine()

                trainers.forEach { trainer ->
                    appendLine("🏃 ${trainer.name} ${trainer.surname}")
                    appendLine("   ID: ${trainer.id}")
                    appendLine("   Описание: ${trainer.description}")

                    if (trainer.kindOfSports.isNotEmpty()) {
                        appendLine("   Виды спорта: ${trainer.kindOfSports.joinToString(", ") { it.name }}")
                    }

                    // Contact information
                    val contacts = mutableListOf<String>()
                    trainer.telegram?.let { contacts.add("Telegram: $it") }
                    trainer.inst?.let { contacts.add("Instagram: $it") }
                    trainer.vk?.let { contacts.add("VK: $it") }
                    trainer.facebook?.let { contacts.add("Facebook: $it") }
                    trainer.twitter?.let { contacts.add("Twitter: $it") }

                    if (contacts.isNotEmpty()) {
                        appendLine("   Контакты: ${contacts.joinToString(", ")}")
                    }

                    appendLine()
                }
            }

            ToolResult.Success(
                data = formattedResult,
                metadata = mapOf(
                    "toolType" to "club_trainers",
                    "trainersCount" to trainers.size,
                    "trainerIds" to trainers.map { it.id }
                )
            )
        } catch (e: Exception) {
            logger.error("Error fetching trainers information", e)
            ToolResult.Error(
                message = "Failed to fetch trainers information: ${e.message}",
                code = "API_ERROR"
            )
        }
    }

    override fun validateParams(params: JsonObject): String? {
        // No parameters required
        return null
    }
}

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
 * MCP tool for getting workouts/training types information from SportSauce Club API
 */
class GetWorkoutsInfoTool(
    private val apiClient: SportSauceClubsNetworkApiBase,
) : Tool {
    private val logger = LoggerFactory.getLogger(GetWorkoutsInfoTool::class.java)

    override val name: String = "get_club_workouts"
    override val description: String = """
        Get information about available workout types in SportSauce Club.
        Returns list of workout/training types with descriptions.
        Use this when user asks about:
        - What types of training are available
        - Training categories or groups
        - Specific workout descriptions
    """.trimIndent()

    override val type: ToolType = ToolType.MCP
    override val schema: ToolSchema = ToolSchema(
        properties = buildJsonObject {
            // No parameters required - returns all workouts
        },
        required = emptyList()
    )

    override suspend fun execute(params: JsonObject): ToolResult {
        return try {
            logger.info("Fetching workouts information from SportSauce API")

            val workouts = apiClient.workout()

            if (workouts.isEmpty()) {
                return ToolResult.Success(
                    data = "No workout types found in the system.",
                    metadata = mapOf("count" to 0)
                )
            }

            // Format workouts information for LLM
            val formattedResult = buildString {
                appendLine("🏋️ SportSauce Club - Типы тренировок")
                appendLine("=".repeat(50))
                appendLine()
                appendLine("Всего типов тренировок: ${workouts.size}")
                appendLine()

                workouts.forEach { workout ->
                    appendLine("📋 ${workout.type}")
                    appendLine("   ID: ${workout.id}")
                    appendLine("   Описание: ${workout.description}")
                    appendLine()
                }
            }

            ToolResult.Success(
                data = formattedResult,
                metadata = mapOf(
                    "toolType" to "club_workouts",
                    "workoutsCount" to workouts.size,
                    "workoutIds" to workouts.map { it.id },
                    "workoutTypes" to workouts.map { it.type }
                )
            )
        } catch (e: Exception) {
            logger.error("Error fetching workouts information", e)
            ToolResult.Error(
                message = "Failed to fetch workouts information: ${e.message}",
                code = "API_ERROR"
            )
        }
    }

    override fun validateParams(params: JsonObject): String? {
        // No parameters required
        return null
    }
}

package com.markettwits.aichallenge.tools.implementations.club

import com.markettwits.aichallenge.sportsauce.club.SportSauceClubsNetworkApiBase
import com.markettwits.aichallenge.tools.core.Tool
import com.markettwits.aichallenge.tools.core.ToolResult
import com.markettwits.aichallenge.tools.core.ToolSchema
import com.markettwits.aichallenge.tools.core.ToolType
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

/**
 * MCP tool for getting training schedule from SportSauce Club API
 */
class GetScheduleTool(
    private val apiClient: SportSauceClubsNetworkApiBase,
) : Tool {
    private val logger = LoggerFactory.getLogger(GetScheduleTool::class.java)

    override val name: String = "get_club_schedule"
    override val description: String = """
        Get training schedule from SportSauce Club.
        Can filter by specific workout type if needed.
        Returns schedule with dates, times, locations, trainers, and descriptions.
        Use this when user asks about:
        - Training schedule or timetable
        - When specific workouts happen
        - Where trainings take place
        - Which trainer leads specific sessions
    """.trimIndent()

    override val type: ToolType = ToolType.MCP
    override val schema: ToolSchema = ToolSchema(
        properties = buildJsonObject {
            put("workoutId", buildJsonObject {
                put("type", "integer")
                put(
                    "description",
                    "Optional: Filter schedule by specific workout type ID. Leave empty to get all schedule."
                )
            })
        },
        required = emptyList()
    )

    override suspend fun execute(params: JsonObject): ToolResult {
        return try {
            val workoutId = params["workoutId"]?.jsonPrimitive?.intOrNull

            logger.info("Fetching schedule from SportSauce API" + if (workoutId != null) " for workout ID: $workoutId" else "")

            val schedule = apiClient.schedule(workoutId)

            if (schedule.isEmpty()) {
                return ToolResult.Success(
                    data = if (workoutId != null) {
                        "No schedule found for workout ID: $workoutId"
                    } else {
                        "No schedule entries found."
                    },
                    metadata = mapOf("count" to 0)
                )
            }

            // Format schedule information for LLM
            val formattedResult = buildString {
                appendLine("📅 SportSauce Club - Расписание тренировок")
                appendLine("=".repeat(50))
                appendLine()
                if (workoutId != null) {
                    appendLine("Фильтр по типу тренировки ID: $workoutId")
                    appendLine()
                }
                appendLine("Всего записей в расписании: ${schedule.size}")
                appendLine()

                // Group by weekday if available
                val grouped = schedule.groupBy { it.weekday ?: "Не указан день недели" }

                grouped.forEach { (weekday, entries) ->
                    appendLine("📌 $weekday")
                    appendLine("-".repeat(50))

                    entries.forEach { entry ->
                        appendLine("   🕐 ${entry.startDate}")
                        appendLine("   📍 ${entry.address}")

                        entry.workout?.let {
                            appendLine("   🏋️ ${it.type}: ${it.description}")
                        }

                        entry.kindOfSport?.let {
                            appendLine("   🎯 Вид спорта: ${it.name}")
                        }

                        if (entry.trainers.isNotEmpty()) {
                            val trainerNames = entry.trainers.joinToString(", ") {
                                "${it.name} ${it.surname}"
                            }
                            appendLine("   👨‍🏫 Тренеры: $trainerNames")
                        }

                        if (entry.description.isNotBlank()) {
                            appendLine("   📝 ${entry.description}")
                        }

                        appendLine()
                    }
                }
            }

            ToolResult.Success(
                data = formattedResult,
                metadata = mapOf(
                    "toolType" to "club_schedule",
                    "scheduleCount" to schedule.size,
                    "workoutIdFilter" to (workoutId ?: "all"),
                    "uniqueLocations" to schedule.map { it.address }.distinct(),
                    "uniqueWeekdays" to schedule.mapNotNull { it.weekday }.distinct()
                )
            )
        } catch (e: Exception) {
            logger.error("Error fetching schedule", e)
            ToolResult.Error(
                message = "Failed to fetch schedule: ${e.message}",
                code = "API_ERROR"
            )
        }
    }

    override fun validateParams(params: JsonObject): String? {
        val workoutId = params["workoutId"]?.jsonPrimitive?.intOrNull

        if (params.containsKey("workoutId") && workoutId == null) {
            // Key exists but value is not a valid integer
            val value = params["workoutId"]?.jsonPrimitive?.content
            if (value != null && value.isNotBlank()) {
                return "workoutId must be a valid integer"
            }
        }

        return null
    }
}

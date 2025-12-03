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
 * MCP tool for getting frequently asked questions from SportSauce Club API
 */
class GetClubQuestionsTool(
    private val apiClient: SportSauceClubsNetworkApiBase,
) : Tool {
    private val logger = LoggerFactory.getLogger(GetClubQuestionsTool::class.java)

    override val name: String = "get_club_faq"
    override val description: String = """
        Get frequently asked questions (FAQ) from SportSauce Club.
        Returns common questions with their answers.
        Use this when user has general questions about the club that might be in FAQ.
    """.trimIndent()

    override val type: ToolType = ToolType.MCP
    override val schema: ToolSchema = ToolSchema(
        properties = buildJsonObject {
            // No parameters required - returns all FAQ
        },
        required = emptyList()
    )

    override suspend fun execute(params: JsonObject): ToolResult {
        return try {
            logger.info("Fetching FAQ from SportSauce API")

            val questions = apiClient.questions()

            if (questions.isEmpty()) {
                return ToolResult.Success(
                    data = "No FAQ entries found in the system.",
                    metadata = mapOf("count" to 0)
                )
            }

            // Format FAQ information for LLM
            val formattedResult = buildString {
                appendLine("❓ SportSauce Club - Часто задаваемые вопросы")
                appendLine("=".repeat(50))
                appendLine()
                appendLine("Всего вопросов: ${questions.size}")
                appendLine()

                questions.forEachIndexed { index, question ->
                    appendLine("Q${index + 1}: ${question.question}")
                    appendLine("A${index + 1}: ${question.answer}")
                    appendLine()
                }
            }

            ToolResult.Success(
                data = formattedResult,
                metadata = mapOf(
                    "toolType" to "club_faq",
                    "questionsCount" to questions.size,
                    "questionIds" to questions.map { it.id }
                )
            )
        } catch (e: Exception) {
            logger.error("Error fetching FAQ", e)
            ToolResult.Error(
                message = "Failed to fetch FAQ: ${e.message}",
                code = "API_ERROR"
            )
        }
    }

    override fun validateParams(params: JsonObject): String? {
        // No parameters required
        return null
    }
}

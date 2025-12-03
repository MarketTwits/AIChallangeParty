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
 * MCP tool for getting subscription/membership information from SportSauce Club API
 */
class GetSubscriptionInfoTool(
    private val apiClient: SportSauceClubsNetworkApiBase,
) : Tool {
    private val logger = LoggerFactory.getLogger(GetSubscriptionInfoTool::class.java)

    override val name: String = "get_club_subscriptions"
    override val description: String = """
        Get information about available subscriptions and membership plans in SportSauce Club.
        Returns subscription options with pricing, benefits, and details.
        Use this when user asks about:
        - Membership costs or pricing
        - Subscription plans or types
        - Club membership benefits
        - How to join the club
    """.trimIndent()

    override val type: ToolType = ToolType.MCP
    override val schema: ToolSchema = ToolSchema(
        properties = buildJsonObject {
            // No parameters required - returns all subscriptions
        },
        required = emptyList()
    )

    override suspend fun execute(params: JsonObject): ToolResult {
        return try {
            logger.info("Fetching subscription information from SportSauce API")

            val subscriptions = apiClient.subscription()

            if (subscriptions.isEmpty()) {
                return ToolResult.Success(
                    data = "No subscription plans found in the system.",
                    metadata = mapOf("count" to 0)
                )
            }

            // Format subscriptions information for LLM
            val formattedResult = buildString {
                appendLine("💳 SportSauce Club - Абонементы и подписки")
                appendLine("=".repeat(50))
                appendLine()
                appendLine("Всего групп абонементов: ${subscriptions.size}")
                appendLine()

                subscriptions.forEach { group ->
                    appendLine("📂 Группа: ${group.name}")
                    appendLine("   По умолчанию: ${if (group.isDefault) "Да" else "Нет"}")
                    appendLine()

                    if (group.subscription.isNotEmpty()) {
                        appendLine("   Доступные абонементы:")
                        group.subscription.forEach { subscription ->
                            appendLine("   📦 ${subscription.name}")
                            appendLine("      ID: ${subscription.id}")
                            appendLine("      💰 Цена: ${subscription.price} руб.")
                            appendLine("      📝 Описание: ${subscription.description}")
                            appendLine("      🎨 Цвет: ${subscription.color.name}")
                            appendLine("      Тип: ${subscription.type}")

                            subscription.discount?.let {
                                appendLine("      🎁 Скидка: $it%")
                            }

                            subscription.maxAmount?.let {
                                appendLine("      📊 Максимум: $it")
                            }

                            appendLine()
                        }
                    }

                    appendLine()
                }
            }

            ToolResult.Success(
                data = formattedResult,
                metadata = mapOf(
                    "toolType" to "club_subscriptions",
                    "subscriptionGroupsCount" to subscriptions.size,
                    "totalSubscriptions" to subscriptions.sumOf { it.subscription.size }
                )
            )
        } catch (e: Exception) {
            logger.error("Error fetching subscription information", e)
            ToolResult.Error(
                message = "Failed to fetch subscription information: ${e.message}",
                code = "API_ERROR"
            )
        }
    }

    override fun validateParams(params: JsonObject): String? {
        // No parameters required
        return null
    }
}

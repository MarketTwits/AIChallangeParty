package com.markettwits.aichallenge.codereview

import com.markettwits.aichallenge.AnthropicClient
import com.markettwits.aichallenge.ContentBlock
import com.markettwits.aichallenge.Message
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

/**
 * Client for generating code reviews using Claude AI
 */
class ClaudeCodeReviewClient(
    private val anthropicClient: AnthropicClient,
) {
    private val logger = LoggerFactory.getLogger(ClaudeCodeReviewClient::class.java)

    private val codeReviewSystemPrompt = """
        You are an expert code reviewer with deep knowledge of software engineering best practices.
        Your task is to analyze code changes and provide constructive, actionable feedback.

        When reviewing code, consider:
        1. **Security**: Look for vulnerabilities, authentication issues, input validation
        2. **Performance**: Identify inefficient algorithms, N+1 queries, unnecessary loops
        3. **Architecture**: Check SOLID principles, separation of concerns, proper layering
        4. **Best Practices**: Code readability, naming conventions, error handling, logging
        5. **Bugs**: Potential runtime errors, edge cases, null pointer exceptions
        6. **Testing**: Identify code that needs test coverage

        For each issue found, provide:
        - Severity level (CRITICAL, HIGH, MEDIUM, LOW, INFO)
        - Category (security, performance, architecture, best_practices, bugs, testing)
        - Clear description of the problem
        - Actionable suggestion for improvement

        Be constructive and educational. Explain WHY something is a problem and HOW to fix it.

        Format your response as a JSON object with the following structure:
        {
          "summary": "Brief overall assessment of the changes",
          "findings": [
            {
              "severity": "HIGH",
              "category": "security",
              "file": "path/to/file.kt",
              "line": 42,
              "title": "Short title of the issue",
              "description": "Detailed description of the problem",
              "suggestion": "How to fix it"
            }
          ],
          "recommendations": [
            "General recommendation 1",
            "General recommendation 2"
          ]
        }

        If the code looks good, still provide a summary and any minor improvements.
    """.trimIndent()

    /**
     * Generate code review for given diff and context
     */
    suspend fun generateReview(
        diff: String,
        changedFiles: String,
        ragContext: List<String>,
        analysisTypes: List<String>,
    ): CodeReviewResponse {
        try {
            logger.info("Generating code review with Claude AI")

            val contextSection = if (ragContext.isNotEmpty()) {
                """

                **Project Context from Documentation:**
                ${ragContext.joinToString("\n\n") { "- $it" }}

                """.trimIndent()
            } else {
                ""
            }

            val userMessage = """
                Please review the following Pull Request changes.

                **Analysis Focus:** ${analysisTypes.joinToString(", ")}
                $contextSection

                **Changed Files:**
                $changedFiles

                **Diff:**
                ```
                $diff
                ```

                Please provide a comprehensive code review in JSON format as specified.
            """.trimIndent()

            val messages = listOf(
                Message(
                    role = "user",
                    content = listOf(
                        ContentBlock(
                            type = "text",
                            text = userMessage
                        )
                    )
                )
            )

            val response = anthropicClient.sendMessage(
                messages = messages,
                systemPrompt = codeReviewSystemPrompt,
                temperature = 0.3  // Lower temperature for more focused, consistent reviews
            )

            // Parse the response
            val content = response.content.firstOrNull()?.text ?: ""
            logger.debug("Claude response: $content")

            return parseCodeReviewResponse(content)
        } catch (e: Exception) {
            logger.error("Error generating code review with Claude", e)
            throw RuntimeException("Failed to generate code review: ${e.message}", e)
        }
    }

    /**
     * Parse Claude's response into structured CodeReviewResponse
     */
    private fun parseCodeReviewResponse(content: String): CodeReviewResponse {
        return try {
            // Try to extract JSON from the response
            val jsonText = extractJson(content)
            val json = Json { ignoreUnknownKeys = true }

            val jsonElement = json.parseToJsonElement(jsonText)
            val jsonObject = jsonElement.jsonObject

            val summary = jsonObject["summary"]?.jsonPrimitive?.contentOrNull ?: "No summary provided"

            val findings = jsonObject["findings"]?.jsonArray?.mapNotNull { findingElement ->
                try {
                    val finding = findingElement.jsonObject
                    CodeReviewFinding(
                        severity = finding["severity"]?.jsonPrimitive?.contentOrNull ?: "INFO",
                        category = finding["category"]?.jsonPrimitive?.contentOrNull ?: "general",
                        file = finding["file"]?.jsonPrimitive?.contentOrNull,
                        line = finding["line"]?.jsonPrimitive?.intOrNull,
                        title = finding["title"]?.jsonPrimitive?.contentOrNull ?: "Issue found",
                        description = finding["description"]?.jsonPrimitive?.contentOrNull ?: "",
                        suggestion = finding["suggestion"]?.jsonPrimitive?.contentOrNull
                    )
                } catch (e: Exception) {
                    logger.warn("Could not parse finding: ${findingElement}", e)
                    null
                }
            } ?: emptyList()

            val recommendations = jsonObject["recommendations"]?.jsonArray?.mapNotNull {
                it.jsonPrimitive.contentOrNull
            } ?: emptyList()

            CodeReviewResponse(
                summary = summary,
                findings = findings,
                recommendations = recommendations
            )
        } catch (e: Exception) {
            logger.error("Could not parse Claude response as JSON", e)
            // Fallback: return the raw content as summary
            CodeReviewResponse(
                summary = content,
                findings = emptyList(),
                recommendations = listOf("Review the raw output for details")
            )
        }
    }

    /**
     * Extract JSON from markdown code blocks or raw text
     */
    private fun extractJson(text: String): String {
        // Try to find JSON in markdown code block
        val jsonBlockRegex = "```(?:json)?\\s*\\n(.*?)\\n```".toRegex(RegexOption.DOT_MATCHES_ALL)
        val match = jsonBlockRegex.find(text)

        return if (match != null) {
            match.groupValues[1].trim()
        } else {
            // Try to find JSON object directly
            val jsonStart = text.indexOf('{')
            val jsonEnd = text.lastIndexOf('}')

            if (jsonStart != -1 && jsonEnd != -1 && jsonEnd > jsonStart) {
                text.substring(jsonStart, jsonEnd + 1)
            } else {
                text.trim()
            }
        }
    }
}

/**
 * Response from Claude code review
 */
data class CodeReviewResponse(
    val summary: String,
    val findings: List<CodeReviewFinding>,
    val recommendations: List<String>,
)

/**
 * Individual code review finding from Claude
 */
data class CodeReviewFinding(
    val severity: String,
    val category: String,
    val file: String?,
    val line: Int?,
    val title: String,
    val description: String,
    val suggestion: String?,
)

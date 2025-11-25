package com.markettwits.aichallenge.rag

import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

/**
 * Comparison result between RAG and non-RAG responses
 */
@Serializable
data class RAGComparisonResult(
    val question: String,
    val withRAG: RAGResponse,
    val withoutRAG: RAGResponse,
    val analysis: ComparisonAnalysis,
)

/**
 * RAG Response details
 */
@Serializable
data class RAGResponse(
    val answer: String,
    val answerLength: Int,
    val retrievedChunksCount: Int,
    val contextLength: Int,
    val topSources: List<String>,
)

/**
 * Analysis comparing the two responses
 */
@Serializable
data class ComparisonAnalysis(
    val ragHelpful: Boolean,
    val reason: String,
    val specificityComparison: String,
    val factsFromContext: List<String>,
)

/**
 * RAG Comparison Service
 * Compares responses with and without RAG to evaluate effectiveness
 */
class RAGComparisonService(
    private val ragQueryService: RAGQueryService,
) {
    private val logger = LoggerFactory.getLogger(RAGComparisonService::class.java)

    /**
     * Compare RAG vs non-RAG responses for a given question
     * @param question User's question
     * @param topK Number of chunks to retrieve for RAG (default: 5)
     * @return RAGComparisonResult with both responses and analysis
     */
    suspend fun compare(question: String, topK: Int = 5): RAGComparisonResult {
        logger.info("Starting RAG comparison for question: $question")

        // Step 1: Get response WITHOUT RAG
        logger.debug("Step 1: Generating response WITHOUT RAG")
        val resultWithoutRAG = try {
            ragQueryService.queryWithoutRAG(question)
        } catch (e: Exception) {
            logger.error("Failed to generate response without RAG", e)
            throw Exception("Failed to generate response without RAG: ${e.message}")
        }

        // Step 2: Get response WITH RAG
        logger.debug("Step 2: Generating response WITH RAG")
        val resultWithRAG = try {
            ragQueryService.queryWithRAG(question, topK)
        } catch (e: Exception) {
            logger.error("Failed to generate response with RAG", e)
            throw Exception("Failed to generate response with RAG: ${e.message}")
        }

        // Step 3: Analyze the differences
        logger.debug("Step 3: Analyzing differences")
        val analysis = analyzeResponses(question, resultWithRAG, resultWithoutRAG)

        logger.info("RAG comparison completed successfully")

        return RAGComparisonResult(
            question = question,
            withRAG = RAGResponse(
                answer = resultWithRAG.answer,
                answerLength = resultWithRAG.answer.length,
                retrievedChunksCount = resultWithRAG.retrievedChunks.size,
                contextLength = resultWithRAG.contextUsed.length,
                topSources = resultWithRAG.retrievedChunks
                    .take(3)
                    .map { "${it.sourceFile} (similarity: ${"%.3f".format(it.similarity)})" }
            ),
            withoutRAG = RAGResponse(
                answer = resultWithoutRAG.answer,
                answerLength = resultWithoutRAG.answer.length,
                retrievedChunksCount = 0,
                contextLength = 0,
                topSources = emptyList()
            ),
            analysis = analysis
        )
    }

    /**
     * Analyze the responses and determine if RAG was helpful
     * @param question The original question
     * @param withRAG RAG result
     * @param withoutRAG Non-RAG result
     * @return ComparisonAnalysis
     */
    private fun analyzeResponses(
        question: String,
        withRAG: RAGQueryResult,
        withoutRAG: RAGQueryResult,
    ): ComparisonAnalysis {
        // Check if RAG provided specific information
        val hasSpecificContext = withRAG.retrievedChunks.isNotEmpty()

        // Extract potential facts from context
        val factsFromContext = if (hasSpecificContext) {
            extractKeyFacts(withRAG.retrievedChunks)
        } else {
            emptyList()
        }

        // Compare answer lengths and specificity
        val ragAnswerLength = withRAG.answer.length
        val noRagAnswerLength = withoutRAG.answer.length
        val lengthDifference = ragAnswerLength - noRagAnswerLength

        // Determine if RAG was helpful
        val ragHelpful = when {
            !hasSpecificContext -> {
                // No relevant context found
                false
            }

            withRAG.answer.contains("context doesn't contain", ignoreCase = true) -> {
                // LLM couldn't find answer in context
                false
            }

            factsFromContext.isNotEmpty() -> {
                // RAG provided specific facts
                true
            }

            lengthDifference > 50 -> {
                // RAG answer is significantly longer (more detailed)
                true
            }

            else -> {
                // Default: assume helpful if context was used
                hasSpecificContext
            }
        }

        // Generate reason
        val reason = when {
            !hasSpecificContext -> {
                "No relevant chunks found in the knowledge base for this question."
            }

            ragHelpful && factsFromContext.isNotEmpty() -> {
                "RAG provided specific information from ${withRAG.retrievedChunks.size} relevant documents. " +
                        "The answer includes concrete facts from the knowledge base."
            }

            ragHelpful -> {
                "RAG provided more detailed and contextual information from the knowledge base."
            }

            else -> {
                "RAG retrieved context but couldn't provide more specific information than the general knowledge response."
            }
        }

        // Specificity comparison
        val specificityComparison = when {
            !hasSpecificContext -> {
                "No context available for comparison."
            }

            ragAnswerLength > noRagAnswerLength + 100 -> {
                "RAG answer is significantly more detailed (+${lengthDifference} chars)."
            }

            ragAnswerLength > noRagAnswerLength -> {
                "RAG answer is slightly more detailed (+${lengthDifference} chars)."
            }

            ragAnswerLength < noRagAnswerLength -> {
                "RAG answer is more concise (${lengthDifference} chars)."
            }

            else -> {
                "Both answers are similar in length."
            }
        }

        return ComparisonAnalysis(
            ragHelpful = ragHelpful,
            reason = reason,
            specificityComparison = specificityComparison,
            factsFromContext = factsFromContext
        )
    }

    /**
     * Extract key facts from retrieved chunks
     * Simple heuristic: look for sentences with numbers, specific terms, etc.
     * @param chunks Retrieved chunks
     * @return List of potential facts
     */
    private fun extractKeyFacts(chunks: List<RetrievedChunkInfo>): List<String> {
        val facts = mutableListOf<String>()

        chunks.take(3).forEach { chunk ->
            // Split into sentences
            val sentences = chunk.text.split(Regex("[.!?]"))
                .map { it.trim() }
                .filter { it.length > 20 }

            // Look for sentences with numbers or specific keywords
            sentences.forEach { sentence ->
                val hasNumbers = sentence.contains(Regex("\\d+"))
                val hasPercentage = sentence.contains("%")
                val hasKeywords = sentence.contains(
                    Regex(
                        "(recommend|suggest|should|must|important|key|essential)",
                        RegexOption.IGNORE_CASE
                    )
                )

                if (hasNumbers || hasPercentage || hasKeywords) {
                    if (facts.size < 5) { // Limit to 5 facts
                        facts.add(sentence)
                    }
                }
            }
        }

        return facts.distinct()
    }
}

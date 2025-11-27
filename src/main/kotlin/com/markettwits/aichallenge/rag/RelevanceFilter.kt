package com.markettwits.aichallenge.rag

import org.slf4j.LoggerFactory

/**
 * Relevance filter and reranker for RAG results
 * Filters out low-quality chunks and provides alternative suggestions
 */
class RelevanceFilter(
    private val minSimilarityThreshold: Double = 0.25,  // Minimum similarity to consider relevant
    private val optimalSimilarityThreshold: Double = 0.40, // Threshold for high-quality results
) {
    private val logger = LoggerFactory.getLogger(RelevanceFilter::class.java)

    data class FilteredResults(
        val relevantChunks: List<RetrievedChunk>,
        val filteredOutChunks: List<RetrievedChunk>,
        val qualityScore: Double, // 0-1, where 1 is best
        val suggestion: String,
    )

    /**
     * Filter and rank retrieved chunks based on similarity and relevance
     */
    fun filterAndRank(
        chunks: List<RetrievedChunk>,
        query: String,
    ): FilteredResults {
        if (chunks.isEmpty()) {
            return FilteredResults(
                relevantChunks = emptyList(),
                filteredOutChunks = emptyList(),
                qualityScore = 0.0,
                suggestion = "Не найдено результатов. Попробуйте переформулировать вопрос."
            )
        }

        // Split chunks into relevant and filtered out
        val relevant = chunks.filter { it.similarity >= minSimilarityThreshold }
        val filteredOut = chunks.filter { it.similarity < minSimilarityThreshold }

        // Calculate quality score
        val avgSimilarity = if (relevant.isNotEmpty()) {
            relevant.map { it.similarity }.average()
        } else {
            0.0
        }

        val qualityScore = calculateQualityScore(relevant, avgSimilarity)
        val suggestion = generateSuggestion(relevant, filteredOut, avgSimilarity, qualityScore)

        logger.info("Filtered ${chunks.size} chunks: ${relevant.size} relevant, ${filteredOut.size} filtered out")
        logger.info("Quality score: $qualityScore, Average similarity: $avgSimilarity")

        return FilteredResults(
            relevantChunks = relevant,
            filteredOutChunks = filteredOut,
            qualityScore = qualityScore,
            suggestion = suggestion
        )
    }

    /**
     * Calculate overall quality score (0-1)
     */
    private fun calculateQualityScore(chunks: List<RetrievedChunk>, avgSimilarity: Double): Double {
        if (chunks.isEmpty()) return 0.0

        // Factors:
        // 1. Average similarity (weighted 0.6)
        // 2. Number of relevant chunks (weighted 0.2)
        // 3. Max similarity (weighted 0.2)

        val normalizedAvgSimilarity = (avgSimilarity - minSimilarityThreshold) /
                (1.0 - minSimilarityThreshold)

        val chunkCountScore = minOf(chunks.size / 5.0, 1.0) // Optimal: 5 chunks

        val maxSimilarity = chunks.maxOfOrNull { it.similarity } ?: 0.0
        val normalizedMaxSimilarity = (maxSimilarity - minSimilarityThreshold) /
                (1.0 - minSimilarityThreshold)

        return (normalizedAvgSimilarity * 0.6) +
                (chunkCountScore * 0.2) +
                (normalizedMaxSimilarity * 0.2)
    }

    /**
     * Generate suggestion based on quality
     */
    private fun generateSuggestion(
        relevant: List<RetrievedChunk>,
        filteredOut: List<RetrievedChunk>,
        avgSimilarity: Double,
        qualityScore: Double,
    ): String {
        return when {
            relevant.isEmpty() -> {
                "❌ Не найдено релевантных результатов (все ниже порога $minSimilarityThreshold). " +
                        "Попробуйте переформулировать вопрос или уточнить детали."
            }

            qualityScore >= 0.7 -> {
                "✅ Найдены высококачественные результаты (качество: ${String.format("%.1f", qualityScore * 100)}%)"
            }

            qualityScore >= 0.5 -> {
                "⚠️ Результаты средней релевантности (качество: ${String.format("%.1f", qualityScore * 100)}%). " +
                        if (filteredOut.isNotEmpty()) {
                            "Отфильтровано ${filteredOut.size} менее релевантных чанков. " +
                                    "Хотите посмотреть альтернативные результаты?"
                        } else {
                            "Попробуйте уточнить вопрос для более точных результатов."
                        }
            }

            else -> {
                "⚠️ Низкая релевантность результатов (качество: ${String.format("%.1f", qualityScore * 100)}%). " +
                        "Рекомендуется переформулировать вопрос или использовать другие ключевые слова."
            }
        }
    }

    /**
     * Rerank chunks using heading context boost
     * Boosts chunks that have matching keywords in headingContext
     */
    fun rerankWithHeadingBoost(
        chunks: List<RetrievedChunk>,
        query: String,
        headingBoostFactor: Double = 0.15,
    ): List<RetrievedChunk> {
        val queryKeywords = extractKeywords(query)

        return chunks.map { chunk ->
            val headingMatches = queryKeywords.count { keyword ->
                chunk.headingContext.contains(keyword, ignoreCase = true)
            }

            val boost = if (headingMatches > 0) {
                headingBoostFactor * headingMatches
            } else {
                0.0
            }

            val boostedSimilarity = minOf(chunk.similarity + boost, 1.0)

            chunk.copy(similarity = boostedSimilarity)
        }.sortedByDescending { it.similarity }
    }

    /**
     * Extract keywords from query (simple implementation)
     */
    private fun extractKeywords(text: String): List<String> {
        // Remove common stop words and extract meaningful terms
        val stopWords = setOf(
            "что", "как", "где", "когда", "почему", "какой", "какая", "какие",
            "есть", "это", "в", "на", "с", "по", "для", "из", "о", "и", "а", "но"
        )

        return text.lowercase()
            .split(Regex("\\s+"))
            .filter { it.length > 2 && it !in stopWords }
    }
}

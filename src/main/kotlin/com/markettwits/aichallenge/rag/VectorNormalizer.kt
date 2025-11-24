package com.markettwits.aichallenge.rag

import kotlin.math.sqrt

/**
 * Utilities for normalizing vectors to [0, 1] range and computing similarity
 */
object VectorNormalizer {

    /**
     * Generate a synthetic embedding based on text hash
     * Used as fallback when Ollama is unavailable
     * Creates a 768-dimensional vector (same as nomic-embed-text)
     */
    fun generateSyntheticEmbedding(text: String): List<Double> {
        val seed = text.hashCode().toLong()
        val seededRandom = java.util.Random(seed)
        return (0 until 768).map {
            // Generate value between -1 and 1
            seededRandom.nextDouble() * 2 - 1
        }
    }

    /**
     * Normalize vector to [0, 1] range using min-max normalization
     * Formula: (x - min) / (max - min)
     *
     * @param vector Input vector with arbitrary values
     * @return Normalized vector with values in [0, 1]
     */
    fun normalizeMinMax(vector: List<Double>): List<Double> {
        if (vector.isEmpty()) return emptyList()

        val min = vector.minOrNull() ?: return vector
        val max = vector.maxOrNull() ?: return vector

        val range = max - min
        if (range == 0.0) {
            // All values are the same, return normalized to 0.5
            return vector.map { 0.5 }
        }

        return vector.map { (it - min) / range }
    }

    /**
     * L2 normalize vector (unit norm)
     * Formula: vector / ||vector||
     * Result values will be in approximately [-1, 1] range, normalized to unit length
     *
     * @param vector Input vector
     * @return L2-normalized vector
     */
    fun normalizeL2(vector: List<Double>): List<Double> {
        if (vector.isEmpty()) return emptyList()

        val magnitude = sqrt(vector.sumOf { it * it })
        if (magnitude == 0.0) {
            return vector
        }

        return vector.map { it / magnitude }
    }

    /**
     * Normalize vector to [0, 1] range using sigmoid function
     * Formula: 1 / (1 + e^(-x))
     * Useful for squashing outliers while maintaining relative distances
     *
     * @param vector Input vector
     * @return Vector with values in (0, 1)
     */
    fun normalizeSigmoid(vector: List<Double>): List<Double> {
        return vector.map { 1.0 / (1.0 + Math.exp(-it)) }
    }

    /**
     * Cosine similarity between two vectors
     * Formula: (A · B) / (||A|| * ||B||)
     * Returns value in [-1, 1] range (typically [0, 1] for text embeddings)
     *
     * @param vectorA First vector
     * @param vectorB Second vector
     * @return Cosine similarity score
     */
    fun cosineSimilarity(vectorA: List<Double>, vectorB: List<Double>): Double {
        if (vectorA.size != vectorB.size) {
            throw IllegalArgumentException("Vector dimensions must match")
        }
        if (vectorA.isEmpty()) return 0.0

        // Compute dot product
        val dotProduct = vectorA.zip(vectorB).sumOf { (a, b) -> a * b }

        // Compute magnitudes
        val magnitudeA = sqrt(vectorA.sumOf { it * it })
        val magnitudeB = sqrt(vectorB.sumOf { it * it })

        if (magnitudeA == 0.0 || magnitudeB == 0.0) {
            return 0.0
        }

        return dotProduct / (magnitudeA * magnitudeB)
    }

    /**
     * Euclidean distance between two vectors
     * Formula: sqrt(sum((A_i - B_i)^2))
     *
     * @param vectorA First vector
     * @param vectorB Second vector
     * @return Euclidean distance
     */
    fun euclideanDistance(vectorA: List<Double>, vectorB: List<Double>): Double {
        if (vectorA.size != vectorB.size) {
            throw IllegalArgumentException("Vector dimensions must match")
        }

        return sqrt(vectorA.zip(vectorB).sumOf { (a, b) ->
            val diff = a - b
            diff * diff
        })
    }

    /**
     * Manhattan distance between two vectors
     * Formula: sum(|A_i - B_i|)
     *
     * @param vectorA First vector
     * @param vectorB Second vector
     * @return Manhattan distance
     */
    fun manhattanDistance(vectorA: List<Double>, vectorB: List<Double>): Double {
        if (vectorA.size != vectorB.size) {
            throw IllegalArgumentException("Vector dimensions must match")
        }

        return vectorA.zip(vectorB).sumOf { (a, b) -> kotlin.math.abs(a - b) }
    }
}

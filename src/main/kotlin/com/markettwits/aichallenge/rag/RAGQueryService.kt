package com.markettwits.aichallenge.rag

import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.Database
import org.slf4j.LoggerFactory

/**
 * Result of a RAG query
 */
@Serializable
data class RAGQueryResult(
    val question: String,
    val answer: String,
    val retrievedChunks: List<RetrievedChunkInfo>,
    val contextUsed: String,
    val mode: String, // "rag" or "no-rag"
)

/**
 * Information about a retrieved chunk (without embedding for serialization)
 */
@Serializable
data class RetrievedChunkInfo(
    val text: String,
    val sourceFile: String,
    val chunkIndex: Int,
    val similarity: Double,
)

/**
 * RAG Query Service
 * Implements the full RAG pipeline: question → embedding → search → retrieve → format context
 */
class RAGQueryService(
    private val database: Database,
    private val embeddingClient: OllamaEmbeddingClient,
    private val llmClient: OllamaLLMClient,
    private val vectorStore: VectorStore,
) {
    private val logger = LoggerFactory.getLogger(RAGQueryService::class.java)

    /**
     * Execute RAG query WITH context retrieval
     * @param question User's question
     * @param topK Number of chunks to retrieve (default: 5)
     * @return RAGQueryResult with answer and retrieved chunks
     */
    suspend fun queryWithRAG(question: String, topK: Int = 5): RAGQueryResult {
        logger.info("Processing RAG query: $question")

        // Step 1: Generate embedding for the question
        logger.debug("Step 1: Generating embedding for question")
        val questionEmbedding = try {
            embeddingClient.generateEmbedding(question)
        } catch (e: Exception) {
            logger.error("Failed to generate embedding for question", e)
            throw Exception("Failed to generate embedding: ${e.message}")
        }

        logger.debug("Question embedding generated, vector size: ${questionEmbedding.size}")

        // Step 2: Search for similar chunks in vector store
        logger.debug("Step 2: Searching for top $topK similar chunks")
        val retrievedChunks = try {
            vectorStore.search(database, questionEmbedding, topK)
        } catch (e: Exception) {
            logger.error("Failed to search vector store", e)
            throw Exception("Failed to search vector store: ${e.message}")
        }

        if (retrievedChunks.isEmpty()) {
            logger.warn("No chunks found in vector store")
            return RAGQueryResult(
                question = question,
                answer = "No relevant information found in the knowledge base.",
                retrievedChunks = emptyList(),
                contextUsed = "",
                mode = "rag"
            )
        }

        logger.info(
            "Retrieved ${retrievedChunks.size} chunks with similarities: ${
                retrievedChunks.map {
                    "%.3f".format(
                        it.similarity
                    )
                }
            }"
        )

        // Step 3: Format context from retrieved chunks
        val context = formatContext(retrievedChunks)
        logger.debug("Context formatted, length: ${context.length} chars")

        // Step 4: Generate answer using LLM with context
        logger.debug("Step 4: Generating answer with LLM using context")
        val answer = try {
            llmClient.generateWithContext(question, context)
        } catch (e: Exception) {
            logger.error("Failed to generate answer with LLM", e)
            throw Exception("Failed to generate answer: ${e.message}")
        }

        logger.info("RAG query completed successfully")

        return RAGQueryResult(
            question = question,
            answer = answer,
            retrievedChunks = retrievedChunks.map { chunk ->
                RetrievedChunkInfo(
                    text = chunk.text,
                    sourceFile = chunk.sourceFile,
                    chunkIndex = chunk.chunkIndex,
                    similarity = chunk.similarity
                )
            },
            contextUsed = context,
            mode = "rag"
        )
    }

    /**
     * Execute query WITHOUT RAG (no context retrieval)
     * @param question User's question
     * @return RAGQueryResult with answer only
     */
    suspend fun queryWithoutRAG(question: String): RAGQueryResult {
        logger.info("Processing query WITHOUT RAG: $question")

        val answer = try {
            llmClient.generateWithoutContext(question)
        } catch (e: Exception) {
            logger.error("Failed to generate answer without context", e)
            throw Exception("Failed to generate answer: ${e.message}")
        }

        logger.info("Query without RAG completed successfully")

        return RAGQueryResult(
            question = question,
            answer = answer,
            retrievedChunks = emptyList(),
            contextUsed = "",
            mode = "no-rag"
        )
    }

    /**
     * Format retrieved chunks into a single context string
     * @param chunks List of retrieved chunks
     * @return Formatted context string
     */
    private fun formatContext(chunks: List<RetrievedChunk>): String {
        return chunks.mapIndexed { index, chunk ->
            """
            [Source ${index + 1}] (from ${chunk.sourceFile}, similarity: ${"%.3f".format(chunk.similarity)})
            ${chunk.text}
            """.trimIndent()
        }.joinToString("\n\n---\n\n")
    }

    /**
     * Get statistics about the vector store
     */
    fun getStats(): Map<String, Any> {
        return vectorStore.getStats(database)
    }

    /**
     * Check if the RAG system is ready
     */
    suspend fun isReady(): Boolean {
        return try {
            // Check if vector store has documents
            val stats = getStats()
            val totalChunks = stats["totalChunks"] as? Long ?: 0L

            if (totalChunks == 0L) {
                logger.warn("Vector store is empty")
                return false
            }

            // Check if embedding service is available
            if (!embeddingClient.isAvailable()) {
                logger.warn("Ollama embedding service is not available")
                return false
            }

            // Check if LLM service is available
            if (!llmClient.isAvailable()) {
                logger.warn("Ollama LLM service is not available")
                return false
            }

            logger.info("RAG system is ready (${totalChunks} chunks indexed)")
            true
        } catch (e: Exception) {
            logger.error("Error checking RAG system readiness", e)
            false
        }
    }
}

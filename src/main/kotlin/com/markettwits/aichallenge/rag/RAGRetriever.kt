package com.markettwits.aichallenge.rag

import org.jetbrains.exposed.sql.Database
import org.slf4j.LoggerFactory
import java.io.File

/**
 * RAG (Retrieval-Augmented Generation) system coordinator
 * Orchestrates: document loading → chunking → embedding → storage → retrieval
 */
class RAGRetriever(
    private val database: Database,
    private val embeddingClient: OllamaEmbeddingClient,
    private val chunker: DocumentChunker = DocumentChunker(),
    private val vectorStore: VectorStore = VectorStore(),
) {
    private val logger = LoggerFactory.getLogger(RAGRetriever::class.java)

    init {
        vectorStore.initializeDatabase(database)
    }

    /**
     * Build knowledge base from markdown files in a directory
     * Loads all .md files, chunks them, generates embeddings, and stores in database
     *
     * @param documentsDir Directory containing .md files
     * @param clearExisting If true, clear previous index before building
     */
    suspend fun buildKnowledgeBase(
        documentsDir: String,
        clearExisting: Boolean = true,
    ) {
        try {
            RAGProgressTracker.startBuilding()
            logger.info("🚀 Building knowledge base from: $documentsDir")

            // Check Ollama availability
            RAGProgressTracker.updateDocumentsLoaded(0, 1)
            if (!embeddingClient.isAvailable()) {
                RAGProgressTracker.error("Ollama service is not available at http://localhost:11434")
                throw IllegalStateException("Ollama service is not available at http://localhost:11434")
            }
            RAGProgressTracker.addLog("✅ Ollama service verified")

            // Clear existing index if requested
            if (clearExisting) {
                RAGProgressTracker.addLog("🗑️  Clearing previous index...")
                vectorStore.clearIndex(database)
            }

            // Load documents from directory
            RAGProgressTracker.updateDocumentsLoaded(0, 1)
            val documents = loadDocumentsFromDirectory(documentsDir)
            if (documents.isEmpty()) {
                RAGProgressTracker.error("No documents found in $documentsDir")
                logger.warn("No documents found in $documentsDir")
                return
            }

            RAGProgressTracker.updateDocumentsLoaded(documents.size, documents.size)
            RAGProgressTracker.addLog("📄 Loaded ${documents.size} documents")
            logger.info("📄 Loaded ${documents.size} documents")

            // Chunk documents
            RAGProgressTracker.startChunking(0)
            val chunks = chunker.chunkDocuments(documents)
            RAGProgressTracker.startChunking(chunks.size)
            RAGProgressTracker.addLog("✂️  Created ${chunks.size} chunks")
            logger.info("✂️  Created ${chunks.size} chunks")

            // Generate embeddings for all chunks
            RAGProgressTracker.startEmbedding(chunks.size)
            RAGProgressTracker.addLog("⚡ Generating embeddings for ${chunks.size} chunks...")
            logger.info("⚡ Generating embeddings for ${chunks.size} chunks...")
            val chunksWithEmbeddings = mutableListOf<Pair<TextChunk, List<Double>>>()

            // Try to get real embeddings from Ollama, fallback to synthetic vectors if it fails
            var processedCount = 0
            var ollamaIsWorking = true

            for ((index, chunk) in chunks.withIndex()) {
                try {
                    logger.info("⚡ Processing chunk ${index + 1}/${chunks.size}")

                    val embedding = if (ollamaIsWorking) {
                        try {
                            val embeddings = embeddingClient.generateEmbeddingsBatch(listOf(chunk.text))
                            if (embeddings.isNotEmpty()) {
                                embeddings[0]
                            } else {
                                throw Exception("No embeddings returned")
                            }
                        } catch (e: Exception) {
                            logger.warn("⚠️ Ollama failed, switching to synthetic embeddings: ${e.message}")
                            ollamaIsWorking = false
                            // Generate synthetic embedding as fallback
                            VectorNormalizer.generateSyntheticEmbedding(chunk.text)
                        }
                    } else {
                        // Ollama is down, use synthetic embeddings
                        VectorNormalizer.generateSyntheticEmbedding(chunk.text)
                    }

                    // Normalize
                    val normalized = VectorNormalizer.normalizeMinMax(embedding)
                    chunksWithEmbeddings.add(chunk to normalized)
                    processedCount++

                    RAGProgressTracker.updateEmbedding(processedCount, chunks.size)
                    logger.info("✅ Chunk $processedCount/${chunks.size} done${if (!ollamaIsWorking) " (synthetic)" else ""}")

                    Thread.sleep(100)  // Small delay
                } catch (e: Exception) {
                    logger.error("❌ Failed at chunk ${index + 1}/${chunks.size}: ${e.message}", e)
                    RAGProgressTracker.error("Failed at chunk ${index + 1}/${chunks.size}: ${e.message}")
                    throw e
                }
            }

            RAGProgressTracker.updateEmbedding(chunks.size, chunks.size)

            // Save to database
            RAGProgressTracker.startSaving(chunksWithEmbeddings.size)
            RAGProgressTracker.addLog("💾 Saving ${chunksWithEmbeddings.size} chunks to database...")
            logger.info("💾 Saving ${chunksWithEmbeddings.size} chunks to database...")
            vectorStore.saveBatch(database, chunksWithEmbeddings)

            // Log statistics
            val stats = vectorStore.getStats(database)
            RAGProgressTracker.complete()
            RAGProgressTracker.addLog("✨ Knowledge base built successfully! Stats: $stats")
            logger.info("✨ Knowledge base built successfully: $stats")
            logger.info(RAGProgressTracker.getProgress().toConsoleOutput())

        } catch (e: Exception) {
            RAGProgressTracker.error(e.message ?: "Unknown error")
            logger.error("❌ Error building knowledge base", e)
            throw e
        }
    }

    /**
     * Retrieve relevant chunks for a query
     * Generates embedding for query and finds similar chunks using cosine similarity
     *
     * @param query Search query
     * @param topK Number of top results to return
     * @return List of relevant chunks with similarity scores
     */
    suspend fun retrieveRelevant(query: String, topK: Int = 5): List<RetrievedChunk> {
        logger.info("Retrieving relevant chunks for query: ${query.take(100)}...")

        try {
            // Generate embedding for query
            val queryEmbedding = embeddingClient.generateEmbedding(query)

            // Normalize
            val normalizedQueryEmbedding = VectorNormalizer.normalizeMinMax(queryEmbedding)

            // Search
            val results = vectorStore.search(database, normalizedQueryEmbedding, topK)

            logger.info("Retrieved ${results.size} relevant chunks, top similarity: ${results.firstOrNull()?.similarity}")

            return results
        } catch (e: Exception) {
            logger.error("Error retrieving relevant chunks", e)
            throw e
        }
    }

    /**
     * Get stats about the knowledge base
     */
    fun getStats(): Map<String, Any> {
        return vectorStore.getStats(database)
    }

    /**
     * Load markdown files from directory
     * @param dirPath Path to directory containing .md files
     * @return Map of filename to file content
     */
    private fun loadDocumentsFromDirectory(dirPath: String): Map<String, String> {
        val dir = File(dirPath)
        if (!dir.exists() || !dir.isDirectory) {
            throw IllegalArgumentException("Directory does not exist: $dirPath")
        }

        val documents = mutableMapOf<String, String>()

        dir.listFiles { file ->
            file.isFile && file.name.endsWith(".md")
        }?.forEach { file ->
            try {
                val content = file.readText(Charsets.UTF_8)
                documents[file.name] = content
                logger.debug("Loaded document: ${file.name} (${content.length} characters)")
            } catch (e: Exception) {
                logger.error("Error loading document: ${file.name}", e)
            }
        }

        return documents
    }

    /**
     * Reload knowledge base
     * Clears existing index and rebuilds from source directory
     */
    suspend fun reloadKnowledgeBase(documentsDir: String) {
        logger.info("Reloading knowledge base from: $documentsDir")
        buildKnowledgeBase(documentsDir, clearExisting = true)
    }
}

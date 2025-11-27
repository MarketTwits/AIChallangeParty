package com.markettwits.aichallenge.rag

import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.Database
import java.io.File

/**
 * Script to reindex a single file with improved markdown-aware chunking
 */
fun main() = runBlocking {
    println("=== Reindexing Single File ===")

    // 1. Initialize database
    val dbPath = "data/documents.db"
    val database = Database.connect("jdbc:sqlite:$dbPath", driver = "org.sqlite.JDBC")
    val vectorStore = VectorStore()
    vectorStore.initializeDatabase(database)

    // 2. Clear existing data
    println("Clearing existing data...")
    vectorStore.clearIndex(database)

    // 3. Read the file
    val filePath = "data/farming/1. Основы анатомии.md"
    val file = File(filePath)
    if (!file.exists()) {
        println("ERROR: File not found: $filePath")
        return@runBlocking
    }

    val content = file.readText()
    println("Read file: $filePath (${content.length} characters)")

    // 4. Chunk the document with markdown-aware chunking
    val chunker = DocumentChunker(
        targetChunkSize = 750,
        overlapSize = 75,
        useMarkdownAware = true
    )

    println("Chunking document with markdown-aware strategy...")
    val chunks = chunker.chunkText(content, file.name)
    println("Created ${chunks.size} chunks\n")

    // Display chunks with their heading context
    chunks.forEachIndexed { index, chunk ->
        println("Chunk #$index:")
        println("  Heading: ${chunk.headingContext}")
        println("  Tokens: ~${chunk.tokenCount}")
        println("  Preview: ${chunk.text.take(100)}...")
        println()
    }

    // 5. Generate embeddings
    val embeddingClient = OllamaEmbeddingClient()

    // Check if Ollama is available
    if (!embeddingClient.isAvailable()) {
        println("WARNING: Ollama is not available. Using synthetic embeddings as fallback.")
    }

    println("Generating embeddings for ${chunks.size} chunks...")
    val chunksWithEmbeddings = mutableListOf<Pair<TextChunk, List<Double>>>()

    for ((index, chunk) in chunks.withIndex()) {
        println("Processing chunk ${index + 1}/${chunks.size}...")

        val embedding = try {
            embeddingClient.generateEmbedding(chunk.text)
        } catch (e: Exception) {
            println("  Ollama failed, using synthetic embedding")
            VectorNormalizer.generateSyntheticEmbedding(chunk.text)
        }

        val normalizedEmbedding = VectorNormalizer.normalizeL2(embedding)
        chunksWithEmbeddings.add(chunk to normalizedEmbedding)
    }

    // 6. Save to vector store
    println("\nSaving to vector store...")
    vectorStore.saveBatch(database, chunksWithEmbeddings)

    // 7. Display statistics
    val stats = vectorStore.getStats(database)
    println("\n=== Indexing Complete ===")
    println("Total chunks: ${stats["totalChunks"]}")
    println("Files indexed: ${stats["files"]}")

    embeddingClient.close()
    println("\nDone!")
}

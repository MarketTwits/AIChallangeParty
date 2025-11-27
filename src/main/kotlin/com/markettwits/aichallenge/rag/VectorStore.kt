package com.markettwits.aichallenge.rag

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

data class RetrievedChunk(
    val id: Int,
    val text: String,
    val sourceFile: String,
    val chunkIndex: Int,
    val embedding: List<Double>,
    val similarity: Double,
    val headingContext: String = "",
)

data class StoredDocument(
    val id: Int,
    val chunkText: String,
    val sourceFile: String,
    val chunkIndex: Int,
    val embedding: List<Double>,
    val headingContext: String = "",
)

/**
 * SQLite-based vector store for document chunks and their embeddings
 * Stores chunks with metadata and performs vector similarity search
 */
object DocumentsTable : IntIdTable("documents") {
    val chunkText = text("chunk_text")
    val sourceFile = varchar("source_file", 255)
    val chunkIndex = integer("chunk_index")
    val embedding = text("embedding") // JSON-serialized vector
    val headingContext = text("heading_context").default("") // Markdown heading hierarchy
    val createdAt = long("created_at").default(System.currentTimeMillis())
}

class VectorStore {
    private val logger = LoggerFactory.getLogger(VectorStore::class.java)
    private val json = Json

    /**
     * Initialize database tables
     */
    fun initializeDatabase(database: Database) {
        transaction(database) {
            SchemaUtils.createMissingTablesAndColumns(DocumentsTable)
            logger.info("Vector store initialized")
        }
    }

    /**
     * Save a chunk with its embedding
     *
     * @param database Database connection
     * @param chunk Text chunk to save
     * @param embedding Normalized embedding vector
     */
    fun saveChunk(database: Database, chunk: TextChunk, embedding: List<Double>) {
        transaction(database) {
            DocumentsTable.insert {
                it[chunkText] = chunk.text
                it[sourceFile] = chunk.sourceFile
                it[chunkIndex] = chunk.chunkIndex
                it[this.embedding] = json.encodeToString(embedding)
                it[headingContext] = chunk.headingContext
            }
        }
        logger.debug("Saved chunk ${chunk.chunkIndex} from ${chunk.sourceFile}")
    }

    /**
     * Save multiple chunks with their embeddings (batch operation)
     *
     * @param database Database connection
     * @param chunksWithEmbeddings List of pairs (chunk, embedding)
     */
    fun saveBatch(
        database: Database,
        chunksWithEmbeddings: List<Pair<TextChunk, List<Double>>>,
    ) {
        transaction(database) {
            chunksWithEmbeddings.forEach { (chunk, embedding) ->
                DocumentsTable.insert {
                    it[chunkText] = chunk.text
                    it[sourceFile] = chunk.sourceFile
                    it[chunkIndex] = chunk.chunkIndex
                    it[this.embedding] = json.encodeToString(embedding)
                    it[headingContext] = chunk.headingContext
                }
            }
        }
        logger.info("Batch saved ${chunksWithEmbeddings.size} chunks")
    }

    /**
     * Search for similar chunks using cosine similarity
     *
     * @param database Database connection
     * @param queryEmbedding Embedding of the search query
     * @param topK Number of top results to return
     * @return List of retrieved chunks with similarity scores
     */
    fun search(
        database: Database,
        queryEmbedding: List<Double>,
        topK: Int = 5,
    ): List<RetrievedChunk> {
        return transaction(database) {
            val allDocuments = DocumentsTable.selectAll()
                .map { row ->
                    StoredDocument(
                        id = row[DocumentsTable.id].value,
                        chunkText = row[DocumentsTable.chunkText],
                        sourceFile = row[DocumentsTable.sourceFile],
                        chunkIndex = row[DocumentsTable.chunkIndex],
                        embedding = json.decodeFromString(row[DocumentsTable.embedding]),
                        headingContext = row[DocumentsTable.headingContext]
                    )
                }

            // Compute similarity for each document
            val similarityScores = allDocuments.map { doc ->
                val similarity = VectorNormalizer.cosineSimilarity(queryEmbedding, doc.embedding)
                doc to similarity
            }

            // Sort by similarity and take top K
            val results = similarityScores
                .sortedByDescending { it.second }
                .take(topK)
                .map { (doc, similarity) ->
                    RetrievedChunk(
                        id = doc.id,
                        text = doc.chunkText,
                        sourceFile = doc.sourceFile,
                        chunkIndex = doc.chunkIndex,
                        embedding = doc.embedding,
                        similarity = similarity,
                        headingContext = doc.headingContext
                    )
                }

            logger.debug("Search returned ${results.size} results")
            results
        }
    }

    /**
     * Get all documents from the vector store
     */
    fun getAllDocuments(database: Database): List<StoredDocument> {
        return transaction(database) {
            DocumentsTable.selectAll()
                .map { row ->
                    StoredDocument(
                        id = row[DocumentsTable.id].value,
                        chunkText = row[DocumentsTable.chunkText],
                        sourceFile = row[DocumentsTable.sourceFile],
                        chunkIndex = row[DocumentsTable.chunkIndex],
                        embedding = json.decodeFromString(row[DocumentsTable.embedding]),
                        headingContext = row[DocumentsTable.headingContext]
                    )
                }
        }
    }

    /**
     * Get documents by source file
     */
    fun getDocumentsBySource(database: Database, sourceFile: String): List<StoredDocument> {
        return transaction(database) {
            DocumentsTable.select { DocumentsTable.sourceFile eq sourceFile }
                .map { row ->
                    StoredDocument(
                        id = row[DocumentsTable.id].value,
                        chunkText = row[DocumentsTable.chunkText],
                        sourceFile = row[DocumentsTable.sourceFile],
                        chunkIndex = row[DocumentsTable.chunkIndex],
                        embedding = json.decodeFromString(row[DocumentsTable.embedding]),
                        headingContext = row[DocumentsTable.headingContext]
                    )
                }
        }
    }

    /**
     * Clear all documents from the vector store
     */
    fun clearIndex(database: Database) {
        transaction(database) {
            DocumentsTable.deleteAll()
        }
        logger.info("Vector store cleared")
    }

    /**
     * Get statistics about the vector store
     */
    fun getStats(database: Database): Map<String, Any> {
        return transaction(database) {
            val totalChunks = DocumentsTable.selectAll().count()
            val sources = DocumentsTable.selectAll()
                .map { it[DocumentsTable.sourceFile] }
                .distinct()

            mapOf(
                "totalChunks" to totalChunks,
                "sources" to sources.size,
                "files" to sources
            )
        }
    }
}

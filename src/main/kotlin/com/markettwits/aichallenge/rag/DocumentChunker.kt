package com.markettwits.aichallenge.rag

import org.slf4j.LoggerFactory
import kotlin.math.ceil

data class TextChunk(
    val text: String,
    val sourceFile: String,
    val chunkIndex: Int,
    val startPosition: Int = 0,
    val endPosition: Int = text.length,
) {
    val tokenCount: Int
        get() = estimateTokenCount(text)

    private fun estimateTokenCount(text: String): Int {
        // Approximate: 1 token ≈ 4 characters or 0.75 words
        return ceil(text.length / 4.0).toInt()
    }
}

/**
 * Document chunker for splitting text into overlapping chunks
 * Uses word-based splitting to maintain semantic integrity
 */
class DocumentChunker(
    private val targetChunkSize: Int = 750, // tokens (middle of 500-1000 range)
    private val overlapSize: Int = 75,      // tokens (middle of 50-100 range)
) {
    private val logger = LoggerFactory.getLogger(DocumentChunker::class.java)

    init {
        require(targetChunkSize in 500..1000) { "Target chunk size should be 500-1000 tokens" }
        require(overlapSize in 50..100) { "Overlap size should be 50-100 tokens" }
        require(targetChunkSize > overlapSize) { "Chunk size must be larger than overlap size" }
    }

    /**
     * Split text into overlapping chunks
     * @param text Input text to chunk
     * @param sourceFile Name of source file for metadata
     * @return List of text chunks with metadata
     */
    fun chunkText(text: String, sourceFile: String): List<TextChunk> {
        logger.info("Chunking text from $sourceFile, total length: ${text.length}")

        // Split by sentences first to maintain semantic integrity
        val sentences = splitBySentences(text)
        val chunks = mutableListOf<TextChunk>()

        var currentChunk = StringBuilder()
        var currentTokenCount = 0
        var chunkStartPosition = 0
        var chunkIndex = 0

        for ((sentenceIndex, sentence) in sentences.withIndex()) {
            val sentenceTokens = estimateTokenCount(sentence)

            // If adding this sentence would exceed chunk size (and chunk is not empty)
            if (currentTokenCount + sentenceTokens > targetChunkSize && currentChunk.isNotEmpty()) {
                // Save current chunk
                val chunkText = currentChunk.toString().trim()
                if (chunkText.isNotEmpty()) {
                    chunks.add(
                        TextChunk(
                            text = chunkText,
                            sourceFile = sourceFile,
                            chunkIndex = chunkIndex,
                            startPosition = chunkStartPosition,
                            endPosition = chunkStartPosition + chunkText.length
                        )
                    )
                    chunkIndex++
                    logger.debug("Created chunk $chunkIndex: $sentenceIndex sentences, ~$currentTokenCount tokens")
                }

                // Create overlap: keep last N tokens worth of sentences
                val overlapBuilder = StringBuilder()
                var overlapTokens = 0
                var overlapSentenceCount = 0

                // Walk backwards to find overlap
                var overlapIndex = sentenceIndex - 1
                while (overlapIndex >= 0 && overlapTokens < overlapSize && overlapSentenceCount < 5) {
                    val overlapSentence = sentences[overlapIndex]
                    val overlapSentenceTokens = estimateTokenCount(overlapSentence)
                    if (overlapTokens + overlapSentenceTokens <= overlapSize * 2) { // Allow slight overflow for sentences
                        overlapBuilder.insert(0, overlapSentence + " ")
                        overlapTokens += overlapSentenceTokens
                        overlapSentenceCount++
                    }
                    overlapIndex--
                }

                currentChunk = overlapBuilder
                currentTokenCount = overlapTokens
                chunkStartPosition = chunkStartPosition + chunkText.length - overlapBuilder.length
            }

            currentChunk.append(sentence).append(" ")
            currentTokenCount += sentenceTokens
        }

        // Add final chunk
        if (currentChunk.isNotEmpty()) {
            val finalChunk = currentChunk.toString().trim()
            if (finalChunk.isNotEmpty()) {
                chunks.add(
                    TextChunk(
                        text = finalChunk,
                        sourceFile = sourceFile,
                        chunkIndex = chunkIndex,
                        startPosition = chunkStartPosition,
                        endPosition = chunkStartPosition + finalChunk.length
                    )
                )
                logger.debug("Created final chunk $chunkIndex: ~$currentTokenCount tokens")
            }
        }

        logger.info("Created ${chunks.size} chunks from $sourceFile")
        return chunks
    }

    /**
     * Split document into chunks
     * @param documents Map of filename to text content
     * @return List of text chunks with metadata
     */
    fun chunkDocuments(documents: Map<String, String>): List<TextChunk> {
        val allChunks = mutableListOf<TextChunk>()
        for ((filename, content) in documents) {
            allChunks.addAll(chunkText(content, filename))
        }
        logger.info("Total chunks created: ${allChunks.size}")
        return allChunks
    }

    private fun splitBySentences(text: String): List<String> {
        // Split by common sentence delimiters while preserving punctuation
        val sentenceRegex = Regex("""[.!?]+""")
        val sentences = mutableListOf<String>()
        var lastEnd = 0

        for (match in sentenceRegex.findAll(text)) {
            val sentenceText = text.substring(lastEnd, match.range.last + 1).trim()
            if (sentenceText.isNotEmpty()) {
                sentences.add(sentenceText)
            }
            lastEnd = match.range.last + 1
        }

        // Add remaining text as final sentence if not empty
        val remaining = text.substring(lastEnd).trim()
        if (remaining.isNotEmpty()) {
            sentences.add(remaining)
        }

        return sentences.filter { it.isNotEmpty() }
    }

    private fun estimateTokenCount(text: String): Int {
        // Approximate: 1 token ≈ 4 characters (varies by model)
        return ceil(text.length / 4.0).toInt()
    }
}

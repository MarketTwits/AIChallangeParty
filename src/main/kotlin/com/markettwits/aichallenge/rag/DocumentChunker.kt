package com.markettwits.aichallenge.rag

import org.slf4j.LoggerFactory
import kotlin.math.ceil

data class TextChunk(
    val text: String,
    val sourceFile: String,
    val chunkIndex: Int,
    val startPosition: Int = 0,
    val endPosition: Int = text.length,
    val headingContext: String = "", // Markdown heading hierarchy for context
) {
    val tokenCount: Int
        get() = estimateTokenCount(text)

    private fun estimateTokenCount(text: String): Int {
        // Approximate: 1 token ≈ 4 characters or 0.75 words
        return ceil(text.length / 4.0).toInt()
    }
}

data class MarkdownSection(
    val heading: String,
    val level: Int,
    val content: String,
    val startLine: Int,
)

/**
 * Document chunker for splitting text into overlapping chunks
 * Supports Markdown structure-aware chunking
 */
class DocumentChunker(
    private val targetChunkSize: Int = 750, // tokens (middle of 500-1000 range)
    private val overlapSize: Int = 75,      // tokens (middle of 50-100 range)
    private val useMarkdownAware: Boolean = true, // Enable markdown-aware chunking
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

        // Use markdown-aware chunking if enabled and file is markdown
        if (useMarkdownAware && sourceFile.endsWith(".md")) {
            return chunkMarkdownText(text, sourceFile)
        }

        // Fallback to sentence-based chunking
        return chunkBySentences(text, sourceFile)
    }

    /**
     * Markdown-aware chunking: splits by semantic sections (headings + content)
     */
    private fun chunkMarkdownText(text: String, sourceFile: String): List<TextChunk> {
        logger.info("Using markdown-aware chunking for $sourceFile")

        val sections = parseMarkdownSections(text)
        val chunks = mutableListOf<TextChunk>()
        var chunkIndex = 0

        for (section in sections) {
            val sectionTokens = estimateTokenCount(section.content)

            // If section fits in one chunk, create it directly
            if (sectionTokens <= targetChunkSize) {
                chunks.add(
                    TextChunk(
                        text = section.content.trim(),
                        sourceFile = sourceFile,
                        chunkIndex = chunkIndex++,
                        headingContext = section.heading
                    )
                )
                logger.debug("Created chunk from section '${section.heading}': ~$sectionTokens tokens")
            } else {
                // Section too large, split it into smaller chunks with heading context
                val sectionChunks = splitLargeSection(section, sourceFile, chunkIndex)
                chunks.addAll(sectionChunks)
                chunkIndex += sectionChunks.size
                logger.debug("Split large section '${section.heading}' into ${sectionChunks.size} chunks")
            }
        }

        logger.info("Created ${chunks.size} markdown-aware chunks from $sourceFile")
        return chunks
    }

    /**
     * Parse markdown text into sections based on headings
     */
    private fun parseMarkdownSections(text: String): List<MarkdownSection> {
        val lines = text.lines()
        val sections = mutableListOf<MarkdownSection>()

        var currentHeading = ""
        var currentLevel = 0
        var currentContent = StringBuilder()
        var currentStartLine = 0
        val headingStack = mutableListOf<Pair<Int, String>>() // Stack to track heading hierarchy

        for ((lineIndex, line) in lines.withIndex()) {
            val headingMatch = Regex("""^(#{1,6})\s+(.+)$""").find(line)

            if (headingMatch != null) {
                // Save previous section if exists
                if (currentContent.isNotEmpty()) {
                    sections.add(
                        MarkdownSection(
                            heading = buildHeadingContext(headingStack),
                            level = currentLevel,
                            content = currentContent.toString().trim(),
                            startLine = currentStartLine
                        )
                    )
                }

                // Start new section
                val level = headingMatch.groupValues[1].length
                val heading = headingMatch.groupValues[2].trim()

                // Update heading stack
                while (headingStack.isNotEmpty() && headingStack.last().first >= level) {
                    headingStack.removeAt(headingStack.lastIndex)
                }
                headingStack.add(level to heading)

                currentLevel = level
                currentHeading = heading
                currentContent = StringBuilder()
                currentContent.append(line).append("\n")
                currentStartLine = lineIndex
            } else {
                // Add line to current section
                currentContent.append(line).append("\n")
            }
        }

        // Add final section
        if (currentContent.isNotEmpty()) {
            sections.add(
                MarkdownSection(
                    heading = buildHeadingContext(headingStack),
                    level = currentLevel,
                    content = currentContent.toString().trim(),
                    startLine = currentStartLine
                )
            )
        }

        return sections
    }

    /**
     * Build hierarchical heading context from stack
     * Example: "Основы анатомии > Ткани > Мышечная ткань"
     */
    private fun buildHeadingContext(headingStack: List<Pair<Int, String>>): String {
        return headingStack.joinToString(" > ") { it.second }
    }

    /**
     * Split large section into smaller chunks while preserving heading context
     */
    private fun splitLargeSection(section: MarkdownSection, sourceFile: String, startIndex: Int): List<TextChunk> {
        val sentences = splitBySentences(section.content)
        val chunks = mutableListOf<TextChunk>()

        var currentChunk = StringBuilder()
        var currentTokenCount = 0
        var chunkIndex = startIndex

        for (sentence in sentences) {
            val sentenceTokens = estimateTokenCount(sentence)

            if (currentTokenCount + sentenceTokens > targetChunkSize && currentChunk.isNotEmpty()) {
                chunks.add(
                    TextChunk(
                        text = currentChunk.toString().trim(),
                        sourceFile = sourceFile,
                        chunkIndex = chunkIndex++,
                        headingContext = section.heading
                    )
                )

                // Create overlap
                val lastSentences = sentences.takeLast(3).joinToString(" ")
                currentChunk = StringBuilder(lastSentences + " ")
                currentTokenCount = estimateTokenCount(lastSentences)
            }

            currentChunk.append(sentence).append(" ")
            currentTokenCount += sentenceTokens
        }

        // Add final chunk
        if (currentChunk.isNotEmpty()) {
            chunks.add(
                TextChunk(
                    text = currentChunk.toString().trim(),
                    sourceFile = sourceFile,
                    chunkIndex = chunkIndex,
                    headingContext = section.heading
                )
            )
        }

        return chunks
    }

    /**
     * Fallback: sentence-based chunking (for non-markdown files)
     */
    private fun chunkBySentences(text: String, sourceFile: String): List<TextChunk> {
        val sentences = splitBySentences(text)
        val chunks = mutableListOf<TextChunk>()

        var currentChunk = StringBuilder()
        var currentTokenCount = 0
        var chunkStartPosition = 0
        var chunkIndex = 0

        for ((sentenceIndex, sentence) in sentences.withIndex()) {
            val sentenceTokens = estimateTokenCount(sentence)

            if (currentTokenCount + sentenceTokens > targetChunkSize && currentChunk.isNotEmpty()) {
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

                // Create overlap
                val overlapBuilder = StringBuilder()
                var overlapTokens = 0
                var overlapSentenceCount = 0

                var overlapIndex = sentenceIndex - 1
                while (overlapIndex >= 0 && overlapTokens < overlapSize && overlapSentenceCount < 5) {
                    val overlapSentence = sentences[overlapIndex]
                    val overlapSentenceTokens = estimateTokenCount(overlapSentence)
                    if (overlapTokens + overlapSentenceTokens <= overlapSize * 2) {
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

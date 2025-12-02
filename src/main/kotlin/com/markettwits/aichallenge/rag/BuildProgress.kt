package com.markettwits.aichallenge.rag

import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * Tracks the progress of knowledge base building
 */
@Serializable
data class BuildProgress(
    val status: String = "idle",
    val currentStep: String = "",
    val totalChunks: Int = 0,
    val processedChunks: Int = 0,
    val totalDocuments: Int = 0,
    val loadedDocuments: Int = 0,
    val progressPercent: Int = 0,
    val startTime: Long = 0,
    val elapsedSeconds: Long = 0,
    val estimatedRemainingSeconds: Long = 0,
    val errorMessage: String? = null,
    val detailedLogs: List<String> = emptyList(),
) {
    fun getProgressBar(): String {
        val filledLength = (progressPercent / 5) // 20 chars for 100%
        val emptyLength = 20 - filledLength
        val bar = "█".repeat(filledLength) + "░".repeat(emptyLength)
        return "[$bar] $progressPercent%"
    }

    fun getFormattedTime(): String {
        val minutes = elapsedSeconds / 60
        val seconds = elapsedSeconds % 60
        return String.format("%02d:%02d", minutes, seconds)
    }

    fun getEstimatedTimeRemaining(): String {
        if (estimatedRemainingSeconds <= 0) return "calculating..."
        val minutes = estimatedRemainingSeconds / 60
        val seconds = estimatedRemainingSeconds % 60
        return String.format("%02d:%02d", minutes, seconds)
    }

    fun toConsoleOutput(): String {
        return buildString {
            appendLine("╔════════════════════════════════════════════════════════════╗")
            appendLine("║          RAG Knowledge Base Building Progress              ║")
            appendLine("╠════════════════════════════════════════════════════════════╣")
            appendLine("║ Status: $status")
            appendLine("║ Current Step: $currentStep")
            appendLine("║ Documents: $loadedDocuments/$totalDocuments")
            appendLine("║ Chunks: $processedChunks/$totalChunks")
            appendLine("║ Progress: ${getProgressBar()}")
            appendLine("║ Elapsed: ${getFormattedTime()} | ETA: ${getEstimatedTimeRemaining()}")
            if (errorMessage != null) {
                appendLine("║ ERROR: $errorMessage")
            }
            appendLine("╚════════════════════════════════════════════════════════════╝")
        }
    }
}

/**
 * Global progress tracker for RAG building
 */
object RAGProgressTracker {
    private var currentProgress = BuildProgress(
        status = "idle",
        currentStep = "Waiting to start...",
        progressPercent = 0
    )

    fun getProgress(): BuildProgress = currentProgress.copy(
        elapsedSeconds = if (currentProgress.startTime > 0) {
            (Instant.now().toEpochMilli() - currentProgress.startTime) / 1000
        } else 0
    )

    fun startBuilding() {
        currentProgress = BuildProgress(
            status = "loading",
            currentStep = "Loading documents from disk...",
            startTime = Instant.now().toEpochMilli(),
            progressPercent = 0
        )
    }

    fun updateDocumentsLoaded(loaded: Int, total: Int) {
        currentProgress = currentProgress.copy(
            status = "loading",
            currentStep = "Loading documents from disk... ($loaded/$total)",
            totalDocuments = total,
            loadedDocuments = loaded,
            progressPercent = (loaded * 20 / maxOf(total, 1)) // 0-20% for loading
        )
    }

    fun startChunking(totalChunks: Int) {
        currentProgress = currentProgress.copy(
            status = "chunking",
            currentStep = "Splitting documents into chunks... (0/$totalChunks)",
            totalChunks = totalChunks,
            processedChunks = 0,
            progressPercent = 20
        )
    }

    fun updateChunking(processed: Int, total: Int) {
        val percent = 20 + (processed * 30 / maxOf(total, 1)) // 20-50% for chunking
        currentProgress = currentProgress.copy(
            status = "chunking",
            currentStep = "Splitting documents into chunks... ($processed/$total)",
            processedChunks = processed,
            progressPercent = percent
        )
    }

    fun startEmbedding(totalChunks: Int) {
        currentProgress = currentProgress.copy(
            status = "embedding",
            currentStep = "Generating embeddings with Ollama... (0/$totalChunks)",
            totalChunks = totalChunks,
            processedChunks = 0,
            progressPercent = 50
        )
    }

    fun updateEmbedding(processed: Int, total: Int) {
        val percent = 50 + (processed * 40 / maxOf(total, 1)) // 50-90% for embedding
        val elapsedMs = Instant.now().toEpochMilli() - currentProgress.startTime
        val elapsedSec = elapsedMs / 1000
        val avgTimePerChunk = if (processed > 0) elapsedSec / processed else 0
        val estimatedRemaining = (total - processed) * avgTimePerChunk

        currentProgress = currentProgress.copy(
            status = "embedding",
            currentStep = "Generating embeddings with Ollama... ($processed/$total)",
            processedChunks = processed,
            progressPercent = percent,
            estimatedRemainingSeconds = estimatedRemaining
        )
    }

    fun startSaving(totalChunks: Int) {
        currentProgress = currentProgress.copy(
            status = "saving",
            currentStep = "Saving to database... (0/$totalChunks)",
            progressPercent = 90
        )
    }

    fun updateSaving(processed: Int, total: Int) {
        val percent = 90 + (processed * 10 / maxOf(total, 1)) // 90-100% for saving
        currentProgress = currentProgress.copy(
            status = "saving",
            currentStep = "Saving to database... ($processed/$total)",
            progressPercent = percent
        )
    }

    fun complete() {
        currentProgress = currentProgress.copy(
            status = "completed",
            currentStep = "Knowledge base built successfully!",
            progressPercent = 100,
            processedChunks = currentProgress.totalChunks,
            loadedDocuments = currentProgress.totalDocuments
        )
    }

    fun error(message: String) {
        currentProgress = currentProgress.copy(
            status = "error",
            currentStep = "Building failed!",
            errorMessage = message,
            progressPercent = currentProgress.progressPercent
        )
    }

    fun reset() {
        currentProgress = BuildProgress(
            status = "idle",
            currentStep = "Waiting to start...",
            progressPercent = 0
        )
    }

    fun getDetailedLogs(): List<String> = currentProgress.detailedLogs

    fun addLog(message: String) {
        val logs = currentProgress.detailedLogs.toMutableList()
        val formattedTime = currentProgress.getFormattedTime()
        logs.add("[$formattedTime] $message")
        if (logs.size > 100) {
            logs.removeAt(0) // Keep only last 100 logs
        }
        currentProgress = currentProgress.copy(detailedLogs = logs)
    }
}

package com.markettwits.aichallenge

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicInteger

class StacktraceAnalysisService(
    private val lmStudioClient: LMStudioClient,
    private val repository: StacktraceTaskRepository,
    private val maxConcurrent: Int = 2,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val logger = LoggerFactory.getLogger(StacktraceAnalysisService::class.java)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val semaphore = Semaphore(maxConcurrent)
    private val dateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME
    private val titleCounter = AtomicInteger(0)

    init {
        resumeQueuedTasks()
    }

    fun submitTask(request: StacktraceTaskCreateRequest): StacktraceTaskResponse {
        val rawStacktrace = loadStacktrace(request)
        val normalizedStacktrace = normalizeStacktrace(rawStacktrace)
        val derivedTitle = request.title?.takeIf { it.isNotBlank() }
            ?: rawStacktrace.lineSequence().firstOrNull()?.take(180)
            ?: "Stacktrace #${titleCounter.incrementAndGet()}"

        val clampedTemperature = request.temperature?.coerceIn(0.0, 2.0)
        val clampedMaxTokens = request.maxTokens?.coerceIn(128, MAX_ALLOWED_TOKENS)
        val clampedTopP = request.topP?.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0)
        val clampedPresencePenalty = request.presencePenalty?.takeIf { it.isFinite() }?.coerceIn(-2.0, 2.0)
        val clampedFrequencyPenalty = request.frequencyPenalty?.takeIf { it.isFinite() }?.coerceIn(-2.0, 2.0)

        val record = repository.createTask(
            title = derivedTitle,
            stacktrace = normalizedStacktrace,
            stacktracePath = request.stacktracePath,
            model = request.model,
            temperature = clampedTemperature,
            maxTokens = clampedMaxTokens,
            topP = clampedTopP,
            presencePenalty = clampedPresencePenalty,
            frequencyPenalty = clampedFrequencyPenalty
        )

        enqueue(record.id)
        return record.toResponse(includeStacktrace = false)
    }

    fun getTask(taskId: String): StacktraceTaskResponse? {
        val record = repository.getTask(taskId) ?: return null
        return record.toResponse(includeStacktrace = true)
    }

    fun listTasks(limit: Int = 50): List<StacktraceTaskResponse> {
        return repository.listTasks(limit).map { it.toResponse(includeStacktrace = false) }
    }

    suspend fun listModels(): List<String> = lmStudioClient.listModels()

    fun retryTask(taskId: String, update: StacktraceTaskUpdateRequest?): StacktraceTaskResponse {
        val existing = repository.getTask(taskId)
            ?: throw IllegalArgumentException("Task not found: $taskId")

        val newStacktrace =
            update?.stacktraceText?.takeIf { it.isNotBlank() }?.let { normalizeStacktrace(it) }
                ?: update?.stacktracePath?.takeIf { it.isNotBlank() }
                    ?.let { normalizeStacktrace(loadStacktrace(StacktraceTaskCreateRequest(stacktracePath = it))) }
                ?: existing.stacktrace

        val clampedTemperature = update?.temperature?.coerceIn(0.0, 2.0) ?: existing.temperature
        val clampedMaxTokens =
            update?.maxTokens?.coerceIn(128, MAX_ALLOWED_TOKENS) ?: existing.maxTokens ?: DEFAULT_MAX_TOKENS
        val clampedTopP = update?.topP?.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: existing.topP
        val clampedPresencePenalty =
            update?.presencePenalty?.takeIf { it.isFinite() }?.coerceIn(-2.0, 2.0) ?: existing.presencePenalty
        val clampedFrequencyPenalty =
            update?.frequencyPenalty?.takeIf { it.isFinite() }?.coerceIn(-2.0, 2.0) ?: existing.frequencyPenalty

        val updated = repository.updateAndReset(
            taskId = taskId,
            title = update?.title ?: existing.title,
            stacktrace = newStacktrace,
            stacktracePath = update?.stacktracePath ?: existing.stacktracePath,
            model = update?.model ?: existing.model,
            temperature = clampedTemperature,
            maxTokens = clampedMaxTokens,
            topP = clampedTopP,
            presencePenalty = clampedPresencePenalty,
            frequencyPenalty = clampedFrequencyPenalty
        ) ?: throw IllegalStateException("Failed to update task $taskId")

        enqueue(updated.id)
        return updated.toResponse(includeStacktrace = true)
    }

    private fun enqueue(taskId: String) {
        scope.launch {
            semaphore.withPermit {
                val task = repository.getTask(taskId)
                if (task == null) {
                    logger.warn("Task $taskId not found for processing")
                    return@withPermit
                }

                if (task.status == StacktraceTaskStatus.COMPLETED || task.status == StacktraceTaskStatus.FAILED) {
                    logger.info("Task $taskId already finished with status ${task.status}")
                    return@withPermit
                }

                val startTime = LocalDateTime.now().format(dateTimeFormatter)
                repository.updateStatus(taskId, StacktraceTaskStatus.RUNNING, startedAt = startTime)

                try {
                    val (report, usage) = analyzeStacktrace(task)
                    val doneAt = LocalDateTime.now().format(dateTimeFormatter)
                    repository.saveResult(taskId, report, usage, doneAt)
                } catch (e: Exception) {
                    val doneAt = LocalDateTime.now().format(dateTimeFormatter)
                    logger.error("Failed to process task $taskId", e)
                    repository.markFailed(taskId, e.message ?: "Unknown error", completedAt = doneAt)
                }
            }
        }
    }

    private fun resumeQueuedTasks() {
        val activeTasks = repository.getActiveTasks()
        if (activeTasks.isNotEmpty()) {
            logger.info("Resuming ${activeTasks.size} stacktrace tasks (queued/running)")
        }
        activeTasks.forEach { task ->
            enqueue(task.id)
        }
    }

    private suspend fun analyzeStacktrace(task: StacktraceTaskRecord): Pair<String, LMStudioUsage?> {
        val systemPrompt = """
            Ты опытный Android-инженер. Твоя задача — разобрать Firebase stacktrace и предложить возможные решения.

            Формат ответа:
            1) Короткий итог (1–2 предложения)
            2) Вероятная причина (буллеты)
            3) Как починить (конкретные действия/патчи)
            4) Что проверить после фикса (чек-лист)
            5) Дополнительные заметки (если стек усечён или данных мало)

            Пиши по-русски, технически и без воды.
        """.trimIndent()

        val truncatedNote = if (task.stacktrace.length >= MAX_STACKTRACE_CHARS) {
            "\n[!] Стек усечён до ${MAX_STACKTRACE_CHARS} символов."
        } else {
            ""
        }

        val userContent = buildString {
            appendLine("Стек вызова из Firebase:")
            appendLine(task.stacktrace)
            if (truncatedNote.isNotEmpty()) {
                appendLine(truncatedNote)
            }
        }

        val result = lmStudioClient.chat(
            messages = listOf(
                LMStudioMessage(role = "system", content = systemPrompt),
                LMStudioMessage(role = "user", content = userContent)
            ),
            temperature = task.temperature ?: DEFAULT_TEMPERATURE,
            modelName = task.model,
            maxTokens = task.maxTokens ?: DEFAULT_MAX_TOKENS,
            topP = task.topP,
            presencePenalty = task.presencePenalty,
            frequencyPenalty = task.frequencyPenalty
        )

        if (result.error != null) {
            throw IllegalStateException(result.error)
        }

        return result.reply to result.usage
    }

    private fun loadStacktrace(request: StacktraceTaskCreateRequest): String {
        if (!request.stacktraceText.isNullOrBlank()) {
            return request.stacktraceText
        }

        val path = request.stacktracePath
            ?: throw IllegalArgumentException("Either stacktraceText or stacktracePath must be provided")

        val file = File(path)
        if (!file.exists()) {
            throw IllegalArgumentException("Stacktrace file not found: $path")
        }
        if (!file.isFile || !file.canRead()) {
            throw IllegalArgumentException("Stacktrace path is not readable: $path")
        }

        return file.readText()
    }

    private fun normalizeStacktrace(stacktrace: String): String {
        val cleaned = stacktrace.trim()
        return if (cleaned.length > MAX_STACKTRACE_CHARS) cleaned.take(MAX_STACKTRACE_CHARS) else cleaned
    }

    private fun StacktraceTaskRecord.toResponse(includeStacktrace: Boolean): StacktraceTaskResponse {
        val progress = when (status) {
            StacktraceTaskStatus.QUEUED -> 10
            StacktraceTaskStatus.RUNNING -> 60
            StacktraceTaskStatus.COMPLETED -> 100
            StacktraceTaskStatus.FAILED -> 100
        }

        return StacktraceTaskResponse(
            id = id,
            title = title,
            status = status,
            createdAt = createdAt,
            startedAt = startedAt,
            completedAt = completedAt,
            model = model,
            temperature = temperature,
            maxTokens = maxTokens,
            topP = topP,
            presencePenalty = presencePenalty,
            frequencyPenalty = frequencyPenalty,
            report = report,
            error = error,
            usage = usage,
            stacktracePreview = stacktrace.take(PREVIEW_CHARS),
            stacktrace = if (includeStacktrace) stacktrace else null,
            progress = progress
        )
    }

    companion object {
        private const val MAX_STACKTRACE_CHARS = 60_000
        private const val PREVIEW_CHARS = 600
        private const val DEFAULT_MAX_TOKENS = 40_000
        private const val DEFAULT_TEMPERATURE = 0.2
        private const val MAX_ALLOWED_TOKENS = 40_000
    }
}

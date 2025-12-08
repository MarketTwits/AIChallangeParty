package com.markettwits.aichallenge.whatsnew

import com.markettwits.aichallenge.AnthropicClient
import com.markettwits.aichallenge.ContentBlock
import com.markettwits.aichallenge.Message
import com.markettwits.aichallenge.tools.core.ToolResult
import com.markettwits.aichallenge.tools.implementations.GetPRDiffTool
import com.markettwits.aichallenge.tools.implementations.GetPRFilesTool
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.io.File
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Service that generates user-facing "What's new" notes from a PR diff.
 */
class WhatsNewService(
    private val anthropicClient: AnthropicClient,
    private val repositoryPath: String = ".",
    private val storageDir: File = File("data/whats_new"),
) {
    private val logger = LoggerFactory.getLogger(WhatsNewService::class.java)
    private val getDiffTool = GetPRDiffTool(repositoryPath)
    private val getFilesTool = GetPRFilesTool(repositoryPath)
    private val states = ConcurrentHashMap<String, WhatsNewState>()
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    suspend fun startGeneration(request: WhatsNewRequest): String {
        val id = UUID.randomUUID().toString()
        states[id] = WhatsNewState(
            id = id,
            status = WhatsNewStatus.PENDING,
            request = request
        )

        storageDir.mkdirs()

        GlobalScope.launch {
            try {
                performGeneration(id, request)
            } catch (e: Exception) {
                logger.error("Failed to generate what's new for $id", e)
                updateState(id, WhatsNewStatus.FAILED, error = e.message)
            }
        }

        return id
    }

    fun getStatus(id: String): WhatsNewState? = states[id]

    fun getResult(id: String): WhatsNewResult? = states[id]?.result

    fun getLatestFromDisk(): WhatsNewResult? {
        val latestFile = File(storageDir, "latest.json")
        if (!latestFile.exists()) return null
        return try {
            json.decodeFromString(WhatsNewResult.serializer(), latestFile.readText())
        } catch (e: Exception) {
            logger.warn("Failed to parse latest what's new", e)
            null
        }
    }

    private suspend fun performGeneration(id: String, request: WhatsNewRequest) {
        updateState(id, WhatsNewStatus.IN_PROGRESS)
        logger.info("[$id] Generating what's new for ${request.baseBranch}...${request.headBranch ?: "HEAD"}")

        val diff = fetchDiff(request)
        val files = fetchFiles(request)

        val prompt = buildUserPrompt(request, diff, files)
        val response = anthropicClient.sendMessage(
            messages = listOf(
                Message(
                    role = "user",
                    content = listOf(ContentBlock(type = "text", text = prompt))
                )
            ),
            systemPrompt = SYSTEM_PROMPT,
            temperature = 0.3
        )

        val text = response.content.firstOrNull { it.type == "text" }?.text
            ?: throw IllegalStateException("No text response from model")

        val items = extractBullets(text)
        if (items.isEmpty()) {
            throw IllegalStateException("Model returned empty what's new list")
        }

        val markdown = buildMarkdown(request, items)
        val result = WhatsNewResult(
            id = id,
            status = WhatsNewStatus.COMPLETED,
            baseBranch = request.baseBranch,
            headBranch = request.headBranch ?: "HEAD",
            prTitle = request.prTitle,
            items = items.map { WhatsNewItem(it) },
            markdown = markdown
        )

        persistResult(result)
        updateState(id, WhatsNewStatus.COMPLETED, result = result)
        logger.info("[$id] What's new generated with ${items.size} items")
    }

    private suspend fun fetchDiff(request: WhatsNewRequest): String {
        val params = kotlinx.serialization.json.buildJsonObject {
            put("base_branch", request.baseBranch)
            request.headBranch?.let { put("head_branch", it) }
            put("include_stats", true)
            put("context_lines", 2)
        }

        val result = getDiffTool.execute(params)
        if (result !is ToolResult.Success) {
            throw IllegalStateException("Cannot fetch diff: ${(result as ToolResult.Error).message}")
        }
        return trimForPrompt(result.data, 12000)
    }

    private suspend fun fetchFiles(request: WhatsNewRequest): String {
        val params = kotlinx.serialization.json.buildJsonObject {
            put("base_branch", request.baseBranch)
            request.headBranch?.let { put("head_branch", it) }
            put("include_content", request.includeFileContent)
        }

        val result = getFilesTool.execute(params)
        if (result !is ToolResult.Success) {
            throw IllegalStateException("Cannot fetch files: ${(result as ToolResult.Error).message}")
        }
        return trimForPrompt(result.data, 6000)
    }

    private fun buildUserPrompt(request: WhatsNewRequest, diff: String, files: String): String {
        return buildString {
            appendLine("Сгенерируй краткий блок «Что нового» для сайта виртуального спортивного тренера и клубного ассистента.")
            appendLine("Фокус: только пользовательские изменения (новые функции, улучшения, исправления). Игнорируй рефакторы, тесты, CI, документацию без пользовательского эффекта.")
            appendLine("Формат: до 8 пунктов, каждый начинается с '- ' на русском, 8-12 слов, без технического сленга.")
            appendLine()
            appendLine("PR Title: ${request.prTitle ?: "нет заголовка"}")
            appendLine("Base: ${request.baseBranch}, Head: ${request.headBranch ?: "HEAD"}")
            appendLine()
            appendLine("Изменённые файлы:")
            appendLine(files)
            appendLine()
            appendLine("Diff:")
            appendLine(diff)
        }
    }

    private fun buildMarkdown(request: WhatsNewRequest, items: List<String>): String {
        return buildString {
            appendLine("# Что нового")
            appendLine()
            appendLine("- Ветка: `${request.baseBranch}...${request.headBranch ?: "HEAD"}`")
            request.prTitle?.let { appendLine("- PR: $it") }
            appendLine("- Сгенерировано: ${java.time.Instant.now()}")
            appendLine()
            items.forEach { appendLine("- $it") }
        }
    }

    private fun extractBullets(text: String): List<String> {
        val lines = text.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
        val bullets = lines.filter { it.startsWith("-") || it.startsWith("*") || it.startsWith("•") }
            .map { it.trimStart('-', '*', '•', ' ') }
            .map { it.trim() }
        if (bullets.isNotEmpty()) return bullets

        // Fallback: split sentences
        return text.split("\n\n", ". ", ";")
            .map { it.trim() }
            .filter { it.length > 3 }
            .take(8)
    }

    private fun persistResult(result: WhatsNewResult) {
        try {
            val jsonFile = File(storageDir, "${result.id}.json")
            jsonFile.writeText(json.encodeToString(result))

            val markdownFile = File(storageDir, "${result.id}.md")
            markdownFile.writeText(result.markdown ?: "")

            File(storageDir, "latest.json").writeText(json.encodeToString(result))
            File(storageDir, "latest.md").writeText(result.markdown ?: "")
        } catch (e: Exception) {
            logger.warn("Failed to persist what's new result", e)
        }
    }

    private fun updateState(
        id: String,
        status: WhatsNewStatus,
        result: WhatsNewResult? = null,
        error: String? = null,
    ) {
        val current = states[id]
        if (current == null) {
            states[id] = WhatsNewState(
                id = id,
                status = status,
                request = WhatsNewRequest(),
                result = result,
                error = error,
                completedAt = if (status == WhatsNewStatus.COMPLETED || status == WhatsNewStatus.FAILED) System.currentTimeMillis() else null
            )
            return
        }

        states[id] = current.copy(
            status = status,
            result = result ?: current.result,
            error = error,
            completedAt = if (status == WhatsNewStatus.COMPLETED || status == WhatsNewStatus.FAILED) System.currentTimeMillis() else null
        )
    }

    private fun trimForPrompt(text: String, maxLength: Int): String {
        if (text.length <= maxLength) return text
        return text.take(maxLength) + "\n\n... (truncated)"
    }

    companion object {
        private const val SYSTEM_PROMPT = """
            Ты — ассистент продукта, который пишет блок «Что нового» для сайта AI Running Coach и клубного помощника.
            Говори коротко, по делу, на русском. Указывай только изменения, заметные пользователю: новые функции, исправления, улучшения UX/скорости, обновления данных клуба/тренера.
            Не упоминай внутренние детали разработки, тесты, CI, рефакторы, зависимостные обновления.
            Формат ответа: маркированный список, каждый пункт начинается с '- ' и не длиннее 12 слов.
        """
    }
}

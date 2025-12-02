package com.markettwits.aichallenge.tools.implementations

import com.markettwits.aichallenge.tools.core.Tool
import com.markettwits.aichallenge.tools.core.ToolResult
import com.markettwits.aichallenge.tools.core.ToolSchema
import com.markettwits.aichallenge.tools.core.ToolType
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.io.File

/**
 * MCP tool for getting list of changed files in a PR
 * Returns file paths with change type (added, modified, deleted)
 */
class GetPRFilesTool(
    private val repositoryPath: String = ".",
) : Tool {
    private val logger = LoggerFactory.getLogger(GetPRFilesTool::class.java)

    override val name: String = "get_pr_files"
    override val description: String = """
        Get list of files changed in a Pull Request.
        Returns file paths with their change status (added, modified, deleted, renamed).
        Optionally can read file contents for analysis.
    """.trimIndent()

    override val type: ToolType = ToolType.MCP
    override val schema: ToolSchema = ToolSchema(
        properties = buildJsonObject {
            put("base_branch", buildJsonObject {
                put("type", "string")
                put("description", "Base branch to compare against (e.g., 'main', 'master')")
                put("default", "main")
            })
            put("head_branch", buildJsonObject {
                put("type", "string")
                put("description", "Head branch with changes (default: current branch)")
            })
            put("include_content", buildJsonObject {
                put("type", "boolean")
                put("description", "Include file contents in the response (default: false)")
                put("default", false)
            })
            put("file_types", buildJsonObject {
                put("type", "array")
                put("description", "Filter by file extensions (e.g., ['kt', 'java', 'yml'])")
                put("items", buildJsonObject {
                    put("type", "string")
                })
            })
        },
        required = emptyList()
    )

    override suspend fun execute(params: JsonObject): ToolResult {
        val baseBranch = params["base_branch"]?.jsonPrimitive?.contentOrNull ?: "main"
        val headBranch = params["head_branch"]?.jsonPrimitive?.contentOrNull ?: getCurrentBranch()
        val includeContent = params["include_content"]?.jsonPrimitive?.booleanOrNull ?: false
        val fileTypes = params["file_types"]?.jsonArray?.map { it.jsonPrimitive.content }

        return try {
            logger.info("Getting changed files for PR: $baseBranch...$headBranch")

            val filesResult = buildString {
                appendLine("📁 Changed Files in Pull Request")
                appendLine("=".repeat(60))
                appendLine("Base Branch: $baseBranch")
                appendLine("Head Branch: $headBranch")
                appendLine()

                // Get list of changed files with status
                val filesOutput = executeGitCommand("git diff --name-status $baseBranch...$headBranch")

                if (filesOutput.isBlank()) {
                    appendLine("⚠️  No files changed between $baseBranch and $headBranch")
                    return@buildString
                }

                val changedFiles = filesOutput.lines()
                    .filter { it.isNotBlank() }
                    .map { line ->
                        val parts = line.split("\t", limit = 2)
                        val status = parts[0]
                        val filePath = parts.getOrNull(1) ?: ""
                        FileChange(status, filePath)
                    }
                    .filter { file ->
                        fileTypes == null || fileTypes.any { ext ->
                            file.path.endsWith(".$ext")
                        }
                    }

                // Group files by change type
                val filesByType = changedFiles.groupBy { getChangeType(it.status) }

                appendLine("📊 Summary:")
                appendLine("  Total files changed: ${changedFiles.size}")
                filesByType.forEach { (type, files) ->
                    appendLine("  $type: ${files.size}")
                }
                appendLine()

                // List files by type
                filesByType.forEach { (type, files) ->
                    appendLine("$type Files:")
                    files.forEach { file ->
                        appendLine("  ${getStatusEmoji(file.status)} ${file.path}")

                        if (includeContent && file.status != "D") {
                            try {
                                val content = readFile(file.path)
                                appendLine("    Content Preview (first 20 lines):")
                                content.lines().take(20).forEach { line ->
                                    appendLine("    $line")
                                }
                                if (content.lines().size > 20) {
                                    appendLine("    ... (${content.lines().size - 20} more lines)")
                                }
                                appendLine()
                            } catch (e: Exception) {
                                appendLine("    ⚠️  Could not read file content: ${e.message}")
                                appendLine()
                            }
                        }
                    }
                    appendLine()
                }
            }

            ToolResult.Success(
                data = filesResult,
                metadata = mapOf(
                    "toolType" to "git_files",
                    "baseBranch" to baseBranch,
                    "headBranch" to headBranch,
                    "repository" to repositoryPath
                )
            )
        } catch (e: Exception) {
            logger.error("Error getting PR files", e)
            ToolResult.Error(
                message = "Failed to get PR files: ${e.message}",
                code = "GIT_FILES_ERROR",
                details = mapOf(
                    "baseBranch" to baseBranch,
                    "headBranch" to headBranch
                )
            )
        }
    }

    override fun validateParams(params: JsonObject): String? {
        return null
    }

    private fun getCurrentBranch(): String {
        return try {
            executeGitCommand("git branch --show-current")
        } catch (e: Exception) {
            logger.warn("Could not get current branch, using HEAD")
            "HEAD"
        }
    }

    private fun getChangeType(status: String): String {
        return when (status.first()) {
            'A' -> "Added"
            'M' -> "Modified"
            'D' -> "Deleted"
            'R' -> "Renamed"
            'C' -> "Copied"
            'T' -> "Type Changed"
            else -> "Other"
        }
    }

    private fun getStatusEmoji(status: String): String {
        return when (status.first()) {
            'A' -> "✨"
            'M' -> "✏️"
            'D' -> "🗑️"
            'R' -> "📝"
            'C' -> "📋"
            'T' -> "🔄"
            else -> "❓"
        }
    }

    private fun readFile(path: String): String {
        val file = File(repositoryPath, path)
        return if (file.exists() && file.isFile) {
            file.readText()
        } else {
            throw RuntimeException("File not found: $path")
        }
    }

    private fun executeGitCommand(command: String): String {
        val process = ProcessBuilder()
            .command(command.split(" "))
            .directory(File(repositoryPath))
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().readText().trim()
        val exitCode = process.waitFor()

        if (exitCode != 0) {
            throw RuntimeException("Git command failed: $command\nOutput: $output")
        }

        return output
    }

    private data class FileChange(
        val status: String,
        val path: String,
    )
}

package com.markettwits.aichallenge.tools.implementations

import com.markettwits.aichallenge.tools.core.Tool
import com.markettwits.aichallenge.tools.core.ToolResult
import com.markettwits.aichallenge.tools.core.ToolSchema
import com.markettwits.aichallenge.tools.core.ToolType
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.io.File

/**
 * MCP tool for getting git diff of a PR
 * Supports both comparing branches and specific commit ranges
 */
class GetPRDiffTool(
    private val repositoryPath: String = ".",
) : Tool {
    private val logger = LoggerFactory.getLogger(GetPRDiffTool::class.java)

    override val name: String = "get_pr_diff"
    override val description: String = """
        Get the diff of a Pull Request by comparing branches or commits.
        Returns unified diff format showing all changes in the PR.
        Use this when you need to analyze code changes for review.
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
            put("context_lines", buildJsonObject {
                put("type", "integer")
                put("description", "Number of context lines to show around changes (default: 3)")
                put("default", 3)
            })
            put("include_stats", buildJsonObject {
                put("type", "boolean")
                put("description", "Include diff statistics (files changed, insertions, deletions)")
                put("default", true)
            })
        },
        required = emptyList()
    )

    override suspend fun execute(params: JsonObject): ToolResult {
        val baseBranch = params["base_branch"]?.jsonPrimitive?.contentOrNull ?: "main"
        val headBranch = params["head_branch"]?.jsonPrimitive?.contentOrNull ?: getCurrentBranch()
        val contextLines = params["context_lines"]?.jsonPrimitive?.intOrNull ?: 3
        val includeStats = params["include_stats"]?.jsonPrimitive?.booleanOrNull ?: true

        return try {
            logger.info("Getting PR diff: $baseBranch...$headBranch")

            val diffResult = buildString {
                appendLine("📊 Pull Request Diff Analysis")
                appendLine("=".repeat(60))
                appendLine("Base Branch: $baseBranch")
                appendLine("Head Branch: $headBranch")
                appendLine()

                if (includeStats) {
                    // Get diff statistics
                    val stats = executeGitCommand("git diff --stat $baseBranch...$headBranch")
                    appendLine("📈 Changes Summary:")
                    appendLine(stats)
                    appendLine()
                }

                // Get the actual diff
                appendLine("📝 Detailed Diff:")
                appendLine("-".repeat(60))
                val diff = executeGitCommand("git diff -U$contextLines $baseBranch...$headBranch")

                if (diff.isBlank()) {
                    appendLine("⚠️  No differences found between $baseBranch and $headBranch")
                } else {
                    appendLine(diff)
                }
            }

            ToolResult.Success(
                data = diffResult,
                metadata = mapOf(
                    "toolType" to "git_diff",
                    "baseBranch" to baseBranch,
                    "headBranch" to headBranch,
                    "repository" to repositoryPath
                )
            )
        } catch (e: Exception) {
            logger.error("Error getting PR diff", e)
            ToolResult.Error(
                message = "Failed to get PR diff: ${e.message}",
                code = "GIT_DIFF_ERROR",
                details = mapOf(
                    "baseBranch" to baseBranch,
                    "headBranch" to headBranch
                )
            )
        }
    }

    override fun validateParams(params: JsonObject): String? {
        val contextLines = params["context_lines"]?.jsonPrimitive?.intOrNull
        if (contextLines != null && contextLines < 0) {
            return "context_lines must be non-negative"
        }
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
}

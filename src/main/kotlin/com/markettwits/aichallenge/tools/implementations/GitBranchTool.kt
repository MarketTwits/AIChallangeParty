package com.markettwits.aichallenge.tools.implementations

import com.markettwits.aichallenge.tools.core.Tool
import com.markettwits.aichallenge.tools.core.ToolResult
import com.markettwits.aichallenge.tools.core.ToolSchema
import com.markettwits.aichallenge.tools.core.ToolType
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.io.File

/**
 * MCP-style tool for git repository operations
 * Currently supports getting current branch information
 */
class GitBranchTool(
    private val repositoryPath: String = ".",
) : Tool {
    private val logger = LoggerFactory.getLogger(GitBranchTool::class.java)

    override val name: String = "get_git_branch"
    override val description: String = """
        Get information about the current git branch including:
        - Current branch name
        - Latest commit hash and message
        - Branch status (ahead/behind remote)
        Use this when user asks about the current development state or git status.
    """.trimIndent()

    override val type: ToolType = ToolType.MCP
    override val schema: ToolSchema = ToolSchema(
        properties = buildJsonObject {
            put("include_remote", buildJsonObject {
                put("type", "boolean")
                put("description", "Include remote tracking information (default: true)")
                put("default", true)
            })
        },
        required = emptyList()
    )

    override suspend fun execute(params: JsonObject): ToolResult {
        val includeRemote = params["include_remote"]?.jsonPrimitive?.booleanOrNull ?: true

        return try {
            logger.info("Getting git branch information for repository: $repositoryPath")

            val gitInfo = buildString {
                // Get current branch
                val branch = executeGitCommand("git branch --show-current")
                appendLine("🌿 Current Branch: $branch")
                appendLine()

                // Get latest commit
                val commitHash = executeGitCommand("git rev-parse --short HEAD")
                val commitMessage = executeGitCommand("git log -1 --pretty=%B")
                val commitAuthor = executeGitCommand("git log -1 --pretty=%an")
                val commitDate = executeGitCommand("git log -1 --pretty=%ar")

                appendLine("📝 Latest Commit:")
                appendLine("   Hash: $commitHash")
                appendLine("   Author: $commitAuthor")
                appendLine("   Date: $commitDate")
                appendLine("   Message: ${commitMessage.lines().firstOrNull() ?: ""}")
                appendLine()

                if (includeRemote) {
                    // Get remote tracking info
                    try {
                        val remoteBranch = executeGitCommand("git rev-parse --abbrev-ref --symbolic-full-name @{u}")
                        appendLine("🌐 Remote Tracking: $remoteBranch")

                        // Check if ahead/behind
                        val ahead = executeGitCommand("git rev-list --count @{u}..HEAD").toIntOrNull() ?: 0
                        val behind = executeGitCommand("git rev-list --count HEAD..@{u}").toIntOrNull() ?: 0

                        if (ahead > 0) {
                            appendLine("   ⬆️  Ahead by $ahead commit(s)")
                        }
                        if (behind > 0) {
                            appendLine("   ⬇️  Behind by $behind commit(s)")
                        }
                        if (ahead == 0 && behind == 0) {
                            appendLine("   ✅ Up to date with remote")
                        }
                    } catch (e: Exception) {
                        appendLine("   ⚠️  No remote tracking branch configured")
                    }
                    appendLine()
                }

                // Get working directory status
                val status = executeGitCommand("git status --short")
                if (status.isNotBlank()) {
                    appendLine("📋 Working Directory Status:")
                    status.lines().take(10).forEach { line ->
                        appendLine("   $line")
                    }
                    if (status.lines().size > 10) {
                        appendLine("   ... and ${status.lines().size - 10} more files")
                    }
                } else {
                    appendLine("✨ Working directory is clean")
                }
            }

            ToolResult.Success(
                data = gitInfo,
                metadata = mapOf(
                    "toolType" to "git_mcp",
                    "repository" to repositoryPath
                )
            )
        } catch (e: Exception) {
            logger.error("Error getting git branch information", e)
            ToolResult.Error(
                message = "Failed to get git information: ${e.message}",
                code = "GIT_ERROR"
            )
        }
    }

    override fun validateParams(params: JsonObject): String? {
        // All parameters are optional
        return null
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

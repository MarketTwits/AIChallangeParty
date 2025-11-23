package com.markettwits.aichallenge.mcp

import com.markettwits.aichallenge.GitHubMcpServer
import kotlinx.serialization.json.*

/**
 * Real MCP Server for GitHub operations
 * Implements Model Context Protocol for GitHub API integration
 */
class GitHubRealMcpServer : RealMcpServer(
    serverInfo = Implementation(
        name = "github-mcp-server",
        version = "1.0.0"
    )
) {
    private val githubService = GitHubMcpServer()

    override fun getCapabilities(): ServerCapabilities {
        return ServerCapabilities(
            tools = ToolsCapability(listChanged = false)
        )
    }

    override suspend fun listTools(): List<McpToolDefinition> {
        return listOf(
            McpToolDefinition(
                name = "github_search_repositories",
                description = "Search for repositories on GitHub by query",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("query", buildJsonObject {
                            put("type", "string")
                            put("description", "Search query for repositories (e.g., 'language:kotlin stars:>100')")
                        })
                        put("limit", buildJsonObject {
                            put("type", "integer")
                            put("description", "Maximum number of results (1-100)")
                            put("default", 10)
                        })
                    })
                    put("required", buildJsonArray { add("query") })
                }
            ),
            McpToolDefinition(
                name = "github_get_repository",
                description = "Get detailed information about a specific GitHub repository",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("owner", buildJsonObject {
                            put("type", "string")
                            put("description", "Repository owner username or organization")
                        })
                        put("repo", buildJsonObject {
                            put("type", "string")
                            put("description", "Repository name")
                        })
                    })
                    put("required", buildJsonArray { add("owner"); add("repo") })
                }
            ),
            McpToolDefinition(
                name = "github_get_user_info",
                description = "Get information about a GitHub user",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("username", buildJsonObject {
                            put("type", "string")
                            put("description", "GitHub username")
                        })
                    })
                    put("required", buildJsonArray { add("username") })
                }
            ),
            McpToolDefinition(
                name = "github_get_user_repositories",
                description = "Get list of repositories for a specific user",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("username", buildJsonObject {
                            put("type", "string")
                            put("description", "GitHub username")
                        })
                        put("limit", buildJsonObject {
                            put("type", "integer")
                            put("description", "Maximum number of repositories (1-100)")
                            put("default", 10)
                        })
                    })
                    put("required", buildJsonArray { add("username") })
                }
            )
        )
    }

    override suspend fun callTool(name: String, arguments: JsonObject?): CallToolResult {
        return try {
            if (arguments == null) {
                return createToolError("Missing required arguments")
            }

            val params = arguments.toMap().mapValues { (_, value) ->
                when (value) {
                    is JsonPrimitive -> value.content
                    else -> value.toString()
                }
            }

            val result = githubService.executeTool(name, params)

            createToolResult(result)

        } catch (e: Exception) {
            logger.error("Error executing GitHub tool: $name", e)
            createToolError("Failed to execute tool $name: ${e.message}")
        }
    }

    fun close() {
        githubService.close()
    }
}

package com.markettwits.aichallenge

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class GitHubRepository(
    val id: Long,
    val name: String,
    val full_name: String,
    val description: String?,
    val private: Boolean,
    val fork: Boolean,
    val language: String?,
    val stargazers_count: Int,
    val forks_count: Int,
    val open_issues_count: Int,
    val created_at: String,
    val updated_at: String,
    val pushed_at: String,
    val size: Int,
)

@Serializable
data class GitHubUser(
    val login: String,
    val id: Long,
    val name: String?,
    val email: String?,
    val public_repos: Int,
    val followers: Int,
    val following: Int,
    val created_at: String,
)

data class McpGitHubTool(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
)

class GitHubMcpServer {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient(CIO)
    private val baseUrl = "https://api.github.com"
    private val gitHubToken = System.getProperty("GITHUB_TOKEN") ?: System.getenv("GITHUB_TOKEN") ?: ""

    suspend fun getAvailableTools(): List<McpGitHubTool> {
        return listOf(
            McpGitHubTool(
                name = "github_search_repositories",
                description = "Search for repositories on GitHub",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("query", buildJsonObject {
                            put("type", "string")
                            put("description", "Search query for repositories")
                        })
                        put("limit", buildJsonObject {
                            put("type", "integer")
                            put("description", "Maximum number of results (1-100)")
                            put("default", 10)
                        })
                    })
                    put("required", buildJsonObject {})
                }
            ),

            McpGitHubTool(
                name = "github_get_repository",
                description = "Get detailed information about a specific repository",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("owner", buildJsonObject {
                            put("type", "string")
                            put("description", "Repository owner username")
                        })
                        put("repo", buildJsonObject {
                            put("type", "string")
                            put("description", "Repository name")
                        })
                    })
                    put("required", buildJsonObject {})
                }
            ),

            McpGitHubTool(
                name = "github_get_user_repositories",
                description = "Get repositories for a specific user",
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
                    put("required", buildJsonObject {})
                }
            ),

            McpGitHubTool(
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
                    put("required", buildJsonObject {})
                }
            )
        )
    }

    suspend fun executeTool(toolName: String, parameters: Map<String, Any>): String {
        return try {
            when (toolName) {
                "github_search_repositories" -> searchRepositories(parameters)
                "github_get_repository" -> getRepository(parameters)
                "github_get_user_repositories" -> getUserRepositories(parameters)
                "github_get_user_info" -> getUserInfo(parameters)
                else -> "Error: Unknown tool '$toolName'"
            }
        } catch (e: Exception) {
            "Error executing tool '$toolName': ${e.message}"
        }
    }

    private suspend fun searchRepositories(params: Map<String, Any>): String {
        val query = params["query"]?.toString() ?: "language:kotlin"
        val limit = (params["limit"] as? String)?.toIntOrNull() ?: 10

        val response = client.get("$baseUrl/search/repositories") {
            url {
                parameters.append("q", query)
                parameters.append("sort", "stars")
                parameters.append("order", "desc")
                parameters.append("per_page", limit.coerceIn(1, 100).toString())
            }
            header("Accept", "application/vnd.github.v3+json")
            if (gitHubToken.isNotEmpty()) {
                header("Authorization", "token $gitHubToken")
            }
        }

        if (response.status.isSuccess()) {
            val responseBody = response.bodyAsText()
            val jsonResult = json.decodeFromString<JsonObject>(responseBody)
            val items = jsonResult["items"]?.toString() ?: "[]"

            return "Found repositories for query '$query':\n$items"
        } else {
            return "GitHub API error: ${response.status}"
        }
    }

    private suspend fun getRepository(params: Map<String, Any>): String {
        val owner = params["owner"]?.toString() ?: return "Error: 'owner' parameter required"
        val repo = params["repo"]?.toString() ?: return "Error: 'repo' parameter required"

        val response = client.get("$baseUrl/repos/$owner/$repo") {
            header("Accept", "application/vnd.github.v3+json")
            if (gitHubToken.isNotEmpty()) {
                header("Authorization", "token $gitHubToken")
            }
        }

        if (response.status.isSuccess()) {
            val responseBody = response.bodyAsText()
            val repository = json.decodeFromString<GitHubRepository>(responseBody)

            return """
Repository Information:
Name: ${repository.full_name}
Description: ${repository.description ?: "No description"}
Language: ${repository.language ?: "Unknown"}
Stars: ${repository.stargazers_count}
Forks: ${repository.forks_count}
Open Issues: ${repository.open_issues_count}
Size: ${repository.size} KB
Created: ${repository.created_at}
Last Updated: ${repository.updated_at}
Private: ${repository.private}
Fork: ${repository.fork}
            """.trimIndent()
        } else {
            return "GitHub API error: ${response.status} - Repository not found"
        }
    }

    private suspend fun getUserRepositories(params: Map<String, Any>): String {
        val username = params["username"]?.toString() ?: return "Error: 'username' parameter required"
        val limit = (params["limit"] as? String)?.toIntOrNull() ?: 10

        val response = client.get("$baseUrl/users/$username/repos") {
            url {
                parameters.append("type", "owner")
                parameters.append("sort", "updated")
                parameters.append("per_page", limit.coerceIn(1, 100).toString())
            }
            header("Accept", "application/vnd.github.v3+json")
            if (gitHubToken.isNotEmpty()) {
                header("Authorization", "token $gitHubToken")
            }
        }

        if (response.status.isSuccess()) {
            val responseBody = response.bodyAsText()
            val repositories = json.decodeFromString<List<GitHubRepository>>(responseBody)

            val repoList = repositories.joinToString("\n\n") { repo ->
                """
- ${repo.full_name}
  Language: ${repo.language ?: "Unknown"}
  Stars: ${repo.stargazers_count}
  Forks: ${repo.forks_count}
  Updated: ${repo.updated_at}
  Description: ${repo.description ?: "No description"}
                """.trimIndent()
            }

            return "Repositories for user '$username':\n$repoList"
        } else {
            return "GitHub API error: ${response.status} - User not found"
        }
    }

    private suspend fun getUserInfo(params: Map<String, Any>): String {
        val username = params["username"]?.toString() ?: return "Error: 'username' parameter required"

        val response = client.get("$baseUrl/users/$username") {
            header("Accept", "application/vnd.github.v3+json")
            if (gitHubToken.isNotEmpty()) {
                header("Authorization", "token $gitHubToken")
            }
        }

        if (response.status.isSuccess()) {
            val responseBody = response.bodyAsText()
            val user = json.decodeFromString<GitHubUser>(responseBody)

            return """
User Information:
Username: ${user.login}
Name: ${user.name ?: "Not specified"}
Email: ${user.email ?: "Not public"}
Public Repositories: ${user.public_repos}
Followers: ${user.followers}
Following: ${user.following}
Created: ${user.created_at}
            """.trimIndent()
        } else {
            return "GitHub API error: ${response.status} - User not found"
        }
    }

    fun close() {
        client.close()
    }
}

fun testGitHubMcpServer() = runBlocking {
    val mcpServer = GitHubMcpServer()

    try {
        println("GitHub MCP Server")
        println("Available tools:")

        val tools = mcpServer.getAvailableTools()
        tools.forEach { tool ->
            println("- ${tool.name}: ${tool.description}")
        }

        println("\nTesting search repositories...")
        val searchResult = mcpServer.executeTool(
            "github_search_repositories",
            mapOf("query" to "language:kotlin", "limit" to "3")
        )
        println(searchResult)

    } catch (e: Exception) {
        println("Error: ${e.message}")
    } finally {
        mcpServer.close()
    }
}

fun main() {
    testGitHubMcpServer()
}
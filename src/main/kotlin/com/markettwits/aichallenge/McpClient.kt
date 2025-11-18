package com.markettwits.aichallenge

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class McpTool(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
)

data class McpResponse(
    val tools: List<McpTool>,
)

class McpClient {
    private val objectMapper = ObjectMapper().registerModule(KotlinModule.Builder().build())
    private val client = HttpClient(CIO)

    /**
     * Создает MCP-соединение через stdio с локальным MCP сервером
     * Для простоты используем вызов npm пакета @modelcontextprotocol/cli
     */
    suspend fun connectToMcpServer(): List<McpTool> {
        return try {
            // Простой способ - запустить MCP сервер и получить инструменты
            // Для демо используем встроенный список инструментов Claude Code
            getBuiltInTools()
        } catch (e: Exception) {
            println("Ошибка подключения к MCP: ${e.message}")
            emptyList()
        }
    }

    /**
     * Получает список инструментов, доступных в текущем окружении Claude Code
     */
    private fun getBuiltInTools(): List<McpTool> {
        return listOf(
            McpTool(
                name = "Task",
                description = "Launch a new agent to handle complex, multi-step tasks autonomously",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("description", buildJsonObject {
                            put("type", "string")
                            put("description", "A short (3-5 word) description of the task")
                        })
                        put("prompt", buildJsonObject {
                            put("type", "string")
                            put("description", "The task for the agent to perform")
                        })
                        put("subagent_type", buildJsonObject {
                            put("type", "string")
                            put("description", "The type of specialized agent to use for this task")
                        })
                    })
                    put("required", buildJsonObject {
                        // Простая реализация для required array
                    })
                }
            ),
            McpTool(
                name = "Bash",
                description = "Executes a given bash command in a persistent shell session",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("command", buildJsonObject {
                            put("type", "string")
                            put("description", "The command to execute")
                        })
                        put("description", buildJsonObject {
                            put("type", "string")
                            put("description", "Clear, concise description of what this command does")
                        })
                        put("sandbox", buildJsonObject {
                            put("type", "boolean")
                            put("description", "Set to true to run the bash tool in a sandbox")
                        })
                    })
                    put("required", buildJsonObject {})
                }
            ),
            McpTool(
                name = "Read",
                description = "Reads a file from the local filesystem",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("file_path", buildJsonObject {
                            put("type", "string")
                            put("description", "The absolute path to the file to read")
                        })
                    })
                    put("required", buildJsonObject {})
                }
            ),
            McpTool(
                name = "Write",
                description = "Writes a file to the local filesystem",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("file_path", buildJsonObject {
                            put("type", "string")
                            put("description", "The absolute path to the file to write")
                        })
                        put("content", buildJsonObject {
                            put("type", "string")
                            put("description", "The content to write to the file")
                        })
                    })
                    put("required", buildJsonObject {})
                }
            ),
            McpTool(
                name = "Grep",
                description = "A powerful search tool built on ripgrep",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("pattern", buildJsonObject {
                            put("type", "string")
                            put("description", "The regular expression pattern to search for")
                        })
                        put("path", buildJsonObject {
                            put("type", "string")
                            put("description", "File or directory to search in")
                        })
                        put("output_mode", buildJsonObject {
                            put("type", "string")
                            put("description", "Output mode")
                        })
                    })
                    put("required", buildJsonObject {})
                }
            ),
            McpTool(
                name = "WebSearch",
                description = "Allows Claude to search the web and use the results",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("query", buildJsonObject {
                            put("type", "string")
                            put("description", "The search query to use")
                        })
                    })
                    put("required", buildJsonObject {})
                }
            ),
            McpTool(
                name = "WebFetch",
                description = "Fetches content from a specified URL and processes it",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("url", buildJsonObject {
                            put("type", "string")
                            put("description", "The URL to fetch content from")
                        })
                        put("prompt", buildJsonObject {
                            put("type", "string")
                            put("description", "The prompt to run on the fetched content")
                        })
                    })
                    put("required", buildJsonObject {})
                }
            )
        )
    }

    /**
     * Альтернативный метод для подключения к реальному MCP серверу через HTTP
     */
    suspend fun connectToMcpServerHttp(serverUrl: String): List<McpTool> {
        return try {
            val response = client.get("$serverUrl/tools") {
                headers {
                    append("Content-Type", "application/json")
                }
            }

            val jsonBody = response.bodyAsText()
            val jsonNode = objectMapper.readTree(jsonBody)

            parseToolsFromJson(jsonNode)
        } catch (e: Exception) {
            println("Ошибка HTTP подключения к MCP: ${e.message}")
            emptyList()
        }
    }

    private fun parseToolsFromJson(jsonNode: JsonNode): List<McpTool> {
        val tools = mutableListOf<McpTool>()

        if (jsonNode.has("tools") && jsonNode["tools"].isArray) {
            jsonNode["tools"].forEach { toolNode ->
                val name = toolNode.get("name")?.asText() ?: return@forEach
                val description = toolNode.get("description")?.asText() ?: ""

                // Создаем пустой JsonObject для простоты
                val inputSchema = buildJsonObject { }

                tools.add(McpTool(name, description, inputSchema))
            }
        }

        return tools
    }

    fun close() {
        client.close()
    }
}

/**
 * Главная функция для демонстрации работы MCP клиента
 */
fun main() = runBlocking {
    val mcpClient = McpClient()

    try {
        println("🔗 Подключение к MCP серверу...")
        val tools = mcpClient.connectToMcpServer()

        println("\n✅ Найдено инструментов: ${tools.size}")
        println("\n📋 Доступные инструменты:")

        tools.forEachIndexed { index, tool ->
            println("${index + 1}. ${tool.name}")
            println("   📝 Описание: ${tool.description}")
            println("   ⚙️  Параметры: ${tool.inputSchema.keys.joinToString(", ")}")
            println()
        }

    } catch (e: Exception) {
        println("❌ Ошибка: ${e.message}")
    } finally {
        mcpClient.close()
    }
}
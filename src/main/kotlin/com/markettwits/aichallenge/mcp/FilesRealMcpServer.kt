package com.markettwits.aichallenge.mcp

import kotlinx.serialization.json.*
import java.io.File

/**
 * Real MCP Server for file operations
 * Implements Model Context Protocol for file system operations
 */
class FilesRealMcpServer(
    private val workingDirectory: String = System.getProperty("user.dir"),
) : RealMcpServer(
    serverInfo = Implementation(
        name = "files-mcp-server",
        version = "1.0.0"
    )
) {
    override fun getCapabilities(): ServerCapabilities {
        return ServerCapabilities(
            tools = ToolsCapability(listChanged = false),
            resources = ResourcesCapability(subscribe = false, listChanged = false)
        )
    }

    override suspend fun listTools(): List<McpToolDefinition> {
        return listOf(
            McpToolDefinition(
                name = "read_file",
                description = "Read contents of a text file",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("path", buildJsonObject {
                            put("type", "string")
                            put("description", "Path to the file to read (relative or absolute)")
                        })
                    })
                    put("required", buildJsonArray { add("path") })
                }
            ),
            McpToolDefinition(
                name = "write_file",
                description = "Write content to a file",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("path", buildJsonObject {
                            put("type", "string")
                            put("description", "Path to the file to write")
                        })
                        put("content", buildJsonObject {
                            put("type", "string")
                            put("description", "Content to write to the file")
                        })
                        put("createDirs", buildJsonObject {
                            put("type", "boolean")
                            put("description", "Create parent directories if they don't exist")
                            put("default", true)
                        })
                    })
                    put("required", buildJsonArray { add("path"); add("content") })
                }
            ),
            McpToolDefinition(
                name = "list_directory",
                description = "List contents of a directory",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("path", buildJsonObject {
                            put("type", "string")
                            put("description", "Path to the directory to list")
                            put("default", ".")
                        })
                        put("recursive", buildJsonObject {
                            put("type", "boolean")
                            put("description", "List directory contents recursively")
                            put("default", false)
                        })
                    })
                    put("required", buildJsonArray {})
                }
            ),
            McpToolDefinition(
                name = "search_files",
                description = "Search for files matching a pattern",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("pattern", buildJsonObject {
                            put("type", "string")
                            put("description", "File name pattern to search for")
                        })
                        put("path", buildJsonObject {
                            put("type", "string")
                            put("description", "Directory to search in")
                            put("default", ".")
                        })
                        put("maxResults", buildJsonObject {
                            put("type", "integer")
                            put("description", "Maximum number of results")
                            put("default", 50)
                        })
                    })
                    put("required", buildJsonArray { add("pattern") })
                }
            ),
            McpToolDefinition(
                name = "delete_file",
                description = "Delete a file or directory",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("path", buildJsonObject {
                            put("type", "string")
                            put("description", "Path to the file or directory to delete")
                        })
                    })
                    put("required", buildJsonArray { add("path") })
                }
            ),
            McpToolDefinition(
                name = "get_file_info",
                description = "Get information about a file (size, modified time, etc.)",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("path", buildJsonObject {
                            put("type", "string")
                            put("description", "Path to the file")
                        })
                    })
                    put("required", buildJsonArray { add("path") })
                }
            )
        )
    }

    override suspend fun callTool(name: String, arguments: JsonObject?): CallToolResult {
        return try {
            when (name) {
                "read_file" -> readFile(arguments)
                "write_file" -> writeFile(arguments)
                "list_directory" -> listDirectory(arguments)
                "search_files" -> searchFiles(arguments)
                "delete_file" -> deleteFile(arguments)
                "get_file_info" -> getFileInfo(arguments)
                else -> createToolError("Unknown tool: $name")
            }
        } catch (e: Exception) {
            logger.error("Error executing file tool: $name", e)
            createToolError("Failed to execute tool $name: ${e.message}")
        }
    }

    private fun readFile(arguments: JsonObject?): CallToolResult {
        val path = arguments?.get("path")?.jsonPrimitive?.content
            ?: return createToolError("Missing required parameter: path")

        val file = resolveFile(path)
        if (!file.exists()) {
            return createToolError("File not found: $path")
        }
        if (!file.isFile) {
            return createToolError("Path is not a file: $path")
        }

        val content = file.readText()
        return createToolResult("File: ${file.absolutePath}\nSize: ${file.length()} bytes\n\nContent:\n$content")
    }

    private fun writeFile(arguments: JsonObject?): CallToolResult {
        val path = arguments?.get("path")?.jsonPrimitive?.content
            ?: return createToolError("Missing required parameter: path")
        val content = arguments.get("content")?.jsonPrimitive?.content
            ?: return createToolError("Missing required parameter: content")
        val createDirs = arguments.get("createDirs")?.jsonPrimitive?.booleanOrNull ?: true

        val file = resolveFile(path)
        if (createDirs) {
            file.parentFile?.mkdirs()
        }

        file.writeText(content)
        return createToolResult("Successfully wrote ${content.length} characters to ${file.absolutePath}")
    }

    private fun listDirectory(arguments: JsonObject?): CallToolResult {
        val path = arguments?.get("path")?.jsonPrimitive?.content ?: "."
        val recursive = arguments?.get("recursive")?.jsonPrimitive?.booleanOrNull ?: false

        val dir = resolveFile(path)
        if (!dir.exists()) {
            return createToolError("Directory not found: $path")
        }
        if (!dir.isDirectory) {
            return createToolError("Path is not a directory: $path")
        }

        val files = if (recursive) {
            dir.walkTopDown().toList()
        } else {
            dir.listFiles()?.toList() ?: emptyList()
        }

        val result = buildString {
            appendLine("Directory: ${dir.absolutePath}")
            appendLine("Total items: ${files.size}")
            appendLine()
            files.forEach { file ->
                val type = if (file.isDirectory) "[DIR]" else "[FILE]"
                val size = if (file.isFile) "${file.length()} bytes" else ""
                appendLine("$type ${file.name} $size")
            }
        }

        return createToolResult(result)
    }

    private fun searchFiles(arguments: JsonObject?): CallToolResult {
        val pattern = arguments?.get("pattern")?.jsonPrimitive?.content
            ?: return createToolError("Missing required parameter: pattern")
        val path = arguments.get("path")?.jsonPrimitive?.content ?: "."
        val maxResults = arguments.get("maxResults")?.jsonPrimitive?.intOrNull ?: 50

        val dir = resolveFile(path)
        if (!dir.exists() || !dir.isDirectory) {
            return createToolError("Invalid directory: $path")
        }

        val regex = pattern.replace("*", ".*").toRegex(RegexOption.IGNORE_CASE)
        val matches = dir.walkTopDown()
            .filter { it.isFile && regex.matches(it.name) }
            .take(maxResults)
            .toList()

        val result = buildString {
            appendLine("Search pattern: $pattern")
            appendLine("Found ${matches.size} matches:")
            appendLine()
            matches.forEach { file ->
                appendLine("- ${file.relativeTo(dir).path} (${file.length()} bytes)")
            }
        }

        return createToolResult(result)
    }

    private fun deleteFile(arguments: JsonObject?): CallToolResult {
        val path = arguments?.get("path")?.jsonPrimitive?.content
            ?: return createToolError("Missing required parameter: path")

        val file = resolveFile(path)
        if (!file.exists()) {
            return createToolError("File not found: $path")
        }

        val deleted = if (file.isDirectory) {
            file.deleteRecursively()
        } else {
            file.delete()
        }

        return if (deleted) {
            createToolResult("Successfully deleted: ${file.absolutePath}")
        } else {
            createToolError("Failed to delete: $path")
        }
    }

    private fun getFileInfo(arguments: JsonObject?): CallToolResult {
        val path = arguments?.get("path")?.jsonPrimitive?.content
            ?: return createToolError("Missing required parameter: path")

        val file = resolveFile(path)
        if (!file.exists()) {
            return createToolError("File not found: $path")
        }

        val result = buildString {
            appendLine("File Information:")
            appendLine("Path: ${file.absolutePath}")
            appendLine("Name: ${file.name}")
            appendLine("Type: ${if (file.isDirectory) "Directory" else "File"}")
            appendLine("Size: ${file.length()} bytes")
            appendLine("Readable: ${file.canRead()}")
            appendLine("Writable: ${file.canWrite()}")
            appendLine("Executable: ${file.canExecute()}")
            appendLine("Last Modified: ${java.util.Date(file.lastModified())}")

            if (file.isDirectory) {
                val fileCount = file.listFiles()?.size ?: 0
                appendLine("Contents: $fileCount items")
            }
        }

        return createToolResult(result)
    }

    private fun resolveFile(path: String): File {
        val file = File(path)
        return if (file.isAbsolute) file else File(workingDirectory, path)
    }
}

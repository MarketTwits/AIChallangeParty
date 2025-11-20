package com.markettwits.aichallenge

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.io.File

/**
 * Набор MCP инструментов для композиции: searchDocs, summarize, saveToFile
 */
object CompositionMcpTools {

    fun getAllCompositionTools(): List<Tool> {
        return listOf(
            getSearchDocsTool(),
            getSummarizeTool(),
            getSaveToFileTool(),
            getGitHubRepoInfoTool(),
            getGitHubRepoFilesTool(),
            getGitHubRepoCommitsTool(),
            getGitHubRepoIssuesTool(),
            getGitHubReportTool()
        )
    }

    suspend fun executeCompositionTool(toolName: String, input: JsonObject): String {
        return try {
            when (toolName) {
                "search_docs" -> executeSearchDocs(input)
                "summarize" -> executeSummarize(input)
                "save_to_file" -> executeSaveToFile(input)
                "github_repo_info" -> executeGitHubRepoInfo(input)
                "github_repo_files" -> executeGitHubRepoFiles(input)
                "github_repo_commits" -> executeGitHubRepoCommits(input)
                "github_repo_issues" -> executeGitHubRepoIssues(input)
                "generate_github_report" -> generateGitHubReport(input)
                else -> "Unknown composition tool: $toolName"
            }
        } catch (e: Exception) {
            "Error executing composition tool $toolName: ${e.message}"
        }
    }

    /**
     * Инструмент для поиска в документации проекта
     */
    private fun getSearchDocsTool(): Tool {
        return Tool(
            name = "search_docs",
            description = "Search for text patterns in documentation files and source code comments",
            input_schema = InputSchema(
                type = "object",
                properties = buildJsonObject {
                    put("query", buildJsonObject {
                        put("type", "string")
                        put("description", "Search query or regex pattern to find in documentation")
                    })
                    put("path", buildJsonObject {
                        put("type", "string")
                        put("description", "Directory path to search in (default: current directory)")
                        put("default", ".")
                    })
                    put("file_types", buildJsonObject {
                        put("type", "array")
                        put("description", "File extensions to search in")
                        put("items", buildJsonObject {
                            put("type", "string")
                        })
                        put("default", buildJsonArray { add("md"); add("txt"); add("rst"); add("kt"); add("java") })
                    })
                    put("max_results", buildJsonObject {
                        put("type", "integer")
                        put("description", "Maximum number of results to return")
                        put("default", 20)
                    })
                    put("context_lines", buildJsonObject {
                        put("type", "integer")
                        put("description", "Number of context lines before and after match")
                        put("default", 3)
                    })
                },
                required = listOf("query")
            )
        )
    }

    private fun executeSearchDocs(input: JsonObject): String {
        val query = input["query"]?.jsonPrimitive?.content ?: return "Error: query parameter required"
        val path = input["path"]?.jsonPrimitive?.content ?: "."
        val fileTypes = input["file_types"]?.jsonArray?.map { it.jsonPrimitive.content }
            ?: listOf("md", "txt", "rst", "kt", "java")
        val maxResults = input["max_results"]?.jsonPrimitive?.int ?: 20
        val contextLines = input["context_lines"]?.jsonPrimitive?.int ?: 3

        val searchDir = File(path)
        if (!searchDir.exists()) {
            return "Error: Directory '$path' does not exist"
        }

        val results = mutableListOf<SearchResult>()
        val regex = try {
            Regex(query, setOf(RegexOption.IGNORE_CASE))
        } catch (e: Exception) {
            // If not a valid regex, treat as literal string
            null
        }

        searchDir.walkTopDown()
            .filter { file ->
                file.isFile && file.extension in fileTypes
            }
            .forEach { file ->
                try {
                    val content = file.readText()
                    val lines = content.split('\n')

                    lines.forEachIndexed { index, line ->
                        val matches = if (regex != null) {
                            regex.findAll(line).toList()
                        } else {
                            if (line.contains(query, ignoreCase = true)) {
                                listOf(0)
                            } else emptyList()
                        }

                        if (matches.isNotEmpty()) {
                            val contextStart = maxOf(0, index - contextLines)
                            val contextEnd = minOf(lines.size - 1, index + contextLines)
                            val context = lines.subList(contextStart, contextEnd + 1)
                                .mapIndexed { i, l ->
                                    val lineNum = contextStart + i + 1
                                    val prefix = if (lineNum == index + 1) ">>> " else "    "
                                    "$prefix${lineNum.toString().padStart(4)}: $l"
                                }.joinToString("\n")

                            results.add(
                                SearchResult(
                                    file = file.absolutePath,
                                    lineNumber = index + 1,
                                    match = line.trim(),
                                    context = context
                                )
                            )

                            if (results.size >= maxResults) return@forEach
                        }
                    }
                } catch (e: Exception) {
                    // Skip files that can't be read
                }
            }

        if (results.isEmpty()) {
            return "No matches found for query: $query"
        }

        val formattedResults = results.take(maxResults).joinToString("\n\n---\n\n") { result ->
            """
File: ${result.file}
Line ${result.lineNumber}: ${result.match}

Context:
${result.context}
            """.trimIndent()
        }

        return buildJsonObject {
            put("query", query)
            put("total_matches", results.size)
            put("showing", minOf(results.size, maxResults))
            put("results", formattedResults)
        }.toString()
    }

    /**
     * Инструмент для суммаризации текста с использованием LLM
     */
    private fun getSummarizeTool(): Tool {
        return Tool(
            name = "summarize",
            description = "Summarize text content using AI to extract key points",
            input_schema = InputSchema(
                type = "object",
                properties = buildJsonObject {
                    put("text", buildJsonObject {
                        put("type", "string")
                        put("description", "Text content to summarize")
                    })
                    put("style", buildJsonObject {
                        put("type", "string")
                        put("description", "Summary style: 'brief', 'detailed', 'bullets'")
                        put("enum", buildJsonArray { add("brief"); add("detailed"); add("bullets") })
                        put("default", "brief")
                    })
                    put("max_length", buildJsonObject {
                        put("type", "integer")
                        put("description", "Maximum length of summary in words")
                        put("default", 200)
                    })
                    put("language", buildJsonObject {
                        put("type", "string")
                        put("description", "Language for summary: 'en', 'ru'")
                        put("enum", buildJsonArray { add("en"); add("ru") })
                        put("default", "ru")
                    })
                },
                required = listOf("text")
            )
        )
    }

    private fun executeSummarize(input: JsonObject): String {
        val text = input["text"]?.jsonPrimitive?.content ?: return "Error: text parameter required"
        val style = input["style"]?.jsonPrimitive?.content ?: "brief"
        val maxLength = input["max_length"]?.jsonPrimitive?.int ?: 200
        val language = input["language"]?.jsonPrimitive?.content ?: "ru"

        if (text.length < 50) {
            return "Text too short to summarize meaningfully"
        }

        return runBlocking {
            try {
                val apiKey = System.getProperty("ANTHROPIC_API_KEY") ?: System.getenv("ANTHROPIC_API_KEY") ?: ""
                if (apiKey.isEmpty()) {
                    return@runBlocking "Error: ANTHROPIC_API_KEY not configured"
                }
                val anthropicClient = AnthropicClient(apiKey)

                val systemPrompt = when (language) {
                    "ru" -> """
                        Ты - ассистент для суммаризации текста. Твоя задача создать краткое, но содержательное содержание предоставленного текста.

                        Стиль суммаризации: $style
                        Максимальная длина: $maxLength слов
                        Язык ответа: русский

                        Инструкции:
                        - Выдели основные идеи и ключевые моменты
                        - Сохраняй логическую структуру
                        - Избегай деталей и второстепенной информации
                        ${if (style == "bullets") "- Используй маркированный список" else ""}
                    """.trimIndent()

                    else -> """
                        You are a text summarization assistant. Your task is to create a concise yet comprehensive summary of the provided text.

                        Summary style: $style
                        Maximum length: $maxLength words
                        Response language: English

                        Instructions:
                        - Extract main ideas and key points
                        - Maintain logical structure
                        - Avoid details and secondary information
                        ${if (style == "bullets") "- Use bullet points" else ""}
                    """.trimIndent()
                }

                val messages = listOf(
                    Message(
                        role = "user",
                        content = listOf(
                            ContentBlock(
                                type = "text",
                                text = "Просуммаризируй следующий текст:\n\n$text"
                            )
                        )
                    )
                )

                val response = anthropicClient.sendMessage(
                    messages = messages,
                    tools = emptyList(),
                    systemPrompt = systemPrompt,
                    temperature = 0.3
                )

                val summary = response.content
                    .filter { it.type == "text" }
                    .joinToString("\n") { it.text ?: "" }

                buildJsonObject {
                    put("original_length", text.split(" ").size)
                    put("summary_length", summary.split(" ").size)
                    put("style", style)
                    put("summary", summary)
                }.toString()

            } catch (e: Exception) {
                "Error generating summary: ${e.message}"
            }
        }
    }

    /**
     * Инструмент для сохранения контента в файл
     */
    private fun getSaveToFileTool(): Tool {
        return Tool(
            name = "save_to_file",
            description = "Save content to a file with optional metadata",
            input_schema = InputSchema(
                type = "object",
                properties = buildJsonObject {
                    put("content", buildJsonObject {
                        put("type", "string")
                        put("description", "Content to save to file")
                    })
                    put("file_path", buildJsonObject {
                        put("type", "string")
                        put("description", "Absolute or relative path to the output file")
                    })
                    put("create_dirs", buildJsonObject {
                        put("type", "boolean")
                        put("description", "Create parent directories if they don't exist")
                        put("default", true)
                    })
                    put("append", buildJsonObject {
                        put("type", "boolean")
                        put("description", "Append to existing file instead of overwriting")
                        put("default", false)
                    })
                    put("add_metadata", buildJsonObject {
                        put("type", "boolean")
                        put("description", "Add metadata header with timestamp and source")
                        put("default", true)
                    })
                    put("metadata", buildJsonObject {
                        put("type", "object")
                        put("description", "Additional metadata to include")
                        put("properties", buildJsonObject {
                            put("source", buildJsonObject {
                                put("type", "string")
                                put("description", "Source of the content")
                            })
                            put("author", buildJsonObject {
                                put("type", "string")
                                put("description", "Author or tool that created the content")
                            })
                            put("tags", buildJsonObject {
                                put("type", "array")
                                put("description", "Tags for categorization")
                                put("items", buildJsonObject { put("type", "string") })
                            })
                        })
                    })
                },
                required = listOf("content", "file_path")
            )
        )
    }

    private fun executeSaveToFile(input: JsonObject): String {
        val content = input["content"]?.jsonPrimitive?.content ?: return "Error: content parameter required"
        var filePath = input["file_path"]?.jsonPrimitive?.content ?: return "Error: file_path parameter required"
        val createDirs = input["create_dirs"]?.jsonPrimitive?.booleanOrNull ?: true
        val append = input["append"]?.jsonPrimitive?.booleanOrNull ?: false
        val addMetadata = input["add_metadata"]?.jsonPrimitive?.booleanOrNull ?: true
        val metadata = input["metadata"]?.jsonObject ?: JsonObject(emptyMap())

        val file = File(filePath).absoluteFile

        try {
            // Create parent directories if needed
            if (createDirs) {
                file.parentFile?.mkdirs()
            }

            // Build final content
            val finalContent = if (addMetadata) {
                val timestamp = java.time.Instant.now().toString()
                val source = metadata["source"]?.jsonPrimitive?.content ?: "Unknown"
                val author = metadata["author"]?.jsonPrimitive?.content ?: "MCP Tool"
                val tags = metadata["tags"]?.jsonArray?.joinToString(", ") { it.jsonPrimitive.content } ?: ""

                val metadataHeader = buildString {
                    appendLine("# Generated by MCP Tool Composition System")
                    appendLine("# Timestamp: $timestamp")
                    appendLine("# Source: $source")
                    appendLine("# Author: $author")
                    if (tags.isNotEmpty()) {
                        appendLine("# Tags: $tags")
                    }
                    appendLine("#" + "-".repeat(80))
                    appendLine()
                }

                metadataHeader + content
            } else {
                content
            }

            // Write to file
            if (append && file.exists()) {
                file.appendText("\n\n$finalContent")
            } else {
                file.writeText(finalContent)
            }

            val fileSize = file.length()
            val wordCount = finalContent.split("\\s+".toRegex()).size

            return buildJsonObject {
                put("success", true)
                put("file_path", file.absolutePath)
                put("file_size_bytes", fileSize)
                put("word_count", wordCount)
                put("operation", if (append && file.exists()) "appended" else "created")
                put("timestamp", java.time.Instant.now().toString())
            }.toString()

        } catch (e: Exception) {
            return buildJsonObject {
                put("success", false)
                put("error", e.message ?: "Unknown error")
                put("file_path", filePath)
            }.toString()
        }
    }

    /**
     * Инструмент для получения информации о GitHub репозитории
     */
    private fun getGitHubRepoInfoTool(): Tool {
        return Tool(
            name = "github_repo_info",
            description = "Get comprehensive information about a GitHub repository",
            input_schema = InputSchema(
                type = "object",
                properties = buildJsonObject {
                    put("owner", buildJsonObject {
                        put("type", "string")
                        put("description", "Repository owner username")
                    })
                    put("repo", buildJsonObject {
                        put("type", "string")
                        put("description", "Repository name")
                    })
                },
                required = listOf("owner", "repo")
            )
        )
    }

    /**
     * Инструмент для анализа файлов репозитория
     */
    private fun getGitHubRepoFilesTool(): Tool {
        return Tool(
            name = "github_repo_files",
            description = "Analyze repository files and structure",
            input_schema = InputSchema(
                type = "object",
                properties = buildJsonObject {
                    put("owner", buildJsonObject {
                        put("type", "string")
                        put("description", "Repository owner username")
                    })
                    put("repo", buildJsonObject {
                        put("type", "string")
                        put("description", "Repository name")
                    })
                    put("path", buildJsonObject {
                        put("type", "string")
                        put("description", "Directory path to analyze (default: root)")
                        put("default", "")
                    })
                    put("include_types", buildJsonObject {
                        put("type", "array")
                        put("description", "File types to include in analysis")
                        put("items", buildJsonObject { put("type", "string") })
                        put("default", buildJsonArray {
                            add("kt"); add("java"); add("js"); add("ts"); add("py"); add("md")
                            add("json"); add("yml"); add("yaml"); add("Dockerfile")
                        })
                    })
                },
                required = listOf("owner", "repo")
            )
        )
    }

    /**
     * Инструмент для анализа коммитов репозитория
     */
    private fun getGitHubRepoCommitsTool(): Tool {
        return Tool(
            name = "github_repo_commits",
            description = "Analyze recent commits and activity",
            input_schema = InputSchema(
                type = "object",
                properties = buildJsonObject {
                    put("owner", buildJsonObject {
                        put("type", "string")
                        put("description", "Repository owner username")
                    })
                    put("repo", buildJsonObject {
                        put("type", "string")
                        put("description", "Repository name")
                    })
                    put("limit", buildJsonObject {
                        put("type", "integer")
                        put("description", "Number of recent commits to analyze")
                        put("default", 10)
                    })
                },
                required = listOf("owner", "repo")
            )
        )
    }

    /**
     * Инструмент для анализа задач (issues) репозитория
     */
    private fun getGitHubRepoIssuesTool(): Tool {
        return Tool(
            name = "github_repo_issues",
            description = "Analyze repository issues and pull requests",
            input_schema = InputSchema(
                type = "object",
                properties = buildJsonObject {
                    put("owner", buildJsonObject {
                        put("type", "string")
                        put("description", "Repository owner username")
                    })
                    put("repo", buildJsonObject {
                        put("type", "string")
                        put("description", "Repository name")
                    })
                    put("state", buildJsonObject {
                        put("type", "string")
                        put("description", "Filter issues by state")
                        put("enum", buildJsonArray { add("open"); add("closed"); add("all") })
                        put("default", "open")
                    })
                    put("labels", buildJsonObject {
                        put("type", "array")
                        put("description", "Filter by labels")
                        put("items", buildJsonObject { put("type", "string") })
                    })
                },
                required = listOf("owner", "repo")
            )
        )
    }

    // GitHub Tool Execution Methods
    private fun executeGitHubRepoInfo(input: JsonObject): String {
        val owner = input["owner"]?.jsonPrimitive?.content ?: return "Error: owner parameter required"
        val repo = input["repo"]?.jsonPrimitive?.content ?: return "Error: repo parameter required"

        return runCatching {
            runBlocking {
                val gitHubServer = GitHubMcpServer()
                val result = gitHubServer.executeTool(
                    "github_get_repository",
                    mapOf("owner" to owner, "repo" to repo)
                )
                gitHubServer.close()
                result
            }
        }.getOrElse { "Error getting repository info: ${it.message}" }
    }

    private suspend fun executeGitHubRepoFiles(input: JsonObject): String {
        val owner = input["owner"]?.jsonPrimitive?.content ?: return "Error: owner parameter required"
        val repo = input["repo"]?.jsonPrimitive?.content ?: return "Error: repo parameter required"
        val path = input["path"]?.jsonPrimitive?.content ?: ""
        val includeTypes = input["include_types"]?.jsonArray?.map {
            it.jsonPrimitive.content
        } ?: listOf("kt", "java", "md")

        return runCatching {
            // Анализируем структуру и файлы
            val analysis = GitHubFileAnalyzer.analyzeRepositoryFiles(owner, repo, path, includeTypes)
            buildJsonObject {
                put("success", true)
                put("analysis", analysis)
                put("repository", "$owner/$repo")
                put("path", path.ifEmpty { "/" })
            }.toString()
        }.getOrElse { "Error analyzing repository files: ${it.message}" }
    }

    private suspend fun executeGitHubRepoCommits(input: JsonObject): String {
        val owner = input["owner"]?.jsonPrimitive?.content ?: return "Error: owner parameter required"
        val repo = input["repo"]?.jsonPrimitive?.content ?: return "Error: repo parameter required"
        val limit = input["limit"]?.jsonPrimitive?.int ?: 100

        return runCatching {
            // Анализируем коммиты
            val analysis = GitHubCommitAnalyzer.analyzeRepositoryCommits(owner, repo, limit)
            buildJsonObject {
                put("success", true)
                put("analysis", analysis)
                put("repository", "$owner/$repo")
                put("analyzed_commits", limit)
            }.toString()
        }.getOrElse { "Error analyzing commits: ${it.message}" }
    }

    private suspend fun executeGitHubRepoIssues(input: JsonObject): String {
        val owner = input["owner"]?.jsonPrimitive?.content ?: return "Error: owner parameter required"
        val repo = input["repo"]?.jsonPrimitive?.content ?: return "Error: repo parameter required"
        val limit = input["limit"]?.jsonPrimitive?.int ?: 100

        return runCatching {
            // Анализируем issues
            val analysis = GitHubIssueAnalyzer.analyzeRepositoryIssues(owner, repo, limit)
            buildJsonObject {
                put("success", true)
                put("analysis", analysis)
                put("repository", "$owner/$repo")
                put("analyzed_issues", limit)
            }.toString()
        }.getOrElse { "Error analyzing issues: ${it.message}" }
    }

    private suspend fun generateGitHubReport(input: JsonObject): String {
        val owner = input["owner"]?.jsonPrimitive?.content ?: return "Error: owner parameter required"
        val repo = input["repo"]?.jsonPrimitive?.content ?: return "Error: repo parameter required"
        val format = input["format"]?.jsonPrimitive?.content ?: "markdown"
        val includeSections = input["include_sections"]?.jsonArray?.map {
            it.jsonPrimitive.content
        } ?: listOf("repo_info", "files", "commits", "issues")

        return runCatching {
            // Собираем все данные для отчета
            val reportData = mutableMapOf<String, Any>()

            if (includeSections.contains("repo_info")) {
                val repoInfo = executeGitHubRepoInfo(buildJsonObject {
                    put("owner", owner)
                    put("repo", repo)
                })
                reportData["repository_info"] = repoInfo
            }

            if (includeSections.contains("files")) {
                val fileAnalysis = GitHubFileAnalyzer.analyzeRepositoryFiles(owner, repo)
                reportData["file_analysis"] = fileAnalysis
            }

            if (includeSections.contains("commits")) {
                val commitAnalysis = GitHubCommitAnalyzer.analyzeRepositoryCommits(owner, repo, 100)
                reportData["commit_analysis"] = commitAnalysis
            }

            if (includeSections.contains("issues")) {
                val issueAnalysis = GitHubIssueAnalyzer.analyzeRepositoryIssues(owner, repo, 100)
                reportData["issue_analysis"] = issueAnalysis
            }

            // Генерируем отчет в нужном формате
            val report = when (format.lowercase()) {
                "markdown" -> generateMarkdownReport(owner, repo, reportData, includeSections)
                "html" -> generateHtmlReport(owner, repo, reportData, includeSections)
                "json" -> buildJsonObject {
                    reportData.forEach { (key, value) ->
                        put(key, Json.parseToJsonElement(value.toString()))
                    }
                }.toString()

                else -> generateMarkdownReport(owner, repo, reportData, includeSections)
            }

            // Сохраняем отчет в файл
            val filename = "${owner}_${repo}_analysis_report.${format.lowercase()}"
            val filePath = "reports/$filename"

            // Создаем директорию если нужно
            val reportsDir = File("reports")
            reportsDir.mkdirs()

            val reportFile = File(filePath)
            reportFile.writeText(report)

            buildJsonObject {
                put("success", true)
                put("message", "GitHub repository analysis report generated successfully")
                put("repository", "$owner/$repo")
                put("format", format)
                put("filename", filename)
                put("file_path", filePath)
                put("file_size", File(filePath).length())
                put("sections", buildJsonArray { includeSections.forEach { add(it) } })
            }.toString()

        }.getOrElse { "Error generating GitHub report: ${it.message}" }
    }

    private fun generateMarkdownReport(
        owner: String,
        repo: String,
        data: Map<String, Any>,
        sections: List<String>,
    ): String {
        return buildString {
            appendLine("# 📊 GitHub Repository Analysis Report")
            appendLine()
            appendLine("## Repository: **$owner/$repo**")
            appendLine("Generated on: ${java.time.LocalDateTime.now()}")
            appendLine()

            if (sections.contains("repo_info") && data.containsKey("repository_info")) {
                val repoInfo = data["repository_info"].toString()
                appendLine("## 📋 Repository Information")
                appendLine(repoInfo)
                appendLine()
            }

            if (sections.contains("files") && data.containsKey("file_analysis")) {
                val fileAnalysis = data["file_analysis"].toString()
                appendLine("## 📁 File Structure Analysis")
                appendLine(fileAnalysis)
                appendLine()
            }

            if (sections.contains("commits") && data.containsKey("commit_analysis")) {
                val commitAnalysis = data["commit_analysis"].toString()
                appendLine("## 📝 Commit History Analysis")
                appendLine(commitAnalysis)
                appendLine()
            }

            if (sections.contains("issues") && data.containsKey("issue_analysis")) {
                val issueAnalysis = data["issue_analysis"].toString()
                appendLine("## 🐛 Issues and Pull Requests Analysis")
                appendLine(issueAnalysis)
                appendLine()
            }

            appendLine("---")
            appendLine("Report generated by AI Challenge MCP Composition Tools")
        }
    }

    private fun generateHtmlReport(
        owner: String,
        repo: String,
        data: Map<String, Any>,
        sections: List<String>,
    ): String {
        return buildString {
            appendLine("<!DOCTYPE html>")
            appendLine("<html lang=\"en\">")
            appendLine("<head>")
            appendLine("<meta charset=\"UTF-8\">")
            appendLine("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">")
            appendLine("<title>GitHub Repository Analysis - $owner/$repo</title>")
            appendLine("<style>")
            appendLine("body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; margin: 40px; line-height: 1.6; background: #f8f9fa; }")
            appendLine(".container { max-width: 1200px; margin: 0 auto; background: white; padding: 40px; border-radius: 8px; box-shadow: 0 2px 10px rgba(0,0,0,0.1); }")
            appendLine("h1 { color: #0366d6; border-bottom: 3px solid #0366d6; padding-bottom: 10px; }")
            appendLine("h2 { color: #24292e; margin-top: 40px; border-left: 4px solid #0366d6; padding-left: 15px; }")
            appendLine("h3 { color: #586069; margin-top: 20px; }")
            appendLine(".section { margin: 30px 0; padding: 25px; background: #f6f8fa; border-radius: 6px; border-left: 4px solid #0366d6; }")
            appendLine(".stats-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 20px; margin: 20px 0; }")
            appendLine(".stat-card { background: white; padding: 20px; border-radius: 6px; text-align: center; box-shadow: 0 1px 3px rgba(0,0,0,0.1); }")
            appendLine(".stat-number { font-size: 2em; font-weight: bold; color: #0366d6; display: block; }")
            appendLine(".stat-label { color: #586069; font-size: 0.9em; margin-top: 5px; }")
            appendLine("pre { background: white; padding: 15px; border-radius: 6px; overflow-x: auto; font-size: 14px; line-height: 1.4; }")
            appendLine("code { background: #f1f3f4; padding: 2px 4px; border-radius: 3px; font-family: 'JetBrains Mono', monospace; }")
            appendLine(".list-item { padding: 10px 0; border-bottom: 1px solid #e1e4e8; }")
            appendLine(".list-item:last-child { border-bottom: none; }")
            appendLine(".metric { display: flex; justify-content: space-between; padding: 8px 0; }")
            appendLine(".metric-label { font-weight: 600; color: #24292e; }")
            appendLine(".metric-value { color: #586069; }")
            appendLine(".high-priority { color: #d73a49; font-weight: bold; }")
            appendLine(".medium-priority { color: #f66a0a; font-weight: bold; }")
            appendLine(".low-priority { color: #28a745; font-weight: bold; }")
            appendLine(".download-btn { display: inline-block; background: #0366d6; color: white; padding: 12px 24px; text-decoration: none; border-radius: 6px; font-weight: 600; margin: 20px 0; transition: background 0.3s; }")
            appendLine(".download-btn:hover { background: #0256cc; }")
            appendLine("</style>")
            appendLine("</head>")
            appendLine("<body>")
            appendLine("<div class=\"container\">")
            appendLine("<h1>📊 GitHub Repository Analysis Report</h1>")
            appendLine("<p><strong>Repository:</strong> <a href=\"https://github.com/$owner/$repo\" target=\"_blank\">$owner/$repo</a><br>")
            appendLine("<strong>Generated on:</strong> ${java.time.LocalDateTime.now()}</p>")

            // Download button
            val filename = "${owner}_${repo}_analysis_report.html"
            appendLine("<a href=\"$filename\" class=\"download-btn\" download=\"💾 Download Report\">💾 Download Report</a>")

            sections.forEach { section ->
                when (section) {
                    "repo_info" -> {
                        appendLine("<div class=\"section\">")
                        appendLine("<h2>📋 Repository Information</h2>")
                        if (data.containsKey("repository_info")) {
                            val repoInfo = formatRepositoryInfo(data["repository_info"].toString())
                            appendLine(repoInfo)
                        }
                        appendLine("</div>")
                    }

                    "files" -> {
                        appendLine("<div class=\"section\">")
                        appendLine("<h2>📁 File Structure Analysis</h2>")
                        if (data.containsKey("file_analysis")) {
                            val fileAnalysis = formatFileAnalysis(data["file_analysis"].toString())
                            appendLine(fileAnalysis)
                        }
                        appendLine("</div>")
                    }

                    "commits" -> {
                        appendLine("<div class=\"section\">")
                        appendLine("<h2>📝 Commit History Analysis</h2>")
                        if (data.containsKey("commit_analysis")) {
                            val commitAnalysis = formatCommitAnalysis(data["commit_analysis"].toString())
                            appendLine(commitAnalysis)
                        }
                        appendLine("</div>")
                    }

                    "issues" -> {
                        appendLine("<div class=\"section\">")
                        appendLine("<h2>🐛 Issues and Pull Requests Analysis</h2>")
                        if (data.containsKey("issue_analysis")) {
                            val issueAnalysis = formatIssueAnalysis(data["issue_analysis"].toString())
                            appendLine(issueAnalysis)
                        }
                        appendLine("</div>")
                    }
                }
            }

            appendLine("<hr>")
            appendLine("<p style=\"text-align: center; color: #586069; margin-top: 30px;\"><em>Report generated by AI Challenge MCP Composition Tools</em></p>")
            appendLine("</div>")
            appendLine("</body>")
            appendLine("</html>")
        }
    }

    private fun formatRepositoryInfo(jsonString: String): String {
        return try {
            val json = Json { prettyPrint = true }.parseToJsonElement(jsonString)
            buildString {
                appendLine("<div class=\"stats-grid\">")
                if (json.jsonObject.contains("name")) {
                    appendLine("<div class=\"stat-card\">")
                    appendLine("<span class=\"stat-number\">📝</span>")
                    appendLine("<span class=\"stat-label\">${json.jsonObject["name"]?.jsonPrimitive?.content}</span>")
                    appendLine("</div>")
                }
                if (json.jsonObject.contains("description")) {
                    appendLine("<div class=\"stat-card\" style=\"grid-column: span 2;\">")
                    appendLine("<div>${json.jsonObject["description"]?.jsonPrimitive?.content}</div>")
                    appendLine("</div>")
                }
                if (json.jsonObject.contains("language")) {
                    appendLine("<div class=\"stat-card\">")
                    appendLine("<span class=\"stat-number\">💻</span>")
                    appendLine("<span class=\"stat-label\">${json.jsonObject["language"]?.jsonPrimitive?.content}</span>")
                    appendLine("</div>")
                }
                if (json.jsonObject.contains("stars")) {
                    appendLine("<div class=\"stat-card\">")
                    appendLine("<span class=\"stat-number\">⭐</span>")
                    appendLine("<span class=\"stat-label\">${json.jsonObject["stars"]?.jsonPrimitive?.content}</span>")
                    appendLine("</div>")
                }
                if (json.jsonObject.contains("forks")) {
                    appendLine("<div class=\"stat-card\">")
                    appendLine("<span class=\"stat-number\">🔀</span>")
                    appendLine("<span class=\"stat-label\">${json.jsonObject["forks"]?.jsonPrimitive?.content}</span>")
                    appendLine("</div>")
                }
                appendLine("</div>")
            }
        } catch (e: Exception) {
            "<pre>$jsonString</pre>"
        }
    }

    private fun formatFileAnalysis(jsonString: String): String {
        return try {
            val json = Json { prettyPrint = true }.parseToJsonElement(jsonString)
            buildString {
                appendLine("<div class=\"stats-grid\">")
                appendLine("<div class=\"stat-card\">")
                appendLine("<span class=\"stat-number\">${json.jsonObject["total_files"]?.jsonPrimitive?.content}</span>")
                appendLine("<span class=\"stat-label\">Total Files</span>")
                appendLine("</div>")
                appendLine("<div class=\"stat-card\">")
                appendLine(
                    "<span class=\"stat-number\">${
                        (json.jsonObject["total_size_bytes"]?.jsonPrimitive?.content.toString().toLong() ?: 0) / 1024
                    } KB</span>"
                )
                appendLine("<span class=\"stat-label\">Size</span>")
                appendLine("</div>")
                appendLine("<div class=\"stat-card\">")
                appendLine("<span class=\"stat-number\">${json.jsonObject["complexity_score"]?.jsonPrimitive?.content}</span>")
                appendLine("<span class=\"stat-label\">Complexity Score</span>")
                appendLine("</div>")
                appendLine("</div>")

                // Languages
                val languages = json.jsonObject["languages"]?.jsonObject ?: return ""
                if (languages.jsonObject.isNotEmpty()) {
                    appendLine("<h3>💻 Languages Used</h3>")
                    appendLine("<div class=\"stats-grid\">")
                    languages.jsonObject.forEach { (lang, count) ->
                        appendLine("<div class=\"stat-card\">")
                        appendLine("<div>${lang}</div>")
                        appendLine("<div class=\"stat-number\">${count.jsonPrimitive?.content}</div>")
                        appendLine("</div>")
                    }
                    appendLine("</div>")
                }

                // Largest files
                val largestFiles = json.jsonObject["largest_files"]?.jsonArray
                if (largestFiles != null && largestFiles.jsonArray.isNotEmpty()) {
                    appendLine("<h3>📄 Largest Files</h3>")
                    largestFiles.jsonArray.take(5).forEach { file ->
                        val fileName = file.jsonObject["name"]?.jsonPrimitive?.content ?: ""
                        val fileSize = file.jsonObject["size"]?.jsonPrimitive?.content ?: ""
                        appendLine("<div class=\"list-item\">")
                        appendLine("<strong>$fileName</strong> - ${formatFileSize(fileSize.toString().toLong())}")
                        appendLine("</div>")
                    }
                }
            }
        } catch (e: Exception) {
            "<pre>$jsonString</pre>"
        }
    }

    private fun formatCommitAnalysis(jsonString: String): String {
        return try {
            val json = Json { prettyPrint = true }.parseToJsonElement(jsonString)
            buildString {
                appendLine("<div class=\"stats-grid\">")
                appendLine("<div class=\"stat-card\">")
                appendLine("<span class=\"stat-number\">${json.jsonObject["total_commits_analyzed"]?.jsonPrimitive?.content}</span>")
                appendLine("<span class=\"stat-label\">Commits Analyzed</span>")
                appendLine("</div>")

                val activity = json.jsonObject["development_activity"]?.jsonObject
                if (activity != null) {
                    val activityLevel = activity.jsonObject["activity_level"]?.jsonPrimitive?.content
                    val color = when (activityLevel) {
                        "Very High" -> "#d73a49"
                        "High" -> "#f66a0a"
                        "Medium" -> "#1a7f37"
                        else -> "#6c757d"
                    }
                    appendLine("<div class=\"stat-card\" style=\"border-top: 3px solid $color;\">")
                    appendLine("<span class=\"stat-number\" style=\"color: $color;\">$activityLevel</span>")
                    appendLine("<span class=\"stat-label\">Activity Level</span>")
                    appendLine("</div>")
                }
                appendLine("</div>")

                // Top authors
                val authors = json.jsonObject["most_active_authors"]?.jsonArray
                if (authors != null && authors.jsonArray.isNotEmpty()) {
                    appendLine("<h3>👥 Top Contributors</h3>")
                    authors.jsonArray.take(10).forEach { author ->
                        val authorName = author.jsonObject["author"]?.jsonPrimitive?.content ?: ""
                        val commits = author.jsonObject["commits"]?.jsonPrimitive?.content ?: ""
                        appendLine("<div class=\"metric\">")
                        appendLine("<span class=\"metric-label\">$authorName</span>")
                        appendLine("<span class=\"metric-value\">$commits commits</span>")
                        appendLine("</div>")
                    }
                }
            }
        } catch (e: Exception) {
            "<pre>$jsonString</pre>"
        }
    }

    private fun formatIssueAnalysis(jsonString: String): String {
        return try {
            val json = Json { prettyPrint = true }.parseToJsonElement(jsonString)
            buildString {
                appendLine("<div class=\"stats-grid\">")
                appendLine("<div class=\"stat-card\">")
                appendLine("<span class=\"stat-number\">${json.jsonObject["total_issues_analyzed"]?.jsonPrimitive?.content}</span>")
                appendLine("<span class=\"stat-label\">Total Issues</span>")
                appendLine("</div>")
                appendLine("<div class=\"stat-card\">")
                appendLine("<span class=\"stat-number\">${json.jsonObject["open_issues"]?.jsonPrimitive?.content}</span>")
                appendLine("<span class=\"stat-label\">Open</span>")
                appendLine("</div>")
                appendLine("<div class=\"stat-card\">")
                appendLine("<span class=\"stat-number\">${json.jsonObject["closed_issues"]?.jsonPrimitive?.content}</span>")
                appendLine("<span class=\"stat-label\">Closed</span>")
                appendLine("</div>")
                appendLine("<div class=\"stat-card\">")
                appendLine("<span class=\"stat-number\">${json.jsonObject["pull_requests"]?.jsonPrimitive?.content}</span>")
                appendLine("<span class=\"stat-label\">Pull Requests</span>")
                appendLine("</div>")
                appendLine("</div>")

                // Issue priorities
                val priorities = json.jsonObject["issue_priorities"]?.jsonObject
                if (priorities != null) {
                    appendLine("<h3>🎯 Issue Priorities</h3>")
                    appendLine("<div class=\"list-item\">")
                    appendLine("<span class=\"high-priority\">Critical: ${priorities.jsonObject["critical"]?.jsonPrimitive?.content ?: 0}</span>")
                    appendLine("<span class=\"medium-priority\">High: ${priorities.jsonObject["high"]?.jsonPrimitive?.content ?: 0}</span>")
                    appendLine("<span class=\"low-priority\">Medium: ${priorities.jsonObject["medium"]?.jsonPrimitive?.content ?: 0}</span>")
                    appendLine("<span class=\"low-priority\">Low: ${priorities.jsonObject["low"]?.jsonPrimitive?.content ?: 0}</span>")
                    appendLine("</div>")
                }
            }
        } catch (e: Exception) {
            "<pre>$jsonString</pre>"
        }
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
            else -> "${bytes / (1024 * 1024 * 1024)} GB"
        }
    }

    /**
     * Инструмент для генерации комплексного отчета о GitHub репозитории
     */
    private fun getGitHubReportTool(): Tool {
        return Tool(
            name = "generate_github_report",
            description = "Generate a comprehensive analysis report for a GitHub repository in downloadable format",
            input_schema = InputSchema(
                type = "object",
                properties = buildJsonObject {
                    put("owner", buildJsonObject {
                        put("type", "string")
                        put("description", "Repository owner username")
                    })
                    put("repo", buildJsonObject {
                        put("type", "string")
                        put("description", "Repository name")
                    })
                    put("format", buildJsonObject {
                        put("type", "string")
                        put("description", "Output format for the report")
                        put("enum", buildJsonArray { add("markdown"); add("html"); add("json") })
                        put("default", "markdown")
                    })
                    put("include_sections", buildJsonObject {
                        put("type", "array")
                        put("description", "Sections to include in the report")
                        put("items", buildJsonObject {
                            put("type", "string")
                            put(
                                "enum",
                                buildJsonArray { add("repo_info"); add("files"); add("commits"); add("issues") })
                        })
                        put("default", buildJsonArray { add("repo_info"); add("files"); add("commits"); add("issues") })
                    })
                },
                required = listOf("owner", "repo")
            )
        )
    }

    /**
     * Data class для хранения результатов поиска
     */
    private data class SearchResult(
        val file: String,
        val lineNumber: Int,
        val match: String,
        val context: String,
    )
}
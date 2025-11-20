package com.markettwits.aichallenge

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*

/**
 * Анализатор структуры и файлов GitHub репозитория
 */
object GitHubFileAnalyzer {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient(CIO)
    private val baseUrl = "https://api.github.com"
    private val gitHubToken = System.getProperty("GITHUB_TOKEN") ?: System.getenv("GITHUB_TOKEN") ?: ""

    suspend fun analyzeRepositoryFiles(
        owner: String,
        repo: String,
        path: String = "",
        includeTypes: List<String> = listOf("kt", "java", "md"),
    ): JsonObject {
        try {
            // Получаем содержимое директории
            val contentsUrl = "$baseUrl/repos/$owner/$repo/contents/$path"
            val response = client.get(contentsUrl) {
                header("Accept", "application/vnd.github.v3+json")
                if (gitHubToken.isNotEmpty()) {
                    header("Authorization", "token $gitHubToken")
                }
            }

            if (!response.status.isSuccess()) {
                return createErrorAnalysis("Failed to fetch repository contents")
            }

            val contents = json.decodeFromString<List<JsonElement>>(response.bodyAsText())
            val analysis = analyzeDirectoryContents(contents, owner, repo, path, includeTypes)

            return buildJsonObject {
                put("total_files", analysis.totalFiles)
                put("total_size_bytes", analysis.totalSizeBytes)
                put("languages", buildJsonObject {
                    analysis.languages.forEach { (lang, count) ->
                        put(lang, count)
                    }
                })
                put("file_types", buildJsonObject {
                    analysis.fileTypes.forEach { (type, count) ->
                        put(type, count)
                    }
                })
                put("largest_files", buildJsonArray {
                    analysis.largestFiles.forEach { file ->
                        add(buildJsonObject {
                            put("name", file.name)
                            put("size", file.size)
                            put("path", file.path)
                            put("type", file.type)
                            put("language", file.language)
                        })
                    }
                })
                put("readme_found", analysis.readmeFound)
                put("license_found", analysis.licenseFound)
                put("directories", buildJsonArray {
                    analysis.directories.forEach { dir ->
                        add(dir)
                    }
                })
                put("source_files", analysis.sourceFiles)
                put("complexity_score", analysis.complexityScore)
            }

        } catch (e: Exception) {
            return createErrorAnalysis("Error analyzing repository: ${e.message}")
        }
    }

    private fun analyzeDirectoryContents(
        contents: List<JsonElement>,
        owner: String,
        repo: String,
        currentPath: String,
        includeTypes: List<String>,
    ): FileAnalysisResult {
        var totalFiles = 0
        var totalSizeBytes = 0L
        val languages = mutableMapOf<String, Int>()
        val fileTypes = mutableMapOf<String, Int>()
        val largestFiles = mutableListOf<FileItem>()
        var readmeFound = false
        var licenseFound = false
        val directories = mutableListOf<String>()
        val sourceFiles = mutableListOf<FileItem>()

        for (item in contents) {
            if (item.jsonObject["type"]?.jsonPrimitive?.content == "dir") {
                val dirPath = item.jsonObject["name"]?.jsonPrimitive?.content ?: ""
                directories.add(dirPath)
            } else if (item.jsonObject["type"]?.jsonPrimitive?.content == "file") {
                val fileName = item.jsonObject["name"]?.jsonPrimitive?.content ?: ""
                val fileSize = item.jsonObject["size"]?.jsonPrimitive?.long ?: 0L
                item.jsonObject["download_url"]?.jsonPrimitive?.content ?: ""

                totalFiles++
                totalSizeBytes += fileSize

                // Определяем тип файла
                val extension = fileName.substringAfterLast('.').lowercase()
                fileTypes[extension] = fileTypes.getOrDefault(extension, 0) + 1

                // Определяем язык программирования
                val language = determineLanguage(fileName, extension)
                languages[language] = languages.getOrDefault(language, 0) + 1

                // Сохраняем информацию о файлах
                if (includeTypes.contains(extension) && fileSize > 1000) {
                    largestFiles.add(
                        FileItem(
                            name = fileName,
                            size = fileSize,
                            path = "$currentPath/$fileName",
                            type = extension,
                            language = language
                        )
                    )
                }

                // Проверяем наличие важных файлов
                if (fileName.lowercase().contains("readme")) readmeFound = true
                if (fileName.lowercase().contains("license")) licenseFound = true

                // Анализируем содержимое исходных файлов для оценки сложности
                if (includeTypes.contains(extension) && fileSize < 50000) {
                    sourceFiles.add(
                        FileItem(
                            name = fileName,
                            size = fileSize,
                            path = "$currentPath/$fileName",
                            type = extension,
                            language = language
                        )
                    )
                }
            }
        }

        // Сортируем файлы по размеру
        val sortedLargestFiles = largestFiles.sortedByDescending { it.size }.take(10)

        // Оцениваем сложность проекта на основе структуры и файлов
        val complexityScore = calculateComplexityScore(
            totalFiles, totalSizeBytes, languages.size, directories.size
        )

        return FileAnalysisResult(
            totalFiles = totalFiles,
            totalSizeBytes = totalSizeBytes,
            languages = languages,
            fileTypes = fileTypes,
            largestFiles = sortedLargestFiles,
            readmeFound = readmeFound,
            licenseFound = licenseFound,
            directories = directories,
            sourceFiles = sourceFiles.size,
            complexityScore = complexityScore
        )
    }

    private fun determineLanguage(fileName: String, extension: String): String {
        return when (extension) {
            "kt", "kts" -> "Kotlin"
            "java", "jar" -> "Java"
            "js", "jsx", "mjs", "cjs" -> "JavaScript"
            "ts", "tsx" -> "TypeScript"
            "py", "pyc", "pyd", "pyw" -> "Python"
            "go" -> "Go"
            "rs" -> "Rust"
            "cpp", "cc", "cxx" -> "C++"
            "c", "h" -> "C"
            "cs" -> "C#"
            "php" -> "PHP"
            "rb" -> "Ruby"
            "swift" -> "Swift"
            "scala" -> "Scala"
            "html", "htm" -> "HTML"
            "css", "scss", "sass", "less" -> "CSS"
            "json" -> "JSON"
            "yaml", "yml" -> "YAML"
            "xml" -> "XML"
            "sql" -> "SQL"
            "md", "markdown" -> "Markdown"
            "dockerfile" -> "Docker"
            "gradle", "kts" -> "Gradle"
            else -> "Other"
        }
    }

    private fun calculateComplexityScore(
        totalFiles: Int,
        totalSizeBytes: Long,
        languageCount: Int,
        directoryCount: Int,
    ): Int {
        var score = 0

        // Базовые очки за количество файлов
        score += (totalFiles / 10).coerceAtMost(20)

        // Очки за размер проекта
        score += (totalSizeBytes.toInt() / (1024 * 1024)).coerceAtMost(25)

        // Очки за разнообразие языков
        score += (languageCount * 3).coerceAtMost(15)

        // Очки за сложную структуру (много директорий)
        score += (directoryCount / 5).coerceAtMost(10)

        return score.coerceIn(0, 100)
    }

    private fun createErrorAnalysis(error: String): JsonObject {
        return buildJsonObject {
            put("success", false)
            put("error", error)
            put("analysis", buildJsonObject {
                put("total_files", 0)
                put("total_size_bytes", 0)
                put("languages", buildJsonObject {})
                put("file_types", buildJsonObject {})
                put("largest_files", buildJsonArray {})
                put("readme_found", false)
                put("license_found", false)
                put("complexity_score", 0)
            })
        }
    }
}

data class FileItem(
    val name: String,
    val size: Long,
    val path: String,
    val type: String,
    val language: String,
)

data class FileAnalysisResult(
    val totalFiles: Int,
    val totalSizeBytes: Long,
    val languages: Map<String, Int>,
    val fileTypes: Map<String, Int>,
    val largestFiles: List<FileItem>,
    val readmeFound: Boolean,
    val licenseFound: Boolean,
    val directories: List<String>,
    val sourceFiles: Int,
    val complexityScore: Int,
)
package com.markettwits.aichallenge.personalized

import com.markettwits.aichallenge.*
import com.markettwits.aichallenge.voice.WhisperClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.io.File
import java.util.*

@Serializable
data class PersonalAssistantChatRequest(
    val message: String,
    val sessionId: String? = null,
    val temperature: Double? = null,
    val profile: String? = null,
    val fileIds: List<String>? = null,
)

@Serializable
data class PersonalAssistantChatResponse(
    val sessionId: String,
    val response: String,
    val usage: Usage? = null,
    val messageCount: Int,
)

@Serializable
data class PersonalAssistantVoiceResponse(
    val sessionId: String,
    val transcript: String,
    val answer: String,
    val language: String? = null,
    val usage: Usage? = null,
    val rawTranscription: String? = null,
    val profile: String? = null,
)

@Serializable
data class PersonalAssistantStatus(
    val available: Boolean,
    val whisperConfigured: Boolean,
    val whisperUrl: String? = null,
    val model: String = "claude-3-5-haiku-20241022",
    val message: String? = null,
)

@Serializable
data class PersonalAssistantProfiles(
    val profiles: List<String>,
    val active: String,
)

@Serializable
data class PersonalAssistantFile(
    val id: String,
    val filename: String,
    val sizeBytes: Long? = null,
    val createdAt: String? = null,
    val mimeType: String? = null,
)

@Serializable
data class PersonalAssistantFilesResponse(
    val files: List<PersonalAssistantFile>,
    val count: Int,
)

/**
 * Личный ассистент на базе Claude + Whisper с персональным профилем.
 * Работает в текстовом и голосовом режимах, сохраняет контекст диалога.
 */
class PersonalAssistantService(
    private val anthropicClient: AnthropicClient,
    private val anthropicFilesClient: AnthropicFilesClient,
    private val whisperClient: WhisperClient?,
    private val repository: ConversationRepository,
    private val configPath: String = "data/personal_agent_config.json",
    private val profilesDirPath: String = "data/personal_profiles",
) {
    private val logger = LoggerFactory.getLogger(PersonalAssistantService::class.java)
    private val promptCompiler = PromptCompiler()
    private val maxHistoryMessages = 20
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    private val profilesDir = File(profilesDirPath)

    private val profilesCache = mutableMapOf<String, PersonalAgentConfig>()
    private val filesCache = mutableListOf<PersonalAssistantFile>()

    @Volatile
    private var activeProfileName: String = "default"

    init {
        profilesDir.mkdirs()
        val defaultConfig = loadConfig("default")
        profilesCache["default"] = defaultConfig
        activeProfileName = "default"
    }

    fun getProfiles(): PersonalAssistantProfiles {
        val names = profilesDir.listFiles()
            ?.filter { it.isFile && it.extension == "json" }
            ?.map { it.nameWithoutExtension }
            ?.toMutableSet()
            ?: mutableSetOf()
        names.add("default")
        return PersonalAssistantProfiles(
            profiles = names.sorted(),
            active = activeProfileName
        )
    }

    fun getProfile(name: String? = null): PersonalAgentConfig {
        val target = name?.ifBlank { null } ?: activeProfileName
        return loadConfig(target)
    }

    fun updateProfile(name: String?, config: PersonalAgentConfig) {
        val profileName = name?.ifBlank { "default" } ?: "default"
        saveConfig(profileName, config)
        profilesCache[profileName] = config
        logger.info("Personal assistant profile '$profileName' updated")
        if (profileName == activeProfileName) {
            logger.info("Active profile updated")
        }
    }

    fun activateProfile(name: String) {
        val profileName = name.ifBlank { "default" }
        // Ensure profile exists (load or fallback to default)
        loadConfig(profileName)
        activeProfileName = profileName
        logger.info("Active profile set to $profileName")
    }

    fun getCompiledPrompt(profile: String? = null): String = buildSystemPrompt(profile)

    fun getStatus(localWhisperUrl: String): PersonalAssistantStatus {
        val whisperConfigured = whisperClient != null && localWhisperUrl.isNotBlank()
        return PersonalAssistantStatus(
            available = true,
            whisperConfigured = whisperConfigured,
            whisperUrl = if (whisperConfigured) localWhisperUrl else null,
        )
    }

    suspend fun chat(request: PersonalAssistantChatRequest): PersonalAssistantChatResponse {
        val sessionId = request.sessionId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        val profileName = request.profile?.ifBlank { null } ?: activeProfileName
        val userMessage = request.message.trim()

        if (userMessage.isBlank()) {
            throw IllegalArgumentException("Сообщение не должно быть пустым")
        }

        val history = repository.loadMessages(sessionId)
        val trimmedHistory = if (history.size > maxHistoryMessages) {
            history.takeLast(maxHistoryMessages)
        } else {
            history
        }

        val userContent = Message(
            role = "user",
            content = listOf(ContentBlock(type = "text", text = userMessage))
        )

        val messagesForModel = mutableListOf<Message>()
        messagesForModel.addAll(trimmedHistory)
        messagesForModel.add(userContent)

        val systemPrompt = buildSystemPrompt(profileName)

        logger.info("Personal assistant request: session=$sessionId, history=${messagesForModel.size}")

        val extraBlocks = buildFileBlocks(request.fileIds)
        if (extraBlocks.isNotEmpty()) {
            // Attach file blocks as part of user content
            messagesForModel.removeLast()
            messagesForModel.add(
                Message(
                    role = "user",
                    content = userContent.content + extraBlocks
                )
            )
        }

        val extraHeaders = if (extraBlocks.isNotEmpty()) {
            mapOf("anthropic-beta" to "files-api-2025-04-14")
        } else null

        val llmResponse = anthropicClient.sendMessage(
            messages = messagesForModel,
            systemPrompt = systemPrompt,
            temperature = request.temperature,
            extraHeaders = extraHeaders
        )

        val assistantText = llmResponse.content.joinToString(separator = "") { it.text.orEmpty() }
            .ifBlank { "Модель не вернула ответа. Попробуйте переформулировать запрос." }
            .trim()

        // Сохраняем историю только после успешного ответа
        repository.saveMessage(sessionId, userContent)
        repository.saveMessage(sessionId, Message(role = "assistant", content = llmResponse.content))

        return PersonalAssistantChatResponse(
            sessionId = sessionId,
            response = assistantText,
            usage = llmResponse.usage,
            messageCount = trimmedHistory.count { it.role == "user" } + 1
        )
    }

    suspend fun handleVoice(
        audioBytes: ByteArray,
        fileName: String = "audio.webm",
        sessionId: String? = null,
        language: String? = null,
        profile: String? = null,
    ): PersonalAssistantVoiceResponse {
        val whisper = whisperClient ?: throw IllegalStateException("Whisper не сконфигурирован")
        val transcription = whisper.transcribe(audioBytes, fileName, language)
        val transcriptText = transcription.text.trim()

        if (transcriptText.isBlank()) {
            logger.warn("Empty transcript received from Whisper")
            throw IllegalStateException("Не удалось распознать речь, попробуйте ещё раз.")
        }

        val chatResponse = chat(
            PersonalAssistantChatRequest(
                message = transcriptText,
                sessionId = sessionId,
                profile = profile
            )
        )

        return PersonalAssistantVoiceResponse(
            sessionId = chatResponse.sessionId,
            transcript = transcriptText,
            answer = chatResponse.response,
            language = transcription.language,
            usage = chatResponse.usage,
            rawTranscription = transcription.rawResponse,
            profile = profile ?: activeProfileName
        )
    }

    fun uploadFile(fileName: String, bytes: ByteArray, contentType: String): PersonalAssistantFile {
        val uploaded = runBlockingWithLogging { anthropicFilesClient.uploadFile(fileName, bytes, contentType) }
        val entry = PersonalAssistantFile(
            id = uploaded.id,
            filename = uploaded.filename,
            sizeBytes = uploaded.size_bytes,
            createdAt = uploaded.created_at,
            mimeType = uploaded.mime_type
        )
        filesCache.removeIf { it.id == entry.id }
        filesCache.add(entry)
        return entry
    }

    fun listFiles(): List<PersonalAssistantFile> = filesCache.toList()

    private fun buildSystemPrompt(profileName: String? = null): String {
        val config = loadConfig(profileName ?: activeProfileName)
        val compiledProfile = promptCompiler.compile(config)
        val voiceHints = """
            Ты работаешь с голосовыми и текстовыми запросами. Отвечай чётко, учитывай личный профиль и факты.
            Если запрос неполный, кратко уточни детали и предложи следующее действие.
            Избегай излишней формальности; важны скорость и практичность ответа.
        """.trimIndent()

        return listOf(
            voiceHints,
            compiledProfile,
            "Следуй профилю и инструкциям пользователя в каждом ответе."
        ).joinToString("\n\n")
    }

    private fun buildFileBlocks(fileIds: List<String>?): List<ContentBlock> {
        if (fileIds.isNullOrEmpty()) return emptyList()
        return fileIds.distinct().map { id ->
            val meta = filesCache.firstOrNull { it.id == id }
            val isImage = meta?.mimeType?.startsWith("image/") == true
            ContentBlock(
                type = if (isImage) "image" else "document",
                source = buildJsonObject {
                    put("type", "file")
                    put("file_id", id)
                }
            )
        }
    }

    private fun loadConfig(name: String): PersonalAgentConfig {
        profilesCache[name]?.let { return it }

        val file = profileFile(name)
        if (file.exists()) {
            val cfg = runCatching { file.readText() }
                .mapCatching { text -> json.decodeFromString(PersonalAgentConfig.serializer(), text) }
                .onFailure { logger.error("Failed to load profile '$name': ${it.message}") }
                .getOrElse { getDefaultConfig() }
            profilesCache[name] = cfg
            return cfg
        }

        // Fallback: use default config for new profile
        val defaultConfig = getDefaultConfig()
        saveConfig(name, defaultConfig)
        profilesCache[name] = defaultConfig
        return defaultConfig
    }

    private fun saveConfig(name: String, config: PersonalAgentConfig) {
        val file = profileFile(name)
        try {
            file.parentFile?.mkdirs()
            val serialized = json.encodeToString(PersonalAgentConfig.serializer(), config)
            file.writeText(serialized)
        } catch (e: Exception) {
            logger.error("Failed to save profile '$name' to ${file.absolutePath}: ${e.message}")
        }
    }

    private fun profileFile(name: String): File {
        return if (name == "default") {
            File(configPath)
        } else {
            File(profilesDir, "$name.json")
        }
    }

    private fun <T> runBlockingWithLogging(block: suspend () -> T): T = try {
        runBlocking { block() }
    } catch (e: Exception) {
        logger.error("Files API call failed: ${e.message}", e)
        throw e
    }

    private fun getDefaultConfig(): PersonalAgentConfig {
        return PersonalAgentConfig(
            version = "1.0",
            profile = UserProfile(
                name = "User",
                role = "Professional",
                interests = listOf("Technology", "Productivity", "Learning")
            ),
            personality = Personality(),
            preferences = Preferences(language = "ru"),
            communication = CommunicationStyle(),
            rules = listOf(
                "Будь краток и полезен.",
                "Уточняй детали только когда это ускоряет помощь."
            ),
            customInstructions = "Ты личный ассистент, помогай с задачами, заметками и быстрыми ответами."
        )
    }
}

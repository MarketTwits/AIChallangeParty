package com.markettwits.aichallenge.personalized

import com.markettwits.aichallenge.LMStudioClient
import com.markettwits.aichallenge.LMStudioMessage
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Compiles PersonalAgentConfig into a system prompt for LLM
 */
class PromptCompiler {

    fun compile(config: PersonalAgentConfig): String {
        val sections = mutableListOf<String>()

        // Main role
        sections.add("You are a personal AI assistant.")
        sections.add("")

        // User profile
        if (config.profile.name.isNotEmpty() || config.profile.role != null || config.profile.occupation != null) {
            sections.add("User profile:")
            if (config.profile.name.isNotEmpty()) {
                sections.add("- Name: ${config.profile.name}")
            }
            if (config.profile.role != null) {
                sections.add("- Role: ${config.profile.role}")
            }
            if (config.profile.occupation != null) {
                sections.add("- Occupation: ${config.profile.occupation}")
            }
            if (config.profile.interests.isNotEmpty()) {
                sections.add("- Interests: ${config.profile.interests.joinToString(", ")}")
            }
            if (config.profile.facts.isNotEmpty()) {
                sections.add("- Important facts:")
                config.profile.facts.forEach { fact ->
                    sections.add("  * $fact")
                }
            }
            sections.add("")
        }

        // Personality
        sections.add("Personality:")
        sections.add("- Tone: ${config.personality.tone.name.lowercase().replaceFirstChar { it.uppercase() }}")
        sections.add("- Verbosity: ${getVerbosityDescription(config.personality.verbosity)}")
        sections.add("- Formality: ${getFormalityDescription(config.personality.formality)}")
        if (config.personality.humor) {
            sections.add("- Use humor when appropriate")
        }
        if (config.personality.emoji) {
            sections.add("- Use emojis to make responses more engaging")
        }
        sections.add("")

        // Preferences
        sections.add("Preferences:")
        sections.add("- Output style: ${getOutputStyleDescription(config.preferences.outputStyle)}")
        sections.add("- Language: ${getLanguageName(config.preferences.language)}")
        if (!config.preferences.codeComments) {
            sections.add("- Avoid code comments unless explicitly requested")
        }
        if (!config.preferences.explainBasics) {
            sections.add("- Do not explain basic concepts unless explicitly asked")
        }
        if (!config.preferences.askClarifications) {
            sections.add("- Minimize clarification questions, make reasonable assumptions")
        }
        sections.add("")

        // Rules
        if (config.rules.isNotEmpty()) {
            sections.add("Rules:")
            config.rules.forEachIndexed { index, rule ->
                sections.add("${index + 1}. $rule")
            }
            sections.add("")
        }

        // Communication style
        sections.add("Communication:")
        sections.add("- Address the user as: \"${config.communication.addressing}\"")
        if (config.communication.greetings) {
            sections.add("- Use friendly greetings when appropriate")
        }
        if (config.communication.signoffs) {
            sections.add("- End responses with a brief signoff when appropriate")
        }
        sections.add("")

        // Custom instructions
        if (!config.customInstructions.isNullOrBlank()) {
            sections.add("Additional instructions:")
            sections.add(config.customInstructions)
            sections.add("")
        }

        // Final instruction
        sections.add("Follow these instructions strictly in every response.")

        return sections.joinToString("\n")
    }

    private fun getVerbosityDescription(verbosity: Verbosity): String = when (verbosity) {
        Verbosity.CONCISE -> "short and to the point"
        Verbosity.BALANCED -> "balanced - neither too short nor too long"
        Verbosity.DETAILED -> "detailed and comprehensive"
    }

    private fun getFormalityDescription(formality: Formality): String = when (formality) {
        Formality.FORMAL -> "formal and respectful"
        Formality.CASUAL -> "casual and friendly"
    }

    private fun getOutputStyleDescription(style: OutputStyle): String = when (style) {
        OutputStyle.CODE_FIRST -> "practice-first (focus on practical examples and actions)"
        OutputStyle.BALANCED -> "balanced (mix of theory and practice)"
        OutputStyle.THEORY_FIRST -> "theory-first (explain concepts and provide context)"
    }

    private fun getLanguageName(code: String): String = when (code.lowercase()) {
        "ru" -> "Russian"
        "en" -> "English"
        "es" -> "Spanish"
        "de" -> "German"
        "fr" -> "French"
        else -> code
    }
}

/**
 * Service for managing personalized AI agent
 */
class PersonalizedAgentService(
    private val lmStudioClient: LMStudioClient,
    private val configPath: String = "data/personal_agent_config.json",
) {
    private val logger = LoggerFactory.getLogger(PersonalizedAgentService::class.java)
    private val promptCompiler = PromptCompiler()
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    // Store conversations in memory (conversationId -> list of messages)
    private val conversations = ConcurrentHashMap<String, MutableList<PersonalAgentMessage>>()

    // Current configuration
    private var currentConfig: PersonalAgentConfig

    init {
        currentConfig = loadConfig()
        logger.info("PersonalizedAgentService initialized with config version ${currentConfig.version}")
    }

    /**
     * Get current agent configuration
     */
    fun getConfig(): PersonalAgentConfig = currentConfig

    /**
     * Get compiled system prompt for current configuration
     */
    fun getCompiledPrompt(): String = promptCompiler.compile(currentConfig)

    /**
     * Update agent configuration
     */
    fun updateConfig(newConfig: PersonalAgentConfig) {
        currentConfig = newConfig
        saveConfig(newConfig)
        logger.info("Agent configuration updated")
    }

    /**
     * Chat with personalized agent
     */
    suspend fun chat(
        message: String,
        conversationId: String,
        modelName: String? = null,
    ): PersonalAgentChatResponse {
        // Get or create conversation
        val conversation = conversations.getOrPut(conversationId) { mutableListOf() }

        // Add user message
        conversation.add(PersonalAgentMessage(role = "user", content = message))

        // Compile system prompt from current config
        val systemPrompt = promptCompiler.compile(currentConfig)

        // Build messages for LM Studio
        val messages = mutableListOf(
            LMStudioMessage(role = "system", content = systemPrompt)
        )

        // Add conversation history (limit to last 10 messages to avoid token overflow)
        val recentMessages = conversation.takeLast(10)
        messages.addAll(recentMessages.map {
            LMStudioMessage(role = it.role, content = it.content)
        })

        logger.info("Sending chat request with ${messages.size} messages (including system prompt)")

        // Call LM Studio
        val result = lmStudioClient.chat(
            messages = messages,
            temperature = 0.7,
            modelName = modelName,
            maxTokens = 2000
        )

        if (result.error != null) {
            logger.error("Error from LM Studio: ${result.error}")
            throw Exception("Failed to get response from AI: ${result.error}")
        }

        // Add assistant response to conversation
        conversation.add(PersonalAgentMessage(role = "assistant", content = result.reply))

        return PersonalAgentChatResponse(
            response = result.reply,
            conversationId = conversationId,
            modelUsed = result.modelUsed,
            tokensUsed = result.usage?.totalTokens
        )
    }

    /**
     * Clear conversation history
     */
    fun clearConversation(conversationId: String) {
        conversations.remove(conversationId)
        logger.info("Cleared conversation: $conversationId")
    }

    /**
     * Get conversation history
     */
    fun getConversation(conversationId: String): List<PersonalAgentMessage> {
        return conversations[conversationId]?.toList() ?: emptyList()
    }

    /**
     * Load configuration from file
     */
    private fun loadConfig(): PersonalAgentConfig {
        val file = File(configPath)
        return if (file.exists()) {
            try {
                val content = file.readText()
                json.decodeFromString<PersonalAgentConfig>(content).also {
                    logger.info("Loaded config from $configPath")
                }
            } catch (e: Exception) {
                logger.error("Failed to load config from $configPath: ${e.message}")
                logger.info("Using default configuration")
                getDefaultConfig()
            }
        } else {
            logger.info("Config file not found at $configPath, creating default")
            val defaultConfig = getDefaultConfig()
            saveConfig(defaultConfig)
            defaultConfig
        }
    }

    /**
     * Save configuration to file
     */
    private fun saveConfig(config: PersonalAgentConfig) {
        try {
            val file = File(configPath)
            file.parentFile?.mkdirs()
            val content = json.encodeToString(config)
            file.writeText(content)
            logger.info("Saved config to $configPath")
        } catch (e: Exception) {
            logger.error("Failed to save config to $configPath: ${e.message}")
        }
    }

    /**
     * Get default configuration
     */
    private fun getDefaultConfig(): PersonalAgentConfig {
        return PersonalAgentConfig(
            version = "1.0",
            profile = UserProfile(
                name = "User",
                role = "Professional",
                interests = listOf("Technology", "Innovation", "Problem Solving")
            ),
            personality = Personality(
                verbosity = Verbosity.BALANCED,
                tone = Tone.FRIENDLY,
                formality = Formality.CASUAL,
                humor = false,
                emoji = false
            ),
            preferences = Preferences(
                outputStyle = OutputStyle.BALANCED,
                language = "ru",
                codeComments = true,
                explainBasics = true,
                askClarifications = true
            ),
            rules = listOf(
                "Be helpful and informative across all topics",
                "Provide practical advice and examples when possible",
                "Be concise but complete",
                "Focus on solving problems effectively"
            ),
            communication = CommunicationStyle(
                addressing = "ты",
                greetings = true,
                signoffs = false
            ),
            customInstructions = "You are a versatile personal assistant helping with various tasks and questions."
        )
    }
}

package com.markettwits.aichallenge.personalized

import kotlinx.serialization.Serializable

/**
 * User profile information
 */
@Serializable
data class UserProfile(
    val name: String = "User",
    val role: String? = null,
    val occupation: String? = null,
    val interests: List<String> = emptyList(),
    val facts: List<String> = emptyList(),
)

/**
 * Agent personality configuration
 */
@Serializable
data class Personality(
    val verbosity: Verbosity = Verbosity.BALANCED,
    val tone: Tone = Tone.FRIENDLY,
    val formality: Formality = Formality.CASUAL,
    val humor: Boolean = false,
    val emoji: Boolean = false,
)

@Serializable
enum class Verbosity {
    CONCISE,    // Very short answers
    BALANCED,   // Medium length
    DETAILED    // Comprehensive explanations
}

@Serializable
enum class Tone {
    PROFESSIONAL,
    FRIENDLY,
    CALM,
    ENTHUSIASTIC,
    CASUAL
}

@Serializable
enum class Formality {
    FORMAL,     // "Вы"
    CASUAL      // "ты"
}

/**
 * User preferences for agent behavior
 */
@Serializable
data class Preferences(
    val outputStyle: OutputStyle = OutputStyle.BALANCED,
    val language: String = "ru",
    val codeComments: Boolean = true,
    val explainBasics: Boolean = true,
    val askClarifications: Boolean = true,
)

@Serializable
enum class OutputStyle {
    CODE_FIRST,     // Show code immediately, minimal explanation
    BALANCED,       // Mix of code and explanation
    THEORY_FIRST    // Explain concept first, then code
}

/**
 * Communication style settings
 */
@Serializable
data class CommunicationStyle(
    val addressing: String = "ты",  // How to address the user
    val greetings: Boolean = true,   // Use greetings
    val signoffs: Boolean = false,    // Use signoffs at the end
)

/**
 * Complete personalized agent configuration
 */
@Serializable
data class PersonalAgentConfig(
    val version: String = "1.0",
    val profile: UserProfile = UserProfile(),
    val personality: Personality = Personality(),
    val preferences: Preferences = Preferences(),
    val rules: List<String> = emptyList(),
    val communication: CommunicationStyle = CommunicationStyle(),
    val customInstructions: String? = null,
)

/**
 * Chat message for personalized agent
 */
@Serializable
data class PersonalAgentMessage(
    val role: String,  // "user" or "assistant"
    val content: String,
)

/**
 * Chat request to personalized agent
 */
@Serializable
data class PersonalAgentChatRequest(
    val message: String,
    val conversationId: String? = null,
)

/**
 * Chat response from personalized agent
 */
@Serializable
data class PersonalAgentChatResponse(
    val response: String,
    val conversationId: String,
    val modelUsed: String,
    val tokensUsed: Int? = null,
)

/**
 * Request to update agent configuration
 */
@Serializable
data class UpdateConfigRequest(
    val config: PersonalAgentConfig,
)

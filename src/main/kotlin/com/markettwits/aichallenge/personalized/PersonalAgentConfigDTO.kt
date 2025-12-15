package com.markettwits.aichallenge.personalized

import kotlinx.serialization.Serializable

/**
 * DTO for receiving config from frontend (more lenient)
 */
@Serializable
data class PersonalAgentConfigDTO(
    val version: String,
    val profile: UserProfileDTO,
    val personality: PersonalityDTO,
    val preferences: PreferencesDTO,
    val rules: List<String>,
    val communication: CommunicationStyleDTO,
    val customInstructions: String?,
)

@Serializable
data class UserProfileDTO(
    val name: String,
    val role: String?,
    val occupation: String?,
    val interests: List<String>,
    val facts: List<String>,
)

@Serializable
data class PersonalityDTO(
    val verbosity: String,
    val tone: String,
    val formality: String,
    val humor: Boolean,
    val emoji: Boolean,
)

@Serializable
data class PreferencesDTO(
    val outputStyle: String,
    val language: String,
    val codeComments: Boolean,
    val explainBasics: Boolean,
    val askClarifications: Boolean,
)

@Serializable
data class CommunicationStyleDTO(
    val addressing: String,
    val greetings: Boolean,
    val signoffs: Boolean,
)

/**
 * Convert DTO to domain model
 */
fun PersonalAgentConfigDTO.toDomain(): PersonalAgentConfig {
    return PersonalAgentConfig(
        version = version,
        profile = UserProfile(
            name = profile.name,
            role = profile.role,
            occupation = profile.occupation,
            interests = profile.interests,
            facts = profile.facts
        ),
        personality = Personality(
            verbosity = Verbosity.valueOf(personality.verbosity),
            tone = Tone.valueOf(personality.tone),
            formality = Formality.valueOf(personality.formality),
            humor = personality.humor,
            emoji = personality.emoji
        ),
        preferences = Preferences(
            outputStyle = OutputStyle.valueOf(preferences.outputStyle),
            language = preferences.language,
            codeComments = preferences.codeComments,
            explainBasics = preferences.explainBasics,
            askClarifications = preferences.askClarifications
        ),
        rules = rules,
        communication = CommunicationStyle(
            addressing = communication.addressing,
            greetings = communication.greetings,
            signoffs = communication.signoffs
        ),
        customInstructions = customInstructions
    )
}

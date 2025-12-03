package com.markettwits.aichallenge.sportsauce.club

import com.markettwits.sportsouce.club.cloud.models.club_settings.ClubSettingsRemote
import com.markettwits.sportsouce.club.cloud.models.club_settings.ClubSettingsRemoteRow
import com.markettwits.sportsouce.club.cloud.models.questions.QuestionRemote
import com.markettwits.sportsouce.club.cloud.models.questions.QuestionRemoteRow
import com.markettwits.sportsouce.club.cloud.models.schedule.ScheduleRemote
import com.markettwits.sportsouce.club.cloud.models.schedule.ScheduleRemoteRow
import com.markettwits.sportsouce.club.cloud.models.subscription.SubscriptionItemsRemote
import com.markettwits.sportsouce.club.cloud.models.trainer.TrainersRemote
import com.markettwits.sportsouce.club.cloud.models.trainer.TrainersRemoteRow
import com.markettwits.sportsouce.club.cloud.models.workout.WorkoutRemote
import com.markettwits.sportsouce.club.cloud.models.workout.WorkoutRemoteRow
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import kotlinx.serialization.json.Json

/**
 * base url for sportsauce: https://api.sportsauce.ru
 */
class SportSauceClubsNetworkApiBase(
    httpClient: HttpClient,
) {

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
        useAlternativeNames = false
    }

    private val client = httpClient

    suspend fun trainers(): List<TrainersRemoteRow> {
        val response = client.get("https://api.sportsauce.ru/trainer")
        return json.decodeFromString<TrainersRemote>(response.body<String>()).rows
    }

    suspend fun workout(): List<WorkoutRemoteRow> {
        val response = client.get("https://api.sportsauce.ru/workout")
        return json.decodeFromString<WorkoutRemote>(response.body<String>()).rows
    }

    suspend fun schedule(workoutId: Int?): List<ScheduleRemoteRow> {
        val response = client.get("https://api.sportsauce.ru/schedule") {
            parameter("workoutId", workoutId)
        }
        return json.decodeFromString<ScheduleRemote>(response.body<String>()).rows
    }

    suspend fun subscription(): List<SubscriptionItemsRemote> {
        val response = client.get("https://api.sportsauce.ru/subscription/grouped")
        return json.decodeFromString(response.body<String>())
    }

    suspend fun clubSettings(): List<ClubSettingsRemoteRow> {
        val response = client.get("https://api.sportsauce.ru/club-settings")
        return json.decodeFromString<ClubSettingsRemote>(response.body<String>()).rows
    }

    suspend fun questions(): List<QuestionRemoteRow> {
        val response = client.get("https://api.sportsauce.ru/question")
        return json.decodeFromString<QuestionRemote>(response.body<String>()).rows
    }

}
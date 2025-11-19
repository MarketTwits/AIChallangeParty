package com.markettwits.aichallenge

import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

class ReminderScheduler(
    private val reminderRepository: ReminderRepository,
    private val conversationRepository: ConversationRepository? = null,
    private val checkIntervalSeconds: Long = 30, // Check every 30 seconds
) {
    private val logger = LoggerFactory.getLogger(ReminderScheduler::class.java)
    private val schedulerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val dateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME
    private var schedulerJob: Job? = null

    fun start() {
        logger.info("Starting reminder scheduler with ${checkIntervalSeconds}s interval...")
        schedulerJob = schedulerScope.launch {
            while (isActive) {
                try {
                    checkAndSendReminders()
                    delay(checkIntervalSeconds * 1000) // Convert seconds to milliseconds
                } catch (e: Exception) {
                    logger.error("Error in reminder scheduler", e)
                    delay(5000) // Wait 5 seconds before retrying
                }
            }
        }
        logger.info("Reminder scheduler started successfully")
    }

    fun stop() {
        logger.info("Stopping reminder scheduler...")
        schedulerJob?.cancel()
        schedulerScope.cancel()
        logger.info("Reminder scheduler stopped")
    }

    private suspend fun checkAndSendReminders() {
        val now = LocalDateTime.now()

        // Check for reminders that need to be sent
        val allReminders = reminderRepository.getAllReminders()
        val pendingReminders = allReminders.filter { it.status == "pending" }

        pendingReminders.forEach { reminder ->
            checkReminder(reminder, now)
        }

        // Send periodic summaries based on time
        val currentMinute = now.minute
        val currentHour = now.hour

        // Hourly summary at the beginning of every hour during working hours (8 AM - 9 PM)
        if (currentMinute == 0 && currentHour in 8..21) {
            sendHourlySummary()
        }

        // Daily summary at 9:00 AM
        if (currentHour == 9 && currentMinute == 0) {
            sendDailySummary()
        }

        // Weekly summary on Monday at 9:00 AM
        if (now.dayOfWeek.value == 1 && currentHour == 9 && currentMinute == 0) {
            sendWeeklySummary()
        }
    }

    private suspend fun checkReminder(reminder: ReminderTask, now: LocalDateTime) {
        try {
            // Check if reminder should be triggered now
            val shouldTrigger = shouldTriggerReminder(reminder, now)

            if (shouldTrigger) {
                sendReminderNotification(reminder)

                // Handle recurring reminders - create next instance
                createNextRecurringReminder(reminder, now)
            }
        } catch (e: Exception) {
            logger.warn("Error checking reminder for task ${reminder.id}", e)
        }
    }

    private fun shouldTriggerReminder(reminder: ReminderTask, now: LocalDateTime): Boolean {
        // Check specific reminder time
        reminder.reminderTime?.let { time ->
            try {
                val reminderDateTime = LocalDateTime.parse(time, dateTimeFormatter)
                val minutesSinceReminder = ChronoUnit.MINUTES.between(reminderDateTime, now)

                // Trigger if within the last check interval (30 seconds buffer)
                if (minutesSinceReminder >= 0 && minutesSinceReminder <= 1) {
                    return true
                }
            } catch (e: Exception) {
                logger.warn("Error parsing reminder time for task ${reminder.id}: $time", e)
            }
        }

        // Check nextReminderTime for recurring tasks
        reminder.nextReminderTime?.let { nextTime ->
            try {
                val nextDateTime = LocalDateTime.parse(nextTime, dateTimeFormatter)
                val minutesSinceNext = ChronoUnit.MINUTES.between(nextDateTime, now)

                // Trigger if time has come
                if (minutesSinceNext >= 0) {
                    return true
                }
            } catch (e: Exception) {
                logger.warn("Error parsing next reminder time for task ${reminder.id}: $nextTime", e)
            }
        }

        // Check periodic reminders
        reminder.periodicityMinutes?.let { minutes ->
            if (minutes > 0) {
                val createdAt = LocalDateTime.parse(reminder.createdAt, dateTimeFormatter)
                val minutesSinceCreation = ChronoUnit.MINUTES.between(createdAt, now)

                // Trigger if the elapsed time is a multiple of the period
                if (minutesSinceCreation > 0 && minutesSinceCreation % minutes.toLong() == 0L) {
                    return true
                }
            }
        }

        return false
    }

    private suspend fun sendReminderNotification(reminder: ReminderTask) {
        val priorityEmoji = when (reminder.priority) {
            "high" -> "🔴"
            "medium" -> "🟡"
            "low" -> "🟢"
            else -> "⚪"
        }

        val message = "🔔 **Reminder:** $priorityEmoji ${reminder.title}\n\n" +
                "${reminder.description}\n" +
                "ID: ${reminder.id}"

        val notification = NotificationRequest(
            message = message,
            type = "reminder",
            timestamp = LocalDateTime.now().format(dateTimeFormatter),
            taskId = reminder.id
        )

        reminderRepository.saveNotification(notification)

        // Here you could also send webhook, email, or push notification
        logger.info("Sent reminder notification for task: ${reminder.title}")

        // Mark as notified (you could add a 'notified' field to the database)
        logger.info("Task reminder sent: ${reminder.title}")
    }

    private suspend fun createNextRecurringReminder(reminder: ReminderTask, now: LocalDateTime) {
        try {
            // Calculate next reminder time based on periodicity
            val nextTime = when {
                reminder.periodicityMinutes != null && reminder.periodicityMinutes!! > 0 -> {
                    now.plusMinutes(reminder.periodicityMinutes!!.toLong())
                }

                reminder.recurringType != null -> {
                    when (reminder.recurringType) {
                        "minutely" -> now.plusMinutes(1)
                        "hourly" -> now.plusHours(1)
                        "daily" -> now.plusDays(1)
                        "weekly" -> now.plusWeeks(1)
                        "monthly" -> now.plusMonths(1)
                        else -> return
                    }
                }

                else -> return
            }

            // Update the next reminder time for existing task
            reminderRepository.updateReminder(
                reminder.id, ReminderUpdateRequest(
                    nextReminderTime = nextTime.format(dateTimeFormatter)
                )
            )

            logger.info("Updated next reminder time for task ${reminder.id}: ${nextTime.format(dateTimeFormatter)}")
        } catch (e: Exception) {
            logger.error("Error creating next recurring reminder for task ${reminder.id}", e)
        }
    }

    private fun calculateNextDate(currentDate: String, recurringType: String): String {
        return try {
            val date = LocalDateTime.parse(currentDate, dateTimeFormatter)
            when (recurringType) {
                "daily" -> date.plusDays(1)
                "weekly" -> date.plusWeeks(1)
                "monthly" -> date.plusMonths(1)
                else -> date
            }.format(dateTimeFormatter)
        } catch (e: Exception) {
            currentDate
        }
    }

    private suspend fun sendDailySummary() {
        val summary = reminderRepository.getReminderSummary()
        val conversationSummary = generateConversationSummary()

        var message = "📊 **Daily Summary - ${summary.date}**\n\n" +
                "**Reminders Overview:**\n" +
                "• Total Tasks: ${summary.totalTasks}\n" +
                "• ✅ Completed: ${summary.completedTasks}\n" +
                "• ⏳ Pending: ${summary.pendingTasks}\n" +
                "• ⚠️ Overdue: ${summary.overdueTasks}\n"

        if (summary.todayReminders.isNotEmpty()) {
            message += "\n**Today's Reminders (${summary.todayReminders.size}):**\n"
            summary.todayReminders.forEach { reminder ->
                val emoji = when (reminder.priority) {
                    "high" -> "🔴"
                    "medium" -> "🟡"
                    "low" -> "🟢"
                    else -> "⚪"
                }
                message += "  $emoji ${reminder.title}\n"
            }
        }

        if (conversationSummary.isNotEmpty()) {
            message += "\n**Recent Activity:**\n$conversationSummary"
        }

        val notification = NotificationRequest(
            message = message,
            type = "daily_summary",
            timestamp = LocalDateTime.now().format(dateTimeFormatter)
        )

        reminderRepository.saveNotification(notification)
        logger.info("Sent daily summary")
    }

    private suspend fun sendWeeklySummary() {
        val summary = reminderRepository.getReminderSummary()

        val message = "📈 **Weekly Summary - ${summary.date}**\n\n" +
                "**This Week's Overview:**\n" +
                "• Total Tasks: ${summary.totalTasks}\n" +
                "• ✅ Completed: ${summary.completedTasks}\n" +
                "• ⏳ Still Pending: ${summary.pendingTasks}\n" +
                "• ⚠️ Overdue: ${summary.overdueTasks}\n\n" +
                "Great job staying organized! Keep up the momentum!"

        val notification = NotificationRequest(
            message = message,
            type = "weekly_summary",
            timestamp = LocalDateTime.now().format(dateTimeFormatter)
        )

        reminderRepository.saveNotification(notification)
        logger.info("Sent weekly summary")
    }

    private fun generateConversationSummary(): String {
        return try {
            if (conversationRepository == null) {
                return "💬 Conversation analysis not available"
            }

            // Get recent conversations from last 24 hours
            LocalDateTime.now().minusDays(1)
            val recentSessions = mutableMapOf<String, Int>()
            var totalMessages = 0
            val topics = mutableMapOf<String, Int>()

            // This is a simplified approach - in a real implementation,
            // you'd want to query the database more efficiently
            val sampleSessions = listOf("session1", "session2", "session3") // Placeholder

            sampleSessions.forEach { sessionId ->
                try {
                    val messages = conversationRepository.loadMessages(sessionId)
                    if (messages.isNotEmpty()) {
                        recentSessions[sessionId] = messages.size
                        totalMessages += messages.size

                        // Simple topic extraction from message content
                        messages.forEach { message ->
                            val content = message.content.joinToString(" ") { it.text ?: "" }
                                .lowercase()

                            when {
                                content.contains("тренировк") || content.contains("бег") || content.contains("run") ->
                                    topics["тренировки"] = (topics["тренировки"] ?: 0) + 1

                                content.contains("задач") || content.contains("task") || content.contains("план") ->
                                    topics["планирование"] = (topics["планирование"] ?: 0) + 1

                                content.contains("цель") || content.contains("goal") || content.contains("достиж") ->
                                    topics["цели"] = (topics["цели"] ?: 0) + 1
                            }
                        }
                    }
                } catch (e: Exception) {
                    logger.warn("Could not load messages for session $sessionId", e)
                }
            }

            if (recentSessions.isEmpty()) {
                return "💬 Активность в чатах: За последние 24 часа не было активных сессий"
            }

            val activeHours = if (totalMessages > 0) {
                val avgMessagesPerHour = totalMessages / 24.0
                when {
                    avgMessagesPerHour > 5 -> "🔥 Очень активное общение"
                    avgMessagesPerHour > 2 -> "📈 Регулярная активность"
                    else -> "📝 Умеренная активность"
                }
            } else {
                "📊 Нет сообщений"
            }

            val topTopics = topics.entries
                .sortedByDescending { it.value }
                .take(3)
                .joinToString(", ") { "${it.key} (${it.value})" }

            """💬 **Сводка общения за 24 часа:**
$activeHours
📊 Активных сессий: ${recentSessions.size}
💬 Всего сообщений: $totalMessages
🎯 Основные темы: ${if (topTopics.isNotEmpty()) topTopics else "различные темы"}

✨ Вы отлично работаете над своими целями!""".trimIndent()

        } catch (e: Exception) {
            logger.error("Error generating conversation summary", e)
            "💬 Ошибка при анализе общения: ${e.message}"
        }
    }

    suspend fun sendManualSummary(type: String = "daily"): String {
        return try {
            reminderRepository.getReminderSummary()
            generateConversationSummary()

            when (type) {
                "daily" -> sendDailySummary()
                "weekly" -> sendWeeklySummary()
                "hourly" -> sendHourlySummary()
                else -> sendDailySummary()
            }

            "✅ $type summary sent successfully!"
        } catch (e: Exception) {
            logger.error("Error sending manual summary", e)
            "❌ Error sending summary: ${e.message}"
        }
    }

    private suspend fun sendHourlySummary() {
        val now = LocalDateTime.now()
        val currentHour = now.hour

        // Only send hourly updates during working hours (8 AM - 8 PM)
        if (currentHour < 8 || currentHour > 20) {
            return
        }

        val todayReminders = reminderRepository.getTodayReminders()
        val summary = reminderRepository.getReminderSummary()

        var message = "⏰ **Ежечасное обновление - ${now.format(DateTimeFormatter.ofPattern("HH:mm"))}**\n\n" +
                "📊 **Статистика задач:**\n" +
                "• Сегодня активных: ${todayReminders.size}\n" +
                "• Всего завершено: ${summary.completedTasks}\n" +
                "• Ожидает выполнения: ${summary.pendingTasks}\n"

        val urgentReminders = todayReminders.filter {
            it.priority == "high" || (it.reminderTime?.let { time ->
                try {
                    val reminderTime = LocalDateTime.parse(time, dateTimeFormatter)
                    val minutesUntil = ChronoUnit.MINUTES.between(now, reminderTime)
                    minutesUntil in 0..30
                } catch (e: Exception) {
                    false
                }
            } == true)
        }

        if (urgentReminders.isNotEmpty()) {
            message += "\n🚨 **Срочные задачи (ближайшие 30 мин):**\n"
            urgentReminders.forEach { reminder ->
                val emoji = if (reminder.priority == "high") "🔴" else "🟡"
                message += "  $emoji ${reminder.title}\n"
            }
        }

        // Add brief conversation summary every few hours
        if (currentHour % 3 == 0) { // Every 3 hours at 9 AM, 12 PM, 3 PM, 6 PM
            val conversationSummary = generateConversationSummary()
            message += "\n$conversationSummary"
        }

        message += "\n💪 **Продолжайте в том же духе!**"

        val notification = NotificationRequest(
            message = message,
            type = "hourly_summary",
            timestamp = now.format(dateTimeFormatter)
        )

        reminderRepository.saveNotification(notification)
        logger.info("Sent hourly summary for ${now.hour}:00")
    }
}
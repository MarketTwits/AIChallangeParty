package com.markettwits.aichallenge

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*

object ReminderTasks : Table("reminder_tasks") {
    val id = varchar("id", 36)
    val title = varchar("title", 255)
    val description = text("description")
    val priority = varchar("priority", 20).default("medium")
    val dueDate = varchar("due_date", 50).nullable()
    val reminderTime = varchar("reminder_time", 50).nullable()
    val status = varchar("status", 20).default("pending")
    val createdAt = varchar("created_at", 50)
    val completedAt = varchar("completed_at", 50).nullable()
    val recurringType = varchar("recurring_type", 20).nullable() // daily, weekly, monthly, hourly, minutely, custom
    val periodicityMinutes = integer("periodicity_minutes").nullable() // period in minutes
    val nextReminderTime = varchar("next_reminder_time", 50).nullable() // time of next reminder
    val metadata = text("metadata").default("{}")

    override val primaryKey = PrimaryKey(id)
}

object NotificationHistory : Table("notification_history") {
    val id = integer("id").autoIncrement()
    val taskId = varchar("task_id", 36)
    val message = text("message")
    val type = varchar("type", 20).default("info")
    val timestamp = varchar("timestamp", 50)
    val sent = bool("default").default(false)

    override val primaryKey = PrimaryKey(id)
}

class ReminderRepository(
    databasePath: String = "data/reminders.db",
) {
    private val logger = LoggerFactory.getLogger(ReminderRepository::class.java)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val dateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    init {
        val dbFile = java.io.File(databasePath)
        val dbDir = dbFile.parentFile
        if (dbDir != null && !dbDir.exists()) {
            dbDir.mkdirs()
            logger.info("Created reminders database directory: ${dbDir.absolutePath}")
        }

        Database.connect("jdbc:sqlite:$databasePath", "org.sqlite.JDBC")

        transaction {
            SchemaUtils.create(ReminderTasks, NotificationHistory)

            // Add new columns if they don't exist
            try {
                exec("ALTER TABLE reminder_tasks ADD COLUMN periodicity_minutes INTEGER")
                logger.info("Added periodicity_minutes column")
            } catch (e: Exception) {
                // Column already exists
            }

            try {
                exec("ALTER TABLE reminder_tasks ADD COLUMN next_reminder_time VARCHAR(50)")
                logger.info("Added next_reminder_time column")
            } catch (e: Exception) {
                // Column already exists
            }
        }

        logger.info("Reminders database initialized at $databasePath")
    }

    fun createReminder(request: ReminderCreateRequest): ReminderTask {
        val taskId = UUID.randomUUID().toString()
        val now = LocalDateTime.now().format(dateTimeFormatter)

        // Calculate next reminder time for periodic tasks
        val nextReminderTime = calculateNextReminderTime(request, LocalDateTime.now())

        val task = ReminderTask(
            id = taskId,
            title = request.title,
            description = request.description,
            priority = request.priority,
            dueDate = request.dueDate,
            reminderTime = request.reminderTime,
            status = "pending",
            createdAt = now,
            recurringType = request.recurringType,
            periodicityMinutes = request.periodicityMinutes,
            nextReminderTime = nextReminderTime?.format(dateTimeFormatter),
            metadata = request.metadata
        )

        transaction {
            ReminderTasks.insert {
                it[id] = taskId
                it[title] = request.title
                it[description] = request.description
                it[priority] = request.priority
                it[dueDate] = request.dueDate
                it[reminderTime] = request.reminderTime
                it[status] = "pending"
                it[createdAt] = now
                it[recurringType] = request.recurringType
                it[periodicityMinutes] = request.periodicityMinutes
                it[ReminderTasks.nextReminderTime] = nextReminderTime?.format(dateTimeFormatter)
                it[metadata] = json.encodeToString(request.metadata)
            }
        }

        logger.info("Created reminder: ${task.title} (${task.id}) with periodicity: ${request.periodicityMinutes}min")
        return task
    }

    private fun calculateNextReminderTime(request: ReminderCreateRequest, now: LocalDateTime): LocalDateTime? {
        return when {
            request.periodicityMinutes != null && request.periodicityMinutes!! > 0 -> {
                now.plusMinutes(request.periodicityMinutes!!.toLong())
            }

            request.recurringType != null -> {
                when (request.recurringType) {
                    "minutely" -> now.plusMinutes(1)
                    "hourly" -> now.plusHours(1)
                    "daily" -> now.plusDays(1)
                    "weekly" -> now.plusWeeks(1)
                    "monthly" -> now.plusMonths(1)
                    else -> null
                }
            }

            else -> null
        }
    }

    fun getReminder(taskId: String): ReminderTask? {
        return transaction {
            ReminderTasks.select { ReminderTasks.id eq taskId }
                .map { row -> rowToReminderTask(row) }
                .singleOrNull()
        }
    }

    fun getAllReminders(): List<ReminderTask> {
        return transaction {
            ReminderTasks.selectAll()
                .orderBy(ReminderTasks.createdAt to SortOrder.DESC)
                .map { row -> rowToReminderTask(row) }
        }
    }

    fun getRemindersByStatus(status: String): List<ReminderTask> {
        return transaction {
            ReminderTasks.select { ReminderTasks.status eq status }
                .orderBy(ReminderTasks.createdAt to SortOrder.DESC)
                .map { row -> rowToReminderTask(row) }
        }
    }

    fun getPendingReminders(): List<ReminderTask> {
        return getRemindersByStatus("pending")
    }

    fun getTodayReminders(): List<ReminderTask> {
        val today = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE)

        return transaction {
            ReminderTasks.selectAll()
                .where {
                    (ReminderTasks.status eq "pending") and
                            (ReminderTasks.reminderTime like "$today%")
                }
                .map { row -> rowToReminderTask(row) }
        }
    }

    fun getOverdueReminders(): List<ReminderTask> {
        val now = LocalDateTime.now().format(dateTimeFormatter)

        return transaction {
            ReminderTasks.selectAll()
                .where {
                    (ReminderTasks.status eq "pending") and
                            (ReminderTasks.dueDate lessEq now)
                }
                .map { row -> rowToReminderTask(row) }
        }
    }

    fun updateReminder(taskId: String, request: ReminderUpdateRequest): ReminderTask? {
        return transaction {
            ReminderTasks.select { ReminderTasks.id eq taskId }.singleOrNull()
                ?: return@transaction null

            request.title?.let { ReminderTasks.update({ ReminderTasks.id eq taskId }) { it[title] = request.title } }
            request.description?.let {
                ReminderTasks.update({ ReminderTasks.id eq taskId }) {
                    it[description] = request.description
                }
            }
            request.priority?.let {
                ReminderTasks.update({ ReminderTasks.id eq taskId }) {
                    it[priority] = request.priority
                }
            }
            request.dueDate?.let {
                ReminderTasks.update({ ReminderTasks.id eq taskId }) {
                    it[dueDate] = request.dueDate
                }
            }
            request.reminderTime?.let {
                ReminderTasks.update({ ReminderTasks.id eq taskId }) {
                    it[reminderTime] = request.reminderTime
                }
            }
            request.status?.let { status ->
                ReminderTasks.update({ ReminderTasks.id eq taskId }) {
                    it[this.status] = status
                    if (status == "completed") {
                        it[completedAt] = LocalDateTime.now().format(dateTimeFormatter)
                    }
                }
            }
            request.recurringType?.let {
                ReminderTasks.update({ ReminderTasks.id eq taskId }) {
                    it[recurringType] = request.recurringType
                }
            }
            // Update nextReminderTime from request
            request.nextReminderTime?.let {
                ReminderTasks.update({ ReminderTasks.id eq taskId }) { it[nextReminderTime] = request.nextReminderTime }
            }
            request.metadata?.let {
                ReminderTasks.update({ ReminderTasks.id eq taskId }) {
                    it[metadata] = json.encodeToString(it)
                }
            }

            rowToReminderTask(ReminderTasks.select { ReminderTasks.id eq taskId }.single())
        }
    }

    fun deleteReminder(taskId: String): Boolean {
        return transaction {
            val deletedCount = ReminderTasks.deleteWhere { ReminderTasks.id eq taskId }
            deletedCount > 0
        }
    }

    fun getReminderSummary(): ReminderSummary {
        val today = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE)

        return transaction {
            val allTasks = ReminderTasks.selectAll().toList()
            val totalTasks = allTasks.size
            val completedTasks = allTasks.count { it[ReminderTasks.status] == "completed" }
            val pendingTasks = allTasks.count { it[ReminderTasks.status] == "pending" }

            val overdueTasks = allTasks.count { row ->
                row[ReminderTasks.status] == "pending" &&
                        row[ReminderTasks.dueDate]?.let { it <= LocalDateTime.now().format(dateTimeFormatter) } == true
            }

            val todayReminders = allTasks.filter { row ->
                row[ReminderTasks.status] == "pending" &&
                        row[ReminderTasks.reminderTime]?.startsWith(today) == true
            }.map { row -> rowToReminderTask(row) }

            ReminderSummary(
                date = today,
                totalTasks = totalTasks,
                completedTasks = completedTasks,
                pendingTasks = pendingTasks,
                overdueTasks = overdueTasks,
                todayReminders = todayReminders
            )
        }
    }

    fun saveNotification(notification: NotificationRequest) {
        transaction {
            NotificationHistory.insert {
                it[taskId] = notification.taskId ?: ""
                it[message] = notification.message
                it[type] = notification.type
                it[timestamp] = notification.timestamp
                it[sent] = true
            }
        }
        logger.info("Saved notification: ${notification.message}")
    }

    fun getRecentNotifications(limit: Int = 10): List<NotificationHistoryRecord> {
        return transaction {
            NotificationHistory.selectAll()
                .orderBy(NotificationHistory.timestamp to SortOrder.DESC)
                .limit(limit)
                .map { row ->
                    NotificationHistoryRecord(
                        id = row[NotificationHistory.id],
                        taskId = row[NotificationHistory.taskId],
                        message = row[NotificationHistory.message],
                        type = row[NotificationHistory.type],
                        timestamp = row[NotificationHistory.timestamp],
                        sent = row[NotificationHistory.sent]
                    )
                }
        }
    }

    private fun rowToReminderTask(row: ResultRow): ReminderTask {
        return ReminderTask(
            id = row[ReminderTasks.id],
            title = row[ReminderTasks.title],
            description = row[ReminderTasks.description],
            priority = row[ReminderTasks.priority],
            dueDate = row[ReminderTasks.dueDate],
            reminderTime = row[ReminderTasks.reminderTime],
            status = row[ReminderTasks.status],
            createdAt = row[ReminderTasks.createdAt],
            completedAt = row[ReminderTasks.completedAt],
            recurringType = row[ReminderTasks.recurringType],
            periodicityMinutes = row[ReminderTasks.periodicityMinutes],
            nextReminderTime = row[ReminderTasks.nextReminderTime],
            metadata = try {
                json.decodeFromString<Map<String, String>>(row[ReminderTasks.metadata])
            } catch (e: Exception) {
                emptyMap()
            }
        )
    }
}
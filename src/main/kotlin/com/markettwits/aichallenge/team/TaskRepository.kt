package com.markettwits.aichallenge.team

import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/**
 * Repository for managing tasks in SQLite database
 */
class TaskRepository(private val dbPath: String) {

    private val logger = LoggerFactory.getLogger(TaskRepository::class.java)
    private var connection: Connection? = null

    init {
        initDatabase()
    }

    /**
     * Initialize database and create tables if needed
     */
    private fun initDatabase() {
        try {
            connection = DriverManager.getConnection("jdbc:sqlite:$dbPath")

            connection?.createStatement()?.execute(
                """
                CREATE TABLE IF NOT EXISTS tasks (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    title TEXT NOT NULL,
                    description TEXT NOT NULL,
                    status TEXT NOT NULL DEFAULT 'TODO',
                    priority TEXT NOT NULL DEFAULT 'MEDIUM',
                    assignee TEXT,
                    tags TEXT,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    due_date INTEGER
                )
            """
            )

            logger.info("✅ Task database initialized: $dbPath")
        } catch (e: Exception) {
            logger.error("Failed to initialize task database", e)
            throw e
        }
    }

    /**
     * Create a new task
     */
    fun createTask(request: CreateTaskRequest): Task {
        val now = System.currentTimeMillis()

        val sql = """
            INSERT INTO tasks (title, description, priority, assignee, tags, created_at, updated_at, due_date)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """

        connection?.prepareStatement(sql)?.use { stmt ->
            stmt.setString(1, request.title)
            stmt.setString(2, request.description)
            stmt.setString(3, request.priority)
            stmt.setString(4, request.assignee)
            stmt.setString(5, request.tags)
            stmt.setLong(6, now)
            stmt.setLong(7, now)
            if (request.dueDate != null) {
                stmt.setLong(8, request.dueDate)
            } else {
                stmt.setNull(8, java.sql.Types.INTEGER)
            }

            stmt.executeUpdate()

            // Get the last inserted ID
            val generatedKeys = stmt.generatedKeys
            if (generatedKeys.next()) {
                val id = generatedKeys.getLong(1)
                logger.info("✅ Created task #$id: ${request.title}")
                return getTaskById(id) ?: throw Exception("Failed to retrieve created task")
            }
        }

        throw Exception("Failed to create task")
    }

    /**
     * Get task by ID
     */
    fun getTaskById(id: Long): Task? {
        val sql = "SELECT * FROM tasks WHERE id = ?"

        connection?.prepareStatement(sql)?.use { stmt ->
            stmt.setLong(1, id)
            val rs = stmt.executeQuery()

            if (rs.next()) {
                return mapResultSetToTask(rs)
            }
        }

        return null
    }

    /**
     * Get all tasks with optional filtering
     */
    fun getTasks(filter: TaskFilter = TaskFilter()): List<Task> {
        val conditions = mutableListOf<String>()
        val params = mutableListOf<Any>()

        filter.status?.let {
            conditions.add("status = ?")
            params.add(it)
        }

        filter.priority?.let {
            conditions.add("priority = ?")
            params.add(it)
        }

        filter.assignee?.let {
            conditions.add("assignee = ?")
            params.add(it)
        }

        filter.tags?.let {
            conditions.add("tags LIKE ?")
            params.add("%$it%")
        }

        val whereClause = if (conditions.isNotEmpty()) {
            "WHERE " + conditions.joinToString(" AND ")
        } else {
            ""
        }

        val sql = """
            SELECT * FROM tasks
            $whereClause
            ORDER BY
                CASE priority
                    WHEN 'CRITICAL' THEN 1
                    WHEN 'HIGH' THEN 2
                    WHEN 'MEDIUM' THEN 3
                    WHEN 'LOW' THEN 4
                END,
                created_at DESC
            LIMIT ? OFFSET ?
        """

        val tasks = mutableListOf<Task>()

        connection?.prepareStatement(sql)?.use { stmt ->
            params.forEachIndexed { index, param ->
                when (param) {
                    is String -> stmt.setString(index + 1, param)
                    is Long -> stmt.setLong(index + 1, param)
                    is Int -> stmt.setInt(index + 1, param)
                }
            }
            stmt.setInt(params.size + 1, filter.limit)
            stmt.setInt(params.size + 2, filter.offset)

            val rs = stmt.executeQuery()
            while (rs.next()) {
                tasks.add(mapResultSetToTask(rs))
            }
        }

        return tasks
    }

    /**
     * Update an existing task
     */
    fun updateTask(id: Long, request: UpdateTaskRequest): Task? {
        val updates = mutableListOf<String>()
        val params = mutableListOf<Any>()

        request.title?.let {
            updates.add("title = ?")
            params.add(it)
        }

        request.description?.let {
            updates.add("description = ?")
            params.add(it)
        }

        request.status?.let {
            updates.add("status = ?")
            params.add(it)
        }

        request.priority?.let {
            updates.add("priority = ?")
            params.add(it)
        }

        request.assignee?.let {
            updates.add("assignee = ?")
            params.add(it)
        }

        request.tags?.let {
            updates.add("tags = ?")
            params.add(it)
        }

        request.dueDate?.let {
            updates.add("due_date = ?")
            params.add(it)
        }

        if (updates.isEmpty()) {
            return getTaskById(id)
        }

        updates.add("updated_at = ?")
        params.add(System.currentTimeMillis())

        val sql = "UPDATE tasks SET ${updates.joinToString(", ")} WHERE id = ?"
        params.add(id)

        connection?.prepareStatement(sql)?.use { stmt ->
            params.forEachIndexed { index, param ->
                when (param) {
                    is String -> stmt.setString(index + 1, param)
                    is Long -> stmt.setLong(index + 1, param)
                    is Int -> stmt.setInt(index + 1, param)
                }
            }

            val updated = stmt.executeUpdate()
            if (updated > 0) {
                logger.info("✅ Updated task #$id")
                return getTaskById(id)
            }
        }

        return null
    }

    /**
     * Delete a task
     */
    fun deleteTask(id: Long): Boolean {
        val sql = "DELETE FROM tasks WHERE id = ?"

        connection?.prepareStatement(sql)?.use { stmt ->
            stmt.setLong(1, id)
            val deleted = stmt.executeUpdate()

            if (deleted > 0) {
                logger.info("✅ Deleted task #$id")
                return true
            }
        }

        return false
    }

    /**
     * Get task statistics
     */
    fun getStatistics(): TaskStatistics {
        val tasks = getTasks(TaskFilter(limit = 10000))

        val byStatus = tasks.groupingBy { it.status }.eachCount()
        val byPriority = tasks.groupingBy { it.priority }.eachCount()
        val byAssignee = tasks.filter { it.assignee != null }
            .groupingBy { it.assignee!! }
            .eachCount()

        val completedCount = byStatus[TaskStatus.DONE.name] ?: 0
        val completionRate = if (tasks.isNotEmpty()) {
            (completedCount.toDouble() / tasks.size) * 100
        } else {
            0.0
        }

        val criticalCount = byPriority[TaskPriority.CRITICAL.name] ?: 0
        val blockedCount = byStatus[TaskStatus.BLOCKED.name] ?: 0

        // Count overdue tasks
        val now = System.currentTimeMillis()
        val overdueCount = tasks.count { task ->
            task.dueDate != null && task.dueDate < now && task.status != TaskStatus.DONE.name
        }

        return TaskStatistics(
            total = tasks.size,
            byStatus = byStatus,
            byPriority = byPriority,
            byAssignee = byAssignee,
            completionRate = completionRate,
            criticalCount = criticalCount,
            blockedCount = blockedCount,
            overdueCount = overdueCount
        )
    }

    /**
     * Get project status summary
     */
    fun getProjectStatus(): ProjectStatus {
        val tasks = getTasks(TaskFilter(limit = 10000))

        val completedTasks = tasks.count { it.status == TaskStatus.DONE.name }
        val inProgressTasks = tasks.count { it.status == TaskStatus.IN_PROGRESS.name }
        val blockedTasks = tasks.count { it.status == TaskStatus.BLOCKED.name }
        val criticalTasks = tasks.count { it.priority == TaskPriority.CRITICAL.name }

        val completionRate = if (tasks.isNotEmpty()) {
            (completedTasks.toDouble() / tasks.size) * 100
        } else {
            0.0
        }

        // Get top 5 priority tasks (critical/high, not done)
        val topPriorityTasks = tasks
            .filter { it.status != TaskStatus.DONE.name }
            .filter { it.priority == TaskPriority.CRITICAL.name || it.priority == TaskPriority.HIGH.name }
            .take(5)

        // Recent activity (last 24 hours)
        val yesterday = System.currentTimeMillis() - (24 * 60 * 60 * 1000)
        val recentTasks = tasks.count { it.updatedAt > yesterday }
        val recentActivity = if (recentTasks > 0) {
            "$recentTasks tasks updated in last 24 hours"
        } else {
            "No recent activity"
        }

        return ProjectStatus(
            totalTasks = tasks.size,
            completedTasks = completedTasks,
            inProgressTasks = inProgressTasks,
            blockedTasks = blockedTasks,
            criticalTasks = criticalTasks,
            completionRate = completionRate,
            topPriorityTasks = topPriorityTasks,
            recentActivity = recentActivity
        )
    }

    /**
     * Map ResultSet to Task object
     */
    private fun mapResultSetToTask(rs: ResultSet): Task {
        return Task(
            id = rs.getLong("id"),
            title = rs.getString("title"),
            description = rs.getString("description"),
            status = rs.getString("status"),
            priority = rs.getString("priority"),
            assignee = rs.getString("assignee"),
            tags = rs.getString("tags"),
            createdAt = rs.getLong("created_at"),
            updatedAt = rs.getLong("updated_at"),
            dueDate = rs.getLong("due_date").let { if (rs.wasNull()) null else it }
        )
    }

    /**
     * Close database connection
     */
    fun close() {
        connection?.close()
        logger.info("Database connection closed")
    }
}

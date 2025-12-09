package com.markettwits.aichallenge.team

import kotlinx.serialization.Serializable

/**
 * Task status enum
 */
enum class TaskStatus {
    TODO,           // Not started
    IN_PROGRESS,    // Currently working on
    DONE,           // Completed
    BLOCKED         // Blocked by dependencies or issues
}

/**
 * Task priority enum
 */
enum class TaskPriority {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}

/**
 * Task model for team project management
 */
@Serializable
data class Task(
    val id: Long = 0,
    val title: String,
    val description: String,
    val status: String = TaskStatus.TODO.name,
    val priority: String = TaskPriority.MEDIUM.name,
    val assignee: String? = null,
    val tags: String? = null,  // Comma-separated tags
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val dueDate: Long? = null,
)

/**
 * Request to create a new task
 */
@Serializable
data class CreateTaskRequest(
    val title: String,
    val description: String,
    val priority: String = TaskPriority.MEDIUM.name,
    val assignee: String? = null,
    val tags: String? = null,
    val dueDate: Long? = null,
)

/**
 * Request to update an existing task
 */
@Serializable
data class UpdateTaskRequest(
    val title: String? = null,
    val description: String? = null,
    val status: String? = null,
    val priority: String? = null,
    val assignee: String? = null,
    val tags: String? = null,
    val dueDate: Long? = null,
)

/**
 * Task statistics for reporting
 */
@Serializable
data class TaskStatistics(
    val total: Int,
    val byStatus: Map<String, Int>,
    val byPriority: Map<String, Int>,
    val byAssignee: Map<String, Int>,
    val completionRate: Double,
    val criticalCount: Int,
    val blockedCount: Int,
    val overdueCount: Int,
)

/**
 * Project status summary
 */
@Serializable
data class ProjectStatus(
    val totalTasks: Int,
    val completedTasks: Int,
    val inProgressTasks: Int,
    val blockedTasks: Int,
    val criticalTasks: Int,
    val completionRate: Double,
    val topPriorityTasks: List<Task>,
    val recentActivity: String,
)

/**
 * Task filter for querying
 */
data class TaskFilter(
    val status: String? = null,
    val priority: String? = null,
    val assignee: String? = null,
    val tags: String? = null,
    val limit: Int = 100,
    val offset: Int = 0,
)

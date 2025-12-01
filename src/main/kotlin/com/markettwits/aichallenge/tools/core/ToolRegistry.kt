package com.markettwits.aichallenge.tools.core

import org.slf4j.LoggerFactory

/**
 * Central registry for all tools
 * Follows Single Responsibility Principle (SOLID)
 */
class ToolRegistry {
    private val logger = LoggerFactory.getLogger(ToolRegistry::class.java)
    private val tools = mutableMapOf<String, Tool>()

    /**
     * Register a new tool
     */
    fun register(tool: Tool) {
        if (tools.containsKey(tool.name)) {
            logger.warn("Tool ${tool.name} already registered, overwriting")
        }
        tools[tool.name] = tool
        logger.info("Registered tool: ${tool.name} (type: ${tool.type})")
    }

    /**
     * Register multiple tools at once
     */
    fun registerAll(vararg tools: Tool) {
        tools.forEach { register(it) }
    }

    /**
     * Get tool by name
     */
    fun getTool(name: String): Tool? = tools[name]

    /**
     * Get all tools
     */
    fun getAllTools(): List<Tool> = tools.values.toList()

    /**
     * Get tools by type
     */
    fun getToolsByType(type: ToolType): List<Tool> =
        tools.values.filter { it.type == type }

    /**
     * Check if tool exists
     */
    fun hasTool(name: String): Boolean = tools.containsKey(name)

    /**
     * Get tool count
     */
    fun getToolCount(): Int = tools.size

    /**
     * Unregister tool
     */
    fun unregister(name: String): Boolean {
        val removed = tools.remove(name)
        if (removed != null) {
            logger.info("Unregistered tool: $name")
            return true
        }
        return false
    }

    /**
     * Clear all tools
     */
    fun clear() {
        tools.clear()
        logger.info("Cleared all tools from registry")
    }

    /**
     * Get tools summary
     */
    fun getSummary(): Map<String, Any> = mapOf(
        "totalTools" to tools.size,
        "toolsByType" to ToolType.values().associate { type ->
            type.name to getToolsByType(type).size
        },
        "tools" to tools.keys.toList()
    )
}

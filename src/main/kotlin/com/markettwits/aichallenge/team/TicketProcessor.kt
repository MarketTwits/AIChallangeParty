package com.markettwits.aichallenge.team

import com.markettwits.aichallenge.AnthropicClient
import com.markettwits.aichallenge.ContentBlock
import com.markettwits.aichallenge.Message
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File

/**
 * AI-powered ticket processor
 * Analyzes support requests, filters spam, assigns priorities
 */
class TicketProcessor(
    private val anthropicClient: AnthropicClient,
    private val dataDir: String = "data",
) {
    private val logger = LoggerFactory.getLogger(TicketProcessor::class.java)
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private val supportRequestsFile = File(dataDir, "support_requests.json")
    private val ticketsFile = File(dataDir, "tickets.json")

    init {
        // Ensure data directory exists
        File(dataDir).mkdirs()
    }

    /**
     * Process all pending support requests
     * Reads from support_requests.json, analyzes with AI, saves to tickets.json
     */
    suspend fun processAllRequests(): ProcessingResult {
        logger.info("📨 Starting support requests processing...")

        // Read support requests
        val requests = readSupportRequests()
        if (requests.isEmpty()) {
            logger.info("No support requests to process")
            return ProcessingResult(
                totalProcessed = 0,
                validTickets = 0,
                spamFiltered = 0,
                userErrorsDetected = 0,
                tickets = emptyList()
            )
        }

        logger.info("Found ${requests.size} support requests to analyze")

        // Process each request with AI
        val processedTickets = mutableListOf<Ticket>()
        var spamCount = 0
        var userErrorCount = 0

        for (request in requests) {
            try {
                val classification = classifyRequest(request)

                // Filter spam
                if (classification.isSpam) {
                    logger.info("🚫 Filtered spam: ${request.id} - ${request.subject}")
                    spamCount++
                    continue
                }

                // Track user errors
                if (classification.isUserError) {
                    userErrorCount++
                }

                // Create ticket
                val ticket = Ticket(
                    id = request.id,
                    originalRequest = request,
                    classification = classification,
                    priority = classification.priority,
                    status = "NEW",
                    assignee = null,
                    tags = listOfNotNull(
                        classification.category,
                        if (classification.isUserError) "user_error" else null
                    ),
                    createdAt = request.timestamp,
                    updatedAt = System.currentTimeMillis(),
                    notes = classification.reasoning
                )

                processedTickets.add(ticket)
                logger.info("✅ Processed ticket ${ticket.id}: ${ticket.priority} priority")

            } catch (e: Exception) {
                logger.error("Error processing request ${request.id}", e)
            }
        }

        // Save processed tickets
        saveTickets(processedTickets)

        val result = ProcessingResult(
            totalProcessed = requests.size,
            validTickets = processedTickets.size,
            spamFiltered = spamCount,
            userErrorsDetected = userErrorCount,
            tickets = processedTickets
        )

        logger.info("📊 Processing complete: ${result.validTickets} valid tickets, ${result.spamFiltered} spam filtered")

        return result
    }

    /**
     * Classify a single support request using AI
     */
    private suspend fun classifyRequest(request: SupportRequest): TicketClassification {
        val analysisPrompt = """
            Analyze this support request and classify it:

            FROM: ${request.userName} (${request.userId})
            EMAIL: ${request.email ?: "N/A"}
            SUBJECT: ${request.subject}
            MESSAGE: ${request.message}
            CATEGORY: ${request.category ?: "uncategorized"}

            Classify this request and respond with JSON:
            {
                "isSpam": boolean,
                "isUserError": boolean,
                "category": "bug|question|feature|auth|billing|other",
                "priority": "CRITICAL|HIGH|MEDIUM|LOW",
                "reasoning": "brief explanation",
                "suggestedResponse": "optional quick response template"
            }

            Classification guidelines:

            SPAM detection:
            - Advertising, promotional content
            - Gibberish, random characters
            - Phishing attempts
            - Off-topic messages

            USER ERROR detection:
            - Forgot password (obvious user mistake)
            - Wrong credentials
            - Didn't read documentation
            - Asking about basic features explained in docs

            PRIORITY assignment:
            - CRITICAL: Service down, security issue, data loss, affecting many users
            - HIGH: Major bug, payment issues, feature not working
            - MEDIUM: Minor bugs, feature requests, general questions
            - LOW: Suggestions, cosmetic issues, nice-to-have features

            CATEGORY:
            - bug: Something not working as expected
            - question: User asking how to do something
            - feature: Request for new functionality
            - auth: Authentication/authorization issues
            - billing: Payment/subscription issues
            - other: Doesn't fit above categories

            Respond ONLY with valid JSON, no additional text.
        """.trimIndent()

        try {
            val response = anthropicClient.sendMessage(
                messages = listOf(
                    Message(
                        role = "user",
                        content = listOf(ContentBlock(type = "text", text = analysisPrompt))
                    )
                )
            )

            val responseText = response.content
                .filter { it.type == "text" }
                .firstOrNull()?.text ?: throw Exception("No response from AI")

            // Extract JSON from response (in case AI adds extra text)
            val jsonMatch = Regex("""\{[\s\S]*\}""").find(responseText)
            val jsonText = jsonMatch?.value ?: responseText

            return json.decodeFromString(TicketClassification.serializer(), jsonText)

        } catch (e: Exception) {
            logger.error("Error classifying request ${request.id}, using defaults", e)
            // Fallback classification
            return TicketClassification(
                isSpam = false,
                isUserError = false,
                category = request.category ?: "other",
                priority = "MEDIUM",
                reasoning = "Auto-classified due to AI error: ${e.message}",
                suggestedResponse = null
            )
        }
    }

    /**
     * Read support requests from file
     */
    fun readSupportRequests(): List<SupportRequest> {
        return try {
            if (!supportRequestsFile.exists()) {
                logger.warn("Support requests file not found: ${supportRequestsFile.absolutePath}")
                return emptyList()
            }

            val content = supportRequestsFile.readText()
            val requestList = json.decodeFromString(SupportRequestList.serializer(), content)
            requestList.requests

        } catch (e: Exception) {
            logger.error("Error reading support requests", e)
            emptyList()
        }
    }

    /**
     * Read existing tickets from file
     */
    fun readTickets(): List<Ticket> {
        return try {
            if (!ticketsFile.exists()) {
                return emptyList()
            }

            val content = ticketsFile.readText()
            val ticketList = json.decodeFromString(TicketList.serializer(), content)
            ticketList.tickets

        } catch (e: Exception) {
            logger.error("Error reading tickets", e)
            emptyList()
        }
    }

    /**
     * Save tickets to file (replaces existing)
     */
    fun saveTickets(tickets: List<Ticket>) {
        try {
            val ticketList = TicketList(
                tickets = tickets,
                lastUpdated = System.currentTimeMillis()
            )

            val content = json.encodeToString(TicketList.serializer(), ticketList)
            ticketsFile.writeText(content)

            logger.info("💾 Saved ${tickets.size} tickets to ${ticketsFile.absolutePath}")

        } catch (e: Exception) {
            logger.error("Error saving tickets", e)
            throw e
        }
    }

    /**
     * Get ticket by ID
     */
    fun getTicketById(id: String): Ticket? {
        return readTickets().find { it.id == id }
    }

    /**
     * Update a ticket
     */
    fun updateTicket(id: String, update: UpdateTicketRequest): Ticket? {
        val tickets = readTickets().toMutableList()
        val index = tickets.indexOfFirst { it.id == id }

        if (index == -1) {
            logger.warn("Ticket not found: $id")
            return null
        }

        val existingTicket = tickets[index]
        val updatedTicket = existingTicket.copy(
            priority = update.priority ?: existingTicket.priority,
            status = update.status ?: existingTicket.status,
            assignee = update.assignee ?: existingTicket.assignee,
            tags = update.tags ?: existingTicket.tags,
            notes = update.notes ?: existingTicket.notes,
            updatedAt = System.currentTimeMillis()
        )

        tickets[index] = updatedTicket
        saveTickets(tickets)

        logger.info("✏️ Updated ticket: $id")
        return updatedTicket
    }

    /**
     * Delete a ticket
     */
    fun deleteTicket(id: String): Boolean {
        val tickets = readTickets().toMutableList()
        val removed = tickets.removeIf { it.id == id }

        if (removed) {
            saveTickets(tickets)
            logger.info("🗑️ Deleted ticket: $id")
        } else {
            logger.warn("Ticket not found for deletion: $id")
        }

        return removed
    }

    /**
     * Get ticket statistics
     */
    fun getStatistics(): TicketStatistics {
        val tickets = readTickets()

        return TicketStatistics(
            total = tickets.size,
            byPriority = tickets.groupingBy { it.priority }.eachCount(),
            byStatus = tickets.groupingBy { it.status }.eachCount(),
            byCategory = tickets.groupingBy { it.classification.category }.eachCount(),
            spamFiltered = 0, // Only available after processing
            userErrorsDetected = tickets.count { it.classification.isUserError }
        )
    }
}

/**
 * Result of processing support requests
 */
data class ProcessingResult(
    val totalProcessed: Int,
    val validTickets: Int,
    val spamFiltered: Int,
    val userErrorsDetected: Int,
    val tickets: List<Ticket>,
)

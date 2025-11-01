package com.anthropic

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.slf4j.LoggerFactory

fun Application.configureRouting(agent: Agent) {
    val logger = LoggerFactory.getLogger("Routes")

    routing {
        post("/chat") {
            try {
                val request = call.receive<ChatRequest>()
                logger.info("Received chat request: ${request.message}")
                val response = agent.chat(request.message)
                call.respond(ChatResponse(response = response))
            } catch (e: Exception) {
                logger.error("Error processing chat request", e)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ChatResponse(response = "Error: ${e.message}")
                )
            }
        }

        get("/health") {
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        }
    }
}

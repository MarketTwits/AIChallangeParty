package com.markettwits.aichallenge.whatsnew

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.slf4j.LoggerFactory

fun Application.configureWhatsNewRoutes(service: WhatsNewService) {
    val logger = LoggerFactory.getLogger("WhatsNewRoutes")

    routing {
        route("/whats-new") {
            post("/generate") {
                try {
                    val request = call.receive<WhatsNewRequest>()
                    val id = service.startGeneration(request)
                    call.respond(HttpStatusCode.Accepted, mapOf("id" to id, "status" to "accepted"))
                } catch (e: Exception) {
                    logger.error("Failed to start what's new generation", e)
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "unknown error")))
                }
            }

            get("/status/{id}") {
                val id = call.parameters["id"]
                if (id == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "id is required"))
                    return@get
                }

                val state = service.getStatus(id)
                if (state == null) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "not found"))
                    return@get
                }

                call.respond(
                    mapOf(
                        "id" to state.id,
                        "status" to state.status.name,
                        "startedAt" to state.startedAt,
                        "completedAt" to state.completedAt,
                        "error" to state.error
                    )
                )
            }

            get("/result/{id}") {
                val id = call.parameters["id"]
                if (id == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "id is required"))
                    return@get
                }

                val result = service.getResult(id)
                if (result == null) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "result not found"))
                    return@get
                }

                if (result.status != WhatsNewStatus.COMPLETED) {
                    call.respond(HttpStatusCode.Accepted, mapOf("status" to result.status.name))
                    return@get
                }

                call.respond(result)
            }

            get("/latest") {
                val latest = service.getLatestFromDisk()
                if (latest == null) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "no latest notes"))
                    return@get
                }

                call.respond(latest)
            }
        }
    }
}

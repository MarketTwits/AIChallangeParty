package com.markettwits.aichallenge

import io.github.cdimascio.dotenv.dotenv
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.http.content.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json

fun main() {
    val dotenv = dotenv {
        ignoreIfMissing = true
    }

    val apiKey = dotenv["ANTHROPIC_API_KEY"]
        ?: throw IllegalStateException("ANTHROPIC_API_KEY not found in .env file")

    val sessionManager = SessionManager()

    embeddedServer(Netty, port = 8080, host = "0.0.0.0") {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
                encodeDefaults = false
                explicitNulls = false
            })
        }

        install(CORS) {
            anyHost()
            allowHeader(HttpHeaders.ContentType)
            allowMethod(HttpMethod.Post)
            allowMethod(HttpMethod.Get)
            allowMethod(HttpMethod.Options)
        }

        configureRouting(sessionManager, apiKey)

        routing {
            staticResources("/", "static")
        }
    }.start(wait = true)
}

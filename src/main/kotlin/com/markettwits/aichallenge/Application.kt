package com.markettwits.aichallenge

import io.github.cdimascio.dotenv.dotenv
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json

fun main() {
    val dotenv = dotenv {
        ignoreIfMissing = true
    }

    val apiKey = dotenv["ANTHROPIC_API_KEY"]
        ?: throw IllegalStateException("ANTHROPIC_API_KEY not found in .env file")

    val huggingFaceKey = dotenv["HUGGINGFACE_API_KEY"]
        ?: throw IllegalStateException("HUGGINGFACE_API_KEY not found in .env file")

    println("Loaded API Keys:")
    println("  ANTHROPIC_API_KEY: ${apiKey.take(10)}... (length: ${apiKey.length})")
    println("  HUGGINGFACE_API_KEY: ${huggingFaceKey.take(10)}... (length: ${huggingFaceKey.length})")

    val sessionManager = SessionManager()

    embeddedServer(Netty, port = 8080, host = "0.0.0.0") {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }

        install(CORS) {
            anyHost()
            allowHeader(HttpHeaders.ContentType)
            allowMethod(HttpMethod.Post)
            allowMethod(HttpMethod.Get)
            allowMethod(HttpMethod.Options)
        }

        configureRouting(sessionManager, apiKey, huggingFaceKey)

        routing {
            staticResources("/", "static")
        }
    }.start(wait = true)
}

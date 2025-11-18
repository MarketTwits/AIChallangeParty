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
        ?: System.getenv("ANTHROPIC_API_KEY")
        ?: throw IllegalStateException("ANTHROPIC_API_KEY not found in .env file or environment variables")

    val huggingFaceKey = dotenv["HUGGINGFACE_API_KEY"]
        ?: System.getenv("HUGGINGFACE_API_KEY")
        ?: throw IllegalStateException("HUGGINGFACE_API_KEY not found in .env file or environment variables")

    // Load GitHub Token (optional)
    val gitHubToken = dotenv["GITHUB_TOKEN"] ?: System.getenv("GITHUB_TOKEN") ?: ""
    if (gitHubToken.isNotEmpty()) {
        System.setProperty("GITHUB_TOKEN", gitHubToken)
    }

    println("Loaded API Keys:")
    println("  ANTHROPIC_API_KEY: ${apiKey.take(10)}... (length: ${apiKey.length})")
    println("  HUGGINGFACE_API_KEY: ${huggingFaceKey.take(10)}... (length: ${huggingFaceKey.length})")
    if (gitHubToken.isNotEmpty()) {
        println("  GITHUB_TOKEN: ${gitHubToken.take(10)}...${gitHubToken.takeLast(4)} (length: ${gitHubToken.length})")
    } else {
        println("  GITHUB_TOKEN: Not configured")
    }

    val repository = ConversationRepository()
    val sessionManager = SessionManager(repository)

    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080

    println("Starting server on port $port")

    embeddedServer(Netty, port = port, host = "0.0.0.0") {
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

        configureRouting(sessionManager, apiKey, huggingFaceKey, repository)

        routing {
            staticResources("/", "static")
        }
    }.start(wait = true)
}

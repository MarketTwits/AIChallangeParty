package com.markettwits.aichallenge

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

@Serializable
data class AnthropicFileResponse(
    val id: String,
    val type: String,
    val filename: String,
    val mime_type: String? = null,
    val size_bytes: Long? = null,
    val created_at: String? = null,
    val downloadable: Boolean? = null,
)

class AnthropicFilesClient(private val apiKey: String) {
    private val logger = LoggerFactory.getLogger(AnthropicFilesClient::class.java)
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 120_000
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = 120_000
        }
    }

    suspend fun uploadFile(
        fileName: String,
        bytes: ByteArray,
        contentType: String = ContentType.Application.OctetStream.toString(),
    ): AnthropicFileResponse {
        val endpoint = "https://api.anthropic.com/v1/files"
        logger.info("Uploading file to Anthropic Files API: $fileName (${bytes.size} bytes)")

        val response: HttpResponse = client.post(endpoint) {
            header("x-api-key", apiKey)
            header("anthropic-version", "2023-06-01")
            header("anthropic-beta", "files-api-2025-04-14")
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append(
                            "file",
                            bytes,
                            Headers.build {
                                append(
                                    HttpHeaders.ContentDisposition,
                                    ContentDisposition.File.withParameter(
                                        ContentDisposition.Parameters.FileName,
                                        fileName
                                    ).toString()
                                )
                                append(HttpHeaders.ContentType, contentType)
                            }
                        )
                    }
                )
            )
        }

        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            logger.error("Files API error: ${response.status} - $body")
            throw IllegalStateException("Files API error: ${response.status}")
        }
        return Json.decodeFromString(AnthropicFileResponse.serializer(), body)
    }

    fun close() {
        client.close()
    }
}

package com.markettwits.aichallenge

import java.net.HttpURLConnection
import java.net.URL

object HealthCheck {
    @JvmStatic
    fun main(args: Array<String>) {
        try {
            val url = URL("http://localhost:8080/health")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000

            val responseCode = connection.responseCode

            if (responseCode == 200) {
                println("Health check passed")
                System.exit(0)
            } else {
                println("Health check failed with response code: $responseCode")
                System.exit(1)
            }
        } catch (e: Exception) {
            println("Health check failed: ${e.message}")
            System.exit(1)
        }
    }
}
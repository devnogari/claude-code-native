package com.claudecode.native.data.api

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

/**
 * Base API client for HTTP communication with the backend server.
 * Provides common HTTP methods with automatic JSON serialization and authentication.
 */
class ApiClient(
    @PublishedApi internal val baseUrl: String = "http://localhost:8080/api/v1"
) {
    @PublishedApi internal var currentAuthToken: String? = null

    @PublishedApi internal val httpClient = HttpClient {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
                prettyPrint = false
            })
        }

        install(DefaultRequest) {
            contentType(ContentType.Application.Json)
        }

        HttpResponseValidator {
            handleResponseExceptionWithRequest { exception, _ ->
                throw ApiException(exception.message ?: "Unknown error")
            }
        }
    }

    /**
     * Sets the authentication token for subsequent API requests.
     */
    fun setAuthToken(token: String?) {
        currentAuthToken = token
    }

    /**
     * Returns the current authentication token.
     */
    fun getAuthToken(): String? = currentAuthToken

    /**
     * Performs a GET request to the specified endpoint.
     */
    suspend inline fun <reified T> get(endpoint: String): T {
        return httpClient.get("$baseUrl$endpoint") {
            currentAuthToken?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        }.body()
    }

    /**
     * Performs a POST request to the specified endpoint with a request body.
     */
    suspend inline fun <reified T, reified R> post(endpoint: String, body: T): R {
        return httpClient.post("$baseUrl$endpoint") {
            currentAuthToken?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            setBody(body)
        }.body()
    }

    /**
     * Performs a PUT request to the specified endpoint with a request body.
     */
    suspend inline fun <reified T, reified R> put(endpoint: String, body: T): R {
        return httpClient.put("$baseUrl$endpoint") {
            currentAuthToken?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            setBody(body)
        }.body()
    }

    /**
     * Performs a DELETE request to the specified endpoint.
     */
    suspend inline fun delete(endpoint: String) {
        httpClient.delete("$baseUrl$endpoint") {
            currentAuthToken?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        }
    }
}

/**
 * Exception thrown when an API request fails.
 */
class ApiException(message: String) : Exception(message)

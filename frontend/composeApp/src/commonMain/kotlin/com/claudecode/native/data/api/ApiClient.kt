package com.claudecode.native.data.api

import com.claudecode.native.data.storage.TokenStorage
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/**
 * Base API client for HTTP communication with the backend server.
 * Provides common HTTP methods with automatic JSON serialization and authentication.
 *
 * Thread-safety: Auth token access is protected by a mutex for safe concurrent access.
 * Token persistence: Uses TokenStorage for platform-specific persistence (localStorage on WASM).
 * Server host persistence: Uses TokenStorage for saving/loading server host across sessions.
 */
class ApiClient(
    defaultHost: String = "localhost:8083"
) {
    companion object {
        private const val API_PATH = "/api/v1"
        private const val DEFAULT_PROTOCOL = "http"
    }

    // Load server host from storage, or use default
    private var _serverHost: String = TokenStorage.getServerHost() ?: defaultHost

    /** Current server host (e.g., "localhost:8083" or "192.168.1.100:8080") */
    val serverHost: String get() = _serverHost

    /** Constructed base URL from server host */
    @PublishedApi internal val baseUrl: String get() = "$DEFAULT_PROTOCOL://$_serverHost$API_PATH"
    private val tokenMutex = Mutex()
    // Load token from storage on initialization
    @PublishedApi internal var currentAuthToken: String? = TokenStorage.getToken()

    @PublishedApi internal val httpClient = HttpClient {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
                prettyPrint = false
            })
        }

        install(Logging) {
            logger = Logger.DEFAULT
            level = LogLevel.HEADERS
            sanitizeHeader { header -> header == HttpHeaders.Authorization }
        }

        install(DefaultRequest) {
            contentType(ContentType.Application.Json)
        }

        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 30_000
        }

        HttpResponseValidator {
            validateResponse { response ->
                when (response.status.value) {
                    in 200..299 -> {} // Success - no action needed
                    401 -> throw ApiException("Unauthorized: Please login again", response.status.value)
                    403 -> throw ApiException("Forbidden: Access denied", response.status.value)
                    404 -> throw ApiException("Not found", response.status.value)
                    in 400..499 -> throw ApiException("Client error: ${response.status.description}", response.status.value)
                    in 500..599 -> throw ApiException("Server error: ${response.status.description}", response.status.value)
                }
            }
            handleResponseExceptionWithRequest { exception, _ ->
                if (exception is ApiException) throw exception
                throw ApiException(exception.message ?: "Network error")
            }
        }
    }

    /**
     * Sets the authentication token for subsequent API requests.
     * Thread-safe: Uses mutex for safe concurrent access.
     * Also persists to TokenStorage for WASM page refresh support.
     */
    suspend fun setAuthToken(token: String?) {
        tokenMutex.withLock {
            currentAuthToken = token
            if (token != null) {
                TokenStorage.saveToken(token)
            } else {
                TokenStorage.clearToken()
            }
        }
    }

    /**
     * Returns the current authentication token.
     * Thread-safe: Uses mutex for safe concurrent access.
     */
    suspend fun getAuthToken(): String? = tokenMutex.withLock { currentAuthToken }

    /**
     * Clears the authentication token (for logout).
     * Thread-safe: Uses mutex for safe concurrent access.
     * Also clears from TokenStorage.
     */
    suspend fun clearAuthToken() {
        tokenMutex.withLock {
            currentAuthToken = null
            TokenStorage.clearToken()
        }
    }

    /**
     * Updates the server host for API requests.
     * Note: This affects all subsequent requests and persists across sessions.
     *
     * @param host The new server host (e.g., "localhost:8083" or "192.168.1.100:8080")
     */
    fun updateServerHost(host: String) {
        _serverHost = host
        TokenStorage.saveServerHost(host)
    }

    /**
     * Closes the HTTP client and releases resources.
     * Should be called when the API client is no longer needed.
     */
    fun close() {
        httpClient.close()
    }

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
     * Performs a POST request to the specified endpoint without a body.
     */
    suspend inline fun <reified R> postEmpty(endpoint: String): R {
        return httpClient.post("$baseUrl$endpoint") {
            currentAuthToken?.let { header(HttpHeaders.Authorization, "Bearer $it") }
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
 *
 * @param message Human-readable error message
 * @param statusCode HTTP status code (null for network errors)
 */
class ApiException(
    message: String,
    val statusCode: Int? = null
) : Exception(message) {

    /**
     * Returns true if this is an authentication error (401).
     */
    fun isUnauthorized(): Boolean = statusCode == 401

    /**
     * Returns true if this is an authorization error (403).
     */
    fun isForbidden(): Boolean = statusCode == 403

    /**
     * Returns true if the resource was not found (404).
     */
    fun isNotFound(): Boolean = statusCode == 404

    /**
     * Returns true if this is a server error (5xx).
     */
    fun isServerError(): Boolean = statusCode != null && statusCode in 500..599

    /**
     * Returns true if this is a client error (4xx).
     */
    fun isClientError(): Boolean = statusCode != null && statusCode in 400..499
}

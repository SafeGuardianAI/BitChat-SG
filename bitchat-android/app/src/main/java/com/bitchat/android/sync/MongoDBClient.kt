package com.bitchat.android.sync

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * REST client for MongoDB backend using OkHttp.
 * Wraps data in {"victim_data": {...}} format per the Rescue API spec.
 */
class MongoDBClient {

    companion object {
        private const val TAG = "MongoDBClient"
        private const val CONNECT_TIMEOUT_SEC = 10L
        private const val READ_TIMEOUT_SEC = 30L
        private const val WRITE_TIMEOUT_SEC = 30L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val gson = Gson()

    var baseUrl: String = "https://api.safeguardian.io/v1"
        set(value) {
            field = value.trimEnd('/')
        }

    var authToken: String? = null

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT_SEC, TimeUnit.SECONDS)
        .addInterceptor(LoggingInterceptor())
        .build()

    /**
     * Create a document in a collection.
     */
    suspend fun create(collection: String, documentId: String, payload: String): Result<String> {
        return executeRequest(
            method = "POST",
            url = "$baseUrl/$collection",
            body = wrapPayload(payload, documentId)
        )
    }

    /**
     * Read a document from a collection.
     */
    suspend fun read(collection: String, documentId: String): Result<String> {
        return executeRequest(
            method = "GET",
            url = "$baseUrl/$collection/$documentId",
            body = null
        )
    }

    /**
     * Update an existing document.
     */
    suspend fun update(collection: String, documentId: String, payload: String): Result<String> {
        return executeRequest(
            method = "PUT",
            url = "$baseUrl/$collection/$documentId",
            body = wrapPayload(payload, documentId)
        )
    }

    /**
     * Upsert (create or update) a document.
     */
    suspend fun upsert(collection: String, documentId: String, payload: String): Result<String> {
        return executeRequest(
            method = "PUT",
            url = "$baseUrl/$collection/$documentId?upsert=true",
            body = wrapPayload(payload, documentId)
        )
    }

    /**
     * Delete a document.
     */
    suspend fun delete(collection: String, documentId: String): Result<String> {
        return executeRequest(
            method = "DELETE",
            url = "$baseUrl/$collection/$documentId",
            body = null
        )
    }

    /**
     * Execute a sync operation against MongoDB.
     */
    suspend fun executeOperation(operation: SyncOperation): Result<String> {
        return when (operation.type) {
            OperationType.CREATE -> create(operation.collection, operation.documentId, operation.payload)
            OperationType.UPDATE -> update(operation.collection, operation.documentId, operation.payload)
            OperationType.DELETE -> delete(operation.collection, operation.documentId)
            OperationType.UPSERT -> upsert(operation.collection, operation.documentId, operation.payload)
        }
    }

    /**
     * Wrap the raw payload in MongoDB's expected format: {"victim_data": {...}, "_id": documentId}
     */
    private fun wrapPayload(payload: String, documentId: String): String {
        return try {
            val dataElement = JsonParser.parseString(payload)
            val wrapper = JsonObject()
            wrapper.addProperty("_id", documentId)
            wrapper.add("victim_data", dataElement)
            wrapper.addProperty("updated_at", System.currentTimeMillis())
            gson.toJson(wrapper)
        } catch (e: Exception) {
            Log.w(TAG, "Payload is not valid JSON, wrapping as string", e)
            """{"_id":"$documentId","victim_data":"$payload","updated_at":${System.currentTimeMillis()}}"""
        }
    }

    private suspend fun executeRequest(method: String, url: String, body: String?): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val requestBuilder = Request.Builder().url(url)

                // Add auth header if available
                authToken?.let {
                    requestBuilder.addHeader("Authorization", "Bearer $it")
                }
                requestBuilder.addHeader("Content-Type", "application/json")

                val requestBody = body?.toRequestBody(JSON_MEDIA_TYPE)

                when (method) {
                    "GET" -> requestBuilder.get()
                    "POST" -> requestBuilder.post(requestBody ?: "".toRequestBody(JSON_MEDIA_TYPE))
                    "PUT" -> requestBuilder.put(requestBody ?: "".toRequestBody(JSON_MEDIA_TYPE))
                    "DELETE" -> {
                        if (requestBody != null) {
                            requestBuilder.delete(requestBody)
                        } else {
                            requestBuilder.delete()
                        }
                    }
                }

                val response = client.newCall(requestBuilder.build()).await()
                val responseBody = response.body?.string() ?: ""

                if (response.isSuccessful) {
                    Result.success(responseBody)
                } else {
                    Result.failure(
                        SyncException(
                            "MongoDB ${method} $url failed: ${response.code} $responseBody",
                            response.code
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Request failed: $method $url", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Suspend extension for OkHttp Call.
     */
    private suspend fun Call.await(): Response {
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { cancel() }
            enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!continuation.isCancelled) {
                        continuation.resumeWithException(e)
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response)
                }
            })
        }
    }

    /**
     * Simple request/response logging interceptor.
     */
    private class LoggingInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            Log.d(TAG, "--> ${request.method} ${request.url}")

            val startMs = System.currentTimeMillis()
            val response = chain.proceed(request)
            val durationMs = System.currentTimeMillis() - startMs

            Log.d(TAG, "<-- ${response.code} ${request.url} (${durationMs}ms)")
            return response
        }
    }
}

/**
 * Exception with HTTP status code for sync errors.
 */
class SyncException(message: String, val httpCode: Int = -1) : Exception(message)

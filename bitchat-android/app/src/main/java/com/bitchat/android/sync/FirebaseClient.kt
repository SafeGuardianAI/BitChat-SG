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
 * REST client for Firebase Realtime Database backend using OkHttp.
 * Wraps data in {"victim_info": {...}} format.
 * Uses Firebase REST API: https://<project>.firebaseio.com/<path>.json
 */
class FirebaseClient {

    companion object {
        private const val TAG = "FirebaseClient"
        private const val CONNECT_TIMEOUT_SEC = 10L
        private const val READ_TIMEOUT_SEC = 30L
        private const val WRITE_TIMEOUT_SEC = 30L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val gson = Gson()

    var baseUrl: String = "https://safeguardian-default-rtdb.firebaseio.com"
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
     * Create a document. Uses PUT to set at a specific path (Firebase idempotent write).
     */
    suspend fun create(collection: String, documentId: String, payload: String): Result<String> {
        return executeRequest(
            method = "PUT",
            url = buildUrl(collection, documentId),
            body = wrapPayload(payload)
        )
    }

    /**
     * Read a document from Firebase.
     */
    suspend fun read(collection: String, documentId: String): Result<String> {
        return executeRequest(
            method = "GET",
            url = buildUrl(collection, documentId),
            body = null
        )
    }

    /**
     * Update specific fields of a document using PATCH.
     */
    suspend fun update(collection: String, documentId: String, payload: String): Result<String> {
        return executeRequest(
            method = "PATCH",
            url = buildUrl(collection, documentId),
            body = wrapPayload(payload)
        )
    }

    /**
     * Upsert a document (PUT overwrites or creates in Firebase).
     */
    suspend fun upsert(collection: String, documentId: String, payload: String): Result<String> {
        return executeRequest(
            method = "PUT",
            url = buildUrl(collection, documentId),
            body = wrapPayload(payload)
        )
    }

    /**
     * Delete a document.
     */
    suspend fun delete(collection: String, documentId: String): Result<String> {
        return executeRequest(
            method = "DELETE",
            url = buildUrl(collection, documentId),
            body = null
        )
    }

    /**
     * Execute a sync operation against Firebase.
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
     * Build the Firebase REST URL: baseUrl/collection/documentId.json?auth=token
     */
    private fun buildUrl(collection: String, documentId: String): String {
        val url = "$baseUrl/$collection/$documentId.json"
        return if (authToken != null) {
            "$url?auth=$authToken"
        } else {
            url
        }
    }

    /**
     * Wrap the raw payload in Firebase's expected format: {"victim_info": {...}, "updated_at": ...}
     */
    private fun wrapPayload(payload: String): String {
        return try {
            val dataElement = JsonParser.parseString(payload)
            val wrapper = JsonObject()
            wrapper.add("victim_info", dataElement)
            wrapper.addProperty("updated_at", System.currentTimeMillis())
            gson.toJson(wrapper)
        } catch (e: Exception) {
            Log.w(TAG, "Payload is not valid JSON, wrapping as string", e)
            """{"victim_info":"$payload","updated_at":${System.currentTimeMillis()}}"""
        }
    }

    private suspend fun executeRequest(method: String, url: String, body: String?): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val requestBuilder = Request.Builder().url(url)
                requestBuilder.addHeader("Content-Type", "application/json")

                val requestBody = body?.toRequestBody(JSON_MEDIA_TYPE)

                when (method) {
                    "GET" -> requestBuilder.get()
                    "POST" -> requestBuilder.post(requestBody ?: "".toRequestBody(JSON_MEDIA_TYPE))
                    "PUT" -> requestBuilder.put(requestBody ?: "".toRequestBody(JSON_MEDIA_TYPE))
                    "PATCH" -> requestBuilder.patch(requestBody ?: "".toRequestBody(JSON_MEDIA_TYPE))
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
                            "Firebase $method $url failed: ${response.code} $responseBody",
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

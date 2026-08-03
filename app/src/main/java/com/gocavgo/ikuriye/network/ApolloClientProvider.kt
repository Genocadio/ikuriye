package com.gocavgo.ikuriye.network

import android.util.Log
import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.network.http.DefaultHttpEngine
import com.apollographql.apollo.network.http.HttpInterceptor
import com.apollographql.apollo.network.http.HttpInterceptorChain
import com.apollographql.apollo.api.http.HttpRequest
import com.apollographql.apollo.api.http.HttpResponse
import com.gocavgo.ikuriye.BuildConfig
import com.gocavgo.ikuriye.data.AuthRepository
import com.gocavgo.ikuriye.supa.SupaAuth
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient

object ApolloClientProvider {

    private const val TAG = "ApolloClientProvider"

    // ── Token refresh synchronization ──
    // Prevents concurrent refresh attempts and makes other requests
    // wait for an in-progress refresh before deciding what to do.
    private val refreshMutex = Mutex()
    private var lastRefreshFailedAtMs = 0L
    private val REFRESH_COOLDOWN_MS = 30_000L

    val client: ApolloClient by lazy {
        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

        ApolloClient.Builder()
            .serverUrl("https://api.med.rw/deliveries/graphql")
            .httpEngine(DefaultHttpEngine(okHttpClient))
            .addHttpInterceptor(object : HttpInterceptor {
                override suspend fun intercept(request: HttpRequest, chain: HttpInterceptorChain): HttpResponse {
                    val token = SupaAuth.getAccessToken()
                    if (BuildConfig.DEBUG) {
                        val masked = if (token != null && token.length > 16) {
                            "${token.take(8)}...${token.takeLast(8)}"
                        } else {
                            token ?: "null"
                        }
                        Log.d(TAG, "Request: ${request.method} ${request.url} | token=$masked")
                    }
                    val response = if (token != null) {
                        chain.proceed(request.newBuilder().addHeader("Authorization", "Bearer $token").build())
                    } else {
                        chain.proceed(request)
                    }
                    // ── Detect auth errors (HTTP 401 or GraphQL-level "Unauthorized") ──
                    val isHttp401 = response.statusCode == 401
                    val isGraphQLAuthError = if (response.statusCode == 200) {
                        try {
                            response.body?.let { source ->
                                val bodyText = source.peek().readUtf8()
                                bodyText.contains("\"Unauthorized\"", ignoreCase = true) ||
                                    bodyText.contains("\"Not Authorized\"", ignoreCase = true) ||
                                    bodyText.contains("\"Forbidden\"", ignoreCase = true)
                            } ?: false
                        } catch (_: Exception) {
                            false
                        }
                    } else false

                    if (isHttp401 || isGraphQLAuthError) {
                        // ── Acquire refresh mutex so concurrent requests wait ──
                        // Only one request performs the refresh; others wait and then
                        // either use the fresh token or get the same failure result.
                        refreshMutex.withLock {
                            // Step 1: check if another request already refreshed while we waited
                            val liveToken = SupaAuth.getAccessToken()
                            if (liveToken != null) {
                                Log.d(TAG, "Token already fresh — another request refreshed while we waited, retrying")
                                return chain.proceed(
                                    request.newBuilder()
                                        .addHeader("Authorization", "Bearer $liveToken")
                                        .build()
                                )
                            }

                            // Step 2: cooldown — don't hammer the refresh endpoint if it just failed
                            val now = System.currentTimeMillis()
                            if (now - lastRefreshFailedAtMs < REFRESH_COOLDOWN_MS) {
                                Log.w(TAG, "Refresh recently failed (cooldown ${(now - lastRefreshFailedAtMs) / 1000}s ago) — signalling session expiry")
                                AuthRepository.onSessionExpired()
                                return@withLock response
                            }

                            // Step 3: attempt silent refresh
                            if (BuildConfig.DEBUG) {
                                val ctx = if (isGraphQLAuthError) "GraphQL-level auth" else "HTTP 401"
                                Log.d(TAG, "$ctx — attempting silent token refresh (mutex acquired)")
                            }
                            val refreshed = SupaAuth.refreshSession()
                            if (refreshed) {
                                val newToken = SupaAuth.getAccessToken()
                                if (newToken != null) {
                                    Log.d(TAG, "Token refreshed silently, retrying request")
                                    return chain.proceed(
                                        request.newBuilder()
                                            .addHeader("Authorization", "Bearer $newToken")
                                            .build()
                                    )
                                }
                            }

                            // Step 4: refresh failed — record cooldown and signal logout
                            lastRefreshFailedAtMs = System.currentTimeMillis()
                            Log.e(TAG, "Token refresh failed — signalling session expiry")
                            AuthRepository.onSessionExpired()
                        }
                    } else if (BuildConfig.DEBUG && response.statusCode !in 200..299) {
                        Log.e(TAG, "HTTP ${response.statusCode}")
                    }
                    return response
                }
            })
            .build()
    }
}

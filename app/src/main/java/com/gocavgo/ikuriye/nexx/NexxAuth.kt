package com.gocavgo.ikuriye.nexx

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.gocavgo.ikuriye.BuildConfig
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Nexxauth client — the identity provider for the app.
 *
 * The app authenticates directly against the organisation auth endpoints
 * (register/login/refresh/logout) using the ANDROID client key
 * (BuildConfig.NEXXAUTH_CLIENT_ID). Login accepts an email OR phone identifier.
 * The org-access JWT returned is then sent to the backend GraphQL API, and the
 * opaque refresh token is rotated on every refresh (single-use).
 *
 * Supabase is NOT used for auth or uploads anymore — file uploads go through the backend.
 */
object NexxAuth {

    private const val TAG = "NexxAuth"
    private const val PREFS = "ikuriye_nexxauth"

    private var prefs: SharedPreferences? = null

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    // ── Proactive refresh constants (mirror the old SupaAuth.observeSession) ──
    private const val PROACTIVE_REFRESH_INTERVAL_MS = 5 * 60 * 1000L
    private const val PROACTIVE_REFRESH_THRESHOLD_SECONDS = 5 * 60L

    // ── Endpoints ────────────────────────────────────────────────────────────

    private val baseUrl: String
        get() = BuildConfig.NEXXAUTH_BASE_URL.trimEnd('/')

    private val orgApiBase: String
        get() {
            val orgId = getJwtOrgId() ?: return "$baseUrl/organisations/unknown"
            return "$baseUrl/organisations/$orgId"
        }

    private const val JSON = "application/json; charset=utf-8"

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    // ── Auth calls ───────────────────────────────────────────────────────────

    /**
     * Registers a new organisation user. [email] and/or [phone] are the login
     * identifiers (the organisation decides which are required). Returns the
     * server error message on failure, null on success.
     */
    suspend fun signUp(
        email: String,
        phone: String?,
        password: String,
        firstName: String,
        lastName: String?
    ): String? {
        val body = JSONObject()
            .put("firstName", firstName)
            .apply {
                if (lastName != null && lastName.isNotBlank()) put("lastName", lastName)
                if (email.isNotBlank()) put("email", email.trim())
                if (!phone.isNullOrBlank()) put("phone", phone.trim())
                put("password", password)
            }

        val response = post("$baseUrl/auth/register", body, auth = false)
        return handleAuthResponse(response, "register")
    }

    /**
     * Logs in with an email or phone identifier + password.
     * [identifierType] is omitted when unknown — Nexxauth then tries each
     * login-enabled identifier in order (username → email → phone).
     */
    suspend fun signIn(identifier: String, password: String): String? {
        val body = JSONObject()
            .put("identifier", identifier.trim())
            .put("authType", "PASSWORD")
            .put("password", password)
            .apply {
                detectIdentifierType(identifier)?.let { put("identifierType", it) }
            }

        val response = post("$baseUrl/auth/login", body, auth = false)
        return handleAuthResponse(response, "login")
    }

    /**
     * Rotates the refresh token for a fresh access token. Returns true on
     * success. A rejected refresh token (already used / revoked) clears the
     * session and returns false.
     */
    suspend fun refreshSession(): Boolean {
        val refresh = getRefreshToken() ?: run {
            clearSessionLocally()
            return false
        }
        val body = JSONObject().put("refreshToken", refresh)

        return try {
            val response = post("$baseUrl/auth/refresh", body, auth = false)
            handleAuthResponse(response, "refresh") == null
        } catch (e: Exception) {
            Log.e(TAG, "refreshSession: failed — ${e.message}")
            clearSessionLocally()
            false
        }
    }

    suspend fun signOut() {
        val refresh = getRefreshToken()
        if (refresh != null) {
            try {
                post("$baseUrl/auth/logout", JSONObject().put("refreshToken", refresh), auth = false)
            } catch (e: Exception) {
                Log.w(TAG, "signOut: logout call failed — ${e.message}")
            }
        }
        clearSessionLocally()
    }

    /**
     * Changes the password (org endpoint, authenticated as the user). Returns
     * null on success or an error message.
     */
    suspend fun changePassword(currentPassword: String, newPassword: String): String? {
        val body = JSONObject()
            .put("currentPassword", currentPassword)
            .put("newPassword", newPassword)
        return try {
            val response = post("$orgApiBase/users/me/change-password", body, auth = true)
            if (response.first == 204 || response.first in 200..299) null
            else response.second
        } catch (e: Exception) {
            Log.e(TAG, "changePassword: failed — ${e.message}")
            e.message
        }
    }

    /**
     * Updates the user's own profile in Nexxauth (first/last name). Returns
     * null on success or an error message.
     */
    suspend fun updateProfile(firstName: String, lastName: String?): String? {
        val body = JSONObject().put("firstName", firstName).apply {
            lastName?.let { put("lastName", it) }
        }
        return try {
            val response = patch("$orgApiBase/users/me", body, auth = true)
            if (response.first in 200..299) null else response.second
        } catch (e: Exception) {
            Log.e(TAG, "updateProfile: failed — ${e.message}")
            e.message
        }
    }

    // ── Session state ─────────────────────────────────────────────────────────

    fun getAccessToken(): String? = prefs?.getString("access_token", null)?.takeIf { it.isNotBlank() }

    private fun getRefreshToken(): String? = prefs?.getString("refresh_token", null)?.takeIf { it.isNotBlank() }

    fun getCurrentUserId(): String? = prefs?.getString("user_id", null)?.takeIf { it.isNotBlank() }

    fun isLoggedIn(): Boolean = getAccessToken() != null

    fun clearSessionLocally() {
        prefs?.edit()?.clear()?.apply()
    }

    /**
     * Start the periodic JWT health check: refresh ~5 minutes before the token
     * expires (and every 5 minutes as a safety net when expiry is unknown).
     */
    fun observeSession(scope: CoroutineScope) {
        scope.launch {
            while (true) {
                val exp = getJwtExpirySeconds()
                val now = System.currentTimeMillis() / 1000
                val remaining = exp?.minus(now)

                if (remaining == null || remaining < PROACTIVE_REFRESH_THRESHOLD_SECONDS) {
                    Log.d(TAG, "proactiveCheck: token expiring/unknown (remaining=${remaining}s), refreshing")
                    refreshSession()
                }
                delay(PROACTIVE_REFRESH_INTERVAL_MS)
            }
        }
    }

    // ── HTTP plumbing ─────────────────────────────────────────────────────────

    private suspend fun post(url: String, body: JSONObject, auth: Boolean): Pair<Int, String> =
        send("POST", url, body, auth)

    private suspend fun patch(url: String, body: JSONObject, auth: Boolean): Pair<Int, String> =
        send("PATCH", url, body, auth)

    /**
     * Runs the blocking OkHttp call off the main thread. This must never execute
     * on the Android main thread (NetworkOnMainThreadException) — callers are
     * suspend functions, and we hop to Dispatchers.IO here.
     */
    private suspend fun send(method: String, url: String, body: JSONObject, auth: Boolean): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            val requestBuilder = Request.Builder()
                .url(url)
                .header("Content-Type", JSON)
                .header("X-Client-Id", BuildConfig.NEXXAUTH_CLIENT_ID)
                .header("Origin", "android://${BuildConfig.APPLICATION_ID}")
                .apply {
                    if (auth) getAccessToken()?.let { header("Authorization", "Bearer $it") }
                }

            val request = when (method) {
                "POST" -> requestBuilder.post(body.toString().toRequestBody(JSON.toMediaType())).build()
                "PATCH" -> requestBuilder.patch(body.toString().toRequestBody(JSON.toMediaType())).build()
                else -> throw IllegalArgumentException("Unsupported method $method")
            }

            httpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: ""
                response.code to responseBody
            }
        }

    /**
     * Parses an OrgAuthResponse from register/login/refresh. On success persists
     * the session and returns null; on failure returns the server error message.
     */
    private fun handleAuthResponse(response: Pair<Int, String>, flow: String): String? {
        val (code, body) = response
        if (code !in 200..299) {
            val message = extractErrorMessage(body) ?: "Sign-in failed (HTTP $code)"
            Log.e(TAG, "$flow failed ($code): $message")
            return message
        }
        return try {
            val json = JSONObject(body)
            val accessToken = json.optString("accessToken")
            val refreshToken = json.optString("refreshToken")
            val expiresIn = json.optLong("expiresInSeconds", 900L)
            val user = json.optJSONObject("user")
            val userId = user?.optString("id") ?: ""

            if (accessToken.isBlank()) {
                Log.e(TAG, "$flow: response has no accessToken")
                return "Authentication failed: no access token returned"
            }

            prefs?.edit()?.apply {
                putString("access_token", accessToken)
                if (refreshToken.isNotBlank()) putString("refresh_token", refreshToken)
                putLong("expires_at", System.currentTimeMillis() / 1000 + expiresIn)
                if (userId.isNotBlank()) putString("user_id", userId)
            }?.apply()

            if (BuildConfig.DEBUG) {
                Log.d(TAG, "$flow: session established (userId=$userId, expiresIn=${expiresIn}s, refresh=${refreshToken.isNotBlank()})")
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "$flow: failed to parse response — ${e.message}")
            "Authentication failed: invalid server response"
        }
    }

    private fun extractErrorMessage(body: String): String? {
        return try {
            val json = JSONObject(body)
            val message = json.optString("message")
            if (message.isNotBlank()) message else json.optString("error").takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private fun detectIdentifierType(identifier: String): String? {
        val trimmed = identifier.trim()
        return when {
            trimmed.contains("@") -> "EMAIL"
            trimmed.matches(Regex("[+\\d][\\d\\s()-]*")) -> "PHONE"
            else -> null
        }
    }

    /** Decodes the JWT payload to read the `exp` claim (Unix seconds). */
    private fun getJwtExpirySeconds(): Long? {
        val token = getAccessToken() ?: return null
        val parts = token.split(".")
        if (parts.size < 2) return null
        return try {
            val payload = String(android.util.Base64.decode(parts[1], android.util.Base64.URL_SAFE), Charsets.UTF_8)
            val json = JSONObject(payload)
            if (json.has("exp")) json.getLong("exp") else null
        } catch (e: Exception) {
            Log.w(TAG, "getJwtExpirySeconds: failed to decode — ${e.message}")
            null
        }
    }

    /** Decodes the JWT payload to read the `orgId` claim. */
    private fun getJwtOrgId(): Int? {
        val token = getAccessToken() ?: return null
        val parts = token.split(".")
        if (parts.size < 2) return null
        return try {
            val payload = String(android.util.Base64.decode(parts[1], android.util.Base64.URL_SAFE), Charsets.UTF_8)
            val json = JSONObject(payload)
            if (json.has("orgId")) json.getInt("orgId") else null
        } catch (e: Exception) {
            Log.w(TAG, "getJwtOrgId: failed to decode — ${e.message}")
            null
        }
    }
}

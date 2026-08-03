package com.gocavgo.ikuriye.supa

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.gocavgo.ikuriye.BuildConfig
import com.gocavgo.ikuriye.data.AuthRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.providers.builtin.OTP
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

sealed class SupaSignUpResult {
    data object SessionEstablished : SupaSignUpResult()
    data class EmailVerificationRequired(val email: String) : SupaSignUpResult()
}

object SupaAuth {

    private const val TAG = "SupaAuth"
    private var prefs: SharedPreferences? = null

    private var _client: SupabaseClient? = null

    // ── Proactive refresh constants ──
    // Check every 5 minutes
    private val PROACTIVE_REFRESH_INTERVAL_MS = 5 * 60 * 1000L
    // Refresh when less than 5 minutes until token expiry
    private const val PROACTIVE_REFRESH_THRESHOLD_SECONDS = 5 * 60L

    val client: SupabaseClient
        get() = _client ?: error(
            "SupaAuth not initialized. Call SupaAuth.init(context) before accessing client."
        )

    /**
     * Initialises the Supabase client with a custom [SharedPreferencesSessionManager]
     * that auto-saves and auto-loads the session via the SDK's built-in
     * [io.github.jan.supabase.auth.AuthConfig.autoSaveToStorage] /
     * [io.github.jan.supabase.auth.AuthConfig.autoLoadFromStorage] mechanism.
     *
     * Session persistence is now handled entirely by the SDK — manual
     * [saveSession] / [restoreSession] calls are no longer needed.
     */
    fun init(context: Context) {
        prefs = context.getSharedPreferences("ikuriye_supa", Context.MODE_PRIVATE)
        val sessionManager = SharedPreferencesSessionManager(prefs!!)
        _client = SupaClient.createSupabaseClient(sessionManager = sessionManager)
        if (BuildConfig.DEBUG) Log.d(TAG, "init: Supabase client created (auto-save/load enabled)")
    }

    suspend fun signUp(
        email: String,
        password: String,
        fullName: String,
        phone: String?
    ): SupaSignUpResult {
        if (BuildConfig.DEBUG) Log.d(TAG, "signUp: calling Supabase signUpWith for $email")
        val parts = fullName.trim().split(" ", limit = 2)
        val firstName = parts[0]
        val lastName = parts.getOrNull(1) ?: ""
        val result = client.auth.signUpWith(Email) {
            this.email = email
            this.password = password
            data = buildJsonObject {
                put("full_name", fullName)
                put("first_name", firstName)
                put("last_name", lastName)
                if (!phone.isNullOrBlank()) put("phone", phone)
            }
        }
        if (BuildConfig.DEBUG) Log.d(TAG, "signUp: signUpWith returned result=${result != null}")
        val session = client.auth.currentSessionOrNull()
        return if (session != null) {
            if (BuildConfig.DEBUG) {
                val token = session.accessToken
                val masked = if (token.length > 16) "${token.take(8)}...${token.takeLast(8)}" else token
                Log.d(TAG, "signUp: accessToken=$masked")
            }
            // Session is auto-persisted by the SDK via SharedPreferencesSessionManager
            logSessionPresence("signUp")
            SupaSignUpResult.SessionEstablished
        } else {
            if (BuildConfig.DEBUG) Log.d(TAG, "signUp: email verification required")
            SupaSignUpResult.EmailVerificationRequired(email)
        }
    }

    suspend fun signIn(email: String, password: String): Boolean {
        if (BuildConfig.DEBUG) Log.d(TAG, "signIn: calling Supabase signInWith for $email")
        client.auth.signInWith(Email) {
            this.email = email
            this.password = password
        }
        val session = client.auth.currentSessionOrNull()
        val token = session?.accessToken
        if (BuildConfig.DEBUG) {
            val masked = if (token != null && token.length > 16) "${token.take(8)}...${token.takeLast(8)}" else token ?: "null"
            Log.d(TAG, "signIn: accessToken=$masked")
        }
        // Session is auto-persisted by the SDK via SharedPreferencesSessionManager
        logSessionPresence("signIn")
        return session != null
    }

    suspend fun sendRecoveryOtp(email: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, "sendRecoveryOtp: sending recovery code for $email")
        client.auth.signInWith(OTP) {
            this.email = email
            createUser = false
        }
    }

    suspend fun verifyRecoveryOtp(email: String, token: String): Boolean {
        if (BuildConfig.DEBUG) Log.d(TAG, "verifyRecoveryOtp: verifying recovery code for $email")
        return try {
            client.auth.verifyEmailOtp(
                type = OtpType.Email.RECOVERY,
                email = email,
                token = token
            )
            val hasSession = client.auth.currentSessionOrNull() != null
            if (hasSession && BuildConfig.DEBUG) Log.d(TAG, "verifyRecoveryOtp: success")
            logSessionPresence("verifyRecoveryOtp")
            hasSession
        } catch (e: Exception) {
            Log.e(TAG, "verifyRecoveryOtp: failed — ${e.message}")
            false
        }
    }

    suspend fun verifyEmailOtp(email: String, token: String): Boolean {
        if (BuildConfig.DEBUG) Log.d(TAG, "verifyEmailOtp: verifying OTP for $email")
        try {
            client.auth.verifyEmailOtp(
                type = OtpType.Email.EMAIL,
                email = email,
                token = token
            )
            val hasSession = client.auth.currentSessionOrNull() != null
            if (hasSession && BuildConfig.DEBUG) Log.d(TAG, "verifyEmailOtp: success")
            logSessionPresence("verifyEmailOtp")
            return hasSession
        } catch (e: Exception) {
            Log.e(TAG, "verifyEmailOtp: failed — ${e.message}")
            return false
        }
    }

    suspend fun resendEmailOtp(email: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, "resendEmailOtp: resending OTP for $email")
        client.auth.resendEmail(type = OtpType.Email.SIGNUP, email = email)
    }

    suspend fun updatePassword(newPassword: String): Boolean {
        return try {
            if (BuildConfig.DEBUG) Log.d(TAG, "updatePassword: updating password")
            client.auth.updateUser {
                password = newPassword
            }
            if (BuildConfig.DEBUG) Log.d(TAG, "updatePassword: success")
            true
        } catch (e: Exception) {
            Log.e(TAG, "updatePassword: failed — ${e.message}")
            if (isBadJwtError(e)) AuthRepository.onSessionExpired()
            false
        }
    }

    suspend fun updateProfile(
        fullName: String? = null,
        phone: String? = null,
        username: String? = null,
        avatarUrl: String? = null
    ): Boolean {
        return try {
            if (BuildConfig.DEBUG) Log.d(TAG, "updateProfile: updating metadata")
            client.auth.updateUser {
                phone?.let { this.phone = it }
                data = buildJsonObject {
                    fullName?.let { put("full_name", it) }
                    phone?.let { put("phone", it) }
                    username?.let { put("username", it) }
                    avatarUrl?.let { put("avatar_url", it) }
                }
            }
            if (BuildConfig.DEBUG) Log.d(TAG, "updateProfile: success")
            true
        } catch (e: Exception) {
            Log.e(TAG, "updateProfile: failed — ${e.message}")
            if (isBadJwtError(e)) AuthRepository.onSessionExpired()
            false
        }
    }

    suspend fun signOut() {
        if (BuildConfig.DEBUG) Log.d(TAG, "signOut: calling SDK signOut")
        client.auth.signOut()
        // Session is auto-deleted by the SDK via SharedPreferencesSessionManager.deleteSession()
    }

    /**
     * Clear the persisted session without making a network call.
     * Used when the JWT has expired and we need to force-logout locally.
     */
    fun clearSessionLocally() {
        prefs?.edit()?.remove("session")?.apply()
    }

    /**
     * Attempts to refresh the current session using the refresh token.
     * Call this when a GraphQL request returns 401 — Supabase's built-in
     * refresh mechanism will exchange the refresh token for a new access token.
     * Returns true if a new session was obtained (auto-persisted by the SDK).
     *
     * If there is no in-memory session (e.g. the SDK's async auto-load from
     * storage hasn't finished yet, or the session was cleared in memory but is
     * still persisted), the persisted session is reloaded first so the refresh
     * token is available.
     */
    suspend fun refreshSession(): Boolean {
        return try {
            if (BuildConfig.DEBUG) Log.d(TAG, "refreshSession: attempting token refresh")
            if (client.auth.currentSessionOrNull() == null) {
                Log.w(TAG, "refreshSession: no in-memory session, reloading from storage")
                val loaded = client.auth.loadFromStorage()
                if (!loaded) {
                    Log.e(TAG, "refreshSession: nothing persisted to reload")
                    clearSessionLocally()
                    return false
                }
            }
            val current = client.auth.currentSessionOrNull()
            if (current == null || current.refreshToken.isBlank()) {
                Log.e(TAG, "refreshSession: session still missing a refresh token after reload")
                clearSessionLocally()
                return false
            }
            client.auth.refreshCurrentSession()
            val newSession = client.auth.currentSessionOrNull()
            if (newSession != null) {
                if (BuildConfig.DEBUG) {
                    val masked = if (newSession.accessToken.length > 16)
                        "${newSession.accessToken.take(8)}...${newSession.accessToken.takeLast(8)}"
                    else newSession.accessToken
                    val maskedRefresh = if (newSession.refreshToken.length > 16)
                        "${newSession.refreshToken.take(8)}...${newSession.refreshToken.takeLast(8)}"
                    else newSession.refreshToken
                    Log.d(TAG, "refreshSession: success, new accessToken=$masked refreshToken=$maskedRefresh")
                }
                true
            } else {
                Log.e(TAG, "refreshSession: session became null after refresh")
                clearSessionLocally()
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "refreshSession: failed — ${e.message}", e)
            if (isBadJwtError(e)) clearSessionLocally()
            false
        }
    }

    /**
     * Returns true if the exception message contains a bad_jwt / 401 indicator.
     */
    fun isBadJwtError(e: Exception): Boolean {
        val msg = e.message ?: ""
        return msg.contains("bad_jwt", ignoreCase = true) ||
               msg.contains("401", ignoreCase = false) ||
               msg.contains("invalid claim", ignoreCase = true)
    }

    fun getAccessToken(): String? =
        client.auth.currentSessionOrNull()?.accessToken

    fun getCurrentUserId(): String? =
        client.auth.currentUserOrNull()?.id

    /**
     * Debug helper: logs whether the current session is present and whether it
     * carries a usable refresh token (blank = refresh impossible on restart).
     */
    private fun logSessionPresence(tag: String) {
        if (!BuildConfig.DEBUG) return
        val s = client.auth.currentSessionOrNull()
        val refresh = s?.refreshToken ?: ""
        Log.d(TAG, "$tag: session=${s != null} refreshTokenPresent=${refresh.isNotBlank()}")
    }

    // ── Proactive session observation ────────────────────────────────────────

    /**
     * Decode the JWT payload to extract the [exp] claim (Unix seconds).
     * Returns null if the token can't be parsed.
     */
    private fun getJwtExpirySeconds(): Long? {
        val token = getAccessToken() ?: return null
        val parts = token.split(".")
        if (parts.size < 2) return null
        return try {
            val payload = String(
                android.util.Base64.decode(parts[1], android.util.Base64.URL_SAFE),
                Charsets.UTF_8
            )
            // Quick JSON scan for the "exp": field (avoids a full JSON parser)
            val expKey = "\"exp\":"
            val keyIndex = payload.indexOf(expKey)
            if (keyIndex == -1) return null
            val colonIndex = payload.indexOf(':', keyIndex + expKey.length)
            if (colonIndex == -1) return null
            val valueStart = maxOf(payload.indexOfFirst { it.isDigit() }, colonIndex + 1)
            val valueEnd = payload.indexOfAny(charArrayOf(',', '}', ' '), valueStart)
                .let { if (it == -1) payload.length else it }
            payload.substring(valueStart, valueEnd).toLongOrNull()
        } catch (e: Exception) {
            Log.w(TAG, "getJwtExpirySeconds: failed to decode — ${e.message}")
            null
        }
    }

    /**
     * Start the periodic JWT health check coroutine.
     *
     * **Layer 1 — SDK auto-refresh:** Handled by [alwaysAutoRefresh = true] in the
     * Supabase client config, with session auto-persisted via
     * [SharedPreferencesSessionManager] (configured in [init]).
     *
     * **Layer 2 — Periodic fallback:** This coroutine calls [refreshSession()]
     * roughly 5 minutes before the JWT [exp] claim, and every 5 minutes as a
     * safety net when expiry can't be determined. It catches edge cases where
     * the SDK's internal scheduling may not fire.
     *
     * Call this once after a session is established (login, signup, or restore).
     * The coroutine is launched into [scope] and auto-cancelled when the
     * scope is destroyed (e.g., ViewModel's [viewModelScope]).
     */
    fun observeSession(scope: CoroutineScope) {
        // Periodic health check as safety net.
        // First check runs immediately; subsequent checks every 5 minutes.
        scope.launch {
            while (true) {
                val exp = getJwtExpirySeconds()
                val now = System.currentTimeMillis() / 1000
                val remainingSeconds = if (exp != null) exp - now else null

                if (remainingSeconds != null && remainingSeconds < PROACTIVE_REFRESH_THRESHOLD_SECONDS) {
                    // Token is expiring within the threshold — refresh proactively
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "proactiveCheck: token expires in ${remainingSeconds}s (< ${PROACTIVE_REFRESH_THRESHOLD_SECONDS}s), refreshing")
                    }
                    refreshSession()
                } else if (remainingSeconds != null) {
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "proactiveCheck: token healthy, expires in ${remainingSeconds}s")
                    }
                } else {
                    // Can't determine expiry — refresh as safety net
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "proactiveCheck: no expiry info, refreshing as safety net")
                    }
                    refreshSession()
                }
                delay(PROACTIVE_REFRESH_INTERVAL_MS)
            }
        }
    }
}

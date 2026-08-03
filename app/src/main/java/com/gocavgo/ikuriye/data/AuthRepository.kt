package com.gocavgo.ikuriye.data

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.gocavgo.ikuriye.BuildConfig
import com.gocavgo.ikuriye.SyncUserMutation
import com.gocavgo.ikuriye.data.dto.AuthResult
import com.gocavgo.ikuriye.data.dto.AuthUserDto
import com.gocavgo.ikuriye.data.dto.RoleDto
import com.gocavgo.ikuriye.data.dto.SignInInput
import com.gocavgo.ikuriye.data.dto.SignUpInput
import com.gocavgo.ikuriye.data.dto.SyncResult
import com.gocavgo.ikuriye.data.dto.UserStatusDto
import com.gocavgo.ikuriye.network.ApolloClientProvider
import com.gocavgo.ikuriye.supa.SupaAuth
import com.gocavgo.ikuriye.supa.SupaSignUpResult
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

object AuthRepository {

    private const val TAG = "AuthRepository"
    private var prefs: SharedPreferences? = null
    private var appContext: Context? = null

    fun getAppContext(): Context? = appContext

    private val _sessionExpired = Channel<Unit>(Channel.CONFLATED)
    val sessionExpired: Flow<Unit> = _sessionExpired.receiveAsFlow()

    /**
     * Called whenever the backend (Apollo or Supabase) returns a 401 / bad_jwt.
     * Clears the cached session and emits an event so the UI can force-logout.
     */
    fun onSessionExpired() {
        if (BuildConfig.DEBUG) Log.d(TAG, "onSessionExpired: JWT expired, clearing session")
        // Clear local caches
        clearCachedUser()
        SupaAuth.clearSessionLocally()
        // trySend on CONFLATED channel always succeeds (drops oldest if uncollected)
        _sessionExpired.trySend(Unit)
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = context.getSharedPreferences("ikuriye_supa", Context.MODE_PRIVATE)
        SupaAuth.init(context)
    }

    fun isNetworkAvailable(): Boolean {
        val cm = appContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun cacheUser(user: AuthUserDto) {
        val editor = prefs?.edit() ?: return
        editor.putString("user_id", user.id)
        editor.putString("user_email", user.email)
        editor.putString("user_phone", user.phone)
        editor.putString("user_first_name", user.firstName)
        editor.putString("user_last_name", user.lastName)
        editor.putString("user_username", user.username)
        editor.putString("user_avatar_url", user.avatarUrl)
        editor.putString("user_role", user.role.name)
        editor.putString("user_status", user.status.name)
        editor.apply()
    }

    fun getCachedUser(): AuthUserDto? {
        val p = prefs ?: return null
        val id = p.getString("user_id", null) ?: return null
        return AuthUserDto(
            id = id,
            email = p.getString("user_email", "") ?: "",
            phone = p.getString("user_phone", null),
            firstName = p.getString("user_first_name", null),
            lastName = p.getString("user_last_name", null),
            username = p.getString("user_username", null),
            avatarUrl = p.getString("user_avatar_url", null),
            role = try { RoleDto.valueOf(p.getString("user_role", "CUSTOMER") ?: "CUSTOMER") } catch (_: Exception) { RoleDto.CUSTOMER },
            status = try { UserStatusDto.valueOf(p.getString("user_status", "PENDING") ?: "PENDING") } catch (_: Exception) { UserStatusDto.PENDING }
        )
    }

    fun clearCachedUser() {
        val editor = prefs?.edit() ?: return
        editor.remove("user_id")
        editor.remove("user_email")
        editor.remove("user_phone")
        editor.remove("user_first_name")
        editor.remove("user_last_name")
        editor.remove("user_username")
        editor.remove("user_avatar_url")
        editor.remove("user_role")
        editor.remove("user_status")
        editor.apply()
    }

    suspend fun signUp(input: SignUpInput): AuthResult {
        return try {
            if (BuildConfig.DEBUG) Log.d(TAG, "signUp: attempting for ${input.email}")
            val result = SupaAuth.signUp(
                email = input.email,
                password = input.password,
                fullName = input.fullName,
                phone = input.phone
            )
            when (result) {
                is SupaSignUpResult.EmailVerificationRequired -> {
                    if (BuildConfig.DEBUG) Log.d(TAG, "signUp: email verification required")
                    return AuthResult.VerificationRequired(result.email)
                }
                is SupaSignUpResult.SessionEstablished -> {
                    if (BuildConfig.DEBUG) Log.d(TAG, "signUp: Supabase auth success")
                }
            }
            val user = SupaAuth.client.auth.currentUserOrNull()
            if (user != null) {
                if (!isNetworkAvailable()) {
                    if (BuildConfig.DEBUG) Log.d(TAG, "signUp: no network, skipping backend sync")
                    return AuthResult.Error("No internet connection — signup completed locally, will sync when online")
                }
                val sync = syncWithBackend()
                when (sync) {
                    is SyncResult.Success -> {
                        if (BuildConfig.DEBUG) Log.d(TAG, "signUp: sync success, role=${sync.user.role}")
                        AuthResult.Success(sync.user)
                    }
                    is SyncResult.Error -> {
                        Log.e(TAG, "signUp: sync failed — ${sync.message}")
                        // Don't sign out — Supabase auth succeeded, backend sync can retry later
                        AuthResult.Error("Account created! Could not sync with server: ${sync.message}")
                    }
                }
            } else {
                AuthResult.Error("Signup succeeded but no session returned")
            }
        } catch (e: Exception) {
            Log.e(TAG, "signUp: exception — ${e.message}", e)
            val msg = e.message ?: ""
            if (msg.contains("already exists", ignoreCase = true) ||
                msg.contains("already registered", ignoreCase = true) ||
                msg.contains("already in use", ignoreCase = true) ||
                msg.contains("User already", ignoreCase = true)) {
                return AuthResult.EmailAlreadyExists(input.email)
            }
            AuthResult.Error(msg)
        }
    }

    suspend fun signIn(input: SignInInput): AuthResult {
        return try {
            if (BuildConfig.DEBUG) Log.d(TAG, "signIn: attempting for ${input.email}")
            SupaAuth.signIn(email = input.email, password = input.password)
            if (BuildConfig.DEBUG) Log.d(TAG, "signIn: Supabase auth success")
            if (!isNetworkAvailable()) {
                if (BuildConfig.DEBUG) Log.d(TAG, "signIn: no network, skipping backend sync")
                val cached = getCachedUser()
                if (cached != null) return AuthResult.Success(cached)
                // First login on this device — no cached user yet.
                // Don't attempt the network call; show a direct error.
                return AuthResult.Error("No internet connection — please try again when online")
            }
            val sync = syncWithBackend()
            when (sync) {
                is SyncResult.Success -> {
                    if (BuildConfig.DEBUG) Log.d(TAG, "signIn: sync success, role=${sync.user.role}")
                    AuthResult.Success(sync.user)
                }
                is SyncResult.Error -> {
                    Log.e(TAG, "signIn: sync failed — ${sync.message}")
                    // Don't sign out — Supabase auth succeeded, backend sync can retry later
                    val cached = getCachedUser()
                    if (cached != null) {
                        AuthResult.Success(cached)
                    } else {
                        AuthResult.Error("Login successful! Could not sync profile: ${sync.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "signIn: exception — ${e.message}", e)
            val msg = e.message ?: ""
            // If email not confirmed, offer OTP verification
            if (msg.contains("Email not confirmed", ignoreCase = true) ||
                msg.contains("email_not_confirmed", ignoreCase = true)) {
                AuthResult.VerificationRequired(input.email)
            } else {
                AuthResult.Error(msg)
            }
        }
    }

    suspend fun restoreSession(): AuthResult {
        return if (isLoggedIn()) {
            if (!isNetworkAvailable()) {
                if (BuildConfig.DEBUG) Log.d(TAG, "restoreSession: no network, skipping sync, keeping cached user")
                val cached = getCachedUser()
                return if (cached != null) {
                    AuthResult.Success(cached)
                } else {
                    AuthResult.Error("No internet — showing offline data")
                }
            }
            if (BuildConfig.DEBUG) Log.d(TAG, "restoreSession: session found, syncing...")
            val sync = syncWithBackend()
            when (sync) {
                is SyncResult.Success -> {
                    if (BuildConfig.DEBUG) Log.d(TAG, "restoreSession: sync success, role=${sync.user.role}")
                    AuthResult.Success(sync.user)
                }
                is SyncResult.Error -> {
                    Log.e(TAG, "restoreSession: sync failed — ${sync.message}")
                    // Don't sign out! The session is still valid, just the backend
                    // is unreachable. Keep the cached user for now.
                    val cached = getCachedUser()
                    if (cached != null) {
                        AuthResult.Success(cached)
                    } else {
                        AuthResult.Error("Could not sync — no internet connection")
                    }
                }
            }
        } else {
            if (BuildConfig.DEBUG) Log.d(TAG, "restoreSession: no saved session")
            AuthResult.Error("No saved session")
        }
    }

    suspend fun sendPasswordResetCode(email: String): String? {
        return try {
            if (BuildConfig.DEBUG) Log.d(TAG, "sendPasswordResetCode: for $email")
            SupaAuth.sendRecoveryOtp(email)
            if (BuildConfig.DEBUG) Log.d(TAG, "sendPasswordResetCode: success")
            null
        } catch (e: Exception) {
            Log.e(TAG, "sendPasswordResetCode: failed — ${e.message}")
            e.message ?: "Failed to send reset code"
        }
    }

    suspend fun completePasswordReset(email: String, code: String, newPassword: String): String? {
        return try {
            if (BuildConfig.DEBUG) Log.d(TAG, "completePasswordReset: verifying code for $email")
            val verified = SupaAuth.verifyRecoveryOtp(email, code)
            if (!verified) return "Invalid or expired reset code"
            if (BuildConfig.DEBUG) Log.d(TAG, "completePasswordReset: code verified, updating password")
            val updated = SupaAuth.updatePassword(newPassword)
            if (!updated) return "Failed to update password"
            SupaAuth.signOut()
            null
        } catch (e: Exception) {
            Log.e(TAG, "completePasswordReset: failed — ${e.message}")
            e.message ?: "Password reset failed"
        }
    }

    suspend fun updateProfile(
        fullName: String? = null,
        phone: String? = null,
        username: String? = null,
        avatarUrl: String? = null
    ): AuthResult {
        return try {
            if (BuildConfig.DEBUG) Log.d(TAG, "updateProfile: updating in Supabase")
            val success = SupaAuth.updateProfile(fullName, phone, username, avatarUrl)
            if (!success) return AuthResult.Error("Failed to update profile in Supabase")

            // Cache new avatar locally when user changes their profile picture
            appContext?.let { ctx -> AvatarCache.cache(ctx, avatarUrl) }

            if (BuildConfig.DEBUG) Log.d(TAG, "updateProfile: syncing with backend")
            val sync = syncWithBackend()
            when (sync) {
                is SyncResult.Success -> AuthResult.Success(sync.user)
                is SyncResult.Error -> AuthResult.Error("Profile updated but sync failed: ${sync.message}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "updateProfile: exception — ${e.message}", e)
            AuthResult.Error(e.message ?: "Profile update failed")
        }
    }

    suspend fun verifyEmailOtpAndSync(email: String, code: String): AuthResult {
        return try {
            if (BuildConfig.DEBUG) Log.d(TAG, "verifyEmailOtpAndSync: verifying OTP for $email")
            val verified = SupaAuth.verifyEmailOtp(email, code)
            if (!verified) {
                return AuthResult.Error("Invalid or expired verification code")
            }
            if (BuildConfig.DEBUG) Log.d(TAG, "verifyEmailOtpAndSync: OTP verified, syncing with backend")
            val sync = syncWithBackend()
            when (sync) {
                is SyncResult.Success -> {
                    if (BuildConfig.DEBUG) Log.d(TAG, "verifyEmailOtpAndSync: sync success, role=${sync.user.role}")
                    AuthResult.Success(sync.user)
                }
                is SyncResult.Error -> {
                    Log.e(TAG, "verifyEmailOtpAndSync: sync failed — ${sync.message}")
                    // Don't sign out — OTP verified successfully, backend can sync later
                    val cached = getCachedUser()
                    if (cached != null) {
                        AuthResult.Success(cached)
                    } else {
                        AuthResult.Error("Verification succeeded! Could not sync profile: ${sync.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "verifyEmailOtpAndSync: exception — ${e.message}", e)
            AuthResult.Error(e.message ?: "Email verification failed")
        }
    }

    suspend fun signOut() {
        clearCachedUser()
        appContext?.let { AvatarCache.clear(it) }
        SupaAuth.signOut()
    }

    fun isLoggedIn(): Boolean =
        SupaAuth.client.auth.currentSessionOrNull() != null

    private suspend fun syncWithBackend(): SyncResult {
        return try {
            if (BuildConfig.DEBUG) Log.d(TAG, "syncWithBackend: sending SyncUser mutation")
            val response = ApolloClientProvider.client
                .mutation(SyncUserMutation())
                .execute()
            if (response.errors != null && response.errors!!.isNotEmpty()) {
                val errors = response.errors!!.joinToString("; ") { it.message ?: "unknown" }
                Log.e(TAG, "syncWithBackend: GraphQL errors — $errors")
                SyncResult.Error(errors)
            } else if (response.data != null) {
                val gqlUser = response.data!!.syncUser ?: run {
                    Log.e(TAG, "syncWithBackend: syncUser is null in response data")
                    return SyncResult.Error("GraphQL sync returned no user data")
                }
                val role = when (gqlUser.role) {
                    com.gocavgo.ikuriye.type.Role.DRIVER -> RoleDto.DRIVER
                    com.gocavgo.ikuriye.type.Role.CUSTOMER -> RoleDto.CUSTOMER
                    com.gocavgo.ikuriye.type.Role.WORKER -> RoleDto.WORKER
                    com.gocavgo.ikuriye.type.Role.ADMIN -> RoleDto.ADMIN
                    com.gocavgo.ikuriye.type.Role.SUPER_ADMIN -> RoleDto.SUPER_ADMIN
                    else -> RoleDto.CUSTOMER
                }
                val status = when (gqlUser.status) {
                    com.gocavgo.ikuriye.type.UserStatus.ACTIVE -> UserStatusDto.ACTIVE
                    com.gocavgo.ikuriye.type.UserStatus.DISABLED -> UserStatusDto.DISABLED
                    com.gocavgo.ikuriye.type.UserStatus.PENDING -> UserStatusDto.PENDING
                    else -> UserStatusDto.PENDING
                }
                if (BuildConfig.DEBUG) Log.d(TAG, "syncWithBackend: user=${gqlUser.id}, role=$role, status=$status")
                // Try to get avatarUrl from Supabase auth metadata first (it's stored there
                // when the user updates their profile picture). Fall back to the cached
                // user's avatar URL from a previous session.
                val avatarFromMeta = (SupaAuth.client.auth.currentUserOrNull()
                    ?.userMetadata?.get("avatar_url") as? kotlinx.serialization.json.JsonPrimitive)?.content
                val avatarFromCache = getCachedUser()?.avatarUrl
                val avatarUrl = avatarFromMeta ?: avatarFromCache
                val user = AuthUserDto(
                    id = gqlUser.id,
                    email = gqlUser.email,
                    phone = gqlUser.phone,
                    firstName = gqlUser.firstName,
                    lastName = gqlUser.lastName,
                    username = gqlUser.username,
                    avatarUrl = avatarUrl,
                    role = role,
                    status = status
                )
                cacheUser(user)
                // Cache avatar image locally so it doesn't re-download every time
                appContext?.let { ctx -> AvatarCache.cache(ctx, user.avatarUrl) }
                SyncResult.Success(user)
            } else {
                Log.e(TAG, "syncWithBackend: GraphQL response has no data and no errors")
                SyncResult.Error("GraphQL sync returned no data")
            }
        } catch (e: Exception) {
            Log.e(TAG, "syncWithBackend: exception — ${e.message}", e)
            SyncResult.Error("Sync failed: ${e.message}")
        }
    }
}

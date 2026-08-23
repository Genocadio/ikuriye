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
import com.gocavgo.ikuriye.nexx.NexxAuth
import com.gocavgo.ikuriye.network.ApolloClientProvider
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Auth repository. Authentication is handled by Nexxauth (see [NexxAuth]) —
 * login/register accept an email OR phone identifier + password. The returned
 * org-access JWT is sent to the backend GraphQL API, which verifies it offline
 * and mirrors the profile via `syncUser`.
 *
 * Supabase is no longer used for auth — only for file uploads.
 */
object AuthRepository {

    private const val TAG = "AuthRepository"
    private var prefs: SharedPreferences? = null
    private var appContext: Context? = null

    fun getAppContext(): Context? = appContext

    private val _sessionExpired = Channel<Unit>(Channel.CONFLATED)
    val sessionExpired: Flow<Unit> = _sessionExpired.receiveAsFlow()

    /**
     * Called whenever the backend (Apollo or Nexxauth) returns a 401 / session
     * expiry. Clears the cached session and emits an event so the UI can
     * force-logout.
     */
    fun onSessionExpired() {
        if (BuildConfig.DEBUG) Log.d(TAG, "onSessionExpired: session expired, clearing")
        clearCachedUser()
        NexxAuth.clearSessionLocally()
        _sessionExpired.trySend(Unit)
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = context.getSharedPreferences("ikuriye_auth", Context.MODE_PRIVATE)
        NexxAuth.init(context)
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
        editor.clear()
        editor.apply()
    }

    suspend fun signUp(input: SignUpInput): AuthResult {
        return try {
            if (BuildConfig.DEBUG) Log.d(TAG, "signUp: registering via Nexxauth (email=${input.email}, phone=${input.phone})")
            val error = NexxAuth.signUp(
                email = input.email,
                phone = input.phone,
                password = input.password,
                firstName = input.firstName,
                lastName = input.lastName
            )
            if (error != null) {
                if (error.contains("already", ignoreCase = true)) {
                    return AuthResult.EmailAlreadyExists(input.email)
                }
                return AuthResult.Error(error)
            }

            if (!isNetworkAvailable()) {
                return AuthResult.Error("No internet connection — please try again when online")
            }
            val sync = syncWithBackend()
            when (sync) {
                is SyncResult.Success -> AuthResult.Success(sync.user)
                is SyncResult.Error -> {
                    Log.e(TAG, "signUp: sync failed — ${sync.message}")
                    AuthResult.Error("Account created! Could not sync with server: ${sync.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "signUp: exception — ${e.message}", e)
            AuthResult.Error(e.message ?: "Sign up failed")
        }
    }

    suspend fun signIn(input: SignInInput): AuthResult {
        return try {
            if (BuildConfig.DEBUG) Log.d(TAG, "signIn: logging in via Nexxauth (identifier=${input.identifier})")
            val error = NexxAuth.signIn(identifier = input.identifier, password = input.password)
            if (error != null) {
                return AuthResult.Error(error)
            }

            if (!isNetworkAvailable()) {
                val cached = getCachedUser()
                if (cached != null) return AuthResult.Success(cached)
                return AuthResult.Error("No internet connection — please try again when online")
            }
            val sync = syncWithBackend()
            when (sync) {
                is SyncResult.Success -> AuthResult.Success(sync.user)
                is SyncResult.Error -> {
                    Log.e(TAG, "signIn: sync failed — ${sync.message}")
                    val cached = getCachedUser()
                    if (cached != null) AuthResult.Success(cached)
                    else AuthResult.Error("Login successful! Could not sync profile: ${sync.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "signIn: exception — ${e.message}", e)
            AuthResult.Error(e.message ?: "Sign in failed")
        }
    }

    suspend fun restoreSession(): AuthResult {
        return if (NexxAuth.isLoggedIn()) {
            if (!isNetworkAvailable()) {
                val cached = getCachedUser()
                return if (cached != null) AuthResult.Success(cached) else AuthResult.Error("No internet — showing offline data")
            }
            val sync = syncWithBackend()
            when (sync) {
                is SyncResult.Success -> AuthResult.Success(sync.user)
                is SyncResult.Error -> {
                    val cached = getCachedUser()
                    if (cached != null) AuthResult.Success(cached)
                    else AuthResult.Error("Could not sync — no internet connection")
                }
            }
        } else {
            AuthResult.Error("No saved session")
        }
    }

    /**
     * Self-service password reset is not supported by Nexxauth's public API yet.
     * Resets are done the Nexxauth way: an admin sets a temporary password and
     * the user is forced to change it on first login.
     */
    suspend fun sendPasswordResetCode(identifier: String): String? =
        "Password reset is managed by your administrator — ask them to set a temporary password for you."

    suspend fun completePasswordReset(identifier: String, code: String, newPassword: String): String? =
        "Password reset is managed by your administrator — ask them to set a temporary password for you."

    suspend fun updateProfile(
        fullName: String? = null,
        phone: String? = null,
        username: String? = null,
        avatarUrl: String? = null
    ): AuthResult {
        return try {
            // Avatar is stored locally (Supabase is upload-only; the backend does
            // not persist avatar URLs).
            if (!avatarUrl.isNullOrBlank()) {
                prefs?.edit()?.putString("user_avatar_url", avatarUrl)?.apply()
                appContext?.let { ctx -> AvatarCache.cache(ctx, avatarUrl) }
            }

            val firstName = fullName?.trim()?.split(" ", limit = 2)?.firstOrNull()
            val lastName = fullName?.trim()?.split(" ", limit = 2)?.getOrNull(1)
            if (!firstName.isNullOrBlank()) {
                val error = NexxAuth.updateProfile(firstName, lastName)
                if (error != null) return AuthResult.Error(error)
            }

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

    /**
     * Email OTP verification no longer applies — Nexxauth registers users with a
     * session directly (no email confirmation step). Kept as a stub so callers
     * compile; it should never be reached.
     */
    suspend fun verifyEmailOtpAndSync(email: String, code: String): AuthResult =
        AuthResult.Error("Email verification is not required with Nexxauth")

    suspend fun signOut() {
        clearCachedUser()
        appContext?.let { AvatarCache.clear(it) }
        NexxAuth.signOut()
    }

    fun isLoggedIn(): Boolean = NexxAuth.isLoggedIn()

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
                val avatarFromCache = getCachedUser()?.avatarUrl
                val user = AuthUserDto(
                    id = gqlUser.id,
                    email = gqlUser.email,
                    phone = gqlUser.phone,
                    firstName = gqlUser.firstName,
                    lastName = gqlUser.lastName,
                    username = gqlUser.username,
                    avatarUrl = avatarFromCache,
                    role = role,
                    status = status
                )
                cacheUser(user)
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

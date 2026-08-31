package com.gocavgo.ikuriye.data

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.gocavgo.ikuriye.BuildConfig
import com.gocavgo.ikuriye.MyProfileQuery
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
 * Supabase is no longer used for auth or uploads — file uploads go through the backend.
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

            // Nexxauth response includes the full user object with roles —
            // use it directly for UI routing.
            val nexxUser = NexxAuth.getCachedNexxauthUser()
            if (nexxUser != null) {
                val role = mapNexxauthRole(nexxUser.roles)
                val user = AuthUserDto(
                    id = nexxUser.id,
                    email = nexxUser.email,
                    phone = nexxUser.phone,
                    firstName = nexxUser.firstName,
                    lastName = nexxUser.lastName,
                    username = nexxUser.username,
                    avatarUrl = null,
                    role = role,
                    status = if (nexxUser.enabled) UserStatusDto.ACTIVE else UserStatusDto.DISABLED
                )
                cacheUser(user)
                return AuthResult.Success(user)
            }

            // Fallback: no user data from Nexxauth
            if (!isNetworkAvailable()) {
                return AuthResult.Error("No internet connection — please try again when online")
            }
            val profile = fetchProfile()
            when (profile) {
                is SyncResult.Success -> AuthResult.Success(profile.user)
                is SyncResult.Error -> {
                    AuthResult.Error("Account created! Could not fetch profile: ${profile.message}")
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

            // Nexxauth response includes the full user object with roles —
            // use it directly for UI routing. No extra backend call needed.
            val nexxUser = NexxAuth.getCachedNexxauthUser()
            if (nexxUser != null) {
                val role = mapNexxauthRole(nexxUser.roles)
                val user = AuthUserDto(
                    id = nexxUser.id,
                    email = nexxUser.email,
                    phone = nexxUser.phone,
                    firstName = nexxUser.firstName,
                    lastName = nexxUser.lastName,
                    username = nexxUser.username,
                    avatarUrl = getCachedUser()?.avatarUrl,
                    role = role,
                    status = if (nexxUser.enabled) UserStatusDto.ACTIVE else UserStatusDto.DISABLED
                )
                cacheUser(user)
                return AuthResult.Success(user)
            }

            // Fallback: no user data from Nexxauth (shouldn't happen)
            if (!isNetworkAvailable()) {
                val cached = getCachedUser()
                if (cached != null) return AuthResult.Success(cached)
                return AuthResult.Error("No internet connection — please try again when online")
            }
            val profile = fetchProfile()
            when (profile) {
                is SyncResult.Success -> AuthResult.Success(profile.user)
                is SyncResult.Error -> {
                    val cached = getCachedUser()
                    if (cached != null) AuthResult.Success(cached)
                    else AuthResult.Error("Login successful! Could not fetch profile: ${profile.message}")
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
            val profile = fetchProfile()
            when (profile) {
                is SyncResult.Success -> AuthResult.Success(profile.user)
                is SyncResult.Error -> {
                    val cached = getCachedUser()
                    if (cached != null) AuthResult.Success(cached)
                    else AuthResult.Error("Could not fetch profile — no internet connection")
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

            val profile = fetchProfile()
            when (profile) {
                is SyncResult.Success -> AuthResult.Success(profile.user)
                is SyncResult.Error -> AuthResult.Error("Profile updated but sync failed: ${profile.message}")
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

    /**
     * Maps Nexxauth role names to the local RoleDto enum.
     * Nexxauth roles are lowercase strings (e.g. "driver", "worker").
     * If the user has multiple roles, returns the highest-precedence one.
     */
    private fun mapNexxauthRole(roles: List<String>): RoleDto {
        val precedence = mapOf(
            "customer" to 1,
            "driver" to 2,
            "worker" to 3,
            "admin" to 4,
            "super_admin" to 5
        )
        return roles.mapNotNull { role ->
            val name = role.uppercase().replace("-", "_")
            try { RoleDto.valueOf(name) to (precedence[role.lowercase()] ?: 0) } catch (_: Exception) { null }
        }.maxByOrNull { it.second }?.first ?: RoleDto.CUSTOMER
    }

    /**
     * Fetches the user profile from the backend via the `myProfile` query.
     * The backend automatically syncs from Nexxauth when the JWT's dataHash
     * doesn't match the stored value — no explicit sync mutation needed.
     */
    private suspend fun fetchProfile(): SyncResult {
        return try {
            if (BuildConfig.DEBUG) Log.d(TAG, "fetchProfile: querying myProfile")
            val response = ApolloClientProvider.client
                .query(MyProfileQuery())
                .execute()
            if (response.errors != null && response.errors!!.isNotEmpty()) {
                val errors = response.errors!!.joinToString("; ") { it.message ?: "unknown" }
                Log.e(TAG, "fetchProfile: GraphQL errors — $errors")
                SyncResult.Error(errors)
            } else if (response.data != null) {
                val gqlUser = response.data!!.myProfile ?: run {
                    Log.e(TAG, "fetchProfile: myProfile is null in response data")
                    return SyncResult.Error("GraphQL query returned no user data")
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
                Log.e(TAG, "fetchProfile: GraphQL response has no data and no errors")
                SyncResult.Error("GraphQL query returned no data")
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchProfile: exception — ${e.message}", e)
            SyncResult.Error("Profile fetch failed: ${e.message}")
        }
    }
}

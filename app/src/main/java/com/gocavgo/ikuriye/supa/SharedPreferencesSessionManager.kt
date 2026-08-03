package com.gocavgo.ikuriye.supa

import android.content.SharedPreferences
import android.util.Log
import com.gocavgo.ikuriye.BuildConfig
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * A [SessionManager] backed by Android SharedPreferences.
 *
 * Replaces the manual [SupaAuth.saveSession] / [SupaAuth.restoreSession] flow.
 * The Supabase KMP SDK calls [saveSession] automatically whenever the session
 * changes (login, signup, refresh), and calls [loadSession] on client
 * initialization if [io.github.jan.supabase.auth.AuthConfig.autoLoadFromStorage]
 * is enabled.
 *
 * Uses the same JSON serialisation format as the original manual save/restore,
 * so previously saved sessions are compatible.
 */
class SharedPreferencesSessionManager(
    private val prefs: SharedPreferences
) : SessionManager {

    private val TAG = "SupaSessionManager"
    private val json = Json { ignoreUnknownKeys = true; classDiscriminator = "#class" }
    private val KEY = "session"

    override suspend fun saveSession(session: UserSession) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "saveSession: access=${mask(session.accessToken)} refresh=${mask(session.refreshToken)}")
        }
        val sessionJson = json.encodeToString(session)
        prefs.edit().putString(KEY, sessionJson).apply()
    }

    override suspend fun loadSession(): UserSession {
        val sessionJson = prefs.getString(KEY, null)
            ?: error("No saved session found")
        val session = json.decodeFromString<UserSession>(sessionJson)
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "loadSession: access=${mask(session.accessToken)} refresh=${mask(session.refreshToken)}")
        }
        return session
    }

    override suspend fun deleteSession() {
        if (BuildConfig.DEBUG) Log.d(TAG, "deleteSession: removing persisted session")
        prefs.edit().remove(KEY).apply()
    }

    private fun mask(token: String): String =
        if (token.length > 12) "${token.take(6)}...${token.takeLast(6)}" else token
}
